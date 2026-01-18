package fr.nix.agribot.bot

/**
 * Constantes centralisees pour le bot.
 * Regroupe tous les delais, timeouts et limites utilisees par BotCore.
 */
object BotConstants {
    // ==================== TIMING ====================

    /** Nombre de ticks par seconde dans Minecraft */
    const val TICKS_PER_SECOND = 20

    // ==================== TELEPORTATION ====================

    /** Nombre max de tentatives d'attente de confirmation de teleportation */
    const val MAX_TELEPORT_WAIT_RETRIES = 100  // 100 x 100ms = 10 secondes max

    /** Delai entre chaque verification de teleportation (ms) */
    const val TELEPORT_CHECK_INTERVAL_MS = 100

    /** Distance en blocs au-dela de laquelle on ajoute un delai supplementaire */
    const val LONG_DISTANCE_THRESHOLD = 50.0

    /** Delai supplementaire pour les teleportations longue distance (ms) */
    const val LONG_DISTANCE_EXTRA_DELAY_MS = 2000

    /** Delai supplementaire pour la premiere station de la session (ms) */
    const val FIRST_STATION_EXTRA_DELAY_MS = 3000

    // ==================== MENU / GUI ====================

    /** Nombre max de tentatives d'attente d'ouverture de menu */
    const val MAX_MENU_OPEN_RETRIES = 50  // 50 x 100ms = 5 secondes max

    /** Nombre max de tentatives globales d'ouverture de menu */
    const val MAX_MENU_OPEN_ATTEMPTS = 3

    /** Nombre max de tentatives d'ouverture de coffre */
    const val MAX_CHEST_OPEN_ATTEMPTS = 3

    /** Delai de stabilisation apres ouverture de menu (ticks) */
    const val MENU_STABILIZATION_TICKS = 40  // 2 secondes

    /** Delai de stabilisation supplementaire pour la premiere station (ticks) */
    const val FIRST_STATION_STABILIZATION_EXTRA_TICKS = 40  // 2 secondes de plus

    // ==================== RECOLTE ====================

    /** Nombre max de tentatives de recolte */
    const val MAX_HARVESTING_RETRIES = 5

    /** Nombre de stations consecutives sans melon pour declencher une deconnexion anticipee */
    const val MAX_CONSECUTIVE_STATIONS_WITHOUT_MELON = 3

    /** Delai de reconnexion en minutes apres une deconnexion anticipee (plantes pas pretes) */
    const val EARLY_DISCONNECT_RECONNECT_DELAY_MINUTES = 30

    // ==================== SEAUX / EAU ====================

    /** Nombre max de verifications pour le remplissage des seaux */
    const val MAX_REFILLING_CHECKS = 100  // 100 x 100ms = 10 secondes max

    /** Intervalle entre les verifications de remplissage (ms) */
    const val REFILLING_CHECK_INTERVAL_MS = 100

    /** Nombre max de verifications pour le versement d'eau */
    const val MAX_WATER_POURING_CHECKS = 100  // 100 ticks = 5 secondes max

    /** Limite de seaux utilises par station (securite) */
    const val MAX_BUCKETS_PER_STATION = 50

    // ==================== CONNEXION ====================

    /** Delai entre les tentatives de connexion (ticks) - 30 secondes */
    const val CONNECTION_RETRY_DELAY_TICKS = 600

    /** Intervalle de verification periodique de la connexion (ticks) - 5 secondes */
    const val CONNECTION_CHECK_INTERVAL = 100

    // ==================== GRAINES ====================

    /** Nombre de stacks de graines pour la hotbar */
    const val SEED_STACKS_FOR_HOTBAR = 1

    /** Nombre de stacks de graines pour l'inventaire principal */
    const val SEED_STACKS_FOR_INVENTORY = 5

    // ==================== FONCTIONS UTILITAIRES ====================

    /**
     * Convertit des millisecondes en ticks.
     */
    fun msToTicks(ms: Int): Int = ms * TICKS_PER_SECOND / 1000

    /**
     * Convertit des ticks en millisecondes.
     */
    fun ticksToMs(ticks: Int): Int = ticks * 1000 / TICKS_PER_SECOND
}
