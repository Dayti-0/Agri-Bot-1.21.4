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

    // Sous-etats pour le remplissage des seaux
    private var refillingStep = 0
    private var refillingCheckCount = 0
    private const val MAX_REFILLING_CHECKS = 100  // 100 x 100ms = 10 secondes max
    private const val REFILLING_CHECK_INTERVAL_MS = 100

    // Sous-etats pour l'ouverture des menus (non-bloquant)
    private var menuOpenStep = 0
    private var menuOpenRetries = 0
    private const val MAX_MENU_OPEN_RETRIES = 50  // 50 x 100ms = 5 secondes max
    private const val MENU_STABILIZATION_TICKS = 40  // 2 secondes de stabilisation (40 ticks)

    // Sous-etats pour le versement d'eau (non-bloquant)
    private var waterPouringStep = 0
    private var waterPouringCheckCount = 0
    private var waterBucketsBefore = 0
    private const val MAX_WATER_POURING_CHECKS = 100  // 100 ticks = 5 secondes max

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
     * Si le mot de passe est configure, lance d'abord la connexion automatique.
     */
    fun startSession() {
        if (stateData.state != BotState.IDLE && stateData.state != BotState.PAUSED) {
            logger.warn("Session deja en cours")
            return
        }

        // Verifier la connexion au serveur Minecraft
        if (!ChatManager.isConnected()) {
            ChatManager.showActionBar("Pas connecte au serveur!", "c")
            return
        }

        // Si mot de passe configure, lancer la connexion automatique d'abord
        if (config.loginPassword.isNotBlank()) {
            logger.info("Mot de passe configure - demarrage connexion automatique")
            ServerConnector.reset()
            if (ServerConnector.startConnection()) {
                stateData.state = BotState.CONNECTING
                return
            } else {
                // Erreur lors du demarrage de la connexion
                logger.warn("Erreur demarrage connexion: ${ServerConnector.errorMessage}")
                return
            }
        }

        // Pas de mot de passe, demarrer directement la session
        startFarmingSession()
    }

    /**
     * Demarre la session de farming proprement dite (apres connexion).
     */
    private fun startFarmingSession() {
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

        // Reinitialiser le delai adaptatif au debut de chaque session
        // pour ignorer le premier seau (qui a souvent un lag)
        BucketManager.resetAdaptiveDelay()

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
            BotState.CONNECTING -> handleConnecting()
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

    private fun handleConnecting() {
        // Deleguer au ServerConnector
        ServerConnector.onTick()

        // Verifier si la connexion est terminee
        if (ServerConnector.isFinished()) {
            if (ServerConnector.isConnected()) {
                logger.info("Connexion automatique reussie - demarrage session farming")
                startFarmingSession()
            } else {
                // Erreur de connexion
                logger.error("Echec connexion automatique: ${ServerConnector.errorMessage}")
                stateData.errorMessage = ServerConnector.errorMessage
                stateData.state = BotState.ERROR
            }
        }
    }

    // Variables pour la gestion des seaux dans le coffre (non-bloquant)
    private var bucketSlotsToProcess = listOf<Int>()
    private var bucketSlotIndex = 0

    private fun handleManagingBuckets() {
        // Gestion des seaux via le coffre (depot ou recuperation)
        val mode = BucketManager.getCurrentMode()

        when (bucketManagementStep) {
            0 -> {
                // Etape 1: Se teleporter au coffre
                logger.info("Gestion seaux - TP vers coffre: ${config.homeCoffre}")
                ChatManager.teleportToHome(config.homeCoffre)
                bucketManagementStep = 1
                menuOpenStep = 0
                menuOpenRetries = 0
                waitMs(config.delayAfterTeleport)
            }
            1 -> {
                // Etape 2: Ouvrir le coffre (non-bloquant)
                when (menuOpenStep) {
                    0 -> {
                        // Verifier d'abord si un coffre est deja ouvert
                        if (MenuDetector.isChestOrContainerOpen()) {
                            logger.info("Coffre deja ouvert - Pret pour operations")
                            MenuDetector.debugCurrentMenu()
                            bucketManagementStep = 2
                            menuOpenStep = 0
                            return
                        }

                        logger.info("Gestion seaux - Ouverture coffre")
                        ActionManager.rightClick()
                        menuOpenStep = 1
                        menuOpenRetries = 0
                        wait(2)  // Attendre 2 ticks avant de verifier
                    }
                    1 -> {
                        // Attendre que le coffre soit ouvert
                        if (MenuDetector.isChestOrContainerOpen()) {
                            logger.debug("Coffre detecte - attente stabilisation")
                            menuOpenStep = 2
                            wait(MENU_STABILIZATION_TICKS)  // Stabilisation
                        } else {
                            menuOpenRetries++
                            if (menuOpenRetries >= MAX_MENU_OPEN_RETRIES) {
                                logger.warn("Echec ouverture coffre - Timeout")
                                ChatManager.showActionBar("Echec ouverture coffre!", "c")
                                menuOpenStep = 0
                                waitMs(1000)  // Reessayer apres 1s
                            } else {
                                wait(2)  // Verifier toutes les 100ms
                            }
                        }
                    }
                    2 -> {
                        // Stabilisation terminee, verifier que le coffre est toujours ouvert
                        if (MenuDetector.isChestOrContainerOpen()) {
                            logger.info("Coffre ouvert et charge - Pret pour operations")
                            MenuDetector.debugCurrentMenu()
                            bucketManagementStep = 2
                            menuOpenStep = 0
                        } else {
                            logger.warn("Coffre ferme pendant stabilisation - Reessai")
                            menuOpenStep = 0
                            waitMs(500)
                        }
                    }
                }
            }
            2 -> {
                // Etape 3: Preparer les slots a traiter
                when (mode) {
                    fr.nix.agribot.bucket.BucketMode.MORNING -> {
                        val toDeposit = BucketManager.getBucketsToDeposit()
                        if (toDeposit > 0) {
                            logger.info("Depot de $toDeposit seaux dans le coffre")
                            val bucketSlots = InventoryManager.findBucketSlotsInChestMenu()
                            bucketSlotsToProcess = bucketSlots.take(toDeposit)
                            bucketSlotIndex = 0
                            bucketManagementStep = 3
                        } else {
                            bucketManagementStep = 4
                        }
                    }
                    fr.nix.agribot.bucket.BucketMode.RETRIEVE, fr.nix.agribot.bucket.BucketMode.NORMAL -> {
                        logger.info("Recuperation des seaux du coffre")
                        bucketSlotsToProcess = InventoryManager.findBucketSlotsInChest()
                        bucketSlotIndex = 0
                        bucketManagementStep = 3
                    }
                }
            }
            3 -> {
                // Etape 3b: Traiter les slots un par un (non-bloquant)
                if (bucketSlotIndex < bucketSlotsToProcess.size) {
                    val slot = bucketSlotsToProcess[bucketSlotIndex]
                    ActionManager.shiftClickSlot(slot)
                    bucketSlotIndex++
                    wait(2)  // 100ms entre chaque clic
                } else {
                    logger.info("${bucketSlotsToProcess.size} seaux traites")
                    bucketManagementStep = 4
                    waitMs(300)
                }
            }
            4 -> {
                // Etape 4: Fermer le coffre
                ActionManager.closeScreen()
                bucketManagementStep = 5
                waitMs(500)
            }
            5 -> {
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
        // Ouverture de la station (non-bloquant)
        when (menuOpenStep) {
            0 -> {
                // Verifier d'abord si un menu est deja ouvert
                if (MenuDetector.isSimpleMenuOpen()) {
                    logger.info("Station deja ouverte - Pret pour recolte")
                    MenuDetector.debugCurrentMenu()
                    stateData.state = BotState.HARVESTING
                    menuOpenStep = 0
                    return
                }

                // Clic droit pour ouvrir la station
                logger.info("Ouverture station - clic droit")
                ActionManager.rightClick()
                menuOpenStep = 1
                menuOpenRetries = 0
                wait(2)  // Attendre 2 ticks avant de verifier
            }
            1 -> {
                // Attendre que le menu soit ouvert
                if (MenuDetector.isSimpleMenuOpen()) {
                    logger.debug("Station detectee - attente stabilisation")
                    menuOpenStep = 2
                    wait(MENU_STABILIZATION_TICKS)  // Stabilisation
                } else {
                    menuOpenRetries++
                    if (menuOpenRetries >= MAX_MENU_OPEN_RETRIES) {
                        logger.warn("Echec ouverture station - Timeout")
                        ChatManager.showActionBar("Echec ouverture station!", "c")
                        menuOpenStep = 0
                        waitMs(1000)  // Reessayer apres 1s
                    } else {
                        wait(2)  // Verifier toutes les 100ms
                    }
                }
            }
            2 -> {
                // Stabilisation terminee, verifier que le menu est toujours ouvert
                if (MenuDetector.isSimpleMenuOpen()) {
                    logger.info("Station ouverte et chargee - Pret pour recolte")
                    MenuDetector.debugCurrentMenu()
                    stateData.state = BotState.HARVESTING
                    menuOpenStep = 0
                } else {
                    logger.warn("Station fermee pendant stabilisation - Reessai")
                    menuOpenStep = 0
                    waitMs(500)
                }
            }
        }
    }

    private fun handleHarvesting() {
        when (harvestingStep) {
            0 -> {
                // Etape 1: Verifier d'abord s'il y a un melon a recolter
                val melonSlot = InventoryManager.findMelonSlotInMenu()
                if (melonSlot >= 0) {
                    // Melon present, on clique sur le slot pour recolter
                    logger.info("Melon detecte au slot $melonSlot - clic gauche pour recolter")
                    ActionManager.leftClickSlot(melonSlot)
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
                        logger.info("Melon encore present au slot $melonSlot, retry ${harvestingRetries}/$MAX_HARVESTING_RETRIES")
                        ActionManager.leftClickSlot(melonSlot)
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
        // Machine d'etat pour le remplissage (non-bloquant)
        when (waterPouringStep) {
            0 -> {
                // Etape 0: Verifications initiales
                // Verifier si station pleine
                if (BucketManager.isStationFull()) {
                    logger.info("Station pleine apres ${BucketManager.state.bucketsUsedThisStation} seaux")
                    waterPouringStep = 0
                    stateData.state = BotState.NEXT_STATION
                    return
                }

                // Verifier si on a des seaux d'eau
                if (!BucketManager.hasWaterBuckets()) {
                    // Besoin de remplir les seaux vides
                    if (BucketManager.hasEmptyBuckets()) {
                        waterPouringStep = 0
                        stateData.state = BotState.REFILLING_BUCKETS
                    } else {
                        // Plus de seaux du tout
                        logger.warn("Plus de seaux disponibles!")
                        waterPouringStep = 0
                        stateData.state = BotState.NEXT_STATION
                    }
                    return
                }

                // Limite de securite
                if (BucketManager.state.bucketsUsedThisStation >= 50) {
                    logger.warn("Limite de seaux atteinte")
                    waterPouringStep = 0
                    stateData.state = BotState.NEXT_STATION
                    return
                }

                // Selectionner un seau d'eau
                if (BucketManager.selectWaterBucket()) {
                    waterPouringStep = 1
                    wait(4)  // 200ms avant le clic
                }
            }
            1 -> {
                // Etape 1: Faire le clic droit et sauvegarder l'etat
                waterBucketsBefore = InventoryManager.countWaterBucketsInHotbar()
                ActionManager.rightClick()

                // Ajouter l'animation du bras
                MinecraftClient.getInstance().execute {
                    MinecraftClient.getInstance().player?.swingHand(net.minecraft.util.Hand.MAIN_HAND)
                }

                waterPouringCheckCount = 0
                waterPouringStep = 2
                wait(2)  // Verifier apres 100ms
            }
            2 -> {
                // Etape 2: Attendre que le seau soit consomme (non-bloquant)
                val waterBucketsAfter = InventoryManager.countWaterBucketsInHotbar()

                if (waterBucketsAfter < waterBucketsBefore) {
                    // Seau consomme avec succes
                    BucketManager.state.bucketsUsedThisStation++
                    logger.debug("Seau vide (${BucketManager.state.bucketsUsedThisStation} cette station)")
                    waterPouringStep = 0
                    // Utiliser le delai adaptatif pour le prochain seau
                    waitMs(BucketManager.getAdaptiveDelay().toInt())
                } else {
                    waterPouringCheckCount++
                    if (waterPouringCheckCount >= MAX_WATER_POURING_CHECKS) {
                        // Timeout - continuer quand meme
                        logger.warn("Seau non consomme apres timeout - on continue")
                        BucketManager.state.bucketsUsedThisStation++
                        waterPouringStep = 0
                        waitMs(500)
                    } else {
                        wait(2)  // Verifier toutes les 100ms
                    }
                }
            }
        }
    }

    private fun handleRefillingBuckets() {
        // Machine d'etat pour gerer les etapes du remplissage des seaux
        // Cela evite d'envoyer /eau avant que les seaux soient bien selectionnes
        when (refillingStep) {
            0 -> {
                // Etape 1: Selectionner les seaux vides
                if (!BucketManager.selectEmptyBuckets()) {
                    logger.warn("Pas de seaux vides a remplir")
                    refillingStep = 0  // Reset pour la prochaine fois
                    stateData.state = BotState.NEXT_STATION
                    return
                }
                logger.info("Seaux vides selectionnes - attente avant /eau")
                refillingStep = 1
                waitMs(300)  // Attendre que la selection soit bien effective
            }
            1 -> {
                // Etape 2: Verifier que les seaux vides sont bien en main et envoyer /eau
                if (InventoryManager.isHoldingEmptyBucket()) {
                    logger.info("Seaux vides en main - execution de /eau")
                    BucketManager.fillBucketsWithCommand()
                    refillingStep = 2
                    refillingCheckCount = 0  // Reset compteur de verifications
                    waitMs(REFILLING_CHECK_INTERVAL_MS)  // Premiere verification rapide
                } else {
                    // Les seaux ne sont pas encore en main, reessayer la selection
                    logger.warn("Seaux vides pas encore en main - reessai selection")
                    BucketManager.selectEmptyBuckets()
                    waitMs(200)
                }
            }
            2 -> {
                // Etape 3: Verifier activement que les seaux sont remplis
                refillingCheckCount++
                BucketManager.refreshState()

                if (BucketManager.hasWaterBuckets()) {
                    // Les seaux sont remplis!
                    logger.info("Seaux remplis detectes apres ${refillingCheckCount * REFILLING_CHECK_INTERVAL_MS}ms (${BucketManager.state.waterBucketsCount} seaux d'eau)")
                    refillingStep = 0  // Reset pour la prochaine fois
                    refillingCheckCount = 0
                    stateData.state = BotState.FILLING_WATER
                } else if (refillingCheckCount >= MAX_REFILLING_CHECKS) {
                    // Timeout atteint - continuer quand meme
                    logger.warn("Timeout verification seaux apres ${MAX_REFILLING_CHECKS * REFILLING_CHECK_INTERVAL_MS}ms - seaux toujours vides, tentative de continuer")
                    refillingStep = 0
                    refillingCheckCount = 0
                    stateData.state = BotState.FILLING_WATER
                } else {
                    // Continuer a attendre
                    if (refillingCheckCount % 10 == 0) {
                        logger.debug("Attente remplissage seaux... (${refillingCheckCount * REFILLING_CHECK_INTERVAL_MS}ms)")
                    }
                    waitMs(REFILLING_CHECK_INTERVAL_MS)
                }
            }
        }
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
        logger.info("Deconnexion du serveur...")

        // Deconnecter du serveur et preparer la reconnexion
        ServerConnector.disconnectAndPrepareReconnect()

        // Passer en pause
        stateData.state = BotState.PAUSED
        waitMs(pauseSeconds * 1000)
    }

    private fun handlePaused() {
        // Fin de pause, reconnecter au serveur puis relancer une session
        logger.info("Fin de pause, reconnexion au serveur...")

        // Lancer la reconnexion au serveur
        if (ServerConnector.startReconnection()) {
            stateData.state = BotState.CONNECTING
        } else {
            logger.error("Echec du demarrage de la reconnexion: ${ServerConnector.errorMessage}")
            stateData.errorMessage = ServerConnector.errorMessage
            stateData.state = BotState.ERROR
        }
    }

    private fun handleError() {
        logger.error("Erreur: ${stateData.errorMessage}")
        ChatManager.showActionBar("Erreur: ${stateData.errorMessage}", "c")
        stop()
    }
}
