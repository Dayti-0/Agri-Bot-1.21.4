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

    // Pseudos connus comme etant des messages systeme/serveur (pas des joueurs reels)
    // Evite de repondre aux messages de bienvenue, login, etc.
    private val SYSTEM_SENDERS = setOf(
        "survivalworld", "sw", "server", "serveur", "system", "admin",
        // Faux positifs courants des messages de bienvenue (regex match sur "venu »", "mail »", etc.)
        "venu", "mail", "email"
    )

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

    // ============================================
    // HISTORIQUE DE CONVERSATION (pour contexte API)
    // ============================================
    // Stocke les derniers messages echanges: sender -> liste de (auteur, message, timestamp)
    data class ConversationMessage(val author: String, val content: String, val timestamp: Long, val isBot: Boolean)
    private val conversationHistory = mutableMapOf<String, MutableList<ConversationMessage>>()
    private const val MAX_HISTORY_MESSAGES = 5  // Garder les 5 derniers messages par conversation

    // ============================================
    // VARIETE DES REPONSES (eviter repetitions)
    // ============================================
    // Stocke les dernieres reponses envoyees a chaque joueur: sender -> liste de reponses
    private val recentResponses = mutableMapOf<String, MutableList<String>>()
    private const val MAX_RECENT_RESPONSES = 5  // Garder les 5 dernieres reponses par joueur

    // ============================================
    // REGROUPEMENT DE MESSAGES
    // ============================================
    // Messages en attente de regroupement: sender -> (messages, timestamp premier message)
    data class PendingMessages(val messages: MutableList<String>, val firstMessageTime: Long)
    private val pendingIncomingMessages = mutableMapOf<String, PendingMessages>()
    // Delai d'attente pour regrouper les messages (ms)
    private const val MESSAGE_GROUPING_DELAY_MS = 2000L  // 2 secondes

    /**
     * Initialise le gestionnaire.
     */
    fun init() {
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "agribot-autoresponse").apply { isDaemon = true }
        }

        // Tache periodique pour envoyer les reponses en attente
        scheduler?.scheduleAtFixedRate({
            processPendingResponses()
        }, 100, 100, TimeUnit.MILLISECONDS)

        // Tache periodique pour traiter les messages groupes
        scheduler?.scheduleAtFixedRate({
            processGroupedMessages()
        }, 500, 500, TimeUnit.MILLISECONDS)

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

        // Ajouter a l'historique de conversation
        addToConversationHistory(chatContent.sender, chatContent.sender, chatContent.content, false)

        // Verifier d'abord les reponses speciales (damn)
        if (checkDamnResponse(chatContent.sender, chatContent.content)) {
            return
        }

        // Verifier si c'est un "re" (reponse rapide ~1s)
        if (checkReResponse(chatContent.sender, chatContent.content)) {
            return
        }

        // Ajouter le message a la file de regroupement au lieu de traiter immediatement
        addToPendingMessages(chatContent.sender, chatContent.content)
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
                    // FILTRAGE SUPPLEMENTAIRE
                    val isGreeting = analysis.category == MistralApiClient.MessageCategory.GREETING ||
                                     analysis.category == MistralApiClient.MessageCategory.GREETING_WITH_STATE
                    val isAcknowledgment = analysis.category == MistralApiClient.MessageCategory.ACKNOWLEDGMENT
                    val isConfusion = analysis.category == MistralApiClient.MessageCategory.CONFUSION

                    logger.info("[MODE TEST] -> Est salutation: $isGreeting")
                    logger.info("[MODE TEST] -> Est acknowledgment: $isAcknowledgment")
                    logger.info("[MODE TEST] -> Est confusion: $isConfusion")

                    // ACKNOWLEDGMENT: fin naturelle de la conversation, pas de reponse
                    if (isAcknowledgment) {
                        logger.info("[MODE TEST] -> Acknowledgment recu, fin de conversation (pas de reponse)")
                        logger.info("[MODE TEST] ====================================")
                        return@thenAccept
                    }

                    // CONFUSION: repondre uniquement si conversation active
                    if (isConfusion && !isActiveConversation) {
                        logger.info("[MODE TEST] -> Confusion ignoree (pas de conversation active)")
                        logger.info("[MODE TEST] ====================================")
                        return@thenAccept
                    }

                    val shouldActuallyRespond = isGreeting || isConfusion || pseudoMentioned || isActiveConversation
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

        // Recuperer l'historique de conversation et les reponses recentes
        val conversationHistory = getConversationHistoryForApi(sender)
        val recentResponses = getRecentResponsesForApi(sender)

        if (conversationHistory.isNotEmpty()) {
            logger.info("Historique de conversation: ${conversationHistory.size} messages")
        }

        // ETAPE 1: Classification du message (avec contexte conversationnel ET historique)
        MistralApiClient.analyzeMessage(message, playerName, sender, isActiveConversation, conversationHistory)
            .thenAccept { analysis ->
                if (analysis.shouldRespond) {
                    // FILTRAGE SUPPLEMENTAIRE POUR LES QUESTIONS
                    // - Salutations: toujours repondre
                    // - ACKNOWLEDGMENT: ne JAMAIS repondre (nickel, ok, super = fin de conversation)
                    // - CONFUSION: repondre si conversation active (pour clarifier)
                    // - Autres questions: necessitent mention ou conversation active
                    val isGreeting = analysis.category == MistralApiClient.MessageCategory.GREETING ||
                                     analysis.category == MistralApiClient.MessageCategory.GREETING_WITH_STATE
                    val isAcknowledgment = analysis.category == MistralApiClient.MessageCategory.ACKNOWLEDGMENT
                    val isConfusion = analysis.category == MistralApiClient.MessageCategory.CONFUSION

                    // ACKNOWLEDGMENT: fin naturelle de la conversation, pas de reponse
                    if (isAcknowledgment) {
                        logger.info("Acknowledgment recu, fin de conversation: $message")
                        return@thenAccept
                    }

                    // CONFUSION: repondre uniquement si conversation active (le "?" est pour nous)
                    if (isConfusion && !isActiveConversation) {
                        logger.info("Confusion ignoree (pas de conversation active): $message")
                        return@thenAccept
                    }

                    val shouldActuallyRespond = isGreeting || isConfusion || pseudoMentioned || isActiveConversation

                    if (!shouldActuallyRespond) {
                        logger.info("Question ignoree (pseudo non mentionne, pas de conversation active): $message")
                        return@thenAccept
                    }

                    logger.info("Message classifie: ${analysis.category} - $message (${analysis.reason})")

                    // ETAPE 2: Generation de reponse avec la categorie, l'historique et les reponses recentes
                    MistralApiClient.generateResponse(message, sender, playerName, analysis.category, conversationHistory, recentResponses)
                        .thenAccept { response ->
                            if (response.isNotEmpty()) {
                                scheduleResponse(response)
                                // Marquer la conversation comme active apres avoir repondu
                                markConversationActive(sender)
                                // Ajouter a l'historique et aux reponses recentes
                                addToConversationHistory(sender, playerName, response, true)
                                addRecentResponse(sender, response)
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

            // Filtrer les faux positifs: messages systeme/serveur et pseudos invalides
            if (sender.lowercase() in SYSTEM_SENDERS) {
                logger.debug("Message systeme ignore: sender='$sender' (raw: '$message')")
                return null
            }

            // Un pseudo Minecraft valide fait entre 3 et 16 caracteres
            if (sender.length < 3 || sender.length > 16) {
                logger.debug("Pseudo invalide ignore (longueur ${sender.length}): '$sender' (raw: '$message')")
                return null
            }

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
        conversationHistory.clear()
        recentResponses.clear()
        pendingIncomingMessages.clear()
    }

    // ============================================
    // GESTION DE L'HISTORIQUE DE CONVERSATION
    // ============================================

    /**
     * Ajoute un message a l'historique de conversation.
     * @param sender Le joueur avec qui on converse (cle de l'historique)
     * @param author L'auteur du message (peut etre le bot ou le joueur)
     * @param content Le contenu du message
     * @param isBot True si c'est une reponse du bot
     */
    private fun addToConversationHistory(sender: String, author: String, content: String, isBot: Boolean) {
        val normalizedSender = normalizeText(sender)
        val history = conversationHistory.getOrPut(normalizedSender) { mutableListOf() }

        history.add(ConversationMessage(author, content, System.currentTimeMillis(), isBot))

        // Limiter a MAX_HISTORY_MESSAGES
        while (history.size > MAX_HISTORY_MESSAGES) {
            history.removeAt(0)
        }
    }

    /**
     * Recupere l'historique de conversation formate pour l'API.
     * @param sender Le joueur avec qui on converse
     * @return Liste des messages recents (auteur: message)
     */
    fun getConversationHistoryForApi(sender: String): List<String> {
        val normalizedSender = normalizeText(sender)
        val history = conversationHistory[normalizedSender] ?: return emptyList()

        // Filtrer les messages trop vieux (plus de 2 minutes)
        val now = System.currentTimeMillis()
        val recentHistory = history.filter { now - it.timestamp < 120_000L }

        return recentHistory.map { msg ->
            val prefix = if (msg.isBot) "[Toi]" else "[${msg.author}]"
            "$prefix ${msg.content}"
        }
    }

    // ============================================
    // GESTION DE LA VARIETE DES REPONSES
    // ============================================

    /**
     * Ajoute une reponse aux reponses recentes pour un joueur.
     */
    private fun addRecentResponse(sender: String, response: String) {
        val normalizedSender = normalizeText(sender)
        val responses = recentResponses.getOrPut(normalizedSender) { mutableListOf() }

        responses.add(response.lowercase())

        // Limiter a MAX_RECENT_RESPONSES
        while (responses.size > MAX_RECENT_RESPONSES) {
            responses.removeAt(0)
        }
    }

    /**
     * Recupere les dernieres reponses envoyees a un joueur.
     */
    fun getRecentResponsesForApi(sender: String): List<String> {
        val normalizedSender = normalizeText(sender)
        return recentResponses[normalizedSender]?.toList() ?: emptyList()
    }

    // ============================================
    // REGROUPEMENT DE MESSAGES
    // ============================================

    /**
     * Ajoute un message a la file d'attente de regroupement.
     * Le message sera traite apres MESSAGE_GROUPING_DELAY_MS si aucun autre message n'arrive.
     */
    private fun addToPendingMessages(sender: String, content: String) {
        val normalizedSender = normalizeText(sender)
        val now = System.currentTimeMillis()

        val pending = pendingIncomingMessages.getOrPut(normalizedSender) {
            PendingMessages(mutableListOf(), now)
        }

        pending.messages.add(content)
        logger.debug("Message ajoute a la file de regroupement pour $sender (${pending.messages.size} messages)")
    }

    /**
     * Traite les messages groupes dont le delai d'attente est expire.
     * Appele periodiquement par le scheduler.
     */
    private fun processGroupedMessages() {
        val now = System.currentTimeMillis()
        val playerName = getPlayerUsername()

        // Copier les cles pour eviter ConcurrentModificationException
        val sendersToProcess = pendingIncomingMessages.keys.toList()

        for (sender in sendersToProcess) {
            val pending = pendingIncomingMessages[sender] ?: continue

            // Verifier si le delai est expire
            if (now - pending.firstMessageTime >= MESSAGE_GROUPING_DELAY_MS) {
                // Retirer de la file d'attente
                pendingIncomingMessages.remove(sender)

                if (pending.messages.isEmpty()) continue

                // Combiner les messages si plusieurs
                val combinedMessage = if (pending.messages.size == 1) {
                    pending.messages[0]
                } else {
                    // Joindre les messages avec " | " pour l'API
                    logger.info("Regroupement de ${pending.messages.size} messages de $sender")
                    pending.messages.joinToString(" | ")
                }

                // Retrouver le sender original (non normalise) - on utilise le premier caractere en majuscule
                val originalSender = sender.replaceFirstChar { it.uppercase() }

                // Traiter le message (ou le groupe de messages)
                analyzeAndRespond(originalSender, combinedMessage, playerName)
            }
        }
    }
}
