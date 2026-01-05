package fr.nix.agribot

import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.config.Plants
import fr.nix.agribot.input.KeyBindings
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object AgriBotClient : ClientModInitializer {
    private val logger = LoggerFactory.getLogger("agribot")

    lateinit var config: AgriConfig
        private set

    override fun onInitializeClient() {
        logger.info("==================================================")
        logger.info("AgriBot - Initialisation du client")
        logger.info("==================================================")

        // Charger la configuration
        config = AgriConfig.load()

        // Enregistrer les touches
        KeyBindings.register()

        // Afficher les infos de config
        logger.info("Serveur: ${config.serverAddress}")
        logger.info("Plante: ${config.selectedPlant} (boost: ${config.growthBoost}%)")
        logger.info("Stations actives: ${config.getActiveStationCount()}/30")

        val plantData = config.getSelectedPlantData()
        if (plantData != null) {
            val tempsTotal = plantData.tempsTotalCroissance(config.growthBoost)
            logger.info("Temps de croissance: ${Plants.formatTemps(tempsTotal)}")
        }

        logger.info("Mode seaux actuel: ${config.getBucketMode()} (${config.getBucketCount()} seaux)")
        logger.info("==================================================")
        logger.info("Touches: F6 (toggle bot) | F8 (configuration)")
        logger.info("AgriBot pret!")
    }
}