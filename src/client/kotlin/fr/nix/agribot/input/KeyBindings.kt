package fr.nix.agribot.input

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.gui.ConfigScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory

/**
 * Gestion des touches du mod AgriBot.
 */
object KeyBindings {
    private val logger = LoggerFactory.getLogger("agribot")

    private const val CATEGORY = "category.agribot.main"

    // Touche pour ouvrir le menu de configuration (F8)
    val keyOpenConfig: KeyBinding = KeyBindingHelper.registerKeyBinding(
        KeyBinding(
            "key.agribot.open_config",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            CATEGORY
        )
    )

    // Touche pour activer/desactiver le bot (F6)
    val keyToggleBot: KeyBinding = KeyBindingHelper.registerKeyBinding(
        KeyBinding(
            "key.agribot.toggle_bot",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            CATEGORY
        )
    )

    /**
     * Initialise les keybindings et leurs callbacks.
     */
    fun register() {
        logger.info("Enregistrement des touches...")

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            handleKeyPresses(client)
        }

        logger.info("Touches enregistrees: F6 (toggle bot), F8 (config)")
    }

    private fun handleKeyPresses(client: MinecraftClient) {
        // Ouvrir le menu de configuration
        while (keyOpenConfig.wasPressed()) {
            if (client.currentScreen == null) {
                client.setScreen(ConfigScreen())
                logger.info("Ouverture du menu de configuration")
            }
        }

        // Activer/desactiver le bot
        while (keyToggleBot.wasPressed()) {
            val config = AgriBotClient.config
            config.botEnabled = !config.botEnabled

            val status = if (config.botEnabled) "ACTIVE" else "DESACTIVE"
            logger.info("Bot $status")

            // Afficher un message dans le chat
            client.player?.sendMessage(
                net.minecraft.text.Text.literal("§6[AgriBot]§r Bot $status"),
                true  // Action bar
            )
        }
    }
}
