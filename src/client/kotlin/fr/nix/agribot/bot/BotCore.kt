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

    // Sous-etats pour la recolte
    private var harvestingStep = 0
    private var harvestingRetries = 0
    private const val MAX_HARVESTING_RETRIES = 5

    // Sous-etats pour la plantation
    private var plantingStep = 0

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

        // Determiner si c'est une session de remplissage intermediaire ou une session normale
        val isWaterOnly = stateData.waterRefillsRemaining > 0

        logger.info("========================================")
        if (isWaterOnly) {
            logger.info("Session de REMPLISSAGE D'EAU uniquement")
            logger.info("Remplissages restants: ${stateData.waterRefillsRemaining}")
        } else {
            logger.info("Demarrage session de farming")
        }
        logger.info("Stations: ${stations.size}")
        logger.info("Plante: ${config.selectedPlant}")
        logger.info("========================================")

        // Log l'etat des seaux (refreshState appele dans logState)
        BucketManager.logState()

        // Determiner si on doit remplir l'eau cette session
        val needsWater = if (isWaterOnly) {
            true // Session de remplissage -> toujours remplir
        } else {
            // Session normale (recolte/plantation) -> verifier si replantage necessite de l'eau
            config.shouldRefillWaterOnReplant()
        }

        // Calculer le nombre de remplissages necessaires pour un nouveau cycle
        val refillsNeeded = if (isWaterOnly) {
            stateData.waterRefillsRemaining - 1 // Decrementer car on va faire ce remplissage
        } else {
            config.calculateWaterRefillsNeeded()
        }

        // Timestamp du debut du cycle (garder l'ancien si session intermediaire)
        val cycleStart = if (isWaterOnly) {
            stateData.cycleStartTime
        } else {
            System.currentTimeMillis() / 1000
        }

        stateData.apply {
            state = BotState.MANAGING_BUCKETS
            currentStationIndex = 0
            totalStations = stations.size
            sessionStartTime = System.currentTimeMillis()
            stationsCompleted = 0
            needsWaterRefill = needsWater
            this.isWaterOnlySession = isWaterOnly
            waterRefillsRemaining = refillsNeeded
            cycleStartTime = cycleStart
        }

        val sessionType = if (isWaterOnly) "Remplissage eau" else "Session farming"
        ChatManager.showActionBar("$sessionType - ${stations.size} stations", "a")
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
            BotState.EMPTYING_REMAINING_BUCKETS -> handleEmptyingRemainingBuckets()
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
                            // Trouver les slots des seaux dans l'inventaire du joueur (dans le menu)
                            val bucketSlots = InventoryManager.findBucketSlotsInChestMenu()
                            // Shift+clic sur les seaux a deposer (garder 1)
                            val slotsToDeposit = bucketSlots.take(toDeposit)
                            for (slot in slotsToDeposit) {
                                ActionManager.shiftClickSlot(slot)
                                Thread.sleep(100) // Petit delai entre chaque clic
                            }
                            logger.info("${slotsToDeposit.size} seaux deposes")
                        }
                        bucketManagementStep = 3
                        waitMs(500)
                    }
                    fr.nix.agribot.bucket.BucketMode.RETRIEVE -> {
                        // Apres-midi: recuperer les seaux du coffre
                        logger.info("Recuperation des seaux du coffre")
                        // Trouver les slots des seaux dans le coffre
                        val bucketSlots = InventoryManager.findBucketSlotsInChest()
                        // Shift+clic sur tous les seaux du coffre
                        for (slot in bucketSlots) {
                            ActionManager.shiftClickSlot(slot)
                            Thread.sleep(100) // Petit delai entre chaque clic
                        }
                        logger.info("${bucketSlots.size} seaux recuperes")
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
                // Etape 5: Sauvegarder le mode ET la periode (transition complete)
                BucketManager.saveTransitionComplete()
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
        val actionType = if (stateData.isWaterOnlySession) "Remplissage" else "Station"
        logger.info("$actionType ${stateData.currentStationIndex + 1}/${stations.size}: $stationName")
        ChatManager.showActionBar("$actionType ${stateData.currentStationIndex + 1}/${stations.size}: $stationName", "6")

        // Teleportation
        ChatManager.teleportToHome(stationName)
        ChatListener.resetTeleportDetection()

        stateData.state = BotState.WAITING_TELEPORT
        waitMs(config.delayAfterTeleport)
    }

    private fun handleWaitingTeleport() {
        // Session de remplissage d'eau uniquement -> passer directement au remplissage
        if (stateData.isWaterOnlySession) {
            logger.info("Session remplissage eau - passage direct au remplissage")
            BucketManager.prepareForStation()
            stateData.state = BotState.FILLING_WATER
            waitMs(100)
            return
        }

        // Session normale: ouvrir la station pour recolte/plantation
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
        when (harvestingStep) {
            0 -> {
                // Etape 1: Verifier d'abord s'il y a un melon a recolter
                val melonSlot = InventoryManager.findMelonSlotInMenu()
                if (melonSlot >= 0) {
                    // Melon present, on clique pour recolter
                    logger.info("Melon detecte - clic gauche pour recolter")
                    ActionManager.leftClick()
                    harvestingStep = 1
                    harvestingRetries = 0
                    waitMs(300)
                } else {
                    // Pas de melon - verifier si c'est une station vide (iron_bars presents)
                    val ironBarsSlot = InventoryManager.findIronBarsSlotInMenu()
                    if (ironBarsSlot >= 0) {
                        // Barreaux de fer sans melon = pas de pousse, on ferme directement
                        logger.info("Pas de melon mais barreaux de fer detectes - pas de recolte necessaire")
                        harvestingStep = 2
                    } else {
                        // Ni melon ni barreaux de fer - graine deja plantee, on ferme
                        logger.info("Graine deja plantee - pas de recolte necessaire")
                        harvestingStep = 2
                    }
                }
            }
            1 -> {
                // Etape 2: Verifier que le melon a disparu
                val melonSlot = InventoryManager.findMelonSlotInMenu()
                if (melonSlot < 0) {
                    // Melon disparu, on peut fermer le menu
                    logger.info("Melon recolte avec succes")
                    harvestingStep = 2
                } else {
                    // Melon encore present, reessayer
                    harvestingRetries++
                    if (harvestingRetries >= MAX_HARVESTING_RETRIES) {
                        logger.warn("Echec recolte apres $MAX_HARVESTING_RETRIES tentatives, on continue")
                        harvestingStep = 2
                    } else {
                        logger.info("Melon encore present, retry ${harvestingRetries}/$MAX_HARVESTING_RETRIES")
                        ActionManager.leftClick()
                        waitMs(300)
                    }
                }
            }
            2 -> {
                // Etape 3: Fermer le menu de station
                ActionManager.pressEscape()
                waitMs(300)
                harvestingStep = 0  // Reset pour la prochaine station
                stateData.state = BotState.PLANTING
            }
        }
    }

    private fun handlePlanting() {
        // Planter: sneak + micro pause + clic droit + relacher sneak
        // Machine d'etat pour assurer les delais entre chaque action
        // IMPORTANT: Il faut etre accroupi pour planter sinon ca ouvre le menu
        when (plantingStep) {
            0 -> {
                // Etape 1: S'accroupir (sneak)
                logger.info("Plantation - debut sneak")
                ActionManager.startSneaking()
                plantingStep = 1
                waitMs(300)  // Attendre que le sneak soit bien actif (delai augmente)
            }
            1 -> {
                // Etape 2: Verifier que le sneak est actif puis clic droit pour planter
                val player = MinecraftClient.getInstance().player
                if (player?.isSneaking == true) {
                    logger.info("Plantation - clic droit (sneak actif: ${player.isSneaking})")
                    ActionManager.rightClick()
                    plantingStep = 2
                    waitMs(200)  // Attendre que l'action soit executee
                } else {
                    // Sneak pas encore actif, reessayer de l'activer
                    logger.warn("Plantation - sneak non actif, reactivation...")
                    ActionManager.startSneaking()
                    waitMs(200)  // Attendre et reessayer
                }
            }
            2 -> {
                // Etape 3: Se relever
                logger.info("Plantation - fin sneak")
                ActionManager.stopSneaking()
                plantingStep = 0  // Reset pour la prochaine station

                // Passer au remplissage d'eau si necessaire
                if (stateData.needsWaterRefill) {
                    BucketManager.prepareForStation()
                    stateData.state = BotState.FILLING_WATER
                } else {
                    stateData.state = BotState.NEXT_STATION
                }
                waitMs(500)
            }
        }
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

        val stationType = if (stateData.isWaterOnlySession) "Remplissage" else "Station"
        logger.info("$stationType terminee (${stateData.stationsCompleted}/${stateData.totalStations})")

        if (stateData.currentStationIndex >= stateData.totalStations) {
            // Toutes les stations terminees
            if (stateData.needsWaterRefill) {
                config.lastWaterRefillTime = System.currentTimeMillis() / 1000
                config.save()
                logger.info("Timestamp remplissage eau sauvegarde")
            }
            // Vider les seaux restants avant de terminer (sauf si c'est une session de remplissage uniquement)
            if (stateData.isWaterOnlySession) {
                // Session de remplissage terminee, pas besoin de vider les seaux
                logger.info("Session remplissage terminee - ${stateData.waterRefillsRemaining} remplissages restants")
                stateData.state = BotState.DISCONNECTING
            } else {
                // Session farming terminee, vider les seaux
                stateData.state = BotState.EMPTYING_REMAINING_BUCKETS
            }
        } else {
            stateData.state = BotState.TELEPORTING
            waitMs(800)
        }
    }

    private fun handleEmptyingRemainingBuckets() {
        // Vider tous les seaux d'eau restants dans la derniere station
        BucketManager.refreshState()

        if (BucketManager.hasWaterBuckets()) {
            // Selectionner et vider un seau
            if (BucketManager.selectWaterBucket()) {
                logger.info("Vidage seau restant (${BucketManager.state.waterBucketsCount} restants)")
                ActionManager.rightClick()
                waitMs(4000)  // Delai long entre chaque seau (4 secondes)
            }
        } else {
            // Plus de seaux d'eau, on peut terminer
            logger.info("Tous les seaux ont ete vides")
            stateData.state = BotState.DISCONNECTING
        }
    }

    private fun handleDisconnecting() {
        val duration = (System.currentTimeMillis() - stateData.sessionStartTime) / 1000 / 60
        val sessionType = if (stateData.isWaterOnlySession) "remplissage eau" else "farming"

        logger.info("========================================")
        logger.info("Session $sessionType terminee - Duree: ${duration}min")
        logger.info("Stations completees: ${stateData.stationsCompleted}/${stateData.totalStations}")
        logger.info("Remplissages restants avant recolte: ${stateData.waterRefillsRemaining}")
        logger.info("========================================")

        ChatManager.showActionBar("Session terminee! ${stateData.stationsCompleted} stations", "a")

        // Calculer la pause en fonction des remplissages restants
        val pauseSeconds = config.getNextPauseSeconds(stateData.waterRefillsRemaining, stateData.cycleStartTime)
        val nextSessionType = if (stateData.waterRefillsRemaining > 0) "remplissage eau" else "recolte/plantation"

        logger.info("Pause de ${pauseSeconds / 60} minutes avant prochaine session ($nextSessionType)")

        // Passer en pause
        stateData.state = BotState.PAUSED
        waitMs(pauseSeconds * 1000)
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
