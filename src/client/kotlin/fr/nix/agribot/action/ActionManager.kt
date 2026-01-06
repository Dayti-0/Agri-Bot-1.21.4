package fr.nix.agribot.action

import fr.nix.agribot.menu.MenuDetector
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.util.Hand
import org.slf4j.LoggerFactory

/**
 * Gestionnaire d'actions pour simuler les interactions du joueur.
 */
object ActionManager {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    // Etat des touches simulees
    private var attackKeyHeld = false
    private var useKeyHeld = false
    private var sneakKeyHeld = false

    /**
     * Simule un clic droit (utiliser/interagir).
     * Equivalent a appuyer sur le bouton droit de la souris.
     */
    fun rightClick() {
        val options = client.options
        pressKey(options.useKey)
        logger.debug("Clic droit")
    }

    /**
     * Simule un clic gauche (attaquer/casser).
     */
    fun leftClick() {
        val options = client.options
        pressKey(options.attackKey)
        logger.debug("Clic gauche")
    }

    /**
     * Simule un maintien du clic droit.
     */
    fun holdRightClick() {
        val options = client.options
        KeyBinding.setKeyPressed(options.useKey.defaultKey, true)
        useKeyHeld = true
        logger.debug("Maintien clic droit")
    }

    /**
     * Relache le clic droit.
     */
    fun releaseRightClick() {
        val options = client.options
        KeyBinding.setKeyPressed(options.useKey.defaultKey, false)
        useKeyHeld = false
        logger.debug("Relache clic droit")
    }

    /**
     * Active l'accroupissement (sneak).
     */
    fun startSneaking() {
        val options = client.options
        KeyBinding.setKeyPressed(options.sneakKey.defaultKey, true)
        sneakKeyHeld = true
        logger.debug("Debut accroupissement")
    }

    /**
     * Desactive l'accroupissement.
     */
    fun stopSneaking() {
        val options = client.options
        KeyBinding.setKeyPressed(options.sneakKey.defaultKey, false)
        sneakKeyHeld = false
        logger.debug("Fin accroupissement")
    }

    /**
     * Simule shift + clic droit (pour planter).
     */
    fun shiftRightClick() {
        startSneaking()
        rightClick()
        // Le stopSneaking sera appele apres un delai par le BotCore
    }

    /**
     * Ouvre l'inventaire.
     */
    fun openInventory() {
        val options = client.options
        pressKey(options.inventoryKey)
        logger.debug("Ouverture inventaire")
    }

    /**
     * Ferme l'ecran actuel (Escape).
     */
    fun closeScreen() {
        client.setScreen(null)
        logger.debug("Fermeture ecran")
    }

    /**
     * Appuie sur Escape.
     */
    fun pressEscape() {
        client.player?.closeHandledScreen()
        logger.debug("Touche Escape")
    }

    /**
     * Simule une pression de touche unique.
     */
    private fun pressKey(keyBinding: KeyBinding) {
        KeyBinding.setKeyPressed(keyBinding.defaultKey, true)
        KeyBinding.onKeyPressed(keyBinding.defaultKey)

        // Relacher apres un tick
        client.execute {
            KeyBinding.setKeyPressed(keyBinding.defaultKey, false)
        }
    }

    /**
     * Drop l'item en main (touche Q).
     */
    fun dropItem() {
        val options = client.options
        pressKey(options.dropKey)
        logger.debug("Drop item")
    }

    /**
     * Drop tout le stack en main (Ctrl+Q).
     */
    fun dropStack() {
        // Simuler Ctrl+Q
        val options = client.options
        // Maintenir Ctrl n'est pas directement supporte, on utilise dropSelectedItem avec control=true
        client.player?.dropSelectedItem(true)
        logger.debug("Drop stack")
    }

    /**
     * Verifie si un ecran est ouvert.
     */
    fun isScreenOpen(): Boolean {
        return client.currentScreen != null
    }

    /**
     * Verifie si le joueur est dans un menu d'inventaire/coffre.
     */
    fun isInInventoryScreen(): Boolean {
        val screen = client.currentScreen ?: return false
        return screen is net.minecraft.client.gui.screen.ingame.HandledScreen<*>
    }

    /**
     * Verifie si un menu (coffre, station, etc.) est ouvert.
     * Utilise MenuDetector pour une detection plus precise.
     */
    fun isMenuOpen(): Boolean {
        return MenuDetector.isMenuOpen()
    }

    /**
     * Verifie si un coffre ou container est ouvert.
     */
    fun isChestOpen(): Boolean {
        return MenuDetector.isChestOrContainerOpen()
    }

    /**
     * Ouvre un coffre/container avec clic droit et attend qu'il soit ouvert.
     * @return true si le coffre s'est ouvert, false sinon
     */
    fun openChestAndWait(timeoutMs: Long = 5000): Boolean {
        rightClick()
        return MenuDetector.waitForChestOpen(timeoutMs)
    }

    /**
     * Ouvre un menu simple avec clic droit et attend qu'il soit ouvert.
     * @return true si le menu s'est ouvert, false sinon
     */
    fun openMenuAndWait(timeoutMs: Long = 5000): Boolean {
        rightClick()
        return MenuDetector.waitForSimpleMenuOpen(timeoutMs)
    }

    /**
     * Relache toutes les touches maintenues.
     */
    fun releaseAllKeys() {
        if (useKeyHeld) releaseRightClick()
        if (sneakKeyHeld) stopSneaking()
        if (attackKeyHeld) {
            val options = client.options
            KeyBinding.setKeyPressed(options.attackKey.defaultKey, false)
            attackKeyHeld = false
        }
        logger.debug("Toutes les touches relachees")
    }

    /**
     * Utilise l'item en main sur le bloc vise.
     * Methode alternative plus directe que simuler un clic.
     */
    fun useItemOnTarget() {
        val interactionManager = client.interactionManager ?: return
        val player = client.player ?: return
        val hitResult = client.crosshairTarget

        if (hitResult != null && hitResult.type == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            val blockHitResult = hitResult as net.minecraft.util.hit.BlockHitResult
            interactionManager.interactBlock(player, Hand.MAIN_HAND, blockHitResult)
            logger.debug("Interaction avec bloc")
        }
    }

    /**
     * Attaque/casse le bloc vise.
     */
    fun attackTarget() {
        val interactionManager = client.interactionManager ?: return
        val player = client.player ?: return
        val hitResult = client.crosshairTarget

        if (hitResult != null && hitResult.type == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            val blockHitResult = hitResult as net.minecraft.util.hit.BlockHitResult
            interactionManager.attackBlock(blockHitResult.blockPos, blockHitResult.side)
            logger.debug("Attaque bloc")
        }
    }
}
