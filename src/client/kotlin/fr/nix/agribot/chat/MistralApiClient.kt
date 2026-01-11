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
 * Client pour l'API Mistral.
 * Permet de detecter si un message necessite une reponse et de generer des reponses.
 */
object MistralApiClient {
    private val logger = LoggerFactory.getLogger("agribot")
    private val gson = Gson()
    private val executor = Executors.newSingleThreadExecutor()

    private const val API_URL = "https://api.mistral.ai/v1/chat/completions"
    private const val MODEL = "mistral-small-latest"

    /**
     * Resultat de l'analyse d'un message.
     */
    data class MessageAnalysis(
        val shouldRespond: Boolean,
        val reason: String
    )

    /**
     * Analyse un message pour determiner si le bot doit y repondre.
     * @param message Le message du chat a analyser
     * @param playerUsername Le pseudo du joueur (bot)
     * @param senderName Le nom de l'expediteur du message
     * @return CompletableFuture avec le resultat de l'analyse
     */
    fun analyzeMessage(message: String, playerUsername: String, senderName: String): CompletableFuture<MessageAnalysis> {
        return CompletableFuture.supplyAsync({
            try {
                val config = AutoResponseConfig.get()
                if (!config.isApiConfigured()) {
                    return@supplyAsync MessageAnalysis(false, "API non configuree")
                }

                val systemPrompt = """Tu es un assistant qui analyse les messages de chat dans un jeu Minecraft multijoueur.
Tu dois determiner si un message est adresse directement au joueur "$playerUsername" ou pas.

Un message DOIT recevoir une reponse SI:
- Il contient le pseudo "$playerUsername" (ou une variante proche)
- Il est clairement adresse au joueur qui vient de se connecter (ex: "salut toi", "oh le boss est la", "wesh t'es la")
- C'est une salutation directe apres une connexion recente

Un message NE DOIT PAS recevoir de reponse SI:
- C'est une question generale (ex: "qui veut pvp ?", "quelqu'un a des diamonds ?", "qui veut farm ?")
- C'est un message adresse a quelqu'un d'autre (ex: "Redstone tu peux venir", "Admin tu es la ?")
- C'est un commentaire general (ex: "mdr", "gg", "le serveur lag", "ca lag")
- C'est une annonce generale (ex: "cc tout le monde", "bonsoir tout le monde")
- Le message ne mentionne pas du tout le joueur et n'est pas une reaction directe a sa connexion

Reponds UNIQUEMENT par "OUI" si le message necessite une reponse, ou "NON" sinon.
Ajoute une courte explication apres un tiret."""

                val userPrompt = "Message de $senderName: \"$message\"\n\nCe message est-il adresse a $playerUsername ?"

                // Log pour debug
                if (config.testModeActive) {
                    logger.info("[API MISTRAL] Analyse - Prompt systeme:")
                    logger.info("[API MISTRAL] $systemPrompt")
                    logger.info("[API MISTRAL] Analyse - Prompt utilisateur:")
                    logger.info("[API MISTRAL] $userPrompt")
                }

                val response = callMistralApi(config.mistralApiKey, systemPrompt, userPrompt)

                // Log de la reponse brute
                if (config.testModeActive) {
                    logger.info("[API MISTRAL] Reponse brute: \"$response\"")
                }

                val shouldRespond = response.uppercase().startsWith("OUI")
                val reason = if (response.contains("-")) response.substringAfter("-").trim() else response

                MessageAnalysis(shouldRespond, reason)
            } catch (e: Exception) {
                logger.error("Erreur lors de l'analyse du message: ${e.message}")
                MessageAnalysis(false, "Erreur: ${e.message}")
            }
        }, executor)
    }

    /**
     * Genere une reponse a un message.
     * @param originalMessage Le message original auquel repondre
     * @param senderName Le nom de l'expediteur
     * @param playerUsername Le pseudo du joueur (bot)
     * @return CompletableFuture avec la reponse generee
     */
    fun generateResponse(originalMessage: String, senderName: String, playerUsername: String): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            try {
                val config = AutoResponseConfig.get()
                if (!config.isApiConfigured()) {
                    return@supplyAsync ""
                }

                val systemPrompt = """Tu es un joueur Minecraft qui repond aux messages dans le chat.
Tu dois generer une reponse TRES COURTE et naturelle, comme un vrai joueur.

Regles STRICTES:
- Reponse MAXIMUM 1-3 mots (idealement 1-2 mots)
- Utilise un langage familier de joueur (yo, wesh, salut, cv, trql, etc.)
- Ne mets PAS de ponctuation excessive
- Ne sois PAS trop poli ou formel
- Adapte-toi au style du message recu

Exemples:
- "Salut Dayti !" -> "yo" ou "salut"
- "wesh Dayti" -> "wesh"
- "Ca va ?" ou "cv?" -> "trql et toi" ou "bien et toi"
- "oh le boss est la" -> "yo" ou "present"
- "hey Dayti !" -> "hey"
- "Yo Dayti Ca va ou quoi ?" -> "trql et toi"

Tu joues le role de $playerUsername. Reponds UNIQUEMENT avec le message a envoyer, rien d'autre."""

                val userPrompt = "Message de $senderName: \"$originalMessage\"\n\nGenere une reponse courte:"

                // Log pour debug
                if (config.testModeActive) {
                    logger.info("[API MISTRAL] Generation - Prompt systeme:")
                    logger.info("[API MISTRAL] $systemPrompt")
                    logger.info("[API MISTRAL] Generation - Prompt utilisateur:")
                    logger.info("[API MISTRAL] $userPrompt")
                }

                val response = callMistralApi(config.mistralApiKey, systemPrompt, userPrompt)

                // Log de la reponse brute
                if (config.testModeActive) {
                    logger.info("[API MISTRAL] Reponse brute generation: \"$response\"")
                }

                // Nettoyer la reponse (enlever guillemets potentiels, limiter la longueur)
                var cleanResponse = response.trim()
                    .removePrefix("\"").removeSuffix("\"")
                    .removePrefix("'").removeSuffix("'")

                // Limiter a 50 caracteres max pour le chat Minecraft
                if (cleanResponse.length > 50) {
                    cleanResponse = cleanResponse.substring(0, 50)
                }

                if (config.testModeActive) {
                    logger.info("[API MISTRAL] Reponse nettoyee: \"$cleanResponse\"")
                }

                cleanResponse
            } catch (e: Exception) {
                logger.error("Erreur lors de la generation de reponse: ${e.message}")
                ""
            }
        }, executor)
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

            // Construire le body JSON
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
