package fr.nix.agribot.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Configuration et persistance des statistiques du bot agricole.
 *
 * Terminologie:
 * - Session: Une session complete de farming (toutes les stations ont ete traitees)
 * - Station: Une station individuelle traitee (recolte + plantation)
 * - Revenu: Gain brut de la recolte (avant deduction du cout des graines)
 * - Profit: Gain net apres deduction du cout des graines (revenu - cout)
 */
data class StatsConfig(
    // Compteurs de sessions
    var totalSessions: Int = 0,          // Nombre total de sessions completes
    var totalStationsCompleted: Int = 0, // Nombre total de stations traitees

    // Statistiques financieres (en monnaie du jeu)
    var totalRevenue: Double = 0.0,      // Gain brut total (revenu des recoltes)
    var totalCost: Double = 0.0,         // Cout total des graines
    var totalProfit: Double = 0.0,       // Profit net total (revenu - cout)

    // Statistiques par plante (nom de la plante -> stats)
    val plantStats: MutableMap<String, PlantStats> = mutableMapOf()
) {
    /**
     * Statistiques par type de plante.
     */
    data class PlantStats(
        var sessions: Int = 0,           // Sessions avec cette plante
        var stations: Int = 0,           // Stations traitees avec cette plante
        var revenue: Double = 0.0,       // Revenu total pour cette plante
        var cost: Double = 0.0,          // Cout total pour cette plante
        var profit: Double = 0.0         // Profit total pour cette plante
    )

    companion object {
        private val logger = LoggerFactory.getLogger("agribot-stats")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private var instance: StatsConfig? = null

        private fun getConfigFile(): File {
            val configDir = FabricLoader.getInstance().configDir.toFile()
            return File(configDir, "agribot_stats.json")
        }

        private fun getBackupFile(): File {
            val configDir = FabricLoader.getInstance().configDir.toFile()
            return File(configDir, "agribot_stats.json.backup")
        }

        /**
         * Cree une sauvegarde du fichier de statistiques actuel.
         */
        private fun createBackup() {
            try {
                val configFile = getConfigFile()
                val backupFile = getBackupFile()
                if (configFile.exists()) {
                    configFile.copyTo(backupFile, overwrite = true)
                    logger.debug("Backup de statistiques cree: ${backupFile.absolutePath}")
                }
            } catch (e: Exception) {
                logger.warn("Impossible de creer le backup de statistiques: ${e.message}")
            }
        }

        /**
         * Restaure les statistiques depuis le backup.
         */
        private fun restoreFromBackup(): StatsConfig? {
            try {
                val backupFile = getBackupFile()
                if (backupFile.exists()) {
                    val json = backupFile.readText()
                    val config = gson.fromJson(json, StatsConfig::class.java)
                    if (config != null) {
                        logger.info("Statistiques restaurees depuis le backup")
                        return config
                    }
                }
            } catch (e: Exception) {
                logger.warn("Impossible de restaurer depuis le backup: ${e.message}")
            }
            return null
        }

        /**
         * Charge les statistiques depuis le fichier.
         */
        fun load(): StatsConfig {
            val file = getConfigFile()

            if (file.exists()) {
                try {
                    val json = file.readText()
                    val loadedConfig = gson.fromJson(json, StatsConfig::class.java)

                    if (loadedConfig != null) {
                        instance = loadedConfig
                        logger.info("Statistiques chargees depuis ${file.absolutePath}")
                        createBackup()
                    } else {
                        logger.warn("Statistiques invalides, tentative de restauration")
                        val restored = restoreFromBackup()
                        if (restored != null) {
                            instance = restored
                            instance!!.save()
                        } else {
                            logger.warn("Restauration impossible, utilisation des valeurs par defaut")
                            instance = StatsConfig()
                            instance!!.save()
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Erreur lors du chargement des statistiques: ${e.message}")
                    val restored = restoreFromBackup()
                    if (restored != null) {
                        instance = restored
                        instance!!.save()
                    } else {
                        logger.warn("Utilisation des valeurs par defaut")
                        instance = StatsConfig()
                        instance!!.save()
                    }
                }
            } else {
                logger.info("Aucune statistique trouvee, creation des valeurs par defaut")
                instance = StatsConfig()
                instance!!.save()
            }

            return instance!!
        }

        /**
         * Recupere l'instance des statistiques.
         */
        fun get(): StatsConfig {
            return instance ?: load()
        }
    }

    /**
     * Sauvegarde les statistiques dans le fichier.
     */
    fun save() {
        try {
            val file = getConfigFile()
            file.parentFile?.mkdirs()

            if (file.exists()) {
                Companion.createBackup()
            }

            file.writeText(gson.toJson(this))
            logger.info("Statistiques sauvegardees dans ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Erreur lors de la sauvegarde des statistiques: ${e.message}")
        }
    }

    /**
     * Enregistre une session complete (toutes les stations traitees).
     *
     * @param plantName Nom de la plante cultivee
     * @param stationCount Nombre de stations traitees dans cette session
     */
    fun recordSession(plantName: String, stationCount: Int) {
        val economics = PlantEconomics.get(plantName) ?: return

        // Calculer les gains pour cette session
        val sessionRevenue = economics.revenue * stationCount
        val sessionCost = economics.cost * stationCount
        val sessionProfit = sessionRevenue - sessionCost

        // Mettre a jour les totaux
        totalSessions++
        totalStationsCompleted += stationCount
        totalRevenue += sessionRevenue
        totalCost += sessionCost
        totalProfit += sessionProfit

        // Mettre a jour les stats par plante
        val plantStat = plantStats.getOrPut(plantName) { PlantStats() }
        plantStat.sessions++
        plantStat.stations += stationCount
        plantStat.revenue += sessionRevenue
        plantStat.cost += sessionCost
        plantStat.profit += sessionProfit

        logger.info("Session enregistree: $plantName x$stationCount stations - Revenu: $sessionRevenue, Profit: $sessionProfit")
        save()
    }

    /**
     * Enregistre une station individuelle (utilise pendant le farming).
     *
     * @param plantName Nom de la plante cultivee
     */
    fun recordStation(plantName: String) {
        val economics = PlantEconomics.get(plantName) ?: return

        // Calculer les gains pour cette station
        val stationRevenue = economics.revenue
        val stationCost = economics.cost
        val stationProfit = stationRevenue - stationCost

        // Mettre a jour les totaux
        totalStationsCompleted++
        totalRevenue += stationRevenue
        totalCost += stationCost
        totalProfit += stationProfit

        // Mettre a jour les stats par plante
        val plantStat = plantStats.getOrPut(plantName) { PlantStats() }
        plantStat.stations++
        plantStat.revenue += stationRevenue
        plantStat.cost += stationCost
        plantStat.profit += stationProfit

        logger.debug("Station enregistree: $plantName - Revenu: $stationRevenue, Profit: $stationProfit")
    }

    /**
     * Incremente le compteur de sessions completes.
     * Appele quand toutes les stations ont ete traitees.
     *
     * @param plantName Nom de la plante cultivee
     */
    fun incrementSessionCount(plantName: String) {
        totalSessions++
        val plantStat = plantStats.getOrPut(plantName) { PlantStats() }
        plantStat.sessions++
        logger.info("Session complete enregistree pour $plantName (total: $totalSessions)")
        save()
    }

    /**
     * Remet toutes les statistiques a zero.
     */
    fun reset() {
        totalSessions = 0
        totalStationsCompleted = 0
        totalRevenue = 0.0
        totalCost = 0.0
        totalProfit = 0.0
        plantStats.clear()
        logger.info("Statistiques remises a zero")
        save()
    }

    /**
     * Formate un nombre avec separateurs de milliers et virgule decimale.
     */
    fun formatNumber(value: Double): String {
        // Utiliser locale US pour avoir des virgules comme separateurs de milliers
        // puis remplacer les virgules par des espaces et le point par une virgule
        return String.format(java.util.Locale.US, "%,.1f", value)
            .replace(",", " ")
            .replace(".", ",")
    }

    /**
     * Formate un nombre entier avec separateurs de milliers.
     */
    fun formatNumber(value: Int): String {
        return String.format("%,d", value).replace(",", " ")
    }
}
