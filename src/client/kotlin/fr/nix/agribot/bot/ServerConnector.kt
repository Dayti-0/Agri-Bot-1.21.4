package fr.nix.agribot.bot

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.action.ActionManager
import fr.nix.agribot.chat.AutoResponseManager
import fr.nix.agribot.chat.ChatManager
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.inventory.InventoryManager
import fr.nix.agribot.menu.MenuDetector
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import org.slf4j.LoggerFactory

/**
 * Etat de la connexion au serveur de jeu.
 */
enum class ConnectionState {
    /** Pas encore demarre */
    IDLE,
    /** Envoi de /login */
    SENDING_LOGIN,
    /** Attente apres /login */
    WAITING_AFTER_LOGIN,
    /** Verification captcha */
    CHECKING_CAPTCHA,
    /** Selection de la boussole */
    SELECTING_COMPASS,
    /** Ouverture du menu boussole */
    OPENING_COMPASS_MENU,
    /** Attente du menu boussole */
    WAITING_COMPASS_MENU,
    /** Clic sur la hache en netherite */
    CLICKING_NETHERITE_AXE,
    /** Attente de connexion au serveur de jeu */
    WAITING_GAME_SERVER,
    /** Captcha detecte - deconnexion */
    CAPTCHA_DISCONNECT,
    /** Attente avant reconnexion (5 secondes) */
    WAITING_RECONNECT,
    /** Reconnexion au serveur en cours */
    RECONNECTING,
    /** Attente que la connexion soit etablie */
    WAITING_CONNECTION_ESTABLISHED,
    /** Teleportation vers le home mouvement */
    TELEPORTING_MOVEMENT_HOME,
    /** Attente apres teleportation mouvement */
    WAITING_TELEPORT_MOVEMENT,
    /** Mouvement vers l'avant (1 seconde) */
    MOVING_FORWARD,
    /** Mouvement vers l'arriere (1 seconde) */
    MOVING_BACKWARD,
    /** Connexion terminee avec succes */
    CONNECTED,
    /** Erreur de connexion */
    ERROR
}

/**
 * Gestionnaire de connexion automatique au serveur.
 * Gere le processus de /login et la selection du serveur de jeu via le menu boussole.
 */
object ServerConnector {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    private val config: AgriConfig
        get() = AgriBotClient.config

    /** Etat actuel de la connexion */
    var state = ConnectionState.IDLE
        private set

    /** Sous-etape pour certains etats */
    private var subStep = 0

    /** Nombre de tentatives de reconnexion */
    private var reconnectAttempts = 0
    private const val MAX_RECONNECT_ATTEMPTS = 3

    /** Etats qui necessitent une connexion active (joueur + network handler) */
    // Note: WAITING_GAME_SERVER n'est PAS inclus car le transfert proxy (BungeeCord/Velocity)
    // provoque une deconnexion breve qui est un comportement normal attendu
    private val STATES_REQUIRING_CONNECTION = setOf(
        ConnectionState.SENDING_LOGIN,
        ConnectionState.WAITING_AFTER_LOGIN,
        ConnectionState.CHECKING_CAPTCHA,
        ConnectionState.SELECTING_COMPASS,
        ConnectionState.OPENING_COMPASS_MENU,
        ConnectionState.WAITING_COMPASS_MENU,
        ConnectionState.CLICKING_NETHERITE_AXE,
        ConnectionState.TELEPORTING_MOVEMENT_HOME,
        ConnectionState.WAITING_TELEPORT_MOVEMENT,
        ConnectionState.MOVING_FORWARD,
        ConnectionState.MOVING_BACKWARD
    )

    /** Compteur pour les timeouts */
    private var waitCounter = 0

    /** Message d'erreur */
    var errorMessage = ""
        private set

    /** Informations du serveur pour la reconnexion */
    private var savedServerInfo: ServerInfo? = null
    private var savedServerAddress: String? = null

    /** Flag pour detecter l'ouverture du menu */
    private var menuJustOpened = false

    /** Flag pour detecter la fermeture du menu apres clic hache */
    private var menuJustClosed = false

    /** Flag pour eviter le spam du message "Connexion etablie" */
    private var connectionEstablishedLogged = false

    /** Flag pour detecter le moment exact ou la connexion est etablie (pour le delai post-connexion) */
    private var connectionDetectedForStabilization = false

    /**
     * Demarre le processus de connexion automatique.
     * @return true si le processus a demarre, false si deja en cours ou erreur
     */
    fun startConnection(): Boolean {
        if (state != ConnectionState.IDLE && state != ConnectionState.CONNECTED && state != ConnectionState.ERROR) {
            logger.warn("Connexion deja en cours (etat: $state)")
            return false
        }

        // Verifier que le mot de passe est configure
        if (config.loginPassword.isBlank()) {
            errorMessage = "Mot de passe non configure!"
            ChatManager.showActionBar(errorMessage, "c")
            state = ConnectionState.ERROR
            return false
        }

        // Verifier la connexion au serveur
        if (!ChatManager.isConnected()) {
            errorMessage = "Pas connecte au serveur!"
            ChatManager.showActionBar(errorMessage, "c")
            state = ConnectionState.ERROR
            return false
        }

        logger.info("========================================")
        logger.info("Demarrage connexion automatique")
        logger.info("========================================")

        state = ConnectionState.SENDING_LOGIN
        subStep = 0
        reconnectAttempts = 0
        waitCounter = 0
        errorMessage = ""

        ChatManager.showActionBar("Connexion automatique...", "6")
        return true
    }

    /**
     * Reset l'etat du connecteur.
     */
    fun reset() {
        state = ConnectionState.IDLE
        subStep = 0
        reconnectAttempts = 0
        waitCounter = 0
        errorMessage = ""
        savedServerInfo = null
        savedServerAddress = null
        menuJustOpened = false
        menuJustClosed = false
        connectionEstablishedLogged = false
        connectionDetectedForStabilization = false
    }

    /**
     * Verifie si la connexion est terminee (succes ou erreur).
     */
    fun isFinished(): Boolean {
        return state == ConnectionState.CONNECTED || state == ConnectionState.ERROR
    }

    /**
     * Verifie si la connexion a reussi.
     */
    fun isConnected(): Boolean {
        return state == ConnectionState.CONNECTED
    }

    /**
     * Traite un tick de la machine d'etat de connexion.
     * Appelee depuis BotCore.onTick() quand en etat CONNECTING.
     */
    fun onTick() {
        // Verifier la connexion pour les etats qui necessitent un joueur connecte
        // Si la connexion est perdue pendant ces etats, passer en erreur
        if (state in STATES_REQUIRING_CONNECTION && !ChatManager.isConnected()) {
            logger.warn("Connexion perdue pendant l'etat $state - passage en erreur")
            errorMessage = "Connexion perdue pendant $state"
            state = ConnectionState.ERROR
            return
        }

        when (state) {
            ConnectionState.IDLE -> { /* Rien */ }
            ConnectionState.SENDING_LOGIN -> handleSendingLogin()
            ConnectionState.WAITING_AFTER_LOGIN -> handleWaitingAfterLogin()
            ConnectionState.CHECKING_CAPTCHA -> handleCheckingCaptcha()
            ConnectionState.SELECTING_COMPASS -> handleSelectingCompass()
            ConnectionState.OPENING_COMPASS_MENU -> handleOpeningCompassMenu()
            ConnectionState.WAITING_COMPASS_MENU -> handleWaitingCompassMenu()
            ConnectionState.CLICKING_NETHERITE_AXE -> handleClickingNetheriteAxe()
            ConnectionState.WAITING_GAME_SERVER -> handleWaitingGameServer()
            ConnectionState.CAPTCHA_DISCONNECT -> handleCaptchaDisconnect()
            ConnectionState.WAITING_RECONNECT -> handleWaitingReconnect()
            ConnectionState.RECONNECTING -> handleReconnecting()
            ConnectionState.WAITING_CONNECTION_ESTABLISHED -> handleWaitingConnectionEstablished()
            ConnectionState.TELEPORTING_MOVEMENT_HOME -> handleTeleportingMovementHome()
            ConnectionState.WAITING_TELEPORT_MOVEMENT -> handleWaitingTeleportMovement()
            ConnectionState.MOVING_FORWARD -> handleMovingForward()
            ConnectionState.MOVING_BACKWARD -> handleMovingBackward()
            ConnectionState.CONNECTED -> { /* Rien */ }
            ConnectionState.ERROR -> { /* Rien */ }
        }
    }

    // ==================== HANDLERS ====================

    private fun handleSendingLogin() {
        // Envoyer la commande /login
        val password = config.loginPassword
        logger.info("Envoi commande /login ****")
        ChatManager.sendCommand("login $password")

        state = ConnectionState.WAITING_AFTER_LOGIN
        waitCounter = 0
    }

    private fun handleWaitingAfterLogin() {
        // Attendre 2 secondes (40 ticks) apres /login
        waitCounter++
        if (waitCounter >= 40) {
            state = ConnectionState.CHECKING_CAPTCHA
            waitCounter = 0
        }
    }

    private fun handleCheckingCaptcha() {
        // Verifier si on a une carte (captcha) dans l'inventaire
        if (InventoryManager.hasMapInInventory()) {
            logger.warn("Carte captcha detectee - deconnexion necessaire")
            ChatManager.showActionBar("Captcha detecte - Reconnexion...", "e")
            state = ConnectionState.CAPTCHA_DISCONNECT
            return
        }

        // Pas de captcha, verifier si on a une boussole
        if (InventoryManager.hasCompassInHotbar()) {
            logger.info("Boussole detectee - ouverture du menu serveur")
            state = ConnectionState.SELECTING_COMPASS
        } else {
            // Pas de boussole, peut-etre deja sur le serveur de jeu ou probleme
            logger.info("Pas de boussole - verification si deja connecte")
            // Passer au mouvement initial ou directement connecte
            transitionToMovementOrConnected()
        }
    }

    private fun handleSelectingCompass() {
        // Selectionner la boussole
        if (InventoryManager.selectCompass()) {
            state = ConnectionState.OPENING_COMPASS_MENU
            waitCounter = 0
        } else {
            errorMessage = "Impossible de selectionner la boussole"
            state = ConnectionState.ERROR
            ChatManager.showActionBar(errorMessage, "c")
        }
    }

    private fun handleOpeningCompassMenu() {
        // Attendre un peu que le slot soit bien selectionne
        waitCounter++
        if (waitCounter >= 10) { // 0.5 seconde
            // Clic droit pour ouvrir le menu
            logger.info("Clic droit avec boussole pour ouvrir le menu")
            ActionManager.rightClick()
            state = ConnectionState.WAITING_COMPASS_MENU
            waitCounter = 0
        }
    }

    private fun handleWaitingCompassMenu() {
        // Attendre que le menu soit ouvert
        waitCounter++

        if (MenuDetector.isSimpleMenuOpen()) {
            // Menu vient de s'ouvrir - reset le compteur
            if (!menuJustOpened) {
                menuJustOpened = true
                waitCounter = 0
                logger.info("Menu boussole detecte - attente stabilisation...")
            }

            // Attendre 2 secondes de stabilisation APRES ouverture du menu
            if (waitCounter >= 40) { // 2 secondes de stabilisation
                logger.info("Menu boussole charge - recherche hache")
                state = ConnectionState.CLICKING_NETHERITE_AXE
                waitCounter = 0
                menuJustOpened = false
            }
        } else if (waitCounter >= 100) { // Timeout 5 secondes
            logger.warn("Timeout attente menu boussole")
            // Reessayer d'ouvrir
            state = ConnectionState.OPENING_COMPASS_MENU
            waitCounter = 0
            menuJustOpened = false
        }
    }

    private fun handleClickingNetheriteAxe() {
        // Chercher la hache en netherite dans le menu
        val axeSlot = InventoryManager.findNetheriteAxeSlotInMenu()

        if (axeSlot >= 0) {
            // Cliquer sur la hache
            logger.info("Clic sur la hache en netherite (slot $axeSlot)")
            ActionManager.leftClickSlot(axeSlot)
            state = ConnectionState.WAITING_GAME_SERVER
            waitCounter = 0
        } else {
            // Hache non trouvee, peut-etre pas le bon menu
            logger.warn("Hache en netherite non trouvee dans le menu")
            // Fermer le menu et reessayer
            ActionManager.pressEscape()
            waitCounter++
            if (waitCounter >= 3) {
                errorMessage = "Hache en netherite non trouvee"
                state = ConnectionState.ERROR
                ChatManager.showActionBar(errorMessage, "c")
            } else {
                // Reessayer d'ouvrir le menu
                state = ConnectionState.OPENING_COMPASS_MENU
                waitCounter = 0
            }
        }
    }

    private fun handleWaitingGameServer() {
        // Attendre la connexion au serveur de jeu
        // Note: Pendant le transfert proxy (BungeeCord/Velocity), la connexion est brievement
        // perdue (client.player devient null). C'est un comportement NORMAL et attendu.
        waitCounter++

        val isConnected = ChatManager.isConnected()

        // Verifier si le menu s'est ferme ou si la connexion a ete perdue (signe de transfert proxy)
        if (!MenuDetector.isMenuOpen() || (!isConnected && menuJustClosed)) {
            // Menu vient de se fermer ou connexion perdue pendant le transfert
            if (!menuJustClosed) {
                menuJustClosed = true
                waitCounter = 0
                logger.info("Menu ferme - attente chargement du monde...")
                ChatManager.showActionBar("Chargement du monde...", "6")
            }

            // Attendre 7 secondes ET que la connexion soit retablie
            // Pendant le transfert proxy, la connexion revient apres ~1 seconde
            if (waitCounter >= 140) { // 7 secondes apres fermeture du menu
                if (isConnected) {
                    logger.info("Connexion au serveur de jeu reussie!")
                    menuJustClosed = false
                    // Passer au mouvement initial ou directement connecte
                    transitionToMovementOrConnected()
                } else if (waitCounter >= 600) { // Timeout 30 secondes si la connexion ne revient pas
                    logger.warn("Timeout - connexion non retablie apres transfert serveur")
                    errorMessage = "Timeout attente transfert serveur"
                    state = ConnectionState.ERROR
                    menuJustClosed = false
                }
            }
        } else if (waitCounter >= 400) { // Timeout 20 secondes si menu reste ouvert
            logger.warn("Timeout connexion au serveur de jeu")
            // Fermer le menu et considerer comme connecte (le serveur peut avoir des delais)
            ActionManager.pressEscape()
            menuJustClosed = false
            // Passer au mouvement initial ou directement connecte
            transitionToMovementOrConnected()
        }
    }

    private fun handleCaptchaDisconnect() {
        // Sauvegarder les informations du serveur avant de se deconnecter
        savedServerInfo = client.currentServerEntry
        savedServerAddress = config.serverAddress

        logger.info("Sauvegarde serveur: ${savedServerInfo?.address ?: savedServerAddress}")

        // Deconnecter le joueur
        logger.info("Deconnexion pour captcha...")
        client.execute {
            client.world?.disconnect()
        }
        state = ConnectionState.WAITING_RECONNECT
        waitCounter = 0
    }

    private fun handleWaitingReconnect() {
        // Attendre 5 secondes (100 ticks) avant de se reconnecter
        waitCounter++

        if (waitCounter >= 100) {
            reconnectAttempts++

            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                errorMessage = "Trop de tentatives de reconnexion"
                state = ConnectionState.ERROR
                ChatManager.showActionBar(errorMessage, "c")
                return
            }

            // Passer a l'etat de reconnexion
            logger.info("Reconnexion (tentative $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")
            ChatManager.showActionBar("Reconnexion...", "6")
            state = ConnectionState.RECONNECTING
            waitCounter = 0
        }
    }

    private fun handleReconnecting() {
        // Reconnecter au serveur
        val serverAddress = savedServerInfo?.address ?: savedServerAddress ?: config.serverAddress

        logger.info("Reconnexion au serveur: $serverAddress")

        client.execute {
            try {
                // Creer ServerInfo si on n'en a pas
                val serverInfo = savedServerInfo ?: ServerInfo(
                    "SurvivalWorld",
                    serverAddress,
                    ServerInfo.ServerType.OTHER
                )

                // Parser l'adresse du serveur
                val address = ServerAddress.parse(serverAddress)

                // Se connecter via ConnectScreen
                ConnectScreen.connect(
                    client.currentScreen ?: TitleScreen(),
                    client,
                    address,
                    serverInfo,
                    false,
                    null
                )

                logger.info("Connexion initiee vers $serverAddress")
            } catch (e: Exception) {
                logger.error("Erreur lors de la reconnexion: ${e.message}")
                errorMessage = "Erreur de reconnexion: ${e.message}"
                state = ConnectionState.ERROR
            }
        }

        state = ConnectionState.WAITING_CONNECTION_ESTABLISHED
        waitCounter = 0
    }

    private fun handleWaitingConnectionEstablished() {
        // Attendre que la connexion soit etablie
        waitCounter++

        // Verifier si on est connecte (player existe)
        if (ChatManager.isConnected()) {
            // Logger seulement une fois et reset le compteur pour le delai post-connexion
            if (!connectionDetectedForStabilization) {
                connectionDetectedForStabilization = true
                waitCounter = 0  // Reset pour compter le delai APRES la connexion
                logger.info("Connexion etablie - attente stabilisation avant login...")
                connectionEstablishedLogged = true
            }
            // Attendre 3 secondes APRES connexion detectee avant de renvoyer /login (anti-spam)
            if (waitCounter >= 60) { // 3 secondes de stabilisation post-connexion
                state = ConnectionState.SENDING_LOGIN
                waitCounter = 0
                connectionDetectedForStabilization = false
            }
        } else if (waitCounter >= 400) { // Timeout 20 secondes
            logger.warn("Timeout attente connexion")
            // Reessayer la reconnexion avec delai de 5 secondes (anti-spam)
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                state = ConnectionState.WAITING_RECONNECT
                waitCounter = 0
            } else {
                errorMessage = "Echec de reconnexion apres plusieurs tentatives"
                state = ConnectionState.ERROR
                ChatManager.showActionBar(errorMessage, "c")
            }
        }
    }

    /**
     * Determine si on doit effectuer le mouvement initial ou passer directement a CONNECTED.
     * Verifie si homeMouvement est configure.
     */
    private fun transitionToMovementOrConnected() {
        if (config.homeMouvement.isNotBlank()) {
            // Mouvement initial configure - teleporter d'abord
            logger.info("Mouvement initial configure - teleportation vers ${config.homeMouvement}")
            ChatManager.showActionBar("Mouvement initial...", "6")
            state = ConnectionState.TELEPORTING_MOVEMENT_HOME
            waitCounter = 0
        } else {
            // Pas de mouvement initial - connexion directe
            state = ConnectionState.CONNECTED
            ChatManager.showActionBar("Connexion reussie!", "a")
            AutoResponseManager.onGameServerConnected()
        }
    }

    private fun handleTeleportingMovementHome() {
        // Teleporter vers le home mouvement
        val homeMouvement = config.homeMouvement
        logger.info("Teleportation vers home mouvement: $homeMouvement")
        ChatManager.sendCommand("home $homeMouvement")

        state = ConnectionState.WAITING_TELEPORT_MOVEMENT
        waitCounter = 0
    }

    private fun handleWaitingTeleportMovement() {
        // Attendre 2 secondes apres la teleportation
        waitCounter++

        if (waitCounter >= 40) { // 2 secondes
            logger.info("Teleportation terminee - debut mouvement avant")
            ChatManager.showActionBar("Avance...", "6")
            ActionManager.startMovingForward()
            state = ConnectionState.MOVING_FORWARD
            waitCounter = 0
        }
    }

    private fun handleMovingForward() {
        // Avancer pendant 1 seconde
        waitCounter++

        if (waitCounter >= 20) { // 1 seconde
            logger.info("Fin mouvement avant - debut mouvement arriere")
            ActionManager.stopMovingForward()
            ChatManager.showActionBar("Recule...", "6")
            ActionManager.startMovingBackward()
            state = ConnectionState.MOVING_BACKWARD
            waitCounter = 0
        }
    }

    private fun handleMovingBackward() {
        // Reculer pendant 1 seconde
        waitCounter++

        if (waitCounter >= 20) { // 1 seconde
            logger.info("Fin mouvement arriere - connexion terminee")
            ActionManager.stopMovingBackward()
            state = ConnectionState.CONNECTED
            ChatManager.showActionBar("Connexion reussie!", "a")
            AutoResponseManager.onGameServerConnected()
        }
    }

    /**
     * Deconnecte le joueur et prepare la reconnexion ulterieure.
     * Sauvegarde les informations du serveur pour pouvoir se reconnecter.
     */
    fun disconnectAndPrepareReconnect() {
        // Sauvegarder les informations du serveur avant de se deconnecter
        savedServerInfo = client.currentServerEntry
        savedServerAddress = config.serverAddress

        logger.info("Sauvegarde serveur pour reconnexion: ${savedServerInfo?.address ?: savedServerAddress}")

        // Deconnecter le joueur
        logger.info("Deconnexion du serveur...")
        client.execute {
            client.world?.disconnect()
        }

        // Reset l'etat pour la prochaine reconnexion
        state = ConnectionState.IDLE
        reconnectAttempts = 0
        waitCounter = 0
    }

    /**
     * Demarre une reconnexion au serveur apres une deconnexion.
     * Utilise les informations du serveur sauvegardees precedemment.
     * @return true si la reconnexion a demarre, false si erreur
     */
    fun startReconnection(): Boolean {
        if (state != ConnectionState.IDLE && state != ConnectionState.ERROR) {
            logger.warn("Reconnexion deja en cours (etat: $state)")
            return false
        }

        // Verifier que le mot de passe est configure
        if (config.loginPassword.isBlank()) {
            errorMessage = "Mot de passe non configure!"
            ChatManager.showActionBar(errorMessage, "c")
            state = ConnectionState.ERROR
            return false
        }

        // Verifier qu'on a une adresse de serveur
        val serverAddress = savedServerInfo?.address ?: savedServerAddress ?: config.serverAddress
        if (serverAddress.isBlank()) {
            errorMessage = "Adresse serveur non configuree!"
            ChatManager.showActionBar(errorMessage, "c")
            state = ConnectionState.ERROR
            return false
        }

        logger.info("========================================")
        logger.info("Demarrage reconnexion au serveur: $serverAddress")
        logger.info("========================================")

        reconnectAttempts = 0
        state = ConnectionState.WAITING_RECONNECT
        waitCounter = 0
        connectionEstablishedLogged = false
        connectionDetectedForStabilization = false
        ChatManager.showActionBar("Reconnexion dans 5s (anti-spam)...", "6")

        return true
    }
}
