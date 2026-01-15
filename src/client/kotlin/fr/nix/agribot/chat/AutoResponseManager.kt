package fr.nix.agribot.chat

import fr.nix.agribot.config.AutoResponseConfig
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Gestionnaire de reponses automatiques aux messages du chat.
 * Detecte les messages adresses au joueur et genere des reponses naturelles.
 */
object AutoResponseManager {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    // Pattern pour normaliser le texte (supprimer accents)
    private val DIACRITICS_PATTERN = "\\p{InCombiningDiacriticalMarks}+".toRegex()

    // Regex pour parser les messages de chat du serveur
    // Capture le pseudo (commençant par une lettre) juste avant le separateur »
    // Fonctionne avec tous les formats:
    // - [niveau] 《Guilde》Pseudo★ » Message
    // - [niveau] Pseudo » Message
    // - [Admin] Pseudo » Message
    // - ShopDeGott Pseudo » Message (format shop sans crochets)
    // - Pseudo » Message (format simple)
    private val CHAT_MESSAGE_REGEX = Regex("""([A-Za-z_][A-Za-z0-9_]*)[★☆⚡☠🌙✨🔥❄☢⭐]*\s*»\s*(.+)$""")

    // Regex pour parser les messages de chat en mode solo/local
    // Format: < Pseudo> Message ou <Pseudo> Message
    private val SOLO_CHAT_MESSAGE_REGEX = Regex("""^<\s*(\S+)\s*>\s*(.+)$""")

    // Timestamp de la connexion au serveur de jeu
    private var connectionTimestamp: Long = 0

    // File d'attente des reponses a envoyer (avec delai)
    private data class PendingResponse(val message: String, val sendTime: Long)
    private val pendingResponses = ConcurrentLinkedQueue<PendingResponse>()

    // Scheduler pour envoyer les reponses avec delai
    private var scheduler: ScheduledExecutorService? = null

    // Messages deja traites (pour eviter les doublons)
    private val processedMessages = mutableSetOf<String>()
    private const val MAX_PROCESSED_MESSAGES = 100

    // Vitesse de frappe simulee (caracteres par seconde)
    private const val TYPING_SPEED_MIN = 4.0  // Lent
    private const val TYPING_SPEED_MAX = 8.0  // Rapide

    // Delai minimum avant de commencer a taper (reaction humaine)
    private const val REACTION_DELAY_MIN_MS = 800
    private const val REACTION_DELAY_MAX_MS = 2500

    // ============================================
    // SYSTEME DE CONTEXTE CONVERSATIONNEL
    // ============================================
    // Track les conversations actives: sender -> timestamp du dernier echange
    private val activeConversations = mutableMapOf<String, Long>()
    // Duree pendant laquelle une conversation reste active (30 secondes)
    private const val CONVERSATION_TIMEOUT_MS = 30_000L

    /**
     * Initialise le gestionnaire.
     */
    fun init() {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        // Tache periodique pour envoyer les reponses en attente
        scheduler?.scheduleAtFixedRate({
            processPendingResponses()
        }, 100, 100, TimeUnit.MILLISECONDS)

        logger.info("AutoResponseManager initialise")
    }

    /**
     * Signale que le joueur vient de se connecter au serveur de jeu.
     * Demarre la fenetre de detection de 2 minutes.
     */
    fun onGameServerConnected() {
        connectionTimestamp = System.currentTimeMillis()
        processedMessages.clear()
        logger.info("Fenetre de detection auto-reponse activee pour ${AutoResponseConfig.get().detectionWindowSeconds}s")
        ChatManager.showLocalMessage("Auto-reponse active (${AutoResponseConfig.get().detectionWindowSeconds}s)", "a")
    }

    /**
     * Verifie si on est dans la fenetre de detection.
     */
    fun isInDetectionWindow(): Boolean {
        if (connectionTimestamp == 0L) return false
        val elapsed = System.currentTimeMillis() - connectionTimestamp
        val windowMs = AutoResponseConfig.get().detectionWindowSeconds * 1000L
        return elapsed < windowMs
    }

    /**
     * Retourne le temps restant dans la fenetre de detection (en secondes).
     */
    fun getRemainingDetectionTime(): Int {
        if (connectionTimestamp == 0L) return 0
        val elapsed = System.currentTimeMillis() - connectionTimestamp
        val windowMs = AutoResponseConfig.get().detectionWindowSeconds * 1000L
        val remaining = (windowMs - elapsed) / 1000
        return maxOf(0, remaining.toInt())
    }

    /**
     * Traite un message de chat pour determiner si une reponse est necessaire.
     * @param rawMessage Le message brut du chat (avec prefixes de log)
     */
    fun processMessage(rawMessage: String) {
        val config = AutoResponseConfig.get()

        // Verifier si le systeme est active
        if (!config.enabled && !config.testModeActive) return

        // Extraire le contenu du message de chat (format serveur ou solo)
        val chatContent = extractChatContent(rawMessage)

        // Mode test: traiter les messages "msg:xxx"
        if (config.testModeActive) {
            // Verifier si le contenu du message contient "msg:"
            if (chatContent != null && chatContent.content.startsWith("msg:", ignoreCase = true)) {
                val testMessage = chatContent.content.substringAfter("msg:").trim()
                logger.info("[MODE TEST] ====================================")
                logger.info("[MODE TEST] Message de test recu de ${chatContent.sender}")
                logger.info("[MODE TEST] Contenu a analyser: \"$testMessage\"")
                processTestMessage(testMessage, chatContent.sender)
                return
            }
            // Ignorer les autres messages en mode test
            return
        }

        // Mode normal: verifier qu'on est dans la fenetre de detection
        if (!isInDetectionWindow()) return

        // Le contenu doit etre valide
        if (chatContent == null) return

        // Verifier si deja traite
        val messageKey = "${chatContent.sender}:${chatContent.content}"
        if (processedMessages.contains(messageKey)) return
        addProcessedMessage(messageKey)

        // Ignorer nos propres messages
        val playerName = getPlayerUsername()
        if (chatContent.sender.equals(playerName, ignoreCase = true)) return

        logger.info("Message recu de ${chatContent.sender}: ${chatContent.content}")

        // Verifier d'abord les reponses speciales (damn)
        if (checkDamnResponse(chatContent.sender, chatContent.content)) {
            return
        }

        // Verifier si c'est un "re" (reponse rapide ~1s)
        if (checkReResponse(chatContent.sender, chatContent.content)) {
            return
        }

        // Analyser avec l'API Mistral
        analyzeAndRespond(chatContent.sender, chatContent.content, playerName)
    }

    /**
     * Traite un message de test (mode test actif).
     * Utilise le systeme a 2 etapes: Classification -> Generation
     * @param message Le message a analyser
     * @param sender Le pseudo de l'expediteur (utilise pour le contexte)
     */
    private fun processTestMessage(message: String, sender: String = "TestPlayer") {
        val config = AutoResponseConfig.get()
        val playerName = getPlayerUsername()

        logger.info("[MODE TEST] ====================================")
        logger.info("[MODE TEST] Expediteur: $sender")
        logger.info("[MODE TEST] Joueur (bot): $playerName")
        logger.info("[MODE TEST] Message: \"$message\"")

        // Verifier d'abord les reponses speciales (damn)
        val normalizedSender = normalizeText(sender)
        val isDamnFriend = config.damnFriends.any { friend ->
            normalizedSender.contains(normalizeText(friend), ignoreCase = true)
        }
        logger.info("[MODE TEST] Est ami damn: $isDamnFriend (amis: ${config.damnFriends})")

        if (checkDamnResponse(sender, message)) {
            logger.info("[MODE TEST] -> Reponse damn declenchee!")
            return
        }

        // Verifier si c'est un "re"
        if (checkReResponse(sender, message)) {
            logger.info("[MODE TEST] -> Reponse 're' declenchee!")
            return
        }

        // Analyser avec l'API Mistral
        if (!config.isApiConfigured()) {
            logger.error("[MODE TEST] ERREUR: API Mistral non configuree")
            logger.error("[MODE TEST] Cle API presente: ${config.mistralApiKey.isNotBlank()}")
            return
        }

        // Verifier si on a une conversation active avec cet expediteur
        val isActiveConversation = hasActiveConversation(sender)
        logger.info("[MODE TEST] Conversation active: $isActiveConversation")

        // Verifier si le pseudo du bot est mentionne
        val pseudoMentioned = isPlayerMentioned(message, playerName)
        logger.info("[MODE TEST] Pseudo mentionne: $pseudoMentioned")

        logger.info("[MODE TEST] ------------------------------------")
        logger.info("[MODE TEST] ETAPE 1: CLASSIFICATION")

        MistralApiClient.analyzeMessage(message, playerName, sender, isActiveConversation)
            .thenAccept { analysis ->
                logger.info("[MODE TEST] -> Categorie: ${analysis.category}")
                logger.info("[MODE TEST] -> Doit repondre (Mistral): ${if (analysis.shouldRespond) "OUI" else "NON"}")
                logger.info("[MODE TEST] -> Raison: ${analysis.reason}")

                if (analysis.shouldRespond) {
                    // FILTRAGE SUPPLEMENTAIRE POUR LES QUESTIONS
                    val isGreeting = analysis.category == MistralApiClient.MessageCategory.GREETING ||
                                     analysis.category == MistralApiClient.MessageCategory.GREETING_WITH_STATE

                    val shouldActuallyRespond = isGreeting || pseudoMentioned || isActiveConversation

                    logger.info("[MODE TEST] -> Est salutation: $isGreeting")
                    logger.info("[MODE TEST] -> Repondre (filtre final): ${if (shouldActuallyRespond) "OUI" else "NON (question sans mention)"}")

                    if (!shouldActuallyRespond) {
                        logger.info("[MODE TEST] -> Question ignoree (pseudo non mentionne, pas de conversation active)")
                        logger.info("[MODE TEST] ====================================")
                        return@thenAccept
                    }

                    logger.info("[MODE TEST] ------------------------------------")
                    logger.info("[MODE TEST] ETAPE 2: GENERATION (categorie: ${analysis.category})")

                    // Generer la reponse avec la categorie
                    MistralApiClient.generateResponse(message, sender, playerName, analysis.category)
                        .thenAccept { response ->
                            logger.info("[MODE TEST] ------------------------------------")
                            logger.info("[MODE TEST] RESULTAT:")
                            if (response.isNotEmpty()) {
                                val typingDelay = calculateTypingDelay(response)
                                logger.info("[MODE TEST] -> Reponse generee: \"$response\"")
                                logger.info("[MODE TEST] -> Delai de frappe simule: ${typingDelay}ms")
                                // Envoyer vraiment la reponse dans le chat (conditions reelles)
                                scheduleResponse(response)
                                // Marquer la conversation comme active
                                markConversationActive(sender)
                                logger.info("[MODE TEST] -> Message programme pour envoi!")
                            } else {
                                logger.error("[MODE TEST] -> ERREUR: Reponse vide generee")
                            }
                            logger.info("[MODE TEST] ====================================")
                        }
                } else {
                    logger.info("[MODE TEST] -> Pas de reponse necessaire")
                    logger.info("[MODE TEST] ====================================")
                }
            }
            .exceptionally { e ->
                logger.error("[MODE TEST] ERREUR API: ${e.message}")
                logger.error("[MODE TEST] Stack trace:", e)
                logger.info("[MODE TEST] ====================================")
                null
            }
    }

    /**
     * Verifie si c'est un message "damn" d'un ami et repond en consequence.
     * @return true si une reponse damn a ete envoyee
     */
    private fun checkDamnResponse(sender: String, message: String): Boolean {
        val config = AutoResponseConfig.get()
        val normalizedMessage = normalizeText(message)
        val normalizedSender = normalizeText(sender)

        // Verifier si c'est un ami "damn"
        val isDamnFriend = config.damnFriends.any { friend ->
            normalizedSender.contains(normalizeText(friend), ignoreCase = true)
        }

        if (!isDamnFriend) return false

        // Verifier les patterns "damn"
        if (normalizedMessage.contains("damn neige") || normalizedMessage == "damn") {
            val typingDelay = calculateTypingDelay("damn neige")

            if (config.testModeActive) {
                // En mode test, logger ET envoyer (conditions reelles)
                logger.info("[MODE TEST] Pattern 'damn' detecte!")
                logger.info("[MODE TEST] -> Reponse: \"damn neige\"")
                logger.info("[MODE TEST] -> Delai de frappe simule: ${typingDelay}ms")
            }
            // Envoyer la reponse (mode test ou normal)
            scheduleResponse("damn neige")
            logger.info("Reponse damn programmee pour: $message")
            return true
        }

        // Verifier les patterns de salutation apres "damn" (ca va, cv, etc.)
        val greetingPatterns = listOf("ca va", "cv", "cv?", "ca va?", "ca va trql", "ca va ou quoi")
        val isGreeting = greetingPatterns.any { pattern ->
            normalizedMessage.contains(normalizeText(pattern))
        }

        if (isGreeting) {
            // Pour les salutations d'un ami damn, utiliser l'API Mistral
            // mais on retourne false pour laisser le traitement normal
            return false
        }

        return false
    }

    /**
     * Verifie si c'est un message "re" et repond en consequence.
     * Delai de reponse: ~1 seconde.
     * @return true si une reponse "re" a ete envoyee
     */
    private fun checkReResponse(sender: String, message: String): Boolean {
        val config = AutoResponseConfig.get()
        val normalizedMessage = normalizeText(message).trim()

        // Verifier si le message est exactement "re" (avec ou sans ponctuation)
        val isRe = normalizedMessage == "re" ||
                   normalizedMessage == "re!" ||
                   normalizedMessage == "re." ||
                   normalizedMessage == "re !"

        if (!isRe) return false

        if (config.testModeActive) {
            logger.info("[MODE TEST] Pattern 're' detecte!")
            logger.info("[MODE TEST] -> Reponse: \"re\"")
        }

        scheduleResponse("re")
        logger.info("Reponse 're' programmee pour: $message")
        return true
    }

    /**
     * Verifie si le pseudo du bot est mentionne dans le message.
     */
    private fun isPlayerMentioned(message: String, playerName: String): Boolean {
        val normalizedMessage = normalizeText(message)
        val normalizedPlayerName = normalizeText(playerName)
        return normalizedMessage.contains(normalizedPlayerName)
    }

    /**
     * Analyse un message avec l'API Mistral et repond si necessaire.
     * Utilise le systeme a 2 etapes: Classification -> Generation
     * Prend en compte le contexte conversationnel (conversations actives).
     *
     * FILTRAGE DES QUESTIONS:
     * - Salutations (GREETING, GREETING_WITH_STATE): toujours repondre (poli)
     * - Questions (autres categories): repondre uniquement si:
     *   - Le pseudo du bot est mentionne dans le message, OU
     *   - Une conversation active existe avec l'expediteur
     */
    private fun analyzeAndRespond(sender: String, message: String, playerName: String) {
        val config = AutoResponseConfig.get()

        if (!config.isApiConfigured()) {
            logger.warn("API Mistral non configuree, impossible d'analyser le message")
            return
        }

        // Verifier si on a une conversation active avec cet expediteur
        val isActiveConversation = hasActiveConversation(sender)
        if (isActiveConversation) {
            logger.info("Conversation active detectee avec $sender")
        }

        // Verifier si le pseudo du bot est mentionne
        val pseudoMentioned = isPlayerMentioned(message, playerName)
        if (pseudoMentioned) {
            logger.info("Pseudo mentionne dans le message")
        }

        // ETAPE 1: Classification du message (avec contexte conversationnel)
        MistralApiClient.analyzeMessage(message, playerName, sender, isActiveConversation)
            .thenAccept { analysis ->
                if (analysis.shouldRespond) {
                    // FILTRAGE SUPPLEMENTAIRE POUR LES QUESTIONS
                    // Les salutations passent toujours, les questions necessitent mention ou conversation active
                    val isGreeting = analysis.category == MistralApiClient.MessageCategory.GREETING ||
                                     analysis.category == MistralApiClient.MessageCategory.GREETING_WITH_STATE

                    val shouldActuallyRespond = isGreeting || pseudoMentioned || isActiveConversation

                    if (!shouldActuallyRespond) {
                        logger.info("Question ignoree (pseudo non mentionne, pas de conversation active): $message")
                        return@thenAccept
                    }

                    logger.info("Message classifie: ${analysis.category} - $message (${analysis.reason})")

                    // ETAPE 2: Generation de reponse avec la categorie
                    MistralApiClient.generateResponse(message, sender, playerName, analysis.category)
                        .thenAccept { response ->
                            if (response.isNotEmpty()) {
                                scheduleResponse(response)
                                // Marquer la conversation comme active apres avoir repondu
                                markConversationActive(sender)
                                logger.info("Reponse programmee [${analysis.category}]: $response")
                            }
                        }
                } else {
                    logger.debug("Message ignore: $message (${analysis.reason})")
                }
            }
            .exceptionally { e ->
                logger.error("Erreur lors de l'analyse: ${e.message}")
                null
            }
    }

    /**
     * Programme l'envoi d'une reponse avec un delai simulant la frappe humaine.
     * @param message Le message a envoyer
     * @param customDelayMs Delai personnalise en ms (si null, utilise le calcul de frappe humaine)
     */
    private fun scheduleResponse(message: String, customDelayMs: Long? = null) {
        val delay = customDelayMs ?: calculateTypingDelay(message)
        val sendTime = System.currentTimeMillis() + delay
        pendingResponses.add(PendingResponse(message, sendTime))
        logger.info("Reponse '$message' programmee dans ${delay}ms")
    }

    /**
     * Calcule le delai d'envoi pour simuler une frappe humaine.
     * @param message Le message a envoyer
     * @return Delai en millisecondes
     */
    private fun calculateTypingDelay(message: String): Long {
        // Delai de reaction (temps avant de commencer a taper)
        val reactionDelay = Random.nextLong(REACTION_DELAY_MIN_MS.toLong(), REACTION_DELAY_MAX_MS.toLong())

        // Temps de frappe (basé sur la longueur du message)
        val typingSpeed = Random.nextDouble(TYPING_SPEED_MIN, TYPING_SPEED_MAX)
        val typingTime = (message.length / typingSpeed * 1000).toLong()

        // Variation aleatoire (+/- 20%)
        val variation = (typingTime * Random.nextDouble(-0.2, 0.2)).toLong()

        return reactionDelay + typingTime + variation
    }

    /**
     * Traite les reponses en attente et les envoie quand le moment est venu.
     */
    private fun processPendingResponses() {
        val now = System.currentTimeMillis()

        while (true) {
            val pending = pendingResponses.peek() ?: break

            if (now >= pending.sendTime) {
                pendingResponses.poll()
                sendResponse(pending.message)
            } else {
                break
            }
        }
    }

    /**
     * Envoie une reponse dans le chat.
     */
    private fun sendResponse(message: String) {
        val config = AutoResponseConfig.get()

        // Log en mode test
        if (config.testModeActive) {
            logger.info("[MODE TEST] Envoi reel du message: $message")
        }

        // Envoyer sur le thread principal de Minecraft
        client.execute {
            ChatManager.sendMessage(message)
            logger.info("Reponse envoyee: $message")
        }
    }

    /**
     * Data class pour un message de chat parse.
     */
    private data class ChatContent(val sender: String, val content: String)

    /**
     * Extrait le contenu d'un message de chat du serveur ou en mode solo.
     * @param rawMessage Message brut (peut contenir prefixes de log)
     * @return ChatContent ou null si pas un message de chat valide
     */
    private fun extractChatContent(rawMessage: String): ChatContent? {
        // Nettoyer le message des prefixes de log
        var message = rawMessage

        // Supprimer le prefixe de log Minecraft si present
        // Format: [HH:MM:SS] [Thread/LEVEL]: [System] [CHAT] message
        if (message.contains("[CHAT]")) {
            message = message.substringAfter("[CHAT]").trim()
        }

        // Supprimer les codes couleur Minecraft (§x)
        message = message.replace(Regex("§[0-9a-fk-or]"), "")

        // 1. Parser le format du serveur multijoueur
        // Capture le pseudo (commençant par une lettre) juste avant »
        val serverMatch = CHAT_MESSAGE_REGEX.find(message)
        if (serverMatch != null) {
            val sender = serverMatch.groupValues[1].trim()
            val content = serverMatch.groupValues[2].trim()
            logger.debug("Message parse: sender='$sender', content='$content' (raw: '$message')")
            return ChatContent(sender, content)
        }

        // 2. Parser le format solo/local
        // Format: < Pseudo> Message ou <Pseudo> Message
        val soloMatch = SOLO_CHAT_MESSAGE_REGEX.find(message)
        if (soloMatch != null) {
            val sender = soloMatch.groupValues[1].trim()
            val content = soloMatch.groupValues[2].trim()
            logger.debug("Message solo parse: sender='$sender', content='$content'")
            return ChatContent(sender, content)
        }

        // Log si le message contient » mais n'a pas ete parse (potentiel bug)
        if (message.contains("»")) {
            logger.debug("Message avec » non parse: '$message'")
        }

        return null
    }

    /**
     * Normalise un texte (minuscules, sans accents).
     */
    private fun normalizeText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return DIACRITICS_PATTERN.replace(normalized, "").lowercase()
    }

    /**
     * Recupere le pseudo du joueur.
     */
    private fun getPlayerUsername(): String {
        val config = AutoResponseConfig.get()
        if (config.playerUsername.isNotBlank()) {
            return config.playerUsername
        }

        // Fallback: recuperer depuis le client Minecraft
        return client.session?.username ?: "Player"
    }

    /**
     * Ajoute un message a la liste des messages traites.
     */
    private fun addProcessedMessage(key: String) {
        processedMessages.add(key)

        // Limiter la taille de la liste
        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val iterator = processedMessages.iterator()
            repeat(processedMessages.size - MAX_PROCESSED_MESSAGES) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /**
     * Active/desactive le mode test.
     */
    fun setTestMode(enabled: Boolean) {
        val config = AutoResponseConfig.get()
        config.testModeActive = enabled
        config.save()

        if (enabled) {
            ChatManager.showLocalMessage("Mode test AUTO-REPONSE active. Tapez 'msg:votre message' pour tester.", "a")
        } else {
            ChatManager.showLocalMessage("Mode test AUTO-REPONSE desactive.", "6")
        }
    }

    /**
     * Bascule le mode test.
     */
    fun toggleTestMode() {
        setTestMode(!AutoResponseConfig.get().testModeActive)
    }

    // ============================================
    // GESTION DES CONVERSATIONS ACTIVES
    // ============================================

    /**
     * Verifie si on a une conversation active avec un joueur.
     * Une conversation est active si on a echange avec ce joueur dans les 30 dernieres secondes.
     */
    private fun hasActiveConversation(sender: String): Boolean {
        val normalizedSender = normalizeText(sender)
        val lastInteraction = activeConversations[normalizedSender] ?: return false
        val elapsed = System.currentTimeMillis() - lastInteraction
        return elapsed < CONVERSATION_TIMEOUT_MS
    }

    /**
     * Marque une conversation comme active (apres avoir repondu).
     */
    private fun markConversationActive(sender: String) {
        val normalizedSender = normalizeText(sender)
        activeConversations[normalizedSender] = System.currentTimeMillis()
        logger.debug("Conversation active avec $sender")

        // Nettoyer les vieilles conversations
        cleanupOldConversations()
    }

    /**
     * Nettoie les conversations expirees.
     */
    private fun cleanupOldConversations() {
        val now = System.currentTimeMillis()
        activeConversations.entries.removeIf { (_, timestamp) ->
            now - timestamp > CONVERSATION_TIMEOUT_MS
        }
    }

    /**
     * Reset le gestionnaire (par exemple lors de la deconnexion).
     */
    fun reset() {
        connectionTimestamp = 0
        pendingResponses.clear()
        processedMessages.clear()
        activeConversations.clear()
    }
}
