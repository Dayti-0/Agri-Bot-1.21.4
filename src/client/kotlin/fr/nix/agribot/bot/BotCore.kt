package fr.nix.agribot.bot

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.action.ActionManager
import fr.nix.agribot.bucket.BucketManager
import fr.nix.agribot.chat.ChatListener
import fr.nix.agribot.chat.ChatManager
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.config.StatsConfig
import fr.nix.agribot.inventory.InventoryManager
import fr.nix.agribot.menu.MenuDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.DisconnectedScreen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
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

    // Utilisation des constantes centralisees (voir BotConstants.kt)
    private var tickCounter = 0
    private var waitTicks = 0

    // Sous-etats pour la gestion des seaux dans le coffre
    private var bucketManagementStep = 0
    private var bucketsToKeepTarget = -1  // Nombre de seaux a garder (-1 = pas de limite)
    private var bucketsToDepositRemaining = 0  // Nombre de seaux restant a deposer (clics droits)
    private var emptyChestSlot = -1  // Slot vide du coffre pour deposer
    private var originalBucketSlot = -1  // Slot original des seaux (pour les remettre au meme endroit)

    // Sous-etats pour la recolte
    private var harvestingStep = 0
    private var harvestingRetries = 0

    // Sous-etats pour la plantation
    private var plantingStep = 0

    // Sous-etats pour le remplissage des seaux
    private var refillingStep = 0
    private var refillingCheckCount = 0

    // Sous-etats pour l'attente de teleportation (non-bloquant)
    private var teleportWaitRetries = 0

    // Sous-etats pour l'ouverture des menus (non-bloquant)
    private var menuOpenStep = 0
    private var menuOpenRetries = 0

    // Compteur de tentatives globales d'ouverture de menu (pour echec definitif)
    private var menuOpenAttempts = 0

    // Compteur de tentatives globales d'ouverture de coffre
    private var chestOpenAttempts = 0

    // Sous-etats pour le versement d'eau (non-bloquant)
    private var waterPouringStep = 0
    private var waterPouringCheckCount = 0
    private var waterBucketsBefore = 0

    // Gestion des retries de connexion avec delai anti-spam
    private var connectionRetryCount = 0
    private var connectionRetryDelayTicks = 0

    // Sous-etats pour la recuperation de seaux depuis le coffre de backup
    private var bucketRecoveryStep = 0
    private var bucketsToRecover = 0
    private var bucketsRecovered = 0
    private var sourceBucketSlot = -1  // Slot d'ou on a pris les sceaux (pour remettre le reste)

    // Sous-etats pour la recuperation de graines depuis le coffre de graines
    private var seedFetchingStep = 0
    private var seedStacksMovedToHotbar = 0
    private var seedStacksMovedToInventory = 0
    private var seedFetchingChestOpenAttempts = 0

    /**
     * Initialise le bot et enregistre le tick handler.
     */
    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { _ ->
            // Invalider les caches a chaque tick (OPTIMISATION)
            InventoryManager.onTick()
            MenuDetector.onTick()
            ChatManager.onTick()

            // Ne rien faire si le bot est desactive (optimisation)
            if (!config.botEnabled) return@register

            // Verifier si on est sur l'ecran de deconnexion et le fermer automatiquement
            handleDisconnectedScreen()

            onTick()
        }
        logger.info("BotCore initialise")
    }

    /**
     * Gere l'ecran de deconnexion automatiquement.
     * Quand le bot est actif, ferme TOUJOURS l'ecran et revient au menu serveurs
     * pour permettre la reconnexion automatique.
     */
    private fun handleDisconnectedScreen() {
        val currentScreen = client.currentScreen

        // Verifier si on est sur l'ecran de deconnexion
        if (currentScreen is DisconnectedScreen) {
            // Verifier si le bot est actif
            if (config.botEnabled) {
                val currentState = stateData.state

                // TOUJOURS fermer l'ecran de deconnexion quand le bot est actif
                // pour permettre la reconnexion automatique
                logger.info("Ecran de deconnexion detecte - retour au menu serveurs (etat: $currentState)")
                client.execute {
                    client.setScreen(MultiplayerScreen(TitleScreen()))
                }

                // Si on n'est pas deja en mode reconnexion, declencher une reconnexion
                if (currentState != BotState.PAUSED &&
                    currentState != BotState.DISCONNECTING &&
                    currentState != BotState.CONNECTING &&
                    currentState != BotState.IDLE) {
                    // On etait en pleine session - declencher reconnexion automatique
                    logger.info("Deconnexion inattendue depuis l'etat $currentState - demarrage reconnexion automatique")
                    handleUnexpectedDisconnection()
                }
            }
        }
    }

    /**
     * Demarre une nouvelle session de farming.
     * Si un delai de demarrage est configure, attend d'abord ce delai.
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

        // Verifier si un delai de demarrage est configure
        if (config.startupDelayMinutes > 0) {
            val delaySeconds = config.startupDelayMinutes * 60
            stateData.startupEndTime = System.currentTimeMillis() + (delaySeconds * 1000L)
            stateData.state = BotState.WAITING_STARTUP
            logger.info("Minuteur de demarrage: ${AgriConfig.formatStartupDelay(config.startupDelayMinutes)} avant le demarrage")
            ChatManager.showActionBar("Demarrage dans ${AgriConfig.formatStartupDelay(config.startupDelayMinutes)}", "6")
            // Remettre le delai a 0 pour les prochaines sessions
            config.startupDelayMinutes = 0
            config.save()
            waitMs(delaySeconds * 1000)
            return
        }

        // Si mot de passe configure, lancer la connexion automatique d'abord
        if (config.loginPassword.isNotBlank()) {
            logger.info("Mot de passe configure - demarrage connexion automatique")
            ServerConnector.reset()
            connectionRetryCount = 0
            connectionRetryDelayTicks = 0
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

        // Verifier les stations et cacher la liste (OPTIMISATION)
        val stations = config.getActiveStations()
        if (stations.isEmpty()) {
            ChatManager.showActionBar("Aucune station configuree!", "c")
            return
        }
        stateData.cachedStations = stations  // Cache pour eviter recalcul a chaque tick

        // Verifier periode de redemarrage serveur
        if (config.isServerRestartPeriod()) {
            ChatManager.showActionBar("Redemarrage serveur en cours, attente...", "e")
            return
        }

        // Determiner si c'est une session de remplissage intermediaire ou une session normale
        val isWaterOnly = stateData.waterRefillsRemaining > 0
        val isResumeAfterEvent = stateData.forceFullWaterRefill

        logger.info("========================================")
        if (isResumeAfterEvent) {
            logger.info("REPRISE APRES EVENT - Remplissage complet")
        } else if (isWaterOnly) {
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
        val needsWater = if (isResumeAfterEvent) {
            true // Reprise apres event -> toujours remplir completement
        } else if (isWaterOnly) {
            true // Session de remplissage -> toujours remplir
        } else {
            // Session normale (recolte/plantation) -> verifier si replantage necessite de l'eau
            config.shouldRefillWaterOnReplant()
        }

        // Calculer le nombre de remplissages necessaires pour un nouveau cycle
        val refillsNeeded = if (isResumeAfterEvent) {
            config.calculateWaterRefillsNeeded() // Nouveau cycle apres event
        } else if (isWaterOnly) {
            stateData.waterRefillsRemaining - 1 // Decrementer car on va faire ce remplissage
        } else {
            config.calculateWaterRefillsNeeded()
        }

        // Timestamp du debut du cycle (garder l'ancien si session intermediaire, reset si reprise apres event)
        val cycleStart = if (isResumeAfterEvent) {
            System.currentTimeMillis() / 1000 // Nouveau cycle apres event
        } else if (isWaterOnly) {
            stateData.cycleStartTime
        } else {
            System.currentTimeMillis() / 1000
        }

        // Reinitialiser le flag de reprise apres event
        if (isResumeAfterEvent) {
            stateData.forceFullWaterRefill = false
            logger.info("Reprise apres event: nouveau cycle de croissance demarre")
        }

        // Verifier si des seaux manquent et si le home backup est configure
        val currentBucketCount = BucketManager.state.totalBuckets
        val targetBuckets = config.targetBucketCount
        val missingBuckets = targetBuckets - currentBucketCount

        // IMPORTANT: Verifier d'abord si une transition matin->jour est en attente
        // Si oui, les seaux manquants sont dans le coffre HOME (deposes le matin), pas dans le BACKUP
        val needsTransition = BucketManager.needsModeTransition()
        val currentMode = BucketManager.getCurrentMode()
        val isRetrieveTransition = needsTransition &&
            (currentMode == fr.nix.agribot.bucket.BucketMode.RETRIEVE ||
             currentMode == fr.nix.agribot.bucket.BucketMode.NORMAL)

        if (missingBuckets > 0 && config.homeBackup.isNotBlank() && !isRetrieveTransition) {
            // Seaux manquants ET ce n'est PAS une transition matin->jour
            // -> recuperer depuis le coffre BACKUP (cas crash/perte de seaux)
            logger.warn("========================================")
            logger.warn("SEAUX MANQUANTS DETECTES AU DEMARRAGE")
            logger.warn("Seaux actuels: $currentBucketCount")
            logger.warn("Seaux cibles: $targetBuckets")
            logger.warn("Seaux manquants: $missingBuckets")
            logger.warn("Recuperation depuis: ${config.homeBackup}")
            logger.warn("========================================")

            ChatManager.showActionBar("$missingBuckets seaux manquants - Recuperation...", "e")

            // Preparer les donnees de session avant la recuperation
            stateData.apply {
                cachedStations = stations
                currentStationIndex = 0
                totalStations = stations.size
                sessionStartTime = System.currentTimeMillis()
                stationsCompleted = 0
                needsWaterRefill = needsWater
                this.isWaterOnlySession = isWaterOnly
                waterRefillsRemaining = refillsNeeded
                cycleStartTime = cycleStart
                isFirstStationOfSession = true
            }

            // Configurer la recuperation de seaux
            bucketRecoveryStep = 0
            bucketsToRecover = missingBuckets
            bucketsRecovered = 0
            stateData.state = BotState.RECOVERING_BUCKETS
            return
        } else if (missingBuckets > 0 && !isRetrieveTransition) {
            logger.warn("Seaux manquants ($missingBuckets) mais home backup non configure - on continue quand meme")
        } else if (isRetrieveTransition) {
            logger.info("Transition matin->jour detectee - recuperation depuis coffre HOME (pas BACKUP)")
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
            isFirstStationOfSession = true  // Reinitialiser pour chaque nouvelle session
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
     * Reprend la session de farming apres une deconnexion inattendue (crash).
     * Contrairement a startFarmingSession(), cette fonction ne reinitialise PAS currentStationIndex.
     * Elle reprend a la station ou le bot s'est arrete.
     */
    private fun resumeFarmingSession() {
        // Verifier la connexion - si pas connecte, retenter la connexion
        if (!ChatManager.isConnected()) {
            ChatManager.showActionBar("Pas connecte - retry dans 30s...", "e")
            logger.warn("Pas connecte au serveur apres reconnexion - nouvelle tentative dans 30 secondes")
            connectionRetryDelayTicks = CONNECTION_RETRY_DELAY_TICKS
            stateData.state = BotState.CONNECTING
            return
        }

        val stations = stateData.cachedStations
        val currentStation = if (stateData.currentStationIndex < stations.size) {
            stations[stateData.currentStationIndex]
        } else {
            "N/A"
        }

        logger.info("========================================")
        logger.info("REPRISE DE SESSION APRES CRASH")
        logger.info("Station de reprise: $currentStation (${stateData.currentStationIndex + 1}/${stateData.totalStations})")
        logger.info("Stations deja completees: ${stateData.stationsCompleted}")
        logger.info("Session eau uniquement: ${stateData.isWaterOnlySession}")
        logger.info("Plante: ${config.selectedPlant}")
        logger.info("========================================")

        // Reset des flags de crash
        stateData.isCrashReconnectPause = false
        stateData.stateBeforeCrash = null

        // Reinitialiser le delai adaptatif
        BucketManager.resetAdaptiveDelay()

        // Log l'etat des seaux
        BucketManager.logState()

        // Verifier si des seaux manquent et si le home backup est configure
        val currentBucketCount = BucketManager.state.totalBuckets
        val targetBuckets = config.targetBucketCount
        val missingBuckets = targetBuckets - currentBucketCount

        // IMPORTANT: Verifier d'abord si une transition matin->jour est en attente
        // Si oui, les seaux manquants sont dans le coffre HOME (deposes le matin), pas dans le BACKUP
        val needsTransition = BucketManager.needsModeTransition()
        val currentMode = BucketManager.getCurrentMode()
        val isRetrieveTransition = needsTransition &&
            (currentMode == fr.nix.agribot.bucket.BucketMode.RETRIEVE ||
             currentMode == fr.nix.agribot.bucket.BucketMode.NORMAL)

        if (missingBuckets > 0 && config.homeBackup.isNotBlank() && !isRetrieveTransition) {
            // Seaux manquants ET ce n'est PAS une transition matin->jour
            // -> recuperer depuis le coffre BACKUP (cas crash/perte de seaux)
            logger.warn("========================================")
            logger.warn("SEAUX MANQUANTS DETECTES APRES CRASH")
            logger.warn("Seaux actuels: $currentBucketCount")
            logger.warn("Seaux cibles: $targetBuckets")
            logger.warn("Seaux manquants: $missingBuckets")
            logger.warn("Recuperation depuis: ${config.homeBackup}")
            logger.warn("========================================")

            ChatManager.showActionBar("$missingBuckets seaux manquants - Recuperation...", "e")

            // Configurer la recuperation de seaux
            bucketRecoveryStep = 0
            bucketsToRecover = missingBuckets
            bucketsRecovered = 0
            stateData.state = BotState.RECOVERING_BUCKETS
            return
        } else if (missingBuckets > 0 && !isRetrieveTransition) {
            logger.warn("Seaux manquants ($missingBuckets) mais home backup non configure - on continue quand meme")
        } else if (isRetrieveTransition) {
            logger.info("Transition matin->jour detectee apres crash - recuperation depuis coffre HOME (pas BACKUP)")
        }

        // Reset des detections de chat
        ChatListener.resetAllDetections()

        // Reinitialiser l'etat de la premiere station (pour les delais de chargement)
        stateData.isFirstStationOfSession = true
        stateData.sessionStartTime = System.currentTimeMillis()

        val sessionType = if (stateData.isWaterOnlySession) "Reprise remplissage" else "Reprise farming"
        val stationsRestantes = stateData.totalStations - stateData.currentStationIndex
        ChatManager.showActionBar("$sessionType - $stationsRestantes stations restantes", "a")

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
     * Optimise: early return si en attente pour eviter verifications inutiles.
     */
    private fun onTick() {
        tickCounter++

        // OPTIMISATION: Early return si en attente (evite toutes les verifications)
        if (waitTicks > 0) {
            waitTicks--
            return
        }

        // Verification periodique de la connexion (sauf si en pause, deconnexion ou idle)
        if (stateData.state != BotState.IDLE &&
            stateData.state != BotState.PAUSED &&
            stateData.state != BotState.DISCONNECTING &&
            stateData.state != BotState.ERROR) {
            if (!periodicConnectionCheck()) {
                // Deconnexion detectee - gerer selon l'etat actuel
                if (stateData.state != BotState.CONNECTING) {
                    handleUnexpectedDisconnection()
                    return
                }
            }
        }

        // Verifier si une teleportation forcee a ete detectee (event actif)
        // Sauf si on est deja en pause ou en deconnexion ou idle
        if (ChatListener.forcedTeleportDetected &&
            stateData.state != BotState.IDLE &&
            stateData.state != BotState.PAUSED &&
            stateData.state != BotState.DISCONNECTING &&
            stateData.state != BotState.CONNECTING) {
            handleForcedTeleportDetected()
            return
        }

        // Machine d'etat
        when (stateData.state) {
            BotState.IDLE -> { /* Rien */ }
            BotState.WAITING_STARTUP -> handleWaitingStartup()
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
            BotState.RECOVERING_BUCKETS -> handleRecoveringBuckets()
            BotState.FETCHING_SEEDS -> handleFetchingSeeds()
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
        wait(BotConstants.msToTicks(ms))
    }

    /**
     * Verifie si le joueur est toujours connecte au serveur.
     * Si deconnecte, declenche une reconnexion automatique apres 30 secondes.
     * @param operation Nom de l'operation en cours (pour le message d'erreur)
     * @return true si connecte, false si deconnecte
     */
    private fun checkConnection(operation: String): Boolean {
        if (!ChatManager.isConnected()) {
            logger.error("Connexion perdue pendant: $operation - retry dans 30s")
            ChatManager.showActionBar("Connexion perdue - retry dans 30s...", "e")
            handleUnexpectedDisconnection()
            return false
        }
        return true
    }

    /**
     * Verifie la connexion de maniere periodique (tous les 100 ticks = 5 secondes).
     * Appelee depuis onTick() pour detecter les deconnexions silencieuses.
     */
    private var connectionCheckCounter = 0

    private fun periodicConnectionCheck(): Boolean {
        connectionCheckCounter++
        if (connectionCheckCounter >= BotConstants.CONNECTION_CHECK_INTERVAL) {
            connectionCheckCounter = 0
            if (!ChatManager.isConnected()) {
                logger.warn("Deconnexion detectee lors de la verification periodique")
                return false
            }
        }
        return true
    }

    // ==================== HANDLERS ====================

    /**
     * Gere l'attente du minuteur avant le demarrage.
     * Quand le minuteur expire, lance la session normalement.
     */
    private fun handleWaitingStartup() {
        // Le minuteur a expire, lancer la session normalement
        logger.info("Minuteur de demarrage termine - lancement de la session")
        ChatManager.showActionBar("Minuteur termine - demarrage!", "a")
        stateData.startupEndTime = 0

        // Si mot de passe configure, lancer la connexion automatique
        if (config.loginPassword.isNotBlank()) {
            logger.info("Mot de passe configure - demarrage connexion automatique")
            ServerConnector.reset()
            connectionRetryCount = 0
            connectionRetryDelayTicks = 0
            if (ServerConnector.startConnection()) {
                stateData.state = BotState.CONNECTING
                return
            } else {
                // Echec demarrage connexion - retenter apres 30 secondes
                logger.warn("Erreur demarrage connexion: ${ServerConnector.errorMessage} - retry dans 30s")
                ChatManager.showActionBar("Echec connexion - retry dans 30s...", "e")
                connectionRetryDelayTicks = CONNECTION_RETRY_DELAY_TICKS
                stateData.state = BotState.CONNECTING
                return
            }
        }

        // Pas de mot de passe, demarrer directement la session
        startFarmingSession()
    }

    /**
     * Gere la detection d'une teleportation forcee (event actif).
     * Le bot se deconnecte et attend 2 heures avant de se reconnecter.
     * Si l'eau n'est pas suffisante pour attendre 2h, le bot ne se reconnectera pas.
     */
    private fun handleForcedTeleportDetected() {
        logger.warn("========================================")
        logger.warn("TELEPORTATION FORCEE DETECTEE - EVENT ACTIF!")
        logger.warn("Le bot va se deconnecter et attendre 2 heures")
        logger.warn("========================================")

        // Reinitialiser le flag de detection
        ChatListener.resetForcedTeleportDetection()

        // Fermer tout menu ouvert
        ActionManager.closeScreen()
        ActionManager.releaseAllKeys()

        // Verifier si l'eau est suffisante pour la pause de 2h
        val hasEnoughWater = config.hasEnoughWaterForEventPause()

        // Afficher le message a l'utilisateur
        if (hasEnoughWater) {
            ChatManager.showActionBar("Event detecte! Pause 2h puis reprise", "e")
        } else {
            ChatManager.showActionBar("Event detecte! Pause 2h - PAS DE RECONNEXION (eau insuffisante)", "c")
        }

        // Deconnecter du serveur et preparer la reconnexion
        ServerConnector.disconnectAndPrepareReconnect()

        // Configurer la pause event
        stateData.apply {
            isEventPause = true
            canReconnectAfterEvent = hasEnoughWater
            pauseEndTime = config.getEventPauseEndTime()

            // Reinitialiser pour reprendre a la premiere station comme une nouvelle session
            currentStationIndex = 0
            isFirstStationOfSession = true

            // Toujours remplir completement les stations apres un event
            // (meme si l'eau etait insuffisante, on rempli quand meme a la reconnexion)
            waterRefillsRemaining = 0
            isWaterOnlySession = false
            forceFullWaterRefill = true  // Forcer le remplissage complet apres la reconnexion
        }

        logger.info("Pause event configuree:")
        logger.info("  - Fin de pause: ${java.time.Instant.ofEpochMilli(stateData.pauseEndTime)}")
        logger.info("  - Eau suffisante pour 2h: ${if (hasEnoughWater) "OUI" else "NON (reconnexion quand meme)"}")
        logger.info("  - Station de reprise: premiere station")

        // Passer en pause
        stateData.state = BotState.PAUSED
        waitMs(AgriConfig.EVENT_PAUSE_SECONDS * 1000)
    }

    /**
     * Gere une deconnexion inattendue (crash, connection reset, etc.).
     * Au lieu d'arreter le bot, il attend 2 minutes puis se reconnecte et reprend la session.
     */
    private fun handleUnexpectedDisconnection() {
        val currentState = stateData.state
        val stations = stateData.cachedStations
        val currentStation = if (stateData.currentStationIndex < stations.size) {
            stations[stateData.currentStationIndex]
        } else {
            "N/A"
        }

        logger.warn("========================================")
        logger.warn("DECONNEXION INATTENDUE DETECTEE")
        logger.warn("Etat au moment du crash: $currentState")
        logger.warn("Station: $currentStation (${stateData.currentStationIndex + 1}/${stateData.totalStations})")
        logger.warn("Stations completees: ${stateData.stationsCompleted}")
        logger.warn("Le bot va attendre 2 minutes puis se reconnecter")
        logger.warn("========================================")

        // Fermer tout menu ouvert et relacher les touches
        ActionManager.closeScreen()
        ActionManager.releaseAllKeys()

        // Afficher le message a l'utilisateur
        ChatManager.showActionBar("Deconnexion! Reconnexion dans 2 min...", "e")

        // Preparer la reconnexion
        ServerConnector.disconnectAndPrepareReconnect()

        // Configurer la pause de reconnexion apres crash
        stateData.apply {
            isCrashReconnectPause = true
            stateBeforeCrash = currentState
            pauseEndTime = System.currentTimeMillis() + (AgriConfig.CRASH_RECONNECT_DELAY_SECONDS * 1000L)
            // On garde currentStationIndex et les autres donnees de session pour reprendre
            isFirstStationOfSession = true  // Reinitialiser car on va recharger le monde
        }

        logger.info("Pause de ${AgriConfig.CRASH_RECONNECT_DELAY_SECONDS / 60} minutes avant reconnexion")
        logger.info("Reprise a la station: $currentStation (${stateData.currentStationIndex + 1}/${stateData.totalStations})")

        // Passer en pause
        stateData.state = BotState.PAUSED
        waitMs(AgriConfig.CRASH_RECONNECT_DELAY_SECONDS * 1000)
    }

    private fun handleConnecting() {
        // Si on est en attente de retry, decrementer le delai
        if (connectionRetryDelayTicks > 0) {
            connectionRetryDelayTicks--
            if (connectionRetryDelayTicks % 20 == 0) {
                val secondsRemaining = connectionRetryDelayTicks / 20
                ChatManager.showActionBar("Reconnexion dans ${secondsRemaining}s (anti-spam)...", "6")
            }
            if (connectionRetryDelayTicks <= 0) {
                // Delai termine, relancer la connexion
                connectionRetryCount++
                stateData.clearError()  // Effacer l'erreur precedente avant retry
                logger.info("Retry connexion (tentative $connectionRetryCount)...")
                ServerConnector.reset()
                if (ChatManager.isConnected()) {
                    // Joueur encore sur le serveur, relancer startConnection
                    ServerConnector.startConnection()
                } else {
                    // Joueur deconnecte, utiliser startReconnection
                    ServerConnector.startReconnection()
                }
            }
            return
        }

        // Deleguer au ServerConnector
        ServerConnector.onTick()

        // Verifier si la connexion est terminee
        if (ServerConnector.isFinished()) {
            if (ServerConnector.isConnected()) {
                connectionRetryCount = 0  // Reset pour la prochaine fois
                stateData.clearError()

                // Verifier si on doit reprendre une session apres un crash
                if (stateData.isCrashReconnectPause) {
                    logger.info("Connexion reussie - REPRISE de la session apres crash")
                    resumeFarmingSession()
                } else {
                    logger.info("Connexion automatique reussie - demarrage session farming")
                    startFarmingSession()
                }
            } else {
                // Erreur de connexion - toujours retenter (jamais abandonner)
                val errorContext = "Connexion serveur '${config.serverAddress}' echouee: ${ServerConnector.errorMessage}"
                logger.error(errorContext)
                stateData.setError(errorContext, ErrorType.NETWORK_ERROR)

                // Retenter apres le delai configurable (ne jamais abandonner)
                val retryDelaySeconds = config.connectionRetryDelaySeconds
                logger.info("Retry connexion dans $retryDelaySeconds secondes (tentative ${connectionRetryCount + 1})...")
                ChatManager.showActionBar("Echec connexion - retry dans ${retryDelaySeconds}s...", "e")
                connectionRetryDelayTicks = config.getConnectionRetryDelayTicks()
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

                // Sauvegarder la position avant teleportation
                stateData.positionBeforeTeleport = client.player?.pos

                ChatManager.teleportToHome(config.homeCoffre)
                bucketManagementStep = 1
                menuOpenStep = 0
                menuOpenRetries = 0
                waitMs(config.delayAfterTeleport)
            }
            1 -> {
                // Etape 2: Calculer distance et ouvrir le coffre (non-bloquant)
                when (menuOpenStep) {
                    0 -> {
                        // Calculer la distance de teleportation pour ajuster le delai
                        val positionBefore = stateData.positionBeforeTeleport
                        val positionAfter = client.player?.pos
                        var extraDelay = 0

                        if (positionBefore != null && positionAfter != null) {
                            val distance = positionBefore.distanceTo(positionAfter)
                            // OPTIMISATION: eviter String.format si debug desactive
                            if (logger.isDebugEnabled) {
                                logger.debug("Distance de teleportation vers coffre: %.2f blocs".format(distance))
                            }

                            // Si la distance est superieure a 50 blocs, ajouter un delai supplementaire
                            if (distance > 50.0) {
                                extraDelay = 2000  // 2 secondes de plus
                                logger.info("Teleportation longue distance vers coffre ({} blocs) - delai supplementaire de {}ms", "%.2f".format(distance), extraDelay)
                                // Appliquer le delai supplementaire
                                if (extraDelay > 0) {
                                    waitMs(extraDelay)
                                    // Passer a l'etape suivante apres le delai
                                    menuOpenStep = -1
                                    return
                                }
                            }
                        }

                        // Verifier d'abord si un coffre est deja ouvert
                        if (MenuDetector.isChestOrContainerOpen()) {
                            logger.info("Coffre '${config.homeCoffre}' deja ouvert - Pret pour operations")
                            MenuDetector.debugCurrentMenu()
                            bucketManagementStep = 2
                            menuOpenStep = 0
                            chestOpenAttempts = 0
                            return
                        }

                        logger.info("Gestion seaux - Ouverture coffre '${config.homeCoffre}' (tentative ${chestOpenAttempts + 1}/$MAX_CHEST_OPEN_ATTEMPTS)")
                        ActionManager.rightClick()
                        menuOpenStep = 1
                        menuOpenRetries = 0
                        wait(2)  // Attendre 2 ticks avant de verifier
                    }
                    -1 -> {
                        // Etape intermediaire apres le delai supplementaire
                        // Verifier d'abord si un coffre est deja ouvert
                        if (MenuDetector.isChestOrContainerOpen()) {
                            logger.info("Coffre '${config.homeCoffre}' deja ouvert - Pret pour operations")
                            MenuDetector.debugCurrentMenu()
                            bucketManagementStep = 2
                            menuOpenStep = 0
                            chestOpenAttempts = 0
                            return
                        }

                        logger.info("Gestion seaux - Ouverture coffre '${config.homeCoffre}' (tentative ${chestOpenAttempts + 1}/$MAX_CHEST_OPEN_ATTEMPTS)")
                        ActionManager.rightClick()
                        menuOpenStep = 1
                        menuOpenRetries = 0
                        wait(2)  // Attendre 2 ticks avant de verifier
                    }
                    1 -> {
                        // Attendre que le coffre soit ouvert
                        if (MenuDetector.isChestOrContainerOpen()) {
                            logger.debug("Coffre '${config.homeCoffre}' detecte - attente stabilisation")
                            menuOpenStep = 2
                            wait(BotConstants.MENU_STABILIZATION_TICKS)  // Stabilisation
                        } else {
                            menuOpenRetries++
                            if (menuOpenRetries >= BotConstants.MAX_MENU_OPEN_RETRIES) {
                                chestOpenAttempts++
                                if (chestOpenAttempts >= config.maxChestOpenAttempts) {
                                    logger.error("Echec definitif ouverture coffre '${config.homeCoffre}' apres ${config.maxChestOpenAttempts} tentatives")
                                    stateData.setError("Impossible d'ouvrir le coffre '${config.homeCoffre}' apres ${config.maxChestOpenAttempts} tentatives", ErrorType.MENU_ERROR)
                                    stateData.state = BotState.ERROR
                                    menuOpenStep = 0
                                    chestOpenAttempts = 0
                                    return
                                }
                                logger.warn("Echec ouverture coffre '${config.homeCoffre}' - Timeout (tentative $chestOpenAttempts/${config.maxChestOpenAttempts})")
                                ChatManager.showActionBar("Echec ouverture coffre - Reessai...", "e")
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
                            logger.info("Coffre '${config.homeCoffre}' ouvert et charge - Pret pour operations")
                            MenuDetector.debugCurrentMenu()
                            bucketManagementStep = 2
                            menuOpenStep = 0
                            chestOpenAttempts = 0
                        } else {
                            chestOpenAttempts++
                            if (chestOpenAttempts >= config.maxChestOpenAttempts) {
                                logger.error("Coffre '${config.homeCoffre}' ferme pendant stabilisation - Echec definitif")
                                stateData.setError("Coffre '${config.homeCoffre}' se ferme de maniere repetee", ErrorType.MENU_ERROR)
                                stateData.state = BotState.ERROR
                                menuOpenStep = 0
                                chestOpenAttempts = 0
                                return
                            }
                            logger.warn("Coffre '${config.homeCoffre}' ferme pendant stabilisation - Reessai (tentative $chestOpenAttempts/${config.maxChestOpenAttempts})")
                            menuOpenStep = 0
                            waitMs(500)
                        }
                    }
                }
            }
            2 -> {
                // Etape 2: Preparer le depot/recuperation selon le mode
                when (mode) {
                    fr.nix.agribot.bucket.BucketMode.MORNING -> {
                        val toKeep = BucketManager.getBucketsToKeep()
                        val currentBuckets = InventoryManager.countBucketsInPlayerInventoryInChestMenu()
                        if (currentBuckets > toKeep) {
                            val toDeposit = currentBuckets - toKeep
                            logger.info("Depot de $toDeposit seaux dans le coffre (garder $toKeep sur $currentBuckets)")
                            // Trouver le premier slot de seaux dans l'inventaire
                            val bucketSlots = InventoryManager.findBucketSlotsInChestMenu()
                            if (bucketSlots.isNotEmpty()) {
                                // Trouver un slot vide dans le coffre
                                emptyChestSlot = InventoryManager.findEmptySlotInChest()
                                if (emptyChestSlot >= 0) {
                                    // Sauvegarder le slot original pour remettre le seau au meme endroit
                                    originalBucketSlot = bucketSlots.first()
                                    logger.info("Slot original des seaux: $originalBucketSlot")
                                    // Clic gauche pour prendre les seaux en main
                                    ActionManager.leftClickSlot(originalBucketSlot)
                                    bucketsToDepositRemaining = toDeposit
                                    bucketManagementStep = 3
                                    wait(4)
                                } else {
                                    logger.error("Pas de slot vide dans le coffre pour deposer les seaux!")
                                    bucketManagementStep = 4
                                }
                            } else {
                                logger.warn("Aucun slot de seaux trouve")
                                bucketManagementStep = 4
                            }
                        } else {
                            logger.info("Deja le bon nombre de seaux ($currentBuckets <= $toKeep)")
                            bucketManagementStep = 5
                        }
                    }
                    fr.nix.agribot.bucket.BucketMode.RETRIEVE, fr.nix.agribot.bucket.BucketMode.NORMAL -> {
                        logger.info("Recuperation des seaux du coffre")
                        bucketSlotsToProcess = InventoryManager.findBucketSlotsInChest()
                        bucketSlotIndex = 0
                        bucketManagementStep = 6  // Aller a l'etape de recuperation (shift-click)
                    }
                }
            }
            3 -> {
                // Etape 3: Deposer les seaux un par un avec clic droit (mode MORNING)
                if (bucketsToDepositRemaining > 0) {
                    // Clic droit sur un slot vide du coffre = depose 1 seau
                    ActionManager.rightClickSlot(emptyChestSlot)
                    bucketsToDepositRemaining--
                    logger.debug("Seau depose, reste $bucketsToDepositRemaining a deposer")
                    wait(2)  // 100ms entre chaque clic
                } else {
                    // Tous les seaux deposes, reposer le reste dans l'inventaire
                    logger.info("Depot termine, repose du seau restant dans l'inventaire")
                    bucketManagementStep = 4
                    wait(2)
                }
            }
            4 -> {
                // Etape 4: Reposer le seau restant dans le slot ORIGINAL (comme dans TestActions)
                if (originalBucketSlot >= 0) {
                    logger.info("Remise du seau restant dans le slot original ($originalBucketSlot)")
                    ActionManager.leftClickSlot(originalBucketSlot)
                } else {
                    // Fallback: chercher un slot de seaux existant ou utiliser la hotbar
                    val bucketSlots = InventoryManager.findBucketSlotsInChestMenu()
                    if (bucketSlots.isNotEmpty()) {
                        logger.info("Remise du seau sur slot existant (${bucketSlots.first()})")
                        ActionManager.leftClickSlot(bucketSlots.first())
                    } else {
                        // Pas de slot de seaux, utiliser le premier slot de la hotbar
                        // Dans un menu coffre: hotbar = chestSize + 27 a chestSize + 35
                        val handler = (client.currentScreen as? net.minecraft.client.gui.screen.ingame.HandledScreen<*>)?.screenHandler
                        if (handler != null) {
                            val chestSize = when (handler) {
                                is net.minecraft.screen.GenericContainerScreenHandler -> handler.rows * 9
                                else -> 27
                            }
                            // Premier slot de la hotbar = chestSize + 27
                            val hotbarSlot = chestSize + 27
                            logger.info("Remise du seau dans la hotbar (slot $hotbarSlot)")
                            ActionManager.leftClickSlot(hotbarSlot)
                        }
                    }
                }
                val finalCount = InventoryManager.countBucketsInPlayerInventoryInChestMenu()
                logger.info("Depot termine - $finalCount seaux restants dans l'inventaire")
                originalBucketSlot = -1  // Reset pour la prochaine utilisation
                bucketManagementStep = 5
                waitMs(300)
            }
            5 -> {
                // Etape 5: Fermer le coffre
                ActionManager.closeScreen()
                bucketManagementStep = 7
                waitMs(500)
            }
            6 -> {
                // Etape 6: Recuperation des seaux du coffre (shift-click)
                if (bucketSlotIndex < bucketSlotsToProcess.size) {
                    val slot = bucketSlotsToProcess[bucketSlotIndex]
                    ActionManager.shiftClickSlot(slot)
                    bucketSlotIndex++
                    wait(4)  // 200ms entre chaque clic
                } else {
                    logger.info("${bucketSlotsToProcess.size} slots de seaux recuperes du coffre")
                    bucketManagementStep = 5  // Aller a l'etape de fermeture
                    waitMs(300)
                }
            }
            7 -> {
                // Etape 7: Sauvegarder le mode ET la periode (transition complete)
                BucketManager.saveTransitionComplete()
                logger.info("Gestion seaux terminee")
                stateData.state = BotState.TELEPORTING
            }
        }
    }

    private fun handleTeleporting() {
        // OPTIMISATION: utiliser le cache au lieu de recalculer
        val stations = stateData.cachedStations
        if (stateData.currentStationIndex >= stations.size) {
            // Toutes les stations sont terminees
            stateData.state = BotState.DISCONNECTING
            return
        }

        val stationName = stations[stateData.currentStationIndex]
        val actionType = if (stateData.isWaterOnlySession) "Remplissage" else "Station"
        logger.info("$actionType ${stateData.currentStationIndex + 1}/${stations.size}: $stationName")
        ChatManager.showActionBar("$actionType ${stateData.currentStationIndex + 1}/${stations.size}: $stationName", "6")

        // Sauvegarder la position avant teleportation pour calculer la distance ensuite
        stateData.positionBeforeTeleport = client.player?.pos

        // Teleportation
        ChatManager.teleportToHome(stationName)
        ChatListener.resetTeleportDetection()
        teleportWaitRetries = 0  // Reset le compteur d'attente de teleportation

        stateData.state = BotState.WAITING_TELEPORT
        waitMs(config.delayAfterTeleport)
    }

    private fun handleWaitingTeleport() {
        // Attendre que la teleportation soit confirmee par le serveur
        val maxRetries = config.getTeleportTimeoutRetries()
        if (!ChatListener.teleportDetected) {
            teleportWaitRetries++
            if (teleportWaitRetries >= maxRetries) {
                // Timeout - continuer quand meme avec un warning
                logger.warn("Timeout attente confirmation teleportation (${teleportWaitRetries * BotConstants.TELEPORT_CHECK_INTERVAL_MS}ms) - poursuite sans confirmation")
            } else {
                // Attendre encore un peu
                if (teleportWaitRetries == 1) {
                    logger.debug("En attente de confirmation de teleportation...")
                }
                waitMs(BotConstants.TELEPORT_CHECK_INTERVAL_MS)  // Verifier toutes les 100ms
                return
            }
        } else if (teleportWaitRetries > 0) {
            logger.info("Teleportation confirmee apres ${teleportWaitRetries * BotConstants.TELEPORT_CHECK_INTERVAL_MS}ms d'attente")
        }

        // Teleportation confirmee (ou timeout) - calculer la distance pour ajuster le delai
        val positionBefore = stateData.positionBeforeTeleport
        val positionAfter = client.player?.pos
        var extraDelay = 0

        // Delai supplementaire pour la premiere station de la session
        if (stateData.isFirstStationOfSession) {
            extraDelay = BotConstants.FIRST_STATION_EXTRA_DELAY_MS
            logger.info("Premiere station de la session - delai supplementaire de ${extraDelay}ms pour chargement initial")
        } else if (positionBefore != null && positionAfter != null) {
            val distance = positionBefore.distanceTo(positionAfter)
            // OPTIMISATION: eviter String.format si debug desactive
            if (logger.isDebugEnabled) {
                logger.debug("Distance de teleportation: %.2f blocs".format(distance))
            }

            // Si la distance est superieure au seuil, ajouter un delai supplementaire
            // pour laisser le temps au monde de se charger
            if (distance > BotConstants.LONG_DISTANCE_THRESHOLD) {
                extraDelay = BotConstants.LONG_DISTANCE_EXTRA_DELAY_MS
                logger.info("Teleportation longue distance ({} blocs) - delai supplementaire de {}ms", "%.2f".format(distance), extraDelay)
            }
        }

        // Session de remplissage d'eau uniquement -> passer directement au remplissage
        if (stateData.isWaterOnlySession) {
            logger.info("Session remplissage eau - passage direct au remplissage")
            BucketManager.prepareForStation()
            stateData.state = BotState.FILLING_WATER
            waitMs(100 + extraDelay)
            return
        }

        // Session normale: ouvrir la station pour recolte/plantation
        // Verifier d'abord si on a des graines
        val plantData = config.getSelectedPlantData()
        if (plantData != null) {
            val seedType = plantData.seedType
            val seedsInHotbar = InventoryManager.countSeedsInHotbar(seedType)

            if (seedsInHotbar == 0) {
                // Pas de graines dans la hotbar
                if (config.homeGraines.isNotBlank()) {
                    // Home graines configure - aller chercher des graines
                    logger.warn("========================================")
                    logger.warn("PLUS DE GRAINES DANS LA HOTBAR")
                    logger.warn("Type: $seedType")
                    logger.warn("Recuperation depuis: ${config.homeGraines}")
                    logger.warn("========================================")

                    ChatManager.showActionBar("Plus de graines - Recuperation...", "e")

                    // Configurer la recuperation de graines
                    seedFetchingStep = 0
                    seedStacksMovedToHotbar = 0
                    seedStacksMovedToInventory = 0
                    seedFetchingChestOpenAttempts = 0
                    stateData.state = BotState.FETCHING_SEEDS
                    return
                } else {
                    // Home graines non configure - erreur
                    logger.error("Plus de graines ($seedType) et home graines non configure!")
                    stateData.errorMessage = "Plus de graines ($seedType) - Configurez le home graines"
                    stateData.state = BotState.ERROR
                    return
                }
            }
        }

        // Selectionner le slot des graines (cherche automatiquement dans la hotbar)
        InventoryManager.selectSeedsSlotAuto()

        stateData.state = BotState.OPENING_STATION
        waitMs(100 + extraDelay)
    }

    private fun handleOpeningStation() {
        // Ouverture de la station (non-bloquant)
        // OPTIMISATION: utiliser le cache au lieu de recalculer
        val stations = stateData.cachedStations
        val currentStation = if (stateData.currentStationIndex < stations.size) {
            stations[stateData.currentStationIndex]
        } else {
            "Station ${stateData.currentStationIndex + 1}"
        }

        when (menuOpenStep) {
            0 -> {
                // Verifier d'abord si un menu est deja ouvert
                if (MenuDetector.isSimpleMenuOpen()) {
                    logger.info("Station '$currentStation' deja ouverte - Pret pour recolte")
                    MenuDetector.debugCurrentMenu()
                    stateData.state = BotState.HARVESTING
                    menuOpenStep = 0
                    menuOpenAttempts = 0  // Reset le compteur de tentatives
                    return
                }

                // Clic droit pour ouvrir la station
                logger.info("Ouverture station '$currentStation' - clic droit (tentative ${menuOpenAttempts + 1}/$MAX_MENU_OPEN_ATTEMPTS)")
                ActionManager.rightClick()
                menuOpenStep = 1
                menuOpenRetries = 0
                wait(2)  // Attendre 2 ticks avant de verifier
            }
            1 -> {
                // Attendre que le menu soit ouvert
                if (MenuDetector.isSimpleMenuOpen()) {
                    // Delai de stabilisation plus long pour la premiere station de la session
                    val stabilizationTicks = if (stateData.isFirstStationOfSession) {
                        BotConstants.MENU_STABILIZATION_TICKS + BotConstants.FIRST_STATION_STABILIZATION_EXTRA_TICKS
                    } else {
                        BotConstants.MENU_STABILIZATION_TICKS
                    }

                    if (stateData.isFirstStationOfSession) {
                        logger.info("Premiere station '$currentStation' - attente stabilisation prolongee (${stabilizationTicks / BotConstants.TICKS_PER_SECOND}s)")
                    } else {
                        logger.debug("Station '$currentStation' detectee - attente stabilisation")
                    }

                    menuOpenStep = 2
                    wait(stabilizationTicks)  // Stabilisation
                } else {
                    menuOpenRetries++
                    if (menuOpenRetries >= BotConstants.MAX_MENU_OPEN_RETRIES) {
                        menuOpenAttempts++
                        if (menuOpenAttempts >= config.maxMenuOpenAttempts) {
                            // Echec definitif apres plusieurs tentatives
                            logger.error("Echec definitif ouverture station '$currentStation' apres ${config.maxMenuOpenAttempts} tentatives")
                            stateData.setError("Impossible d'ouvrir la station '$currentStation' apres ${config.maxMenuOpenAttempts} tentatives", ErrorType.STATION_ERROR)
                            stateData.state = BotState.ERROR
                            menuOpenStep = 0
                            menuOpenAttempts = 0
                            return
                        }
                        logger.warn("Echec ouverture station '$currentStation' - Timeout (tentative $menuOpenAttempts/${config.maxMenuOpenAttempts})")
                        ChatManager.showActionBar("Echec ouverture '$currentStation' - Reessai...", "e")
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
                    logger.info("Station '$currentStation' ouverte et chargee - Pret pour recolte")
                    MenuDetector.debugCurrentMenu()
                    stateData.state = BotState.HARVESTING
                    menuOpenStep = 0
                    menuOpenAttempts = 0  // Reset le compteur de tentatives
                } else {
                    menuOpenAttempts++
                    if (menuOpenAttempts >= config.maxMenuOpenAttempts) {
                        logger.error("Station '$currentStation' fermee pendant stabilisation - Echec definitif")
                        stateData.setError("Station '$currentStation' se ferme de maniere repetee", ErrorType.STATION_ERROR)
                        stateData.state = BotState.ERROR
                        menuOpenStep = 0
                        menuOpenAttempts = 0
                        return
                    }
                    logger.warn("Station '$currentStation' fermee pendant stabilisation - Reessai (tentative $menuOpenAttempts/${config.maxMenuOpenAttempts})")
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
                    if (harvestingRetries >= config.maxHarvestingRetries) {
                        logger.warn("Echec recolte apres ${config.maxHarvestingRetries} tentatives, on continue")
                        harvestingStep = 2
                    } else {
                        logger.info("Melon encore present au slot $melonSlot, retry ${harvestingRetries}/${config.maxHarvestingRetries}")
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
                if (BucketManager.state.bucketsUsedThisStation >= BotConstants.MAX_BUCKETS_PER_STATION) {
                    logger.warn("Limite de seaux atteinte (${BotConstants.MAX_BUCKETS_PER_STATION})")
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
                    if (waterPouringCheckCount >= BotConstants.MAX_WATER_POURING_CHECKS) {
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
                    waitMs(BotConstants.REFILLING_CHECK_INTERVAL_MS)  // Premiere verification rapide
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

                val maxChecks = config.getBucketRefillTimeoutChecks()
                if (BucketManager.hasWaterBuckets()) {
                    // Les seaux sont remplis!
                    logger.info("Seaux remplis detectes apres ${refillingCheckCount * BotConstants.REFILLING_CHECK_INTERVAL_MS}ms (${BucketManager.state.waterBucketsCount} seaux d'eau)")
                    refillingStep = 0  // Reset pour la prochaine fois
                    refillingCheckCount = 0
                    stateData.state = BotState.FILLING_WATER
                } else if (refillingCheckCount >= maxChecks) {
                    // Timeout atteint - continuer quand meme
                    logger.warn("Timeout verification seaux apres ${maxChecks * BotConstants.REFILLING_CHECK_INTERVAL_MS}ms - seaux toujours vides, tentative de continuer")
                    refillingStep = 0
                    refillingCheckCount = 0
                    stateData.state = BotState.FILLING_WATER
                } else {
                    // Continuer a attendre
                    if (refillingCheckCount % 10 == 0) {
                        logger.debug("Attente remplissage seaux... (${refillingCheckCount * BotConstants.REFILLING_CHECK_INTERVAL_MS}ms)")
                    }
                    waitMs(BotConstants.REFILLING_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    private fun handleNextStation() {
        stateData.stationsCompleted++
        stateData.currentStationIndex++

        // Enregistrer la station dans les statistiques (sauf si c'est une session de remplissage d'eau seul)
        if (!stateData.isWaterOnlySession) {
            StatsConfig.get().recordStation(config.selectedPlant)
        }

        // Apres la premiere station, desactiver le delai supplementaire de debut de session
        if (stateData.isFirstStationOfSession && stateData.stationsCompleted >= 1) {
            stateData.isFirstStationOfSession = false
            logger.debug("Premiere station completee - delais normaux pour les suivantes")
        }

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
                // Session farming terminee, enregistrer dans les statistiques
                StatsConfig.get().incrementSessionCount(config.selectedPlant)
                logger.info("Session de farming complete enregistree dans les statistiques")
                // Vider les seaux
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

    /**
     * Gere la recuperation de seaux depuis le coffre de backup.
     * Utilise apres un crash si des seaux ont disparu.
     * Recupere les seaux un par un (pas de shift-click car ca prendrait le stack entier).
     */
    private fun handleRecoveringBuckets() {
        when (bucketRecoveryStep) {
            0 -> {
                // Etape 0: Se teleporter vers le home backup
                logger.info("========================================")
                logger.info("RECUPERATION DE SEAUX DEPUIS BACKUP")
                logger.info("Home backup: ${config.homeBackup}")
                logger.info("Seaux a recuperer: $bucketsToRecover")
                logger.info("========================================")

                ChatManager.showActionBar("Recuperation seaux backup ($bucketsToRecover manquants)", "6")
                ChatManager.teleportToHome(config.homeBackup)
                bucketRecoveryStep = 1
                waitMs(config.delayAfterTeleport + 1000)  // Delai supplementaire pour chargement
            }
            1 -> {
                // Etape 1: Ouvrir le coffre (clic droit)
                if (MenuDetector.isChestOrContainerOpen()) {
                    logger.info("Coffre backup deja ouvert")
                    bucketRecoveryStep = 2
                    waitMs(500)
                    return
                }

                logger.info("Ouverture coffre backup")
                ActionManager.rightClick()
                menuOpenStep = 1
                menuOpenRetries = 0
                bucketRecoveryStep = 2
                wait(2)
            }
            2 -> {
                // Etape 2: Attendre que le coffre soit ouvert
                if (MenuDetector.isChestOrContainerOpen()) {
                    logger.info("Coffre backup ouvert - stabilisation")
                    bucketRecoveryStep = 3
                    wait(BotConstants.MENU_STABILIZATION_TICKS)
                } else {
                    menuOpenRetries++
                    if (menuOpenRetries >= BotConstants.MAX_MENU_OPEN_RETRIES) {
                        logger.error("Echec ouverture coffre backup - Timeout")
                        stateData.setError("Impossible d'ouvrir le coffre backup '${config.homeBackup}'", ErrorType.MENU_ERROR)
                        stateData.state = BotState.ERROR
                        bucketRecoveryStep = 0
                        return
                    }
                    wait(2)
                }
            }
            3 -> {
                // Etape 3: Chercher les seaux dans le coffre et prendre un stack
                if (!MenuDetector.isChestOrContainerOpen()) {
                    logger.warn("Coffre backup ferme pendant recuperation - reouverture")
                    bucketRecoveryStep = 1
                    sourceBucketSlot = -1
                    waitMs(500)
                    return
                }

                // IMPORTANT: Verifier que le curseur est vide avant toute action
                if (!InventoryManager.isCursorEmpty()) {
                    logger.warn("Curseur non vide au debut de l'etape 3 - depot necessaire")
                    bucketRecoveryStep = 5  // Aller deposer le contenu du curseur
                    wait(2)
                    return
                }

                if (bucketsRecovered >= bucketsToRecover) {
                    // Tous les seaux recuperes
                    logger.info("Recuperation terminee: $bucketsRecovered seaux recuperes")
                    ActionManager.closeScreen()
                    bucketRecoveryStep = 0
                    bucketsRecovered = 0
                    bucketsToRecover = 0
                    sourceBucketSlot = -1

                    // Continuer vers TELEPORTING pour reprendre la session
                    stateData.state = BotState.TELEPORTING
                    waitMs(500)
                    return
                }

                // Chercher un slot avec des seaux dans le coffre
                val bucketSlot = InventoryManager.findFirstBucketSlotInChest()
                if (bucketSlot < 0) {
                    logger.warn("Plus de seaux dans le coffre backup! Recuperes: $bucketsRecovered/$bucketsToRecover")
                    ActionManager.closeScreen()
                    bucketRecoveryStep = 0
                    sourceBucketSlot = -1

                    // Continuer quand meme avec ce qu'on a
                    stateData.state = BotState.TELEPORTING
                    waitMs(500)
                    return
                }

                // Memoriser le slot source pour pouvoir remettre le reste apres
                sourceBucketSlot = bucketSlot

                // Prendre le stack en main (clic gauche)
                logger.debug("Prise du stack de seaux depuis slot $bucketSlot")
                ActionManager.leftClickSlot(bucketSlot)
                bucketRecoveryStep = 4
                wait(4)
            }
            4 -> {
                // Etape 4: Deposer les seaux 1 par 1 dans l'inventaire (clic droit = deposer 1)
                // Continuer tant qu'on n'a pas atteint le compte ET que le curseur n'est pas vide

                // Verifier si le curseur est vide (on a tout depose ou le stack etait vide)
                if (InventoryManager.isCursorEmpty()) {
                    logger.debug("Curseur vide - verifier si on a assez de seaux")
                    if (bucketsRecovered >= bucketsToRecover) {
                        // Termine!
                        logger.info("Recuperation terminee: $bucketsRecovered seaux recuperes")
                        ActionManager.closeScreen()
                        bucketRecoveryStep = 0
                        bucketsRecovered = 0
                        bucketsToRecover = 0
                        sourceBucketSlot = -1
                        stateData.state = BotState.TELEPORTING
                        waitMs(500)
                    } else {
                        // Pas assez - chercher une autre pile
                        logger.debug("Pas assez de seaux ($bucketsRecovered/$bucketsToRecover) - chercher une autre pile")
                        bucketRecoveryStep = 3
                        wait(2)
                    }
                    return
                }

                // Verifier si on a atteint le compte
                if (bucketsRecovered >= bucketsToRecover) {
                    // On a assez - remettre le reste dans le coffre
                    logger.debug("Compte atteint ($bucketsRecovered/$bucketsToRecover) - remettre le reste")
                    bucketRecoveryStep = 5
                    wait(2)
                    return
                }

                // Deposer 1 seau dans l'inventaire
                val targetSlot = InventoryManager.findBucketSlotWithSpaceInPlayerInventory()
                if (targetSlot >= 0) {
                    ActionManager.rightClickSlot(targetSlot)
                    bucketsRecovered++
                    logger.info("Seau $bucketsRecovered/$bucketsToRecover recupere (slot $targetSlot)")
                    // Rester a l'etape 4 pour continuer a deposer
                    wait(4)
                } else {
                    // Pas de slot disponible - remettre le reste et terminer
                    logger.warn("Inventaire plein - impossible de recuperer plus de seaux")
                    bucketRecoveryStep = 5
                    wait(2)
                }
            }
            5 -> {
                // Etape 5: Remettre le reste du stack dans le coffre
                // Verifier d'abord si le curseur est vide
                if (InventoryManager.isCursorEmpty()) {
                    logger.debug("Curseur vide - continuer ou terminer")
                    if (bucketsRecovered >= bucketsToRecover) {
                        // Termine!
                        logger.info("Recuperation terminee: $bucketsRecovered seaux recuperes")
                        ActionManager.closeScreen()
                        bucketRecoveryStep = 0
                        bucketsRecovered = 0
                        bucketsToRecover = 0
                        sourceBucketSlot = -1
                        stateData.state = BotState.TELEPORTING
                        waitMs(500)
                    } else {
                        // Chercher une autre pile
                        bucketRecoveryStep = 3
                        wait(2)
                    }
                    return
                }

                // IMPORTANT: Utiliser le slot source original pour eviter l'echange de stacks
                // Si le slot source est connu, remettre directement dedans
                if (sourceBucketSlot >= 0) {
                    logger.debug("Remise du reste dans le slot source $sourceBucketSlot")
                    ActionManager.leftClickSlot(sourceBucketSlot)
                    sourceBucketSlot = -1  // Reset apres utilisation
                    bucketRecoveryStep = 6
                    wait(4)
                    return
                }

                // Sinon trouver un slot vide dans le coffre (PRIORITE: slot vide pour eviter echange)
                val emptyChestSlot = InventoryManager.findEmptySlotInChest()
                if (emptyChestSlot >= 0) {
                    logger.debug("Remise du reste dans le slot vide $emptyChestSlot")
                    ActionManager.leftClickSlot(emptyChestSlot)
                    bucketRecoveryStep = 6
                    wait(4)
                    return
                }

                // En dernier recours, essayer un slot avec des seaux (peut causer echange)
                val bucketSlot = InventoryManager.findFirstBucketSlotInChest()
                if (bucketSlot >= 0) {
                    logger.debug("Remise du reste dans le slot seau $bucketSlot (risque d'echange)")
                    ActionManager.leftClickSlot(bucketSlot)
                    bucketRecoveryStep = 6
                    wait(4)
                    return
                }

                // Si le coffre est plein, mettre dans l'inventaire du joueur
                val playerSlot = InventoryManager.findEmptySlotInPlayerInventory()
                if (playerSlot >= 0) {
                    logger.warn("Coffre plein - depot du reste dans l'inventaire joueur slot $playerSlot")
                    ActionManager.leftClickSlot(playerSlot)
                    bucketRecoveryStep = 6
                    wait(4)
                    return
                }

                // Dernier recours: erreur
                logger.error("Impossible de deposer les seaux restants - inventaire et coffre pleins!")
                bucketRecoveryStep = 6
                wait(4)
            }
            6 -> {
                // Etape 6: Verifier que le curseur est bien vide avant de continuer
                if (!InventoryManager.isCursorEmpty()) {
                    logger.warn("Curseur toujours non vide apres depot - nouvelle tentative")
                    bucketRecoveryStep = 5  // Retour a l'etape de depot
                    wait(2)
                    return
                }
                logger.debug("Curseur vide confirme")
                // Verifier si on a termine ou si on doit continuer
                if (bucketsRecovered >= bucketsToRecover) {
                    logger.info("Recuperation terminee: $bucketsRecovered seaux recuperes")
                    ActionManager.closeScreen()
                    bucketRecoveryStep = 0
                    bucketsRecovered = 0
                    bucketsToRecover = 0
                    sourceBucketSlot = -1
                    stateData.state = BotState.TELEPORTING
                    waitMs(500)
                } else {
                    bucketRecoveryStep = 3  // Continuer la recuperation
                    wait(2)
                }
            }
        }
    }

    /**
     * Gere la recuperation de graines depuis le coffre de graines.
     * Recupere 1 stack pour la hotbar et 5 stacks pour l'inventaire.
     */
    private fun handleFetchingSeeds() {
        val plantData = config.getSelectedPlantData()
        if (plantData == null) {
            logger.error("Impossible de recuperer les graines - plante non configuree")
            stateData.setError("Plante non configuree pour la recuperation de graines", ErrorType.CONFIG_ERROR)
            stateData.state = BotState.ERROR
            return
        }
        val seedType = plantData.seedType

        when (seedFetchingStep) {
            0 -> {
                // Etape 0: Se teleporter vers le home graines
                logger.info("========================================")
                logger.info("RECUPERATION DE GRAINES")
                logger.info("Home graines: ${config.homeGraines}")
                logger.info("Type de graine: $seedType")
                logger.info("Objectif: ${BotConstants.SEED_STACKS_FOR_HOTBAR} stack hotbar + ${BotConstants.SEED_STACKS_FOR_INVENTORY} stacks inventaire")
                logger.info("========================================")

                // Sauvegarder la position avant teleportation
                stateData.positionBeforeTeleport = client.player?.pos

                ChatManager.showActionBar("Recuperation graines...", "6")
                ChatManager.teleportToHome(config.homeGraines)
                seedFetchingStep = 1
                waitMs(config.delayAfterTeleport + 1000)  // Delai supplementaire pour chargement
            }
            1 -> {
                // Etape 1: Ouvrir le coffre (clic droit)
                if (MenuDetector.isChestOrContainerOpen()) {
                    logger.info("Coffre graines deja ouvert")
                    MenuDetector.debugCurrentMenu()
                    seedFetchingStep = 3
                    wait(MENU_STABILIZATION_TICKS)
                    return
                }

                logger.info("Ouverture coffre graines (tentative ${seedFetchingChestOpenAttempts + 1}/$MAX_CHEST_OPEN_ATTEMPTS)")
                ActionManager.rightClick()
                menuOpenRetries = 0
                seedFetchingStep = 2
                wait(2)
            }
            2 -> {
                // Etape 2: Attendre que le coffre soit ouvert
                if (MenuDetector.isChestOrContainerOpen()) {
                    logger.info("Coffre graines ouvert - stabilisation")
                    seedFetchingStep = 3
                    wait(BotConstants.MENU_STABILIZATION_TICKS)
                } else {
                    menuOpenRetries++
                    if (menuOpenRetries >= BotConstants.MAX_MENU_OPEN_RETRIES) {
                        seedFetchingChestOpenAttempts++
                        if (seedFetchingChestOpenAttempts >= config.maxChestOpenAttempts) {
                            logger.error("Echec definitif ouverture coffre graines apres ${config.maxChestOpenAttempts} tentatives")
                            stateData.setError("Impossible d'ouvrir le coffre graines '${config.homeGraines}'", ErrorType.MENU_ERROR)
                            stateData.state = BotState.ERROR
                            seedFetchingStep = 0
                            return
                        }
                        logger.warn("Echec ouverture coffre graines - Timeout (tentative $seedFetchingChestOpenAttempts/${config.maxChestOpenAttempts})")
                        seedFetchingStep = 1
                        waitMs(1000)
                    } else {
                        wait(2)
                    }
                }
            }
            3 -> {
                // Etape 3: Verifier que le coffre est bien ouvert et pret
                if (!MenuDetector.isChestOrContainerOpen()) {
                    logger.warn("Coffre graines ferme pendant stabilisation - reouverture")
                    seedFetchingStep = 1
                    waitMs(500)
                    return
                }

                logger.info("Coffre graines pret - debut recuperation")
                seedStacksMovedToHotbar = 0
                seedStacksMovedToInventory = 0
                seedFetchingStep = 4
                wait(4)
            }
            4 -> {
                // Etape 4: Recuperer 1 stack pour la hotbar
                if (!MenuDetector.isChestOrContainerOpen()) {
                    logger.warn("Coffre graines ferme pendant recuperation hotbar - reouverture")
                    seedFetchingStep = 1
                    waitMs(500)
                    return
                }

                if (seedStacksMovedToHotbar < BotConstants.SEED_STACKS_FOR_HOTBAR) {
                    // Chercher des graines dans le coffre
                    val seedSlot = InventoryManager.findSeedsSlotInChest(seedType)
                    if (seedSlot < 0) {
                        logger.warn("Plus de graines ($seedType) dans le coffre!")
                        // Continuer quand meme avec ce qu'on a
                        seedFetchingStep = 6
                        waitMs(300)
                        return
                    }

                    // Trouver le dernier slot vide de la hotbar
                    val hotbarSlot = InventoryManager.findLastEmptySlotInHotbarForSeeds()
                    if (hotbarSlot < 0) {
                        logger.warn("Hotbar pleine - impossible de mettre les graines")
                        // Continuer avec l'inventaire
                        seedStacksMovedToHotbar = BotConstants.SEED_STACKS_FOR_HOTBAR
                        seedFetchingStep = 5
                        wait(4)
                        return
                    }

                    // Transfert cible vers le dernier slot de la hotbar
                    logger.info("Transfert graines du coffre (slot $seedSlot) vers hotbar (slot $hotbarSlot)")
                    ActionManager.pickAndPlaceSlot(seedSlot, hotbarSlot)
                    seedStacksMovedToHotbar++
                    wait(8)
                } else {
                    // Hotbar done, passer a l'inventaire
                    logger.info("1 stack de graines transfere vers hotbar")
                    seedFetchingStep = 5
                    wait(4)
                }
            }
            5 -> {
                // Etape 5: Recuperer stacks pour l'inventaire principal (pas la hotbar)
                if (!MenuDetector.isChestOrContainerOpen()) {
                    logger.warn("Coffre graines ferme pendant recuperation inventaire - reouverture")
                    seedFetchingStep = 1
                    waitMs(500)
                    return
                }

                if (seedStacksMovedToInventory < BotConstants.SEED_STACKS_FOR_INVENTORY) {
                    // Chercher des graines dans le coffre
                    val seedSlot = InventoryManager.findSeedsSlotInChest(seedType)
                    if (seedSlot < 0) {
                        logger.warn("Plus de graines ($seedType) dans le coffre! Recuperes: $seedStacksMovedToInventory/${BotConstants.SEED_STACKS_FOR_INVENTORY} stacks pour inventaire")
                        // Continuer quand meme avec ce qu'on a
                        seedFetchingStep = 6
                        waitMs(300)
                        return
                    }

                    // Trouver un slot vide dans l'inventaire principal (pas la hotbar)
                    val invSlot = InventoryManager.findEmptySlotInMainInventoryForSeeds()
                    if (invSlot < 0) {
                        logger.warn("Inventaire principal plein - impossible de mettre les graines")
                        seedFetchingStep = 6
                        waitMs(300)
                        return
                    }

                    // Transfert cible: prendre du coffre et deposer dans l'inventaire principal
                    logger.info("Transfert graines du coffre (slot $seedSlot) vers inventaire principal (slot $invSlot) (${seedStacksMovedToInventory + 1}/${BotConstants.SEED_STACKS_FOR_INVENTORY})")
                    ActionManager.pickAndPlaceSlot(seedSlot, invSlot)
                    seedStacksMovedToInventory++
                    wait(8)
                } else {
                    // Inventaire done
                    logger.info("$seedStacksMovedToInventory stacks de graines transferes vers inventaire principal")
                    seedFetchingStep = 6
                    wait(4)
                }
            }
            6 -> {
                // Etape 6: Fermer le coffre
                ActionManager.closeScreen()

                val totalStacks = seedStacksMovedToHotbar + seedStacksMovedToInventory
                logger.info("========================================")
                logger.info("RECUPERATION GRAINES TERMINEE")
                logger.info("Stacks hotbar: $seedStacksMovedToHotbar")
                logger.info("Stacks inventaire: $seedStacksMovedToInventory")
                logger.info("Total: $totalStacks stacks")
                logger.info("========================================")

                ChatManager.showActionBar("$totalStacks stacks de graines recuperes", "a")

                // Reset des variables
                seedFetchingStep = 0
                seedFetchingChestOpenAttempts = 0

                // Retourner a la station en cours
                stateData.state = BotState.TELEPORTING
                waitMs(500)
            }
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

        // Calculer la pause en fonction des remplissages restants
        var pauseSeconds = config.getNextPauseSeconds(stateData.waterRefillsRemaining, stateData.cycleStartTime)
        val nextSessionType = if (stateData.waterRefillsRemaining > 0) "remplissage eau" else "recolte/plantation"

        // Ajouter 5 minutes de delai avant une session plante pour etre sur que les plantes sont bien finies
        // (pas de delai pour les sessions de remplissage d'eau uniquement)
        if (stateData.waterRefillsRemaining == 0) {
            val extraDelaySeconds = 5 * 60  // 5 minutes
            pauseSeconds += extraDelaySeconds
            logger.info("Ajout de 5 minutes de delai de securite avant session plante")
        }

        // OPTIMISATION: Si la prochaine session de remplissage est dans moins de 60 minutes,
        // la faire maintenant au lieu de se deconnecter et se reconnecter
        val mergeThresholdSeconds = AgriConfig.SESSION_MERGE_THRESHOLD_MINUTES * 60
        if (stateData.waterRefillsRemaining > 0 && pauseSeconds < mergeThresholdSeconds) {
            logger.info("========================================")
            logger.info("FUSION DE SESSION: Prochaine session ($nextSessionType) dans ${pauseSeconds / 60} min < ${AgriConfig.SESSION_MERGE_THRESHOLD_MINUTES} min")
            logger.info("Remplissage eau maintenant au lieu de se reconnecter")
            logger.info("========================================")

            ChatManager.showActionBar("Fusion: remplissage eau maintenant (${pauseSeconds / 60}min < seuil)", "a")

            // Configurer pour une session de remplissage d'eau
            stateData.apply {
                isWaterOnlySession = true
                waterRefillsRemaining--  // Decrementer car on va faire ce remplissage
                currentStationIndex = 0
                isFirstStationOfSession = true
                needsWaterRefill = true
                stationsCompleted = 0
                sessionStartTime = System.currentTimeMillis()  // Reset pour la nouvelle session
            }

            // Reinitialiser le delai adaptatif pour la nouvelle session
            BucketManager.resetAdaptiveDelay()

            // Verifier si on doit gerer les seaux (transition matin/apres-midi)
            if (BucketManager.needsModeTransition()) {
                bucketManagementStep = 0
                stateData.state = BotState.MANAGING_BUCKETS
            } else {
                BucketManager.saveCurrentMode()
                stateData.state = BotState.TELEPORTING
            }

            logger.info("Session de remplissage fusionnee demarree - ${stateData.waterRefillsRemaining} remplissages restants apres")
            return
        }

        ChatManager.showActionBar("Session terminee! ${stateData.stationsCompleted} stations", "a")

        logger.info("Pause de ${pauseSeconds / 60} minutes avant prochaine session ($nextSessionType)")
        logger.info("Deconnexion du serveur...")

        // Deconnecter du serveur et preparer la reconnexion
        ServerConnector.disconnectAndPrepareReconnect()

        // Calculer et stocker le timestamp de fin de pause
        stateData.pauseEndTime = System.currentTimeMillis() + (pauseSeconds * 1000L)

        // Passer en pause
        stateData.state = BotState.PAUSED
        waitMs(pauseSeconds * 1000)
    }

    private fun handlePaused() {
        // Fin de pause, verifier le type de pause
        if (stateData.isCrashReconnectPause) {
            // Pause due a une deconnexion inattendue (crash)
            val stations = stateData.cachedStations
            val currentStation = if (stateData.currentStationIndex < stations.size) {
                stations[stateData.currentStationIndex]
            } else {
                "N/A"
            }

            logger.info("========================================")
            logger.info("FIN DE PAUSE CRASH - RECONNEXION")
            logger.info("Etat avant crash: ${stateData.stateBeforeCrash}")
            logger.info("Reprise a la station: $currentStation (${stateData.currentStationIndex + 1}/${stateData.totalStations})")
            logger.info("========================================")

            ChatManager.showActionBar("Fin pause - Reconnexion et reprise...", "a")
            // Note: isCrashReconnectPause est reset dans handleConnecting() apres la reprise de session
        } else if (stateData.isEventPause) {
            // Pause due a un event (teleportation forcee)
            if (!stateData.canReconnectAfterEvent) {
                // Eau insuffisante - on se reconnecte quand meme mais on avertit
                logger.warn("========================================")
                logger.warn("FIN DE PAUSE EVENT - RECONNEXION")
                logger.warn("ATTENTION: L'eau etait insuffisante pour les 2h de pause")
                logger.warn("Les plantes ont peut-etre souffert - remplissage complet")
                logger.warn("========================================")

                ChatManager.showActionBar("Fin pause event - Reconnexion (eau etait insuffisante)", "e")
            } else {
                // Eau suffisante - se reconnecter normalement
                logger.info("========================================")
                logger.info("FIN DE PAUSE EVENT - RECONNEXION")
                logger.info("Reprise a la premiere station avec remplissage complet")
                logger.info("========================================")

                ChatManager.showActionBar("Fin pause event - Reconnexion...", "a")
            }
            stateData.isEventPause = false
        } else {
            // Pause normale
            logger.info("Fin de pause, reconnexion au serveur...")
        }

        // Lancer la reconnexion au serveur
        connectionRetryCount = 0
        connectionRetryDelayTicks = 0
        if (ServerConnector.startReconnection()) {
            stateData.state = BotState.CONNECTING
        } else {
            // Echec reconnexion - retenter apres 30 secondes (ne jamais abandonner)
            val errorContext = "Reconnexion au serveur '${config.serverAddress}' impossible: ${ServerConnector.errorMessage}"
            logger.error("$errorContext - retry dans 30s")
            ChatManager.showActionBar("Echec reconnexion - retry dans 30s...", "e")
            connectionRetryDelayTicks = CONNECTION_RETRY_DELAY_TICKS
            stateData.state = BotState.CONNECTING
        }
    }

    private fun handleError() {
        // Construire un message d'erreur detaille avec le contexte
        // OPTIMISATION: utiliser le cache au lieu de recalculer
        val stations = stateData.cachedStations
        val currentStation = if (stateData.currentStationIndex < stations.size) {
            stations[stateData.currentStationIndex]
        } else {
            "N/A"
        }

        val retryDelaySeconds = config.connectionRetryDelaySeconds
        val errorType = stateData.errorType
        val isRecoverable = errorType.isRecoverable && config.autoRecoveryEnabled

        val contextInfo = buildString {
            appendLine("========== ERREUR BOTCORE ==========")
            appendLine("Type: ${errorType.description} (${errorType.name})")
            appendLine("Message: ${stateData.errorMessage}")
            appendLine("Station actuelle: $currentStation (${stateData.currentStationIndex + 1}/${stateData.totalStations})")
            appendLine("Stations completees: ${stateData.stationsCompleted}")
            appendLine("Session eau uniquement: ${stateData.isWaterOnlySession}")
            appendLine("Remplissages restants: ${stateData.waterRefillsRemaining}")
            appendLine("Plante: ${config.selectedPlant}")
            appendLine("Tentative de recuperation: #${stateData.errorRetryCount}")
            appendLine("Recuperable: ${if (isRecoverable) "OUI" else "NON"}")
            if (isRecoverable) {
                appendLine("Le bot va retenter dans $retryDelaySeconds secondes...")
            } else {
                appendLine("ERREUR NON RECUPERABLE - Le bot s'arrete.")
            }
            appendLine("=====================================")
        }

        logger.error(contextInfo)

        // Message court pour l'action bar
        val shortMessage = if (stateData.errorMessage.length > 40) {
            stateData.errorMessage.take(40) + "..."
        } else {
            stateData.errorMessage
        }

        // Fermer tout menu ouvert et relacher les touches avant deconnexion
        ActionManager.closeScreen()
        ActionManager.releaseAllKeys()

        if (!isRecoverable) {
            // Erreur non recuperable - arreter le bot
            ChatManager.showActionBar("Erreur fatale: $shortMessage", "c")
            ChatManager.showLocalMessage("Erreur fatale sur '$currentStation': ${stateData.errorMessage}", "c")
            logger.error("Erreur non recuperable (${errorType.name}) - arret du bot")
            stop()
            return
        }

        ChatManager.showActionBar("Erreur: $shortMessage - retry ${retryDelaySeconds}s", "c")
        ChatManager.showLocalMessage("Erreur sur '$currentStation': ${stateData.errorMessage} - reconnexion dans ${retryDelaySeconds}s", "c")

        // Se deconnecter proprement du serveur pour eviter les problemes
        if (ChatManager.isConnected()) {
            logger.info("Deconnexion propre du serveur suite a une erreur - reconnexion dans $retryDelaySeconds secondes...")
            ServerConnector.disconnectAndPrepareReconnect()
        }

        // Passer en mode reconnexion automatique apres le delai configure
        logger.info("Passage en mode reconnexion automatique ($retryDelaySeconds secondes)...")
        connectionRetryDelayTicks = config.getConnectionRetryDelayTicks()
        stateData.isCrashReconnectPause = true  // Pour reprendre la session
        stateData.stateBeforeCrash = BotState.TELEPORTING  // Reprendre au debut de la station
        stateData.state = BotState.CONNECTING
    }
}
