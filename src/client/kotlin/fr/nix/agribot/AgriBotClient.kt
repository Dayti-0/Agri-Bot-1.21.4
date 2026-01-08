package fr.nix.agribot

import fr.nix.agribot.bot.AutoStartManager
import fr.nix.agribot.bot.BotCore
import fr.nix.agribot.bot.BotState
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.config.Plants
import fr.nix.agribot.input.KeyBindings
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import org.slf4j.LoggerFactory

object AgriBotClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("agribot")

    lateinit var config: AgriConfig
        private set

    override fun onInitializeClient() {
        logger.info("==================================================")
        logger.info("AgriBot - Initialisation du client")
        logger.info("==================================================")

        // Charger la configuration
        config = AgriConfig.load()

        // Enregistrer les touches
        KeyBindings.register()

        // Initialiser le coeur du bot
        BotCore.init()

        // Initialiser le gestionnaire de demarrage automatique
        AutoStartManager.init()

        // Enregistrer le rendu du timer de session sur l'ecran multijoueur
        registerSessionTimerRenderer()

        // Afficher les infos de config
        logger.info("Serveur: ${config.serverAddress}")
        logger.info("Plante: ${config.selectedPlant} (boost: ${config.growthBoost}%)")
        logger.info("Stations actives: ${config.getActiveStationCount()}/30")

        val plantData = config.getSelectedPlantData()
        if (plantData != null) {
            val tempsTotal = plantData.tempsTotalCroissance(config.growthBoost)
            logger.info("Temps de croissance: ${Plants.formatTemps(tempsTotal)}")
        }

        logger.info("Mode seaux actuel: ${config.getBucketMode()} (${config.getBucketCount()} seaux)")
        logger.info("==================================================")
        logger.info("Touches: F6 (toggle bot) | F8 (configuration)")
        logger.info("AgriBot pret!")
    }

    /**
     * Enregistre le callback pour afficher le timer de session sur l'ecran multijoueur.
     */
    private fun registerSessionTimerRenderer() {
        ScreenEvents.BEFORE_INIT.register { client, screen, scaledWidth, scaledHeight ->
            if (screen is MultiplayerScreen) {
                ScreenEvents.afterRender(screen).register { screen, context, mouseX, mouseY, tickDelta ->
                    renderSessionTimer(context)
                }
            }
        }
    }

    /**
     * Affiche le timer de session sur l'ecran multijoueur.
     */
    private fun renderSessionTimer(context: net.minecraft.client.gui.DrawContext) {
        val client = MinecraftClient.getInstance()

        var timeText: String? = null
        var textColor = 0x55FF55 // Vert par defaut

        // Si le bot n'est pas active, verifier la configuration et afficher l'etat
        if (!config.botEnabled) {
            // Verifier les erreurs de configuration
            val hasPassword = config.loginPassword.isNotBlank()
            val stationCount = config.getActiveStationCount()
            val hasStations = stationCount > 0

            if (!hasPassword) {
                timeText = "AgriBot: Mot de passe manquant"
                textColor = 0xFF5555 // Rouge
            } else if (!hasStations) {
                timeText = "AgriBot: 0 stations"
                textColor = 0xFF5555 // Rouge
            } else {
                // Tout est configure, pret a demarrer
                timeText = "AgriBot: Pret $stationCount Stations"
                textColor = 0x55FF55 // Vert clair
            }
        } else {
            val currentState = BotCore.stateData.state

            when (currentState) {
                BotState.PAUSED -> {
                    val pauseEndTime = BotCore.stateData.pauseEndTime
                    val currentTime = System.currentTimeMillis()
                    val remainingMs = pauseEndTime - currentTime

                    if (remainingMs > 0) {
                        // Formater le temps restant
                        val totalSeconds = remainingMs / 1000
                        val hours = totalSeconds / 3600
                        val minutes = (totalSeconds % 3600) / 60
                        val seconds = totalSeconds % 60

                        timeText = if (hours > 0) {
                            String.format("Prochaine session: %dh %02dm %02ds", hours, minutes, seconds)
                        } else if (minutes > 0) {
                            String.format("Prochaine session: %dm %02ds", minutes, seconds)
                        } else {
                            String.format("Prochaine session: %ds", seconds)
                        }
                    } else {
                        // Temps ecoule, attente de reconnexion
                        timeText = "Reconnexion en cours..."
                        textColor = 0xFFFF55 // Jaune
                    }
                }
                BotState.IDLE -> {
                    // Bot en attente - afficher un message d'indication
                    timeText = "AgriBot: Pret"
                    textColor = 0xAAAAAA // Gris
                }
                else -> {
                    // Autres etats - ne rien afficher
                }
            }
        }

        // Dessiner le texte si necessaire
        if (timeText != null) {
            val textWidth = client.textRenderer.getWidth(timeText)

            // Fond semi-transparent
            context.fill(8, 8, 16 + textWidth, 22, 0x80000000.toInt())

            // Texte
            context.drawText(client.textRenderer, timeText, 12, 12, textColor, true)
        }
    }
}