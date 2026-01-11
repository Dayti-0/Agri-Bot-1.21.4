package fr.nix.agribot.chat

import org.slf4j.LoggerFactory
import java.text.Normalizer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gestionnaire d'evenements de chat.
 * Permet d'enregistrer des callbacks pour reagir aux messages recus.
 */
object ChatListener {
    private val logger = LoggerFactory.getLogger("agribot")

    // Pattern pre-compile pour supprimer les accents (Normalizer + regex)
    private val DIACRITICS_PATTERN = "\\p{InCombiningDiacriticalMarks}+".toRegex()

    // Messages a detecter
    const val STATION_FULL_MESSAGE = "Votre Station de Croissance est déjà pleine d'eau"
    const val STATION_EMPTY_MESSAGE = "Votre Station de Croissance est vide"
    const val TELEPORT_MESSAGE = "Téléporté"

    // Messages indiquant un event actif (teleportation forcee)
    // Ces messages signifient qu'un event est en cours et le bot doit se deconnecter
    private val FORCED_TELEPORT_KEYWORDS = listOf(
        "teleportation",
        "vers le dernier emplacement",
        "teleporte en",
        "teleporte a ",  // Espace important pour eviter match avec "teleporte au"
        "teleporte vers",
        "vous avez ete teleporte",
        "a ete teleporte"
    )

    // Pattern indiquant une teleportation initiee par le bot (a ignorer)
    // Format: "Téléporté au home: nomDuHome"
    private const val HOME_TELEPORT_PATTERN = "teleporte au home:"

    // Callbacks enregistres
    private val callbacks = CopyOnWriteArrayList<(String) -> Unit>()

    // Etat de detection
    @Volatile
    var stationFullDetected = false
        private set

    @Volatile
    var teleportDetected = false
        private set

    @Volatile
    var forcedTeleportDetected = false
        private set

    @Volatile
    var lastMessage: String = ""
        private set

    /**
     * Appele par le Mixin quand un message de chat est recu.
     * Cette methode est protegee contre les exceptions pour eviter de bloquer le jeu.
     */
    fun onChatMessage(message: String) {
        try {
            lastMessage = message

            // Detection station pleine
            if (message.contains(STATION_FULL_MESSAGE)) {
                stationFullDetected = true
                logger.info("Detection: Station pleine!")
            }

            // Detection teleportation
            if (message.contains(TELEPORT_MESSAGE)) {
                teleportDetected = true
                logger.debug("Detection: Teleportation effectuee")
            }

            // Detection teleportation forcee (event actif)
            // Normaliser le message en minuscules et sans accents pour comparaison
            val normalizedMessage = normalizeForComparison(message)

            // Verifier d'abord si c'est une teleportation vers un home (initiee par le bot)
            val isHomeTeleport = normalizedMessage.contains(HOME_TELEPORT_PATTERN)

            if (!isHomeTeleport && FORCED_TELEPORT_KEYWORDS.any { keyword -> normalizedMessage.contains(keyword) }) {
                forcedTeleportDetected = true
                logger.warn("Detection: Teleportation forcee (event actif)! Message: $message")
            } else if (isHomeTeleport) {
                logger.debug("Teleportation home ignoree: $message")
            }

            // Traiter le message pour l'auto-reponse
            try {
                AutoResponseManager.processMessage(message)
            } catch (e: Exception) {
                logger.error("Erreur dans AutoResponseManager: ${e.message}")
            }

            // Appeler les callbacks de maniere isolee (un echec ne bloque pas les autres)
            callbacks.forEach { callback ->
                try {
                    callback(message)
                } catch (e: Exception) {
                    // Log detaille de l'erreur avec la stack trace
                    logger.error("Erreur dans callback chat pour message '$message': ${e.message}")
                    logger.error("Stack trace: ${e.stackTraceToString()}")
                    // Continuer avec les autres callbacks malgre l'erreur
                }
            }
        } catch (e: Exception) {
            // Protection globale pour eviter de bloquer le jeu en cas d'erreur inattendue
            logger.error("Erreur critique dans onChatMessage: ${e.message}")
            logger.error("Message qui a cause l'erreur: $message")
            logger.error("Stack trace: ${e.stackTraceToString()}")
        }
    }

    /**
     * Normalise un texte pour la comparaison (minuscules, sans accents).
     * Optimise: utilise java.text.Normalizer au lieu de 13 replace() en chaine.
     */
    private fun normalizeForComparison(text: String): String {
        // NFD decompose les caracteres accentues en base + diacritique
        // Ensuite on supprime les diacritiques avec le pattern pre-compile
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return DIACRITICS_PATTERN.replace(normalized, "").lowercase()
    }

    /**
     * Enregistre un callback pour recevoir les messages de chat.
     */
    fun registerCallback(callback: (String) -> Unit) {
        callbacks.add(callback)
    }

    /**
     * Supprime un callback.
     */
    fun unregisterCallback(callback: (String) -> Unit) {
        callbacks.remove(callback)
    }

    /**
     * Reinitialise la detection de station pleine.
     */
    fun resetStationFullDetection() {
        stationFullDetected = false
    }

    /**
     * Reinitialise la detection de teleportation.
     */
    fun resetTeleportDetection() {
        teleportDetected = false
    }

    /**
     * Reinitialise la detection de teleportation forcee (event).
     */
    fun resetForcedTeleportDetection() {
        forcedTeleportDetected = false
    }

    /**
     * Reinitialise toutes les detections.
     */
    fun resetAllDetections() {
        stationFullDetected = false
        teleportDetected = false
        forcedTeleportDetected = false
    }

    /**
     * Attend qu'un message contenant le texte specifie soit detecte.
     * @param text Texte a chercher dans les messages
     * @param timeoutMs Timeout en millisecondes
     * @return true si detecte, false si timeout
     */
    suspend fun waitForMessage(text: String, timeoutMs: Long = 10000): Boolean {
        val startTime = System.currentTimeMillis()
        var detected = false

        val callback: (String) -> Unit = { message ->
            if (message.contains(text)) {
                detected = true
            }
        }

        registerCallback(callback)

        try {
            while (!detected && (System.currentTimeMillis() - startTime) < timeoutMs) {
                kotlinx.coroutines.delay(100)
            }
        } finally {
            unregisterCallback(callback)
        }

        return detected
    }
}
