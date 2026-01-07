package fr.nix.agribot.bot

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.chat.ChatManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory

/**
 * Gestionnaire du demarrage automatique du bot.
 * Permet de demarrer le bot automatiquement apres connexion au serveur depuis le menu principal.
 */
object AutoStartManager {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    /** Flag pour activer le demarrage automatique */
    private var autoStartEnabled = false

    /** Compteur d'attente apres connexion */
    private var waitCounter = 0

    /** Etat de la connexion precedente */
    private var wasConnected = false

    /**
     * Active le demarrage automatique du bot.
     * Le bot demarrera automatiquement une fois connecte au serveur.
     */
    fun setAutoStartEnabled(enabled: Boolean) {
        autoStartEnabled = enabled
        waitCounter = 0
        if (enabled) {
            logger.info("Demarrage automatique du bot active")
        }
    }

    /**
     * Verifie si le demarrage automatique est active.
     */
    fun isAutoStartEnabled(): Boolean {
        return autoStartEnabled
    }

    /**
     * Initialise le gestionnaire et enregistre le tick handler.
     */
    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            onTick()
        }
        logger.info("AutoStartManager initialise")
    }

    /**
     * Traite un tick pour verifier si on doit demarrer le bot.
     */
    private fun onTick() {
        if (!autoStartEnabled) {
            wasConnected = ChatManager.isConnected()
            return
        }

        val isConnected = ChatManager.isConnected()

        // Detecter la transition de "non connecte" a "connecte"
        if (isConnected && !wasConnected) {
            logger.info("Connexion au serveur detectee - attente stabilisation...")
            waitCounter = 0
        }

        wasConnected = isConnected

        // Si connecte, attendre un peu avant de demarrer le bot
        if (isConnected && autoStartEnabled) {
            waitCounter++

            // Attendre 3 secondes (60 ticks) pour que la connexion se stabilise
            if (waitCounter >= 60) {
                logger.info("Demarrage automatique du bot...")
                autoStartEnabled = false
                waitCounter = 0

                // Activer et demarrer le bot
                val config = AgriBotClient.config
                config.botEnabled = true
                BotCore.startSession()
            }
        }
    }
}
