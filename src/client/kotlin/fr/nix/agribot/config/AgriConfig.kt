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

    // Plante selectionnee
    var selectedPlant: String = "Brocoli",
    var growthBoost: Float = 29f,

    // Stations (30 max, chaine vide = pas de station)
    val stations: MutableList<String> = MutableList(30) { "" },

    // Home pour le coffre de seaux (depot/recuperation)
    var homeCoffre: String = "coffre",

    // Duree de l'eau dans les stations (en minutes)
    // Valeurs possibles: 300 (5h), 340 (5h40), 380 (6h20), 420 (7h), 460 (7h40),
    //                    500 (8h20), 540 (9h), 580 (9h40), 620 (10h20), 660 (11h), 720 (12h)
    var waterDurationMinutes: Int = 720,  // 12h par defaut

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

    // Etat du bot (non sauvegarde)
    @Transient var botEnabled: Boolean = false
) {
    companion object {
        private val logger = LoggerFactory.getLogger("agribot")
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
        private var instance: AgriConfig? = null

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

        private fun getConfigFile(): File {
            val configDir = FabricLoader.getInstance().configDir.toFile()
            return File(configDir, "agribot.json")
        }

        /**
         * Charge la configuration depuis le fichier.
         */
        fun load(): AgriConfig {
            val file = getConfigFile()

            if (file.exists()) {
                try {
                    val json = file.readText()
                    instance = gson.fromJson(json, AgriConfig::class.java)
                    logger.info("Configuration chargee depuis ${file.absolutePath}")
                } catch (e: Exception) {
                    logger.error("Erreur lors du chargement de la config: ${e.message}")
                    instance = AgriConfig()
                }
            } else {
                logger.info("Aucune configuration trouvee, creation des valeurs par defaut")
                instance = AgriConfig()
                instance!!.save()
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
     */
    fun save() {
        try {
            val file = getConfigFile()
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(this))
            logger.info("Configuration sauvegardee dans ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Erreur lors de la sauvegarde de la config: ${e.message}")
        }
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
        val hour = java.time.LocalTime.now().hour
        val minute = java.time.LocalTime.now().minute
        val timeInMinutes = hour * 60 + minute

        // 6h30 = 390 min, 11h30 = 690 min
        return if (timeInMinutes in 390..690) 1 else 16
    }

    /**
     * Determine le mode de gestion des seaux selon l'heure.
     */
    fun getBucketMode(): String {
        val hour = java.time.LocalTime.now().hour
        val minute = java.time.LocalTime.now().minute
        val timeInMinutes = hour * 60 + minute

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
     * Verifie si on est dans la periode de redemarrage serveur (5h50-6h30).
     */
    fun isServerRestartPeriod(): Boolean {
        val hour = java.time.LocalTime.now().hour
        val minute = java.time.LocalTime.now().minute
        val timeInMinutes = hour * 60 + minute

        // 5h50 = 350 min, 6h30 = 390 min
        return timeInMinutes in 350..390
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
}
