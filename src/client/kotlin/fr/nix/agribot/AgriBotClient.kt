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

    // Cache pour le rendu du timer (evite recalculs a chaque frame)
    private var cachedTimerText: String? = null
    private var cachedTimerColor: Int = 0x55FF55
    private var lastTimerUpdateTime: Long = 0
    private var lastBotEnabled: Boolean = false
    private var lastBotState: BotState? = null
    private var lastPauseEndTime: Long = 0
    private var lastStartupEndTime: Long = 0

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
     * Optimise: ne recalcule le texte que toutes les secondes ou si l'etat change.
     */
    private fun renderSessionTimer(context: net.minecraft.client.gui.DrawContext) {
        val client = MinecraftClient.getInstance()
        val currentTime = System.currentTimeMillis()

        // Verifier si on doit recalculer le cache (toutes les secondes ou si etat change)
        val currentBotEnabled = config.botEnabled
        val currentBotState = BotCore.stateData.state
        val currentPauseEndTime = BotCore.stateData.pauseEndTime
        val currentStartupEndTime = BotCore.stateData.startupEndTime

        val needsUpdate = cachedTimerText == null ||
                          currentTime - lastTimerUpdateTime >= 1000 ||
                          currentBotEnabled != lastBotEnabled ||
                          currentBotState != lastBotState ||
                          (currentBotState == BotState.PAUSED && currentPauseEndTime != lastPauseEndTime) ||
                          (currentBotState == BotState.WAITING_STARTUP && currentStartupEndTime != lastStartupEndTime)

        if (needsUpdate) {
            lastTimerUpdateTime = currentTime
            lastBotEnabled = currentBotEnabled
            lastBotState = currentBotState
            lastPauseEndTime = currentPauseEndTime
            lastStartupEndTime = currentStartupEndTime

            // Recalculer le texte et la couleur
            updateTimerCache(currentTime)
        }

        // Dessiner le texte cache si disponible
        val text = cachedTimerText
        if (text != null) {
            val textWidth = client.textRenderer.getWidth(text)
            context.fill(8, 8, 16 + textWidth, 22, 0x80000000.toInt())
            context.drawText(client.textRenderer, text, 12, 12, cachedTimerColor, true)
        }
    }

    /**
     * Met a jour le cache du timer.
     */
    private fun updateTimerCache(currentTime: Long) {
        cachedTimerText = null
        cachedTimerColor = 0x55FF55

        if (!config.botEnabled) {
            val hasPassword = config.loginPassword.isNotBlank()
            val stationCount = config.getActiveStationCount()

            if (!hasPassword) {
                cachedTimerText = "AgriBot: Mot de passe manquant"
                cachedTimerColor = 0xFF5555
            } else if (stationCount == 0) {
                cachedTimerText = "AgriBot: 0 stations"
                cachedTimerColor = 0xFF5555
            } else {
                cachedTimerText = "AgriBot: Pret $stationCount Stations"
                cachedTimerColor = 0x55FF55
            }
        } else {
            when (BotCore.stateData.state) {
                BotState.WAITING_STARTUP -> {
                    val remainingMs = BotCore.stateData.startupEndTime - currentTime
                    if (remainingMs > 0) {
                        val totalSeconds = remainingMs / 1000
                        val hours = totalSeconds / 3600
                        val minutes = (totalSeconds % 3600) / 60
                        val seconds = totalSeconds % 60

                        cachedTimerText = when {
                            hours > 0 -> "Demarrage dans: ${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
                            minutes > 0 -> "Demarrage dans: ${minutes}m ${seconds.toString().padStart(2, '0')}s"
                            else -> "Demarrage dans: ${seconds}s"
                        }
                        cachedTimerColor = 0xFFFF55  // Jaune
                    } else {
                        cachedTimerText = "Demarrage..."
                        cachedTimerColor = 0x55FF55
                    }
                }
                BotState.PAUSED -> {
                    val remainingMs = BotCore.stateData.pauseEndTime - currentTime
                    if (remainingMs > 0) {
                        val totalSeconds = remainingMs / 1000
                        val hours = totalSeconds / 3600
                        val minutes = (totalSeconds % 3600) / 60
                        val seconds = totalSeconds % 60

                        cachedTimerText = when {
                            hours > 0 -> "Prochaine session: ${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
                            minutes > 0 -> "Prochaine session: ${minutes}m ${seconds.toString().padStart(2, '0')}s"
                            else -> "Prochaine session: ${seconds}s"
                        }
                    } else {
                        cachedTimerText = "Reconnexion en cours..."
                        cachedTimerColor = 0xFFFF55
                    }
                }
                BotState.IDLE -> {
                    cachedTimerText = "AgriBot: Pret"
                    cachedTimerColor = 0xAAAAAA
                }
                else -> { }
            }
        }
    }
}