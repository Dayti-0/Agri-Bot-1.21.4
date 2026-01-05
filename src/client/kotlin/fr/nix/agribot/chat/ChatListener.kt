package fr.nix.agribot.chat

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Gestionnaire d'evenements de chat.
 * Permet d'enregistrer des callbacks pour reagir aux messages recus.
 */
object ChatListener {
    private val logger = LoggerFactory.getLogger("agribot")

    // Messages a detecter
    const val STATION_FULL_MESSAGE = "Votre Station de Croissance est déjà pleine d'eau"
    const val STATION_EMPTY_MESSAGE = "Votre Station de Croissance est vide"
    const val TELEPORT_MESSAGE = "Téléporté"

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
    var lastMessage: String = ""
        private set

    /**
     * Appele par le Mixin quand un message de chat est recu.
     */
    fun onChatMessage(message: String) {
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

        // Appeler les callbacks
        callbacks.forEach { callback ->
            try {
                callback(message)
            } catch (e: Exception) {
                logger.error("Erreur dans callback chat: ${e.message}")
            }
        }
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
     * Reinitialise toutes les detections.
     */
    fun resetAllDetections() {
        stationFullDetected = false
        teleportDetected = false
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
