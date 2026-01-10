package fr.nix.agribot.bot

import net.minecraft.util.math.Vec3d

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
        isWaterOnlySession = false
        waterRefillsRemaining = 0
        isEventPause = false
        canReconnectAfterEvent = true
        forceFullWaterRefill = false
        cachedStations = emptyList()
        startupEndTime = 0
    }
}
