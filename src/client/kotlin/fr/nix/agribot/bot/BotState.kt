package fr.nix.agribot.bot

/**
 * Etats possibles du bot.
 */
enum class BotState {
    /** Bot desactive */
    IDLE,

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
    var bucketsUsed: Int = 0,
    var fullBucketsRemaining: Int = 0,
    var currentBucketSlot: Int = 1,
    var sessionStartTime: Long = 0,
    var stationsCompleted: Int = 0,
    var needsWaterRefill: Boolean = false,
    var isFirstSession: Boolean = true,
    var lastActionTime: Long = 0,
    var errorMessage: String = ""
) {
    fun reset() {
        state = BotState.IDLE
        currentStationIndex = 0
        bucketsUsed = 0
        stationsCompleted = 0
        lastActionTime = 0
        errorMessage = ""
    }
}
