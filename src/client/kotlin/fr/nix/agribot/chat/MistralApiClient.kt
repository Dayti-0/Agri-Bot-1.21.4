package fr.nix.agribot.chat

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.nix.agribot.config.AutoResponseConfig
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * Client pour l'API Mistral avec systeme de classification a 2 etapes.
 * Etape 1: Classification du type de message
 * Etape 2: Generation de reponse adaptee a la categorie
 */
object MistralApiClient {
    private val logger = LoggerFactory.getLogger("agribot")
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()

    private const val API_URL = "https://api.mistral.ai/v1/chat/completions"
    private const val MODEL = "mistral-small-latest"

    /**
     * Categories de messages detectables.
     */
    enum class MessageCategory {
        GREETING,           // Salutation simple (yo, salut, wesh, hey)
        GREETING_WITH_STATE,// Salutation avec question d'etat (ca va, cv)
        QUESTION_COMMERCIAL,// Question commerciale (t'as X a vendre, tu veux acheter)
        INVITATION,         // Invitation a faire quelque chose (tu veux farmer, viens pvp)
        QUESTION_HELP,      // Demande d'aide explicite (tu peux m'aider, t'as une sec)
        QUESTION_LOCATION,  // Question de localisation (t'es ou, tu fais quoi)
        ACKNOWLEDGMENT,     // Confirmation/acquiescement (nickel, ok, d'accord, super, cool)
        CONFUSION,          // Expression de confusion (?, hein, quoi, pardon)
        IGNORE              // Message a ignorer (pas adresse au joueur)
    }

    /**
     * Resultat de l'analyse d'un message (etape 1).
     */
    data class MessageAnalysis(
        val shouldRespond: Boolean,
        val category: MessageCategory,
        val reason: String
    )

    /**
     * ETAPE 1: Analyse et classifie un message.
     * Determine la categorie du message pour adapter la reponse.
     * @param hasActiveConversation Si true, on a deja echange avec ce joueur recemment
     * @param conversationHistory Historique des derniers messages de la conversation
     */
    fun analyzeMessage(message: String, playerUsername: String, senderName: String, hasActiveConversation: Boolean = false, conversationHistory: List<String> = emptyList()): CompletableFuture<MessageAnalysis> {
        return CompletableFuture.supplyAsync({
            try {
                val config = AutoResponseConfig.get()
                if (!config.isApiConfigured()) {
                    return@supplyAsync MessageAnalysis(false, MessageCategory.IGNORE, "API non configuree")
                }

                // Construire le contexte conversationnel
                val conversationContext = if (hasActiveConversation) {
                    """
CONTEXTE IMPORTANT: Tu as une CONVERSATION ACTIVE avec $senderName.
Vous avez echange des messages dans les 30 dernieres secondes.
Donc meme si le message ne contient pas le pseudo "$playerUsername",
il est TRES PROBABLEMENT adresse a toi (suite de la conversation).
Dans ce cas, NE PAS mettre IGNORE sauf si le message mentionne explicitement un AUTRE pseudo."""
                } else {
                    ""
                }

                // Construire l'historique de conversation si disponible
                val historyContext = if (conversationHistory.isNotEmpty()) {
                    """

HISTORIQUE RECENT DE LA CONVERSATION (du plus ancien au plus recent):
${conversationHistory.joinToString("\n")}

Utilise cet historique pour mieux comprendre le contexte du message actuel."""
                } else {
                    ""
                }

                val systemPrompt = """Tu es un classificateur de messages pour un bot Minecraft occupe (en train de farmer).
Tu dois analyser les messages du chat et les classifier en categories.

Le joueur bot s'appelle "$playerUsername". Analyse le message de "$senderName".
$conversationContext$historyContext

CATEGORIES (reponds avec le code EXACT):
- GREETING : Salutation simple sans question (yo, salut, wesh, hey, cc, slt, coucou)
- GREETING_WITH_STATE : Salutation AVEC question sur l'etat (ca va, cv, cv?, comment ca va, ca va ou quoi, tu vas bien, ca farte)
- QUESTION_COMMERCIAL : Question d'achat/vente (t'as X a vendre, tu veux acheter, tu vends, t'as du X)
- INVITATION : Invitation a faire quelque chose (tu veux farmer, viens pvp, on fait X, tu viens)
- QUESTION_HELP : Demande d'aide EXPLICITE (tu peux m'aider, t'as une minute, j'ai besoin de toi, aide moi)
- QUESTION_LOCATION : Question de localisation/activite (t'es ou, tu fais quoi, t'es la)
- ACKNOWLEDGMENT : Confirmation/acquiescement (nickel, nikel, ok, d'accord, super, cool, parfait, ca marche, top, bien)
- CONFUSION : Expression de confusion apres un de nos messages (?, ??, hein, quoi, pardon, comment, je comprends pas)
- IGNORE : Message PAS adresse a $playerUsername (contient un AUTRE pseudo specifique, question avec "qui", annonce generale)

REGLES IMPORTANTES:
1. Si le message contient le pseudo "$playerUsername" = PAS IGNORE
2. Si CONVERSATION ACTIVE = le message est probablement pour nous, PAS IGNORE (sauf autre pseudo)
3. Si le message mentionne un AUTRE pseudo specifique = IGNORE
4. "qui veut...", "qui a...", "quelqu'un peut..." = IGNORE (question generale)
5. "cc all", "salut tout le monde" = IGNORE (message collectif)
6. "nickel", "nikel", "ok", "cool", "parfait", "super" = ACKNOWLEDGMENT (pas une salutation!)
7. "?" seul ou "hein", "quoi" = CONFUSION (pas une demande d'aide!)
8. QUESTION_HELP seulement pour demandes d'aide EXPLICITES avec verbe (aider, aide, besoin)

Reponds UNIQUEMENT avec: CATEGORIE - courte explication
Exemple: GREETING_WITH_STATE - salutation avec question ca va
Exemple: ACKNOWLEDGMENT - confirmation positive de la conversation
Exemple: CONFUSION - demande de clarification"""

                val userPrompt = "Message de $senderName: \"$message\""

                if (config.testModeActive) {
                    logger.info("[ETAPE 1 - CLASSIFICATION]")
                    logger.info("[API] Message a analyser: \"$message\"")
                }

                val response = callMistralApi(config.mistralApiKey, systemPrompt, userPrompt)

                if (config.testModeActive) {
                    logger.info("[API] Reponse classification: \"$response\"")
                }

                // Parser la reponse
                val category = parseCategory(response)
                val reason = if (response.contains("-")) response.substringAfter("-").trim() else response
                val shouldRespond = category != MessageCategory.IGNORE

                if (config.testModeActive) {
                    logger.info("[API] Categorie: $category, Doit repondre: $shouldRespond")
                }

                MessageAnalysis(shouldRespond, category, reason)
            } catch (e: Exception) {
                logger.error("Erreur lors de la classification: ${e.message}")
                MessageAnalysis(false, MessageCategory.IGNORE, "Erreur: ${e.message}")
            }
        }, executor)
    }

    /**
     * ETAPE 2: Genere une reponse adaptee a la categorie du message.
     * @param conversationHistory Historique des derniers messages de la conversation
     * @param recentResponses Les dernieres reponses envoyees a ce joueur (pour eviter repetitions)
     */
    fun generateResponse(originalMessage: String, senderName: String, playerUsername: String, category: MessageCategory = MessageCategory.GREETING, conversationHistory: List<String> = emptyList(), recentResponses: List<String> = emptyList()): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            try {
                val config = AutoResponseConfig.get()
                if (!config.isApiConfigured()) {
                    return@supplyAsync ""
                }

                val systemPrompt = buildResponsePrompt(playerUsername, category, conversationHistory, recentResponses)
                val userPrompt = "Message de $senderName: \"$originalMessage\"\n\nGenere ta reponse:"

                if (config.testModeActive) {
                    logger.info("[ETAPE 2 - GENERATION]")
                    logger.info("[API] Categorie: $category")
                    logger.info("[API] Prompt systeme: ${systemPrompt.take(200)}...")
                }

                val response = callMistralApi(config.mistralApiKey, systemPrompt, userPrompt)

                if (config.testModeActive) {
                    logger.info("[API] Reponse brute: \"$response\"")
                }

                // Nettoyer la reponse
                var cleanResponse = response.trim()
                    .removePrefix("\"").removeSuffix("\"")
                    .removePrefix("'").removeSuffix("'")
                    .replace(Regex("^(Reponse|Message)\\s*:\\s*", RegexOption.IGNORE_CASE), "")

                // Limiter a 50 caracteres max
                if (cleanResponse.length > 50) {
                    cleanResponse = cleanResponse.substring(0, 50)
                }

                if (config.testModeActive) {
                    logger.info("[API] Reponse finale: \"$cleanResponse\"")
                }

                cleanResponse
            } catch (e: Exception) {
                logger.error("Erreur lors de la generation: ${e.message}")
                ""
            }
        }, executor)
    }

    /**
     * Construit le prompt de generation selon la categorie.
     * C'est ici que la "magie" opère - chaque categorie a un comportement different.
     * @param conversationHistory Historique des derniers messages de la conversation
     * @param recentResponses Les dernieres reponses envoyees (pour eviter repetitions)
     */
    private fun buildResponsePrompt(playerUsername: String, category: MessageCategory, conversationHistory: List<String> = emptyList(), recentResponses: List<String> = emptyList()): String {
        // Construire le contexte d'historique
        val historyContext = if (conversationHistory.isNotEmpty()) {
            """

HISTORIQUE RECENT DE LA CONVERSATION:
${conversationHistory.joinToString("\n")}
Utilise ce contexte pour repondre de facon coherente."""
        } else {
            ""
        }

        // Construire la liste des reponses a eviter
        val avoidContext = if (recentResponses.isNotEmpty()) {
            """

REPONSES DEJA UTILISEES (NE PAS REPETER):
${recentResponses.joinToString(", ")}
Tu DOIS varier tes reponses! Utilise des synonymes ou reformule."""
        } else {
            ""
        }

        val baseRules = """Tu es $playerUsername, un joueur Minecraft qui est occupe (en train de farmer).
Tu dois generer une reponse TRES COURTE (1-5 mots max).
Style: langage familier de joueur (yo, wesh, trql, dsl, nn, etc.)
JAMAIS de ponctuation excessive. JAMAIS formel.$historyContext$avoidContext"""

        return when (category) {
            MessageCategory.GREETING -> """$baseRules

CONTEXTE: C'est une SALUTATION simple.
OBJECTIF: Repondre amicalement mais brievement.

Exemples de reponses:
- "salut Dayti" -> "yo" ou "salut"
- "wesh" -> "wesh"
- "hey Dayti" -> "hey" ou "yo"
- "cc" -> "cc" ou "yo"
- "coucou" -> "salut"

Reponds UNIQUEMENT avec le message (1-2 mots)."""

            MessageCategory.GREETING_WITH_STATE -> """$baseRules

CONTEXTE: C'est une salutation AVEC question sur ton etat (ca va, cv).
OBJECTIF: Repondre positivement ET retourner la question.

Exemples de reponses:
- "ca va ?" -> "oui et toi" ou "ca va et toi"
- "cv?" -> "oui toi" ou "bien et toi"
- "wesh ca va" -> "trql et toi"
- "yo ca va ou quoi" -> "tranquille toi"
- "salut cv" -> "yo ca va toi"
- "ca farte ?" -> "oui toi" ou "tranquille toi"

IMPORTANT: Tu dois TOUJOURS inclure "et toi" ou "toi" a la fin!
Reponds UNIQUEMENT avec le message (2-4 mots)."""

            MessageCategory.QUESTION_COMMERCIAL -> """$baseRules

CONTEXTE: C'est une question COMMERCIALE (achat/vente).
OBJECTIF: REFUSER poliment. Tu n'as rien a vendre, tu ne veux rien acheter.

Exemples de reponses:
- "t'as du bois a vendre?" -> "nn dsl j'ai pas"
- "tu vends des diamonds?" -> "non dsl"
- "tu veux acheter du fer?" -> "nn merci"
- "t'aurais de la stone?" -> "dsl j'en ai pas"

Reponds UNIQUEMENT avec le message (2-4 mots). Toujours negatif/refus."""

            MessageCategory.INVITATION -> """$baseRules

CONTEXTE: C'est une INVITATION a faire quelque chose.
OBJECTIF: REFUSER poliment. Tu es occupe.

Exemples de reponses:
- "tu veux farmer avec moi?" -> "nn dsl jsuis occupe"
- "viens pvp" -> "pas maintenant dsl"
- "on fait un donjon?" -> "nn je peux pas la"
- "tu viens spawn?" -> "dsl jsuis occupe la"

Reponds UNIQUEMENT avec le message (2-4 mots). Toujours refuser poliment."""

            MessageCategory.QUESTION_HELP -> """$baseRules

CONTEXTE: C'est une DEMANDE D'AIDE explicite.
OBJECTIF: REFUSER poliment. Tu es occupe et ne peux pas aider maintenant.

Exemples de reponses:
- "tu peux m'aider?" -> "dsl jsuis occupe la"
- "t'as 2 sec?" -> "nn dsl pas maintenant"
- "j'ai besoin d'aide" -> "dsl je peux pas la"

Reponds UNIQUEMENT avec le message (2-4 mots). Toujours refuser poliment."""

            MessageCategory.QUESTION_LOCATION -> """$baseRules

CONTEXTE: Question sur ta localisation ou activite.
OBJECTIF: Repondre vaguement, tu es occupe.

Exemples de reponses:
- "t'es ou?" -> "jsuis occupe la"
- "tu fais quoi?" -> "des trucs"
- "t'es la?" -> "oui mais occupe"

Reponds UNIQUEMENT avec le message (2-3 mots). Rester vague."""

            MessageCategory.ACKNOWLEDGMENT -> """$baseRules

CONTEXTE: C'est une CONFIRMATION/ACQUIESCEMENT (nickel, ok, d'accord, super, cool).
OBJECTIF: NE PAS REPONDRE ou reponse minimaliste. La conversation est terminee naturellement.

Exemples de situations ou NE PAS repondre:
- "nickel" apres une reponse -> pas de reponse necessaire
- "ok" -> pas de reponse necessaire
- "d'accord" -> pas de reponse necessaire
- "super" -> pas de reponse necessaire

Si vraiment necessaire, reponse ultra-courte:
- "nickel" -> "" (rien)
- "ok cool" -> "" (rien)

Reponds avec une chaine VIDE "" dans 90% des cas. La conversation est finie."""

            MessageCategory.CONFUSION -> """$baseRules

CONTEXTE: La personne est CONFUSE par quelque chose que tu as dit (?, hein, quoi).
OBJECTIF: Clarifier brevement ou s'excuser pour la confusion.

Exemples de reponses:
- "?" -> "nan rien tkt" ou "laisse tkt"
- "hein" -> "rien rien" ou "tkt"
- "quoi" -> "nan laisse" ou "rien dsl"
- "??" -> "tkt laisse" ou "oublie"

Reponds UNIQUEMENT avec le message (2-3 mots). Desamorcer la confusion."""

            MessageCategory.IGNORE -> """$baseRules
Ne reponds pas, ce message n'est pas pour toi."""
        }
    }

    /**
     * Parse la categorie depuis la reponse de l'API.
     */
    private fun parseCategory(response: String): MessageCategory {
        val upperResponse = response.uppercase()
        return when {
            upperResponse.startsWith("GREETING_WITH_STATE") -> MessageCategory.GREETING_WITH_STATE
            upperResponse.startsWith("GREETING") -> MessageCategory.GREETING
            upperResponse.startsWith("QUESTION_COMMERCIAL") -> MessageCategory.QUESTION_COMMERCIAL
            upperResponse.startsWith("INVITATION") -> MessageCategory.INVITATION
            upperResponse.startsWith("QUESTION_HELP") -> MessageCategory.QUESTION_HELP
            upperResponse.startsWith("QUESTION_LOCATION") -> MessageCategory.QUESTION_LOCATION
            upperResponse.startsWith("ACKNOWLEDGMENT") -> MessageCategory.ACKNOWLEDGMENT
            upperResponse.startsWith("CONFUSION") -> MessageCategory.CONFUSION
            else -> MessageCategory.IGNORE
        }
    }

    /**
     * Appelle l'API Mistral avec un prompt systeme et utilisateur.
     */
    private fun callMistralApi(apiKey: String, systemPrompt: String, userPrompt: String): String {
        val url = URI(API_URL).toURL()
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val requestBody = JsonObject().apply {
                addProperty("model", MODEL)
                add("messages", com.google.gson.JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        addProperty("content", userPrompt)
                    })
                })
                addProperty("temperature", 0.7)
                addProperty("max_tokens", 100)
            }

            connection.outputStream.use { os ->
                os.write(gson.toJson(requestBody).toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
                logger.error("Erreur API Mistral ($responseCode): $errorBody")
                throw RuntimeException("Erreur API: $responseCode")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject

            return jsonResponse
                .getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()

        } finally {
            connection.disconnect()
        }
    }
}
