package fr.nix.agribot.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Configuration principale du bot agricole.
 */
data class AgriConfig(
    // Serveur
    val serverAddress: String = "play.survivalworld.fr",

    // Mot de passe pour la connexion automatique (/login)
    var loginPassword: String = "",

    // Plante selectionnee
    var selectedPlant: String = "Brocoli",
    var growthBoost: Float = 29f,

    // Stations (30 max, chaine vide = pas de station)
    val stations: MutableList<String> = MutableList(30) { "" },

    // Home pour le coffre de seaux (depot/recuperation)
    var homeCoffre: String = "coffre",

    // Home pour le coffre de backup (seaux de secours apres crash)
    // Si vide, la recuperation de seaux de backup est desactivee
    var homeBackup: String = "",

    // Home pour le coffre de graines (ravitaillement automatique)
    // Si vide, la recuperation de graines est desactivee
    var homeGraines: String = "",

    // Home pour le mouvement initial apres connexion
    // Si vide, le mouvement initial est desactive
    // Permet de contourner le bug serveur qui necessite un mouvement avant d'envoyer des messages
    var homeMouvement: String = "",

    // Nombre de seaux cible (nombre de seaux a avoir en inventaire)
    var targetBucketCount: Int = 16,

    // Duree de l'eau dans les stations (en minutes)
    // Valeurs possibles: 300 (5h), 340 (5h40), 380 (6h20), 420 (7h), 460 (7h40),
    //                    500 (8h20), 540 (9h), 580 (9h40), 620 (10h20), 660 (11h), 720 (12h)
    var waterDurationMinutes: Int = 720,  // 12h par defaut

    // Delai avant le demarrage du bot (en minutes)
    // 0 = demarrage immediat, sinon le bot attend ce delai avant de commencer
    var startupDelayMinutes: Int = 0,

    // Parametres des seaux
    var lastBucketMode: String? = null, // "drop", "retrieve", ou "normal"
    var lastTransitionPeriod: String? = null, // Periode de la derniere transition (ex: "2024-01-15-matin")
    var lastWaterRefillTime: Long? = null, // Timestamp du dernier remplissage

    // Delais (en millisecondes)
    var delayShort: Int = 300,
    var delayMedium: Int = 800,
    var delayLong: Int = 1500,
    var delayAfterTeleport: Int = 2000,
    var delayAfterOpenMenu: Int = 3000,
    var delayBetweenBuckets: Int = 3000,
    var delayAfterCommand: Int = 2000,

    // Touches (codes GLFW)
    var keyToggleBot: Int = 294,       // F5 par defaut
    var keyFillBuckets: Int = 296,     // F7 par defaut (execute /eau)

    // ==================== PARAMETRES DE RETRY / ROBUSTESSE ====================

    /** Nombre max de tentatives d'ouverture de menu avant echec */
    var maxMenuOpenAttempts: Int = 3,

    /** Nombre max de tentatives d'ouverture de coffre avant echec */
    var maxChestOpenAttempts: Int = 3,

    /** Delai entre les tentatives de reconnexion (secondes) */
    var connectionRetryDelaySeconds: Int = 30,

    /** Timeout pour l'attente de teleportation (secondes) */
    var teleportTimeoutSeconds: Int = 10,

    /** Timeout pour le remplissage des seaux (secondes) */
    var bucketRefillTimeoutSeconds: Int = 10,

    /** Nombre max de tentatives de recolte */
    var maxHarvestingRetries: Int = 5,

    /** Activer la recuperation automatique apres erreur */
    var autoRecoveryEnabled: Boolean = true,

    // Etat du bot (non sauvegarde)
    @Transient var botEnabled: Boolean = false
) {
    companion object {
        private val logger = LoggerFactory.getLogger("agribot")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private var instance: AgriConfig? = null

        // Version du schema de configuration (incrementer lors de changements incompatibles)
        const val CONFIG_VERSION = 1

        // Duree de pause en cas d'event (2 heures)
        const val EVENT_PAUSE_SECONDS = 2 * 60 * 60 // 2 heures = 7200 secondes
        const val EVENT_PAUSE_MINUTES = 120 // 2 heures en minutes

        // Delai de reconnexion apres une deconnexion inattendue (crash/connection reset)
        const val CRASH_RECONNECT_DELAY_SECONDS = 2 * 60 // 2 minutes = 120 secondes

        // Seuil de fusion des sessions (si prochaine session dans moins de X minutes, faire maintenant)
        const val SESSION_MERGE_THRESHOLD_MINUTES = 60 // 1 heure

        // Durees d'eau disponibles (en minutes)
        val WATER_DURATIONS = listOf(
            300,  // 5h
            340,  // 5h40
            380,  // 6h20
            420,  // 7h
            460,  // 7h40
            500,  // 8h20
            540,  // 9h
            580,  // 9h40
            620,  // 10h20
            660,  // 11h
            720   // 12h
        )

        /**
         * Formate une duree en minutes en texte lisible.
         */
        fun formatDuration(minutes: Int): String {
            val hours = minutes / 60
            val mins = minutes % 60
            return if (mins > 0) "${hours}h${mins}" else "${hours}h"
        }

        /**
         * Formate un delai de demarrage en texte lisible.
         * Ex: 0 -> "0", 10 -> "10", 60 -> "1h", 70 -> "1h10", 130 -> "2h10"
         */
        fun formatStartupDelay(minutes: Int): String {
            if (minutes == 0) return "0"
            val hours = minutes / 60
            val mins = minutes % 60
            return when {
                hours == 0 -> "$mins"
                mins == 0 -> "${hours}h"
                else -> "${hours}h${mins}"
            }
        }

        private fun getConfigFile(): File {
            val configDir = FabricLoader.getInstance().configDir.toFile()
            return File(configDir, "agribot.json")
        }

        private fun getBackupFile(): File {
            val configDir = FabricLoader.getInstance().configDir.toFile()
            return File(configDir, "agribot.json.backup")
        }

        /**
         * Cree une sauvegarde du fichier de configuration actuel.
         */
        private fun createBackup() {
            try {
                val configFile = getConfigFile()
                val backupFile = getBackupFile()
                if (configFile.exists()) {
                    configFile.copyTo(backupFile, overwrite = true)
                    logger.debug("Backup de configuration cree: ${backupFile.absolutePath}")
                }
            } catch (e: Exception) {
                logger.warn("Impossible de creer le backup de configuration: ${e.message}")
            }
        }

        /**
         * Restaure la configuration depuis le backup.
         * @return true si la restauration a reussi, false sinon
         */
        private fun restoreFromBackup(): AgriConfig? {
            try {
                val backupFile = getBackupFile()
                if (backupFile.exists()) {
                    val json = backupFile.readText()
                    val config = gson.fromJson(json, AgriConfig::class.java)
                    if (config.validate()) {
                        logger.info("Configuration restauree depuis le backup")
                        return config
                    }
                }
            } catch (e: Exception) {
                logger.warn("Impossible de restaurer depuis le backup: ${e.message}")
            }
            return null
        }

        /**
         * Charge la configuration depuis le fichier.
         * Cree un backup avant le chargement et valide la configuration.
         * En cas d'erreur, tente de restaurer depuis le backup.
         */
        fun load(): AgriConfig {
            val file = getConfigFile()

            if (file.exists()) {
                try {
                    val json = file.readText()
                    val loadedConfig = gson.fromJson(json, AgriConfig::class.java)

                    // Valider la configuration chargee
                    if (loadedConfig != null && loadedConfig.validate()) {
                        instance = loadedConfig
                        logger.info("Configuration chargee et validee depuis ${file.absolutePath}")
                        // Creer un backup de la config valide
                        createBackup()
                    } else {
                        // Configuration invalide, tenter la restauration
                        logger.warn("Configuration invalide detectee, tentative de restauration depuis backup")
                        instance = (restoreFromBackup() ?: AgriConfig().also {
                            logger.warn("Restauration impossible, utilisation des valeurs par defaut")
                        }).also { it.save() }
                    }
                } catch (e: Exception) {
                    logger.error("Erreur lors du chargement de la config: ${e.message}")
                    // Tenter la restauration depuis le backup
                    instance = (restoreFromBackup() ?: AgriConfig().also {
                        logger.warn("Utilisation des valeurs par defaut")
                    }).also { it.save() }
                }
            } else {
                logger.info("Aucune configuration trouvee, creation des valeurs par defaut")
                instance = AgriConfig().also { it.save() }
            }

            return instance!!
        }

        /**
         * Recupere l'instance de configuration.
         */
        fun get(): AgriConfig {
            return instance ?: load()
        }
    }

    /**
     * Sauvegarde la configuration dans le fichier.
     * Cree un backup avant la sauvegarde si la config actuelle est valide.
     */
    fun save() {
        try {
            val file = getConfigFile()
            file.parentFile?.mkdirs()

            // Creer un backup avant de sauvegarder (si le fichier existe deja)
            if (file.exists()) {
                Companion.createBackup()
            }

            file.writeText(gson.toJson(this))
            logger.info("Configuration sauvegardee dans ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Erreur lors de la sauvegarde de la config: ${e.message}")
        }
    }

    /**
     * Valide la configuration.
     * Verifie que toutes les valeurs sont dans des plages acceptables.
     * @return true si la configuration est valide, false sinon
     */
    fun validate(): Boolean {
        // Verifier que les delais sont positifs
        if (delayShort < 0 || delayMedium < 0 || delayLong < 0 ||
            delayAfterTeleport < 0 || delayAfterOpenMenu < 0 ||
            delayBetweenBuckets < 0 || delayAfterCommand < 0) {
            logger.warn("Validation echouee: delais negatifs detectes")
            return false
        }

        // Verifier que le growthBoost est dans une plage raisonnable (0-1000%)
        if (growthBoost < 0 || growthBoost > 1000) {
            logger.warn("Validation echouee: growthBoost hors limites ($growthBoost)")
            return false
        }

        // Verifier que la duree d'eau est valide
        if (waterDurationMinutes !in WATER_DURATIONS) {
            logger.warn("Validation echouee: waterDurationMinutes invalide ($waterDurationMinutes)")
            return false
        }

        // Verifier que les stations ne sont pas null
        if (stations.size != 30) {
            logger.warn("Validation echouee: nombre de stations incorrect (${stations.size})")
            return false
        }

        // Verifier que la plante selectionnee existe
        if (selectedPlant.isBlank() || Plants.get(selectedPlant) == null) {
            logger.warn("Validation echouee: plante selectionnee invalide ($selectedPlant)")
            return false
        }

        return true
    }

    /**
     * Verifie si au moins un home est configure.
     * Les homes sont: homeCoffre, homeBackup, homeGraines, homeMouvement
     * Au moins un doit etre non vide pour que le bot puisse fonctionner.
     */
    fun hasAtLeastOneHome(): Boolean {
        return homeCoffre.isNotBlank() ||
               homeBackup.isNotBlank() ||
               homeGraines.isNotBlank() ||
               homeMouvement.isNotBlank()
    }

    /**
     * Retourne la liste des erreurs de configuration qui empechent le demarrage du bot.
     * Tous les parametres de la page config sont obligatoires sauf:
     * - Les homes: au moins 1 parmi les 4 doit etre configure
     * - Les stations: au moins 1 parmi les 30 doit etre configuree
     * @return Liste des messages d'erreur, vide si la config est valide pour le demarrage
     */
    fun getStartupErrors(): List<String> {
        val errors = mutableListOf<String>()

        // Mot de passe obligatoire
        if (loginPassword.isBlank()) {
            errors.add("Mot de passe manquant")
        }

        // Plante obligatoire
        if (selectedPlant.isBlank()) {
            errors.add("Plante non selectionnee")
        }

        // Boost doit etre un nombre valide (>= 0)
        if (growthBoost < 0) {
            errors.add("Boost invalide")
        }

        // Au moins 1 station obligatoire
        if (getActiveStationCount() == 0) {
            errors.add("0 stations")
        }

        // Au moins 1 home obligatoire
        if (!hasAtLeastOneHome()) {
            errors.add("Aucun home configure")
        }

        // Verifier l'auto-reponse si activee
        val autoResponseConfig = AutoResponseConfig.get()
        if (autoResponseConfig.enabled && !autoResponseConfig.isApiConfigured()) {
            errors.add("Auto-reponse: cle API manquante")
        }

        return errors
    }

    /**
     * Verifie si la configuration est prete pour demarrer le bot.
     * @return true si le bot peut demarrer, false sinon
     */
    fun isReadyToStart(): Boolean {
        return getStartupErrors().isEmpty()
    }

    /**
     * Recupere la liste des stations actives (non vides).
     */
    fun getActiveStations(): List<String> {
        return stations.filter { it.isNotBlank() }
    }

    /**
     * Recupere le nombre de stations actives.
     */
    fun getActiveStationCount(): Int {
        return getActiveStations().size
    }

    /**
     * Definit une station par son index (0-29).
     */
    fun setStation(index: Int, name: String) {
        if (index in 0..29) {
            stations[index] = name.trim()
        }
    }

    /**
     * Recupere les donnees de la plante selectionnee.
     */
    fun getSelectedPlantData(): PlantData? {
        return Plants.get(selectedPlant)
    }

    /**
     * Calcule le temps de pause entre les sessions en secondes.
     * Base sur le temps de croissance de la plante avec le boost.
     */
    fun getSessionPauseSeconds(): Int {
        val plantData = getSelectedPlantData() ?: return 900 // 15 min par defaut
        return plantData.tempsTotalEnSecondes(growthBoost)
    }

    /**
     * Determine le nombre de seaux a utiliser selon l'heure.
     * Matin (6h30-11h30): 1 seau
     * Reste du temps: 16 seaux
     */
    fun getBucketCount(): Int {
        val now = java.time.LocalTime.now()
        val timeInMinutes = now.hour * 60 + now.minute

        // 6h30 = 390 min, 11h30 = 690 min
        return if (timeInMinutes in 390..690) 1 else 16
    }

    /**
     * Determine le mode de gestion des seaux selon l'heure.
     */
    fun getBucketMode(): String {
        val now = java.time.LocalTime.now()
        val timeInMinutes = now.hour * 60 + now.minute

        return when {
            timeInMinutes in 390..690 -> "drop"      // 6h30-11h30: jeter les seaux
            timeInMinutes > 690 -> "retrieve"        // Apres 11h30: recuperer
            else -> "normal"
        }
    }

    /**
     * Retourne l'identifiant de la periode actuelle (pour eviter les transitions repetees).
     * Format: "YYYY-MM-DD-matin" ou "YYYY-MM-DD-aprem"
     */
    fun getCurrentPeriod(): String {
        val now = java.time.LocalDateTime.now()
        val date = now.toLocalDate().toString()
        val timeInMinutes = now.hour * 60 + now.minute

        return if (timeInMinutes in 390..690) {
            "$date-matin"  // 6h30-11h30
        } else {
            "$date-aprem"  // Apres 11h30 ou avant 6h30
        }
    }

    /**
     * Verifie si on est dans la periode de redemarrage serveur (5h40-6h40).
     */
    fun isServerRestartPeriod(): Boolean {
        val now = java.time.LocalTime.now()
        val timeInMinutes = now.hour * 60 + now.minute

        // 5h40 = 340 min, 6h40 = 400 min
        return timeInMinutes in 340..400
    }

    /**
     * Calcule le temps en millisecondes jusqu'a la fin de la periode de redemarrage (6h30).
     * @return Temps en ms jusqu'a 6h30, ou 0 si on est deja apres 6h30
     */
    fun getTimeUntilRestartEnd(): Long {
        val now = java.time.LocalDateTime.now()
        val today = now.toLocalDate()

        // Fin de la periode de redemarrage = 6h30
        val restartEnd = java.time.LocalDateTime.of(today, java.time.LocalTime.of(6, 30))

        // Si on est avant 6h30 aujourd'hui
        return if (now.isBefore(restartEnd)) {
            java.time.Duration.between(now, restartEnd).toMillis()
        } else {
            0L
        }
    }

    /**
     * Calcule le chevauchement entre une periode donnee et la fenetre de redemarrage serveur (5h40-6h40).
     * Les plantes ne poussent pas pendant cette periode.
     *
     * @param periodStart Debut de la periode
     * @param periodEnd Fin de la periode
     * @return Duree du chevauchement en secondes
     */
    private fun calculateRestartOverlap(periodStart: java.time.LocalDateTime, periodEnd: java.time.LocalDateTime): Int {
        if (periodEnd.isBefore(periodStart) || periodStart == periodEnd) {
            return 0
        }

        var totalOverlap = 0
        var currentDate = periodStart.toLocalDate()
        val endDate = periodEnd.toLocalDate()

        while (!currentDate.isAfter(endDate)) {
            val restartStart = java.time.LocalDateTime.of(currentDate, java.time.LocalTime.of(5, 40))
            val restartEnd = java.time.LocalDateTime.of(currentDate, java.time.LocalTime.of(6, 40))

            val overlapStart = maxOf(periodStart, restartStart)
            val overlapEnd = minOf(periodEnd, restartEnd)

            if (overlapStart.isBefore(overlapEnd)) {
                totalOverlap += java.time.Duration.between(overlapStart, overlapEnd).seconds.toInt()
            }

            currentDate = currentDate.plusDays(1)
        }

        return totalOverlap
    }

    /**
     * Calcule le temps de croissance restant ajuste en tenant compte des redemarrages serveur.
     * Le serveur redemarre entre 5h40 et 6h40 chaque jour (60 min), et les plantes ne poussent pas.
     *
     * @param cycleStartTimeMs Timestamp du debut du cycle en millisecondes
     * @param baseGrowthTimeSeconds Temps de croissance de base en secondes
     * @return Temps restant ajuste en secondes jusqu'a la fin de croissance
     */
    fun calculateAdjustedRemainingGrowthTime(cycleStartTimeMs: Long, baseGrowthTimeSeconds: Int): Int {
        val zoneId = java.time.ZoneId.systemDefault()
        val cycleStart = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(cycleStartTimeMs), zoneId
        )
        val now = java.time.LocalDateTime.now()

        // Temps ecoule depuis le debut du cycle
        val elapsedSeconds = java.time.Duration.between(cycleStart, now).seconds.toInt()

        // Temps de redemarrage passe pendant cette periode (les plantes n'ont pas pousse)
        val pastRestartTime = calculateRestartOverlap(cycleStart, now)

        // Temps effectif de croissance (temps ecoule - temps de pause serveur)
        val effectiveGrowthTime = elapsedSeconds - pastRestartTime

        // Temps de croissance restant
        val remainingGrowthTime = baseGrowthTimeSeconds - effectiveGrowthTime

        if (remainingGrowthTime <= 0) {
            return maxOf(60, remainingGrowthTime) // Minimum 1 minute
        }

        // Verifier s'il y a un redemarrage a venir pendant le temps restant
        val expectedEnd = now.plusSeconds(remainingGrowthTime.toLong())
        val futureRestartTime = calculateRestartOverlap(now, expectedEnd)

        // Ajouter le temps de redemarrage futur
        val adjustedRemainingTime = remainingGrowthTime + futureRestartTime

        if (pastRestartTime > 0 || futureRestartTime > 0) {
            logger.info("Ajustement redemarrage serveur: passe=${pastRestartTime/60}min, futur=${futureRestartTime/60}min, temps restant ajuste=${adjustedRemainingTime/60}min")
        }

        return adjustedRemainingTime
    }

    /**
     * Ajuste la duree de pause pour eviter que la prochaine session tombe pendant le redemarrage serveur.
     * Si la fin de pause tombe entre 5h40 et 6h40, on etend la pause jusqu'a 6h30.
     *
     * @param pauseSeconds Duree de pause initiale en secondes
     * @return Duree de pause ajustee en secondes
     */
    fun adjustPauseForServerRestart(pauseSeconds: Int): Int {
        val now = java.time.LocalDateTime.now()
        val pauseEnd = now.plusSeconds(pauseSeconds.toLong())

        val pauseEndHour = pauseEnd.hour
        val pauseEndMinute = pauseEnd.minute
        val pauseEndTimeInMinutes = pauseEndHour * 60 + pauseEndMinute

        // Periode de redemarrage: 5h40 (340 min) a 6h40 (400 min)
        // On veut eviter cette periode, donc on etend jusqu'a 6h30 (390 min)
        if (pauseEndTimeInMinutes in 340..400) {
            // La fin de pause tombe pendant le redemarrage
            val today = pauseEnd.toLocalDate()
            val restartEnd = java.time.LocalDateTime.of(today, java.time.LocalTime.of(6, 30))

            val adjustedPauseMs = java.time.Duration.between(now, restartEnd).toMillis()
            val adjustedPauseSeconds = (adjustedPauseMs / 1000).toInt()

            val originalMinutes = pauseSeconds / 60
            val adjustedMinutes = adjustedPauseSeconds / 60
            logger.info("Pause ajustee pour eviter redemarrage: ${originalMinutes}min -> ${adjustedMinutes}min (fin a 6h30)")

            return adjustedPauseSeconds
        }

        return pauseSeconds
    }

    /**
     * Determine si on doit remplir les stations d'eau cette session.
     * Utilise la duree configuree (waterDurationMinutes).
     */
    fun shouldRefillWater(): Boolean {
        val waterDuration = waterDurationMinutes * 60 // Convertir en secondes
        val currentTime = System.currentTimeMillis() / 1000
        val sessionPause = getSessionPauseSeconds()

        // Premiere session (pas de timestamp) -> REMPLIR l'eau
        if (lastWaterRefillTime == null) {
            logger.info("Premiere session - remplissage de l'eau necessaire")
            return true
        }

        val timeSinceRefill = currentTime - lastWaterRefillTime!!
        val margin = 10 * 60 // 10 minutes de marge

        if (timeSinceRefill + sessionPause >= waterDuration - margin) {
            val hoursSince = timeSinceRefill / 3600.0
            logger.info("Remplissage d'eau necessaire - ${String.format("%.1f", hoursSince)}h depuis dernier remplissage")
            return true
        }

        val hoursRemaining = (waterDuration - timeSinceRefill) / 3600.0
        logger.info("Pas besoin de remplir l'eau - reste ${String.format("%.1f", hoursRemaining)}h")
        return false
    }

    /**
     * Formate la duree de l'eau en texte lisible.
     */
    fun formatWaterDuration(): String {
        val hours = waterDurationMinutes / 60
        val minutes = waterDurationMinutes % 60
        return if (minutes > 0) "${hours}h${minutes}" else "${hours}h"
    }

    // ==================== GESTION DES SESSIONS DE REMPLISSAGE D'EAU ====================

    /**
     * Intervalle entre les remplissages d'eau (capacite - 1 minute de marge).
     * Ex: capacite 5h (300 min) -> intervalle 299 min (4h59)
     */
    fun getWaterRefillIntervalMinutes(): Int {
        return waterDurationMinutes - 1
    }

    /**
     * Calcule le nombre de sessions de remplissage intermediaires necessaires.
     * Ex: plante 12h, capacite 5h -> 2 sessions de remplissage avant la recolte
     *
     * @return Nombre de remplissages intermediaires (0 si le temps de croissance <= capacite)
     */
    fun calculateWaterRefillsNeeded(): Int {
        val plantData = getSelectedPlantData() ?: return 0
        val growthTimeMinutes = plantData.tempsTotalCroissance(growthBoost)
        val waterInterval = getWaterRefillIntervalMinutes()

        // Si le temps de croissance est inferieur ou egal a la capacite, pas besoin de remplissage intermediaire
        if (growthTimeMinutes <= waterDurationMinutes) {
            return 0
        }

        // Calcul: combien de fois l'eau va s'epuiser pendant la croissance
        // Premier remplissage couvre [0, waterInterval]
        // Chaque remplissage suivant couvre waterInterval de plus
        var covered = waterInterval
        var refills = 0
        while (covered < growthTimeMinutes) {
            refills++
            covered += waterInterval
        }

        logger.info("Plante ${growthTimeMinutes}min, capacite ${waterDurationMinutes}min -> $refills remplissages intermediaires necessaires")
        return refills
    }

    /**
     * Calcule l'eau restante en minutes depuis le dernier remplissage.
     *
     * @return Minutes d'eau restantes, ou waterDurationMinutes si jamais rempli
     */
    fun getRemainingWaterMinutes(): Int {
        if (lastWaterRefillTime == null) {
            return 0 // Jamais rempli, pas d'eau
        }

        val currentTime = System.currentTimeMillis() / 1000
        val secondsSinceRefill = currentTime - lastWaterRefillTime!!
        val minutesSinceRefill = (secondsSinceRefill / 60).toInt()

        val remaining = waterDurationMinutes - minutesSinceRefill
        return maxOf(0, remaining)
    }

    /**
     * Determine si on doit remplir l'eau lors du replantage.
     * True si l'eau restante est insuffisante pour le prochain cycle complet.
     *
     * @return True si remplissage necessaire au replantage
     */
    fun shouldRefillWaterOnReplant(): Boolean {
        val plantData = getSelectedPlantData() ?: return true
        val growthTimeMinutes = plantData.tempsTotalCroissance(growthBoost)
        val remainingWater = getRemainingWaterMinutes()

        // Si l'eau restante ne couvre pas le temps jusqu'au premier remplissage necessaire
        val waterInterval = getWaterRefillIntervalMinutes()
        val firstIntervalNeeded = minOf(waterInterval, growthTimeMinutes)

        val needsRefill = remainingWater < firstIntervalNeeded
        logger.info("Replantage: eau restante ${remainingWater}min, besoin ${firstIntervalNeeded}min -> remplissage ${if (needsRefill) "necessaire" else "pas necessaire"}")
        return needsRefill
    }

    /**
     * Calcule la duree de la prochaine pause en secondes.
     * Prend en compte le redemarrage serveur (5h40-6h40) pendant lequel les plantes ne poussent pas.
     *
     * @param waterRefillsRemaining Nombre de remplissages encore a faire avant la recolte
     * @param cycleStartTime Timestamp du debut du cycle (premiere plantation) en SECONDES
     * @return Duree de la pause en secondes
     */
    fun getNextPauseSeconds(waterRefillsRemaining: Int, cycleStartTime: Long): Int {
        val plantData = getSelectedPlantData() ?: return 900 // 15 min par defaut
        val growthTimeSeconds = plantData.tempsTotalEnSecondes(growthBoost)
        val waterIntervalSeconds = getWaterRefillIntervalMinutes() * 60

        if (waterRefillsRemaining <= 0) {
            // Plus de remplissage a faire, attendre la fin de la croissance
            // Utiliser le calcul ajuste qui prend en compte le redemarrage serveur
            val cycleStartTimeMs = cycleStartTime * 1000 // Convertir en millisecondes
            val adjustedRemainingTime = calculateAdjustedRemainingGrowthTime(cycleStartTimeMs, growthTimeSeconds)

            logger.info("Prochaine pause: fin de croissance dans ${adjustedRemainingTime / 60}min")
            return maxOf(60, adjustedRemainingTime) // Minimum 1 minute
        } else {
            // Encore des remplissages a faire, pause = intervalle entre remplissages
            logger.info("Prochaine pause: remplissage eau dans ${waterIntervalSeconds / 60}min ($waterRefillsRemaining restants)")
            return waterIntervalSeconds
        }
    }

    // ==================== GESTION DES EVENTS (TELEPORTATION FORCEE) ====================

    /**
     * Verifie si l'eau dans les stations sera suffisante apres une pause de 2 heures.
     * L'eau doit pouvoir tenir pendant la pause de 2h + le temps necessaire pour remplir
     * toutes les stations apres la reconnexion.
     *
     * @return True si l'eau est suffisante pour attendre 2h, false sinon
     */
    fun hasEnoughWaterForEventPause(): Boolean {
        val remainingWater = getRemainingWaterMinutes()

        // On a besoin que l'eau tienne au moins pendant les 2h de pause
        // avec une marge de securite de 10 minutes pour le temps de reconnexion et remplissage
        val minimumWaterNeeded = EVENT_PAUSE_MINUTES + 10

        val hasEnough = remainingWater >= minimumWaterNeeded

        if (hasEnough) {
            logger.info("Eau suffisante pour pause event: ${remainingWater}min restantes (besoin: ${minimumWaterNeeded}min)")
        } else {
            logger.warn("Eau INSUFFISANTE pour pause event: ${remainingWater}min restantes (besoin: ${minimumWaterNeeded}min)")
            logger.warn("Le bot ne se reconnectera PAS apres la pause event car les plantes vont mourir")
        }

        return hasEnough
    }

    /**
     * Retourne le timestamp de fin de pause pour un event (2h a partir de maintenant).
     *
     * @return Timestamp en millisecondes de la fin de pause
     */
    fun getEventPauseEndTime(): Long {
        return System.currentTimeMillis() + (EVENT_PAUSE_SECONDS * 1000L)
    }

    // ==================== FONCTIONS UTILITAIRES POUR LES PARAMETRES DE RETRY ====================

    /**
     * Convertit le delai de reconnexion en ticks.
     */
    fun getConnectionRetryDelayTicks(): Int = connectionRetryDelaySeconds * 20

    /**
     * Convertit le timeout de teleportation en nombre de retries (100ms par retry).
     */
    fun getTeleportTimeoutRetries(): Int = teleportTimeoutSeconds * 10

    /**
     * Convertit le timeout de remplissage en nombre de checks (100ms par check).
     */
    fun getBucketRefillTimeoutChecks(): Int = bucketRefillTimeoutSeconds * 10
}
