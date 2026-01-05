package fr.nix.agribot.bot

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.action.ActionManager
import fr.nix.agribot.chat.ChatListener
import fr.nix.agribot.chat.ChatManager
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.inventory.InventoryManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import org.slf4j.LoggerFactory

/**
 * Coeur du bot - gere le cycle de farming.
 */
object BotCore {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    private val config: AgriConfig
        get() = AgriBotClient.config

    val stateData = BotStateData()

    // Delais en ticks (20 ticks = 1 seconde)
    private const val TICKS_PER_SECOND = 20
    private var tickCounter = 0
    private var waitTicks = 0

    /**
     * Initialise le bot et enregistre le tick handler.
     */
    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            if (config.botEnabled) {
                onTick()
            }
        }
        logger.info("BotCore initialise")
    }

    /**
     * Demarre une nouvelle session de farming.
     */
    fun startSession() {
        if (stateData.state != BotState.IDLE && stateData.state != BotState.PAUSED) {
            logger.warn("Session deja en cours")
            return
        }

        // Verifier la connexion
        if (!ChatManager.isConnected()) {
            ChatManager.showActionBar("Pas connecte au serveur!", "c")
            return
        }

        // Verifier les stations
        val stations = config.getActiveStations()
        if (stations.isEmpty()) {
            ChatManager.showActionBar("Aucune station configuree!", "c")
            return
        }

        // Verifier periode de redemarrage serveur
        if (config.isServerRestartPeriod()) {
            ChatManager.showActionBar("Redemarrage serveur en cours, attente...", "e")
            return
        }

        logger.info("========================================")
        logger.info("Demarrage session de farming")
        logger.info("Stations: ${stations.size}")
        logger.info("Plante: ${config.selectedPlant}")
        logger.info("========================================")

        stateData.apply {
            state = BotState.MANAGING_BUCKETS
            currentStationIndex = 0
            totalStations = stations.size
            sessionStartTime = System.currentTimeMillis()
            stationsCompleted = 0
            needsWaterRefill = config.shouldRefillWater()
            currentBucketSlot = config.bucketSlot
            fullBucketsRemaining = config.fullBucketsInSlot
        }

        // Determiner le mode seaux au premier lancement
        if (stateData.isFirstSession) {
            stateData.currentBucketSlot = 1
            stateData.fullBucketsRemaining = 0
            config.bucketSlot = 1
            config.fullBucketsInSlot = 0
            stateData.isFirstSession = false
        }

        ChatManager.showActionBar("Session demarree - ${stations.size} stations", "a")
        ChatListener.resetAllDetections()

        // Commencer par la gestion des seaux si necessaire
        checkBucketManagement()
    }

    /**
     * Arrete le bot.
     */
    fun stop() {
        logger.info("Arret du bot")
        ActionManager.releaseAllKeys()
        stateData.state = BotState.IDLE
        config.botEnabled = false
        ChatManager.showActionBar("Bot arrete", "c")
    }

    /**
     * Appele a chaque tick quand le bot est actif.
     */
    private fun onTick() {
        tickCounter++

        // Attendre si necessaire
        if (waitTicks > 0) {
            waitTicks--
            return
        }

        // Machine d'etat
        when (stateData.state) {
            BotState.IDLE -> { /* Rien */ }
            BotState.MANAGING_BUCKETS -> handleManagingBuckets()
            BotState.TELEPORTING -> handleTeleporting()
            BotState.WAITING_TELEPORT -> handleWaitingTeleport()
            BotState.OPENING_STATION -> handleOpeningStation()
            BotState.HARVESTING -> handleHarvesting()
            BotState.PLANTING -> handlePlanting()
            BotState.FILLING_WATER -> handleFillingWater()
            BotState.REFILLING_BUCKETS -> handleRefillingBuckets()
            BotState.NEXT_STATION -> handleNextStation()
            BotState.DISCONNECTING -> handleDisconnecting()
            BotState.PAUSED -> handlePaused()
            BotState.ERROR -> handleError()
            else -> {}
        }
    }

    /**
     * Attend un certain nombre de ticks avant la prochaine action.
     */
    private fun wait(ticks: Int) {
        waitTicks = ticks
        stateData.lastActionTime = System.currentTimeMillis()
    }

    /**
     * Attend un certain nombre de millisecondes.
     */
    private fun waitMs(ms: Int) {
        wait(ms * TICKS_PER_SECOND / 1000)
    }

    // ==================== HANDLERS ====================

    private fun checkBucketManagement() {
        val currentMode = config.getBucketMode()
        val lastMode = config.lastBucketMode

        if (currentMode != lastMode && lastMode != null) {
            // Transition de mode
            when (currentMode) {
                "drop" -> {
                    logger.info("Transition vers mode matin - drop seaux")
                    // TODO: Implementer drop seaux
                }
                "retrieve" -> {
                    logger.info("Transition vers mode apres-midi - recuperation seaux")
                    // TODO: Implementer recuperation seaux
                }
            }
            config.lastBucketMode = currentMode
            config.save()
        }

        // Passer a la premiere station
        stateData.state = BotState.TELEPORTING
    }

    private fun handleManagingBuckets() {
        checkBucketManagement()
    }

    private fun handleTeleporting() {
        val stations = config.getActiveStations()
        if (stateData.currentStationIndex >= stations.size) {
            // Toutes les stations sont terminees
            stateData.state = BotState.DISCONNECTING
            return
        }

        val stationName = stations[stateData.currentStationIndex]
        logger.info("Station ${stateData.currentStationIndex + 1}/${stations.size}: $stationName")
        ChatManager.showActionBar("Station ${stateData.currentStationIndex + 1}/${stations.size}: $stationName", "6")

        // Teleportation
        ChatManager.teleportToHome(stationName)
        ChatListener.resetTeleportDetection()

        stateData.state = BotState.WAITING_TELEPORT
        waitMs(config.delayAfterTeleport)
    }

    private fun handleWaitingTeleport() {
        // Attente terminee, ouvrir la station
        // Selectionner le slot des graines d'abord (slot 0 / touche 0)
        InventoryManager.selectSlot(9) // Slot 10 = touche 0

        stateData.state = BotState.OPENING_STATION
        waitMs(200)
    }

    private fun handleOpeningStation() {
        // Clic droit pour ouvrir la station
        ActionManager.rightClick()

        stateData.state = BotState.HARVESTING
        waitMs(config.delayAfterOpenMenu)
    }

    private fun handleHarvesting() {
        // Recolte: clic droit (fruits) puis clic gauche (plante)
        ActionManager.rightClick()
        waitMs(500)

        // Le prochain tick fera le clic gauche
        stateData.state = BotState.PLANTING
        waitMs(500)
    }

    private fun handlePlanting() {
        // Clic gauche pour recolter
        ActionManager.leftClick()
        waitMs(300)

        // Fermer le menu
        ActionManager.pressEscape()
        waitMs(300)

        // Planter: shift + clic droit
        ActionManager.startSneaking()
        waitMs(100)
        ActionManager.rightClick()
        waitMs(300)
        ActionManager.stopSneaking()

        // Passer au remplissage d'eau si necessaire
        if (stateData.needsWaterRefill) {
            stateData.state = BotState.FILLING_WATER
            stateData.bucketsUsed = 0
            ChatListener.resetStationFullDetection()
        } else {
            stateData.state = BotState.NEXT_STATION
        }
        waitMs(500)
    }

    private fun handleFillingWater() {
        // Verifier si station pleine
        if (ChatListener.stationFullDetected) {
            logger.info("Station pleine apres ${stateData.bucketsUsed} seaux")
            stateData.state = BotState.NEXT_STATION
            return
        }

        // Selectionner le slot des seaux
        InventoryManager.selectBucketSlot(stateData.currentBucketSlot)
        waitMs(200)

        // Verifier si on a besoin de remplir les seaux
        if (stateData.fullBucketsRemaining <= 0) {
            stateData.state = BotState.REFILLING_BUCKETS
            return
        }

        // Vider un seau
        ActionManager.rightClick()
        stateData.fullBucketsRemaining--
        stateData.bucketsUsed++
        waitMs(config.delayBetweenBuckets)

        // Limite de securite
        if (stateData.bucketsUsed >= 32) {
            logger.warn("Limite de seaux atteinte")
            stateData.state = BotState.NEXT_STATION
        }
    }

    private fun handleRefillingBuckets() {
        // Utiliser /eau pour remplir les seaux
        ChatManager.fillBuckets()

        // Selon le mode (1 ou 16 seaux)
        val bucketCount = config.getBucketCount()
        if (bucketCount == 1) {
            stateData.fullBucketsRemaining = 1
        } else {
            // Changer de slot si necessaire
            if (stateData.currentBucketSlot == 1) {
                stateData.currentBucketSlot = 2
            } else {
                stateData.currentBucketSlot = 1
            }
            InventoryManager.selectBucketSlot(stateData.currentBucketSlot)
            stateData.fullBucketsRemaining = 16
        }

        config.bucketSlot = stateData.currentBucketSlot
        config.fullBucketsInSlot = stateData.fullBucketsRemaining

        stateData.state = BotState.FILLING_WATER
        waitMs(3000) // Attendre que /eau s'execute
    }

    private fun handleNextStation() {
        stateData.stationsCompleted++
        stateData.currentStationIndex++

        // Sauvegarder l'etat des seaux
        config.fullBucketsInSlot = stateData.fullBucketsRemaining
        config.bucketSlot = stateData.currentBucketSlot
        config.save()

        logger.info("Station terminee (${stateData.stationsCompleted}/${stateData.totalStations})")

        if (stateData.currentStationIndex >= stateData.totalStations) {
            // Toutes les stations terminees
            if (stateData.needsWaterRefill) {
                config.lastWaterRefillTime = System.currentTimeMillis() / 1000
                config.save()
            }
            stateData.state = BotState.DISCONNECTING
        } else {
            stateData.state = BotState.TELEPORTING
            waitMs(800)
        }
    }

    private fun handleDisconnecting() {
        val duration = (System.currentTimeMillis() - stateData.sessionStartTime) / 1000 / 60
        logger.info("========================================")
        logger.info("Session terminee - Duree: ${duration}min")
        logger.info("Stations completees: ${stateData.stationsCompleted}/${stateData.totalStations}")
        logger.info("========================================")

        ChatManager.showActionBar("Session terminee! ${stateData.stationsCompleted} stations", "a")

        // Passer en pause
        stateData.state = BotState.PAUSED
        val pauseSeconds = config.getSessionPauseSeconds()
        waitMs(pauseSeconds * 1000)

        logger.info("Pause de ${pauseSeconds / 60} minutes avant prochaine session")
    }

    private fun handlePaused() {
        // Fin de pause, relancer une session
        logger.info("Fin de pause, nouvelle session")
        startSession()
    }

    private fun handleError() {
        logger.error("Erreur: ${stateData.errorMessage}")
        ChatManager.showActionBar("Erreur: ${stateData.errorMessage}", "c")
        stop()
    }
}
