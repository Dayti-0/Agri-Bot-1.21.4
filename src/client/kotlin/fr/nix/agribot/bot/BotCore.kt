package fr.nix.agribot.bot

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.action.ActionManager
import fr.nix.agribot.bucket.BucketManager
import fr.nix.agribot.chat.ChatListener
import fr.nix.agribot.chat.ChatManager
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.inventory.InventoryManager
import fr.nix.agribot.menu.MenuDetector
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

    // Sous-etats pour la gestion des seaux dans le coffre
    private var bucketManagementStep = 0

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

        // Log l'etat des seaux (refreshState appele dans logState)
        BucketManager.logState()

        stateData.apply {
            state = BotState.MANAGING_BUCKETS
            currentStationIndex = 0
            totalStations = stations.size
            sessionStartTime = System.currentTimeMillis()
            stationsCompleted = 0
            needsWaterRefill = config.shouldRefillWater()
        }

        ChatManager.showActionBar("Session demarree - ${stations.size} stations", "a")
        ChatListener.resetAllDetections()

        // Verifier si on doit gerer les seaux (transition matin/apres-midi)
        if (BucketManager.needsModeTransition()) {
            bucketManagementStep = 0
            stateData.state = BotState.MANAGING_BUCKETS
        } else {
            BucketManager.saveCurrentMode()
            stateData.state = BotState.TELEPORTING
        }
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

    private fun handleManagingBuckets() {
        // Gestion des seaux via le coffre (depot ou recuperation)
        val mode = BucketManager.getCurrentMode()

        when (bucketManagementStep) {
            0 -> {
                // Etape 1: Se teleporter au coffre
                logger.info("Gestion seaux - TP vers coffre: ${config.homeCoffre}")
                ChatManager.teleportToHome(config.homeCoffre)
                bucketManagementStep = 1
                waitMs(config.delayAfterTeleport)
            }
            1 -> {
                // Etape 2: Ouvrir le coffre (clic droit)
                logger.info("Gestion seaux - Ouverture coffre")
                ActionManager.rightClick()

                // Attendre que le coffre soit ouvert et charge (timeout 5s + stabilisation 2s)
                if (MenuDetector.waitForChestOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
                    logger.info("Coffre ouvert et charge - Pret pour operations")
                    MenuDetector.debugCurrentMenu()
                    bucketManagementStep = 2
                    // Plus besoin de waitMs supplementaire, la stabilisation est geree
                } else {
                    logger.warn("Echec ouverture coffre - Timeout")
                    ChatManager.showActionBar("Echec ouverture coffre!", "c")
                    // Reessayer l'ouverture
                    bucketManagementStep = 1
                    waitMs(1000)
                }
            }
            2 -> {
                // Etape 3: Deposer ou recuperer les seaux selon le mode
                when (mode) {
                    fr.nix.agribot.bucket.BucketMode.MORNING -> {
                        // Matin: deposer 15 seaux (garder 1)
                        val toDeposit = BucketManager.getBucketsToDeposit()
                        if (toDeposit > 0) {
                            logger.info("Depot de $toDeposit seaux dans le coffre")
                            // Shift+clic pour transferer les seaux
                            // On selectionne d'abord un slot avec seaux
                            if (InventoryManager.findAllBucketSlots().isNotEmpty()) {
                                ActionManager.startSneaking()
                                ActionManager.rightClick() // Shift+clic transfere le stack
                                ActionManager.stopSneaking()
                            }
                        }
                        bucketManagementStep = 3
                        waitMs(500)
                    }
                    fr.nix.agribot.bucket.BucketMode.RETRIEVE -> {
                        // Apres-midi: recuperer les seaux du coffre
                        logger.info("Recuperation des seaux du coffre")
                        // Shift+clic sur les seaux dans le coffre
                        ActionManager.startSneaking()
                        ActionManager.rightClick()
                        ActionManager.stopSneaking()
                        bucketManagementStep = 3
                        waitMs(500)
                    }
                    else -> {
                        bucketManagementStep = 3
                    }
                }
            }
            3 -> {
                // Etape 4: Fermer le coffre
                ActionManager.closeScreen()
                bucketManagementStep = 4
                waitMs(500)
            }
            4 -> {
                // Etape 5: Sauvegarder le mode et passer aux stations
                BucketManager.saveCurrentMode()
                BucketManager.refreshState()
                logger.info("Gestion seaux terminee")
                stateData.state = BotState.TELEPORTING
            }
        }
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
        // Selectionner le slot des graines d'abord (cherche automatiquement dans la hotbar)
        InventoryManager.selectSeedsSlotAuto()

        stateData.state = BotState.OPENING_STATION
        waitMs(100)
    }

    private fun handleOpeningStation() {
        // Clic droit pour ouvrir la station
        ActionManager.rightClick()

        // Attendre que le menu de la station soit ouvert et charge (timeout 5s + stabilisation 2s)
        if (MenuDetector.waitForSimpleMenuOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
            logger.info("Station ouverte et chargee - Pret pour recolte")
            MenuDetector.debugCurrentMenu()
            stateData.state = BotState.HARVESTING
            // Plus besoin de waitMs supplementaire, la stabilisation est geree
        } else {
            logger.warn("Echec ouverture station - Timeout")
            ChatManager.showActionBar("Echec ouverture station!", "c")
            // Reessayer l'ouverture
            stateData.state = BotState.OPENING_STATION
            waitMs(1000)
        }
    }

    private fun handleHarvesting() {
        // Recolte: clic droit sur le slot du bloc de melon dans le menu
        // Detecter automatiquement le slot contenant le melon
        val melonSlot = InventoryManager.findMelonSlotInMenu()

        if (melonSlot >= 0) {
            logger.info("Recolte du melon au slot $melonSlot")
            ActionManager.rightClickSlot(melonSlot)
        } else {
            logger.warn("Aucun melon detecte, clic droit generique")
            ActionManager.rightClick()
        }

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
            BucketManager.prepareForStation()
            stateData.state = BotState.FILLING_WATER
        } else {
            stateData.state = BotState.NEXT_STATION
        }
        waitMs(500)
    }

    private fun handleFillingWater() {
        // Verifier si station pleine
        if (BucketManager.isStationFull()) {
            logger.info("Station pleine apres ${BucketManager.state.bucketsUsedThisStation} seaux")
            stateData.state = BotState.NEXT_STATION
            return
        }

        // Verifier si on a des seaux d'eau
        if (!BucketManager.hasWaterBuckets()) {
            // Besoin de remplir les seaux vides
            if (BucketManager.hasEmptyBuckets()) {
                stateData.state = BotState.REFILLING_BUCKETS
            } else {
                // Plus de seaux du tout
                logger.warn("Plus de seaux disponibles!")
                stateData.state = BotState.NEXT_STATION
            }
            return
        }

        // Selectionner un seau d'eau et le vider
        if (BucketManager.selectWaterBucket()) {
            waitMs(200)
            BucketManager.pourWaterBucket()
            waitMs(config.delayBetweenBuckets)
        }

        // Limite de securite
        if (BucketManager.state.bucketsUsedThisStation >= 50) {
            logger.warn("Limite de seaux atteinte")
            stateData.state = BotState.NEXT_STATION
        }
    }

    private fun handleRefillingBuckets() {
        // Selectionner les seaux vides
        if (!BucketManager.selectEmptyBuckets()) {
            logger.warn("Pas de seaux vides a remplir")
            stateData.state = BotState.NEXT_STATION
            return
        }

        waitMs(200)

        // Utiliser /eau pour remplir les seaux
        BucketManager.fillBucketsWithCommand()

        stateData.state = BotState.FILLING_WATER
        waitMs(3000) // Attendre que /eau s'execute
    }

    private fun handleNextStation() {
        stateData.stationsCompleted++
        stateData.currentStationIndex++

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
