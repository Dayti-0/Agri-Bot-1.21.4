package fr.nix.agribot

import fr.nix.agribot.bot.AutoStartManager
import fr.nix.agribot.bot.BotCore
import fr.nix.agribot.bot.BotState
import fr.nix.agribot.bot.PreConnectionTimer
import fr.nix.agribot.chat.AutoResponseManager
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.config.AutoResponseConfig
import fr.nix.agribot.config.Plants
import fr.nix.agribot.config.StatsConfig
import fr.nix.agribot.gui.ConfigScreen
import fr.nix.agribot.gui.StatsScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
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
    private var lastPreConnectionTimerActive: Boolean = false

    override fun onInitializeClient() {
        logger.info("==================================================")
        logger.info("AgriBot - Initialisation du client")
        logger.info("==================================================")

        // Charger la configuration
        config = AgriConfig.load()

        // Charger les statistiques
        StatsConfig.load()

        // Initialiser le coeur du bot
        BotCore.init()

        // Initialiser le gestionnaire de demarrage automatique
        AutoStartManager.init()

        // Initialiser le timer de pre-connexion
        PreConnectionTimer.init()

        // Initialiser le gestionnaire de reponses automatiques
        AutoResponseConfig.load()
        AutoResponseManager.init()

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

        // Ajouter les boutons Stats et Config sur l'ecran multijoueur
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            if (screen is MultiplayerScreen) {
                val statsButton = ButtonWidget.builder(Text.literal("Stats")) { _ ->
                    client.setScreen(StatsScreen(screen))
                }.dimensions(8, 26, 50, 20).build()
                Screens.getButtons(screen).add(statsButton)

                val configButton = ButtonWidget.builder(Text.literal("Config")) { _ ->
                    client.setScreen(ConfigScreen(screen))
                }.dimensions(62, 26, 50, 20).build()
                Screens.getButtons(screen).add(configButton)
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
        val currentPreConnectionTimerActive = PreConnectionTimer.isActive

        val needsUpdate = cachedTimerText == null ||
                          currentTime - lastTimerUpdateTime >= 1000 ||
                          currentBotEnabled != lastBotEnabled ||
                          currentBotState != lastBotState ||
                          currentPreConnectionTimerActive != lastPreConnectionTimerActive ||
                          (currentBotState == BotState.PAUSED && currentPauseEndTime != lastPauseEndTime) ||
                          (currentBotState == BotState.WAITING_STARTUP && currentStartupEndTime != lastStartupEndTime)

        if (needsUpdate) {
            lastTimerUpdateTime = currentTime
            lastBotEnabled = currentBotEnabled
            lastBotState = currentBotState
            lastPauseEndTime = currentPauseEndTime
            lastStartupEndTime = currentStartupEndTime
            lastPreConnectionTimerActive = currentPreConnectionTimerActive

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
     * Formate un compte a rebours en texte lisible avec l'heure de fin.
     * @param remainingMs millisecondes restantes
     * @param endEpochMs timestamp de fin (pour afficher l'heure)
     * @return "Xh XXm XXs (HH:MM)" ou null si remainingMs <= 0
     */
    private fun formatCountdown(remainingMs: Long, endEpochMs: Long): String? {
        if (remainingMs <= 0) return null
        val totalSeconds = remainingMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val endTime = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(endEpochMs),
            java.time.ZoneId.systemDefault()
        )
        val hourStr = String.format("%02d:%02d", endTime.hour, endTime.minute)

        val timeStr = when {
            hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m ${seconds.toString().padStart(2, '0')}s"
            minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
            else -> "${seconds}s"
        }
        return "$timeStr ($hourStr)"
    }

    /**
     * Met a jour le cache du timer.
     */
    private fun updateTimerCache(currentTime: Long) {
        cachedTimerText = null
        cachedTimerColor = 0x55FF55

        // Verifier d'abord si le timer de pre-connexion est actif
        if (PreConnectionTimer.isActive) {
            val preConnText = PreConnectionTimer.formatRemainingTime()
            val preConnEndTime = PreConnectionTimer.getEndTime()
            if (preConnEndTime > 0) {
                val endHour = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(preConnEndTime),
                    java.time.ZoneId.systemDefault()
                )
                val hourStr = String.format("%02d:%02d", endHour.hour, endHour.minute)
                cachedTimerText = "$preConnText ($hourStr)"
            } else {
                cachedTimerText = preConnText
            }
            cachedTimerColor = 0xFFFF55  // Jaune
            return
        }

        if (!config.botEnabled) {
            val errors = config.getStartupErrors()

            if (errors.isNotEmpty()) {
                cachedTimerText = "AgriBot: ${errors.first()}"
                cachedTimerColor = 0xFF5555
            } else {
                val stationCount = config.getActiveStationCount()
                cachedTimerText = "AgriBot: Pret $stationCount Stations"
                cachedTimerColor = 0x55FF55
            }
        } else {
            when (BotCore.stateData.state) {
                BotState.WAITING_STARTUP -> {
                    val remainingMs = BotCore.stateData.startupEndTime - currentTime
                    val countdown = formatCountdown(remainingMs, BotCore.stateData.startupEndTime)
                    if (countdown != null) {
                        cachedTimerText = "Demarrage dans: $countdown"
                        cachedTimerColor = 0xFFFF55  // Jaune
                    } else {
                        cachedTimerText = "Demarrage..."
                        cachedTimerColor = 0x55FF55
                    }
                }
                BotState.PAUSED -> {
                    val remainingMs = BotCore.stateData.pauseEndTime - currentTime
                    val countdown = formatCountdown(remainingMs, BotCore.stateData.pauseEndTime)
                    if (countdown != null) {
                        cachedTimerText = "Prochaine session: $countdown"
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