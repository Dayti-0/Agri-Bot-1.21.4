package fr.nix.agribot

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object AgriBot : ModInitializer {
    private val logger = LoggerFactory.getLogger("agribot")

	override fun onInitialize() {
		logger.info("AgriBot initialise (server-side)")
	}
}