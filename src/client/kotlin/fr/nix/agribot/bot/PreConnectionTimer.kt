package fr.nix.agribot.bot

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.config.AgriConfig
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import org.slf4j.LoggerFactory

/**
 * Gestionnaire du timer de pre-connexion.
 * Affiche un compte a rebours sur la page des serveurs avant de se connecter.
 */
object PreConnectionTimer {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    /** Temps de fin du timer (0 si pas actif) */
    var timerEndTime: Long = 0
        private set

    /** Flag indiquant si le timer est actif */
    val isActive: Boolean
        get() = timerEndTime > 0 && System.currentTimeMillis() < timerEndTime

    /** Temps restant en millisecondes */
    val remainingMs: Long
        get() = if (isActive) timerEndTime - System.currentTimeMillis() else 0

    /** Reference a l'ecran pour la connexion */
    private var parentScreen: net.minecraft.client.gui.screen.Screen? = null

    /**
     * Initialise le gestionnaire et enregistre le tick handler.
     */
    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            onTick()
        }
        logger.info("PreConnectionTimer initialise")
    }

    /**
     * Demarre le timer de pre-connexion.
     * @param delayMinutes Duree du delai en minutes
     * @param screen L'ecran parent pour la connexion
     */
    fun startTimer(delayMinutes: Int, screen: net.minecraft.client.gui.screen.Screen) {
        if (delayMinutes <= 0) {
            // Pas de delai, connecter immediatement
            connectToServer(screen)
            return
        }

        val delayMs = delayMinutes * 60 * 1000L
        timerEndTime = System.currentTimeMillis() + delayMs
        parentScreen = screen

        logger.info("Timer de pre-connexion demarre: ${delayMinutes} minutes")
    }

    /**
     * Annule le timer actif.
     */
    fun cancelTimer() {
        if (isActive) {
            logger.info("Timer de pre-connexion annule")
        }
        timerEndTime = 0
        parentScreen = null
    }

    /**
     * Traite un tick pour verifier si le timer est expire.
     */
    private fun onTick() {
        if (timerEndTime <= 0) return

        val currentTime = System.currentTimeMillis()
        if (currentTime >= timerEndTime) {
            val config = AgriBotClient.config

            // Verifier si on est dans la periode de redemarrage serveur (5h40-6h40)
            if (config.isServerRestartPeriod()) {
                val waitTime = config.getTimeUntilRestartEnd()
                if (waitTime > 0) {
                    // Reporter la connexion a 6h30
                    timerEndTime = currentTime + waitTime
                    logger.info("Periode de redemarrage serveur - connexion reportee a 6h30 (attente ${waitTime / 60000} min)")
                    return
                }
            }

            logger.info("Timer de pre-connexion expire - connexion au serveur...")
            val screen = parentScreen
            timerEndTime = 0
            parentScreen = null

            if (screen != null) {
                connectToServer(screen)
            }
        }
    }

    /**
     * Se connecte au serveur configure.
     */
    private fun connectToServer(screen: net.minecraft.client.gui.screen.Screen) {
        val config = AgriBotClient.config
        val serverAddress = config.serverAddress

        // Creer les infos du serveur
        val serverInfo = ServerInfo(
            "SurvivalWorld",
            serverAddress,
            ServerInfo.ServerType.OTHER
        )

        // Parser l'adresse
        val address = ServerAddress.parse(serverAddress)

        // Activer le demarrage automatique du bot
        AutoStartManager.setAutoStartEnabled(true)

        // Remettre le delai a zero
        config.startupDelayMinutes = 0
        config.save()

        // Se connecter au serveur
        ConnectScreen.connect(
            screen,
            client,
            address,
            serverInfo,
            false,
            null
        )
    }

    /**
     * Formate le temps restant pour l'affichage.
     */
    fun formatRemainingTime(): String {
        val remaining = remainingMs
        if (remaining <= 0) return ""

        val totalSeconds = remaining / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "Connexion dans: ${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
            minutes > 0 -> "Connexion dans: ${minutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "Connexion dans: ${seconds}s"
        }
    }
}
