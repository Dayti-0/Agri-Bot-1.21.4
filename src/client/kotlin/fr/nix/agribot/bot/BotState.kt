package fr.nix.agribot.bot

import net.minecraft.util.math.Vec3d

/**
 * Types d'erreurs specifiques pour un meilleur diagnostic.
 */
enum class ErrorType(val description: String, val isRecoverable: Boolean) {
    /** Pas d'erreur */
    NONE("Aucune erreur", true),

    /** Erreur de connexion au serveur */
    NETWORK_ERROR("Erreur reseau", true),

    /** Timeout lors d'une operation */
    TIMEOUT_ERROR("Delai depasse", true),

    /** Impossible d'ouvrir un menu/coffre */
    MENU_ERROR("Erreur d'ouverture de menu", true),

    /** Station introuvable ou inaccessible */
    STATION_ERROR("Erreur de station", true),

    /** Plus de seaux disponibles */
    BUCKET_ERROR("Erreur de seaux", true),

    /** Plus de graines disponibles */
    SEED_ERROR("Plus de graines", false),

    /** Erreur de configuration */
    CONFIG_ERROR("Erreur de configuration", false),

    /** Erreur inconnue/generique */
    UNKNOWN_ERROR("Erreur inconnue", true);

    companion object {
        /**
         * Determine le type d'erreur a partir d'un message d'erreur.
         */
        fun fromMessage(message: String): ErrorType {
            val lowerMessage = message.lowercase()
            return when {
                lowerMessage.contains("connexion") || lowerMessage.contains("deconnect") ||
                    lowerMessage.contains("network") || lowerMessage.contains("reseau") -> NETWORK_ERROR

                lowerMessage.contains("timeout") || lowerMessage.contains("delai") ||
                    lowerMessage.contains("attente") -> TIMEOUT_ERROR

                lowerMessage.contains("menu") || lowerMessage.contains("coffre") ||
                    lowerMessage.contains("ouvrir") -> MENU_ERROR

                lowerMessage.contains("station") || lowerMessage.contains("teleport") -> STATION_ERROR

                lowerMessage.contains("seau") || lowerMessage.contains("bucket") ||
                    lowerMessage.contains("eau") -> BUCKET_ERROR

                lowerMessage.contains("graine") || lowerMessage.contains("seed") -> SEED_ERROR

                lowerMessage.contains("config") -> CONFIG_ERROR

                else -> UNKNOWN_ERROR
            }
        }
    }
}

/**
 * Etats possibles du bot.
 */
enum class BotState {
    /** Bot desactive */
    IDLE,

    /** Attente du minuteur avant demarrage */
    WAITING_STARTUP,

    /** Connexion automatique au serveur de jeu en cours */
    CONNECTING,

    /** En attente de connexion au serveur */
    WAITING_CONNECTION,

    /** Gestion des seaux (transition matin/apres-midi) */
    MANAGING_BUCKETS,

    /** Teleportation vers une station */
    TELEPORTING,

    /** Attente apres teleportation */
    WAITING_TELEPORT,

    /** Ouverture de la station */
    OPENING_STATION,

    /** Recolte en cours */
    HARVESTING,

    /** Plantation en cours */
    PLANTING,

    /** Remplissage de l'eau */
    FILLING_WATER,

    /** Remplissage des seaux avec /eau */
    REFILLING_BUCKETS,

    /** Passage a la station suivante */
    NEXT_STATION,

    /** Vidage des seaux restants en fin de session */
    EMPTYING_REMAINING_BUCKETS,

    /** Recuperation de seaux depuis le coffre de backup (apres crash) */
    RECOVERING_BUCKETS,

    /** Recuperation de graines depuis le coffre de graines */
    FETCHING_SEEDS,

    /** Fin de session, deconnexion */
    DISCONNECTING,

    /** Pause entre les sessions */
    PAUSED,

    /** Erreur */
    ERROR
}

/**
 * Donnees d'etat du bot.
 */
data class BotStateData(
    var state: BotState = BotState.IDLE,
    var currentStationIndex: Int = 0,
    var totalStations: Int = 0,
    var sessionStartTime: Long = 0,
    var stationsCompleted: Int = 0,
    var needsWaterRefill: Boolean = false,
    var lastActionTime: Long = 0,
    var errorMessage: String = "",
    var errorType: ErrorType = ErrorType.NONE,
    var errorRetryCount: Int = 0,

    // Gestion des sessions de remplissage d'eau intermediaires
    /** True si cette session est uniquement pour remplir l'eau (pas de recolte/plantation) */
    var isWaterOnlySession: Boolean = false,
    /** Nombre de sessions de remplissage encore necessaires avant la prochaine recolte */
    var waterRefillsRemaining: Int = 0,
    /** Timestamp de debut du cycle de croissance actuel (premiere plantation) */
    var cycleStartTime: Long = 0,

    // Gestion de la distance de teleportation
    /** Position du joueur avant la teleportation */
    var positionBeforeTeleport: Vec3d? = null,

    /** Indique si c'est la premiere station de la session (necessite plus de temps de chargement) */
    var isFirstStationOfSession: Boolean = true,

    /** Timestamp de fin de la pause (pour affichage du temps restant) */
    var pauseEndTime: Long = 0,

    /** Timestamp de fin du delai de demarrage (pour affichage du temps restant) */
    var startupEndTime: Long = 0,

    // Gestion des events (teleportation forcee)
    /** True si la pause actuelle est due a un event (teleportation forcee) */
    var isEventPause: Boolean = false,
    /** True si l'eau est suffisante pour se reconnecter apres la pause event */
    var canReconnectAfterEvent: Boolean = true,
    /** True si la prochaine session doit forcer le remplissage complet (apres event) */
    var forceFullWaterRefill: Boolean = false,

    // Gestion des deconnexions inattendues (crash/connection reset)
    /** True si la pause actuelle est due a une deconnexion inattendue */
    var isCrashReconnectPause: Boolean = false,
    /** Etat du bot avant la deconnexion (pour log informatif) */
    var stateBeforeCrash: BotState? = null,

    // OPTIMISATION: Cache des stations actives (evite recalcul a chaque tick)
    /** Liste des stations actives cachee au debut de la session */
    var cachedStations: List<String> = emptyList()
) {
    fun reset() {
        state = BotState.IDLE
        currentStationIndex = 0
        stationsCompleted = 0
        lastActionTime = 0
        errorMessage = ""
        errorType = ErrorType.NONE
        errorRetryCount = 0
        isWaterOnlySession = false
        waterRefillsRemaining = 0
        isEventPause = false
        canReconnectAfterEvent = true
        forceFullWaterRefill = false
        isCrashReconnectPause = false
        stateBeforeCrash = null
        cachedStations = emptyList()
        startupEndTime = 0
    }

    /**
     * Definit une erreur avec type automatique.
     */
    fun setError(message: String, type: ErrorType? = null) {
        errorMessage = message
        errorType = type ?: ErrorType.fromMessage(message)
        errorRetryCount++
    }

    /**
     * Efface l'erreur courante.
     */
    fun clearError() {
        errorMessage = ""
        errorType = ErrorType.NONE
    }
}
