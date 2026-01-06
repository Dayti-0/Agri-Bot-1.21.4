package fr.nix.agribot.action

import fr.nix.agribot.menu.MenuDetector
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
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
     * Simule un clic droit (utiliser/interagir) comme un humain.
     * Utilise la vraie methode doItemUse() de Minecraft via reflexion.
     * C'est exactement ce que le jeu appelle quand un joueur fait un vrai clic.
     */
    fun rightClick() {
        client.execute {
            try {
                // Trouver et appeler doItemUse() via reflexion
                // C'est une methode privee de MinecraftClient
                val doItemUseMethod = MinecraftClient::class.java.getDeclaredMethod("doItemUse")
                doItemUseMethod.isAccessible = true
                doItemUseMethod.invoke(client)
                logger.debug("Clic droit via doItemUse()")
            } catch (e: NoSuchMethodException) {
                // Fallback: essayer avec le nom obfusque ou mappings differents
                logger.warn("doItemUse() non trouve, utilisation de l'alternative")
                rightClickFallback()
            } catch (e: Exception) {
                logger.error("Erreur lors du clic droit: ${e.message}")
                rightClickFallback()
            }
        }
    }

    /**
     * Methode de fallback pour le clic droit si doItemUse() n'est pas accessible.
     * Reproduit le comportement de doItemUse() manuellement.
     */
    private fun rightClickFallback() {
        val interactionManager = client.interactionManager ?: return
        val player = client.player ?: return
        val hitResult = client.crosshairTarget

        if (hitResult == null) {
            logger.debug("Pas de cible pour clic droit")
            return
        }

        when (hitResult.type) {
            HitResult.Type.BLOCK -> {
                val blockHitResult = hitResult as BlockHitResult

                // Interagir avec le bloc (comme un vrai clic droit)
                val result = interactionManager.interactBlock(player, Hand.MAIN_HAND, blockHitResult)

                if (result == ActionResult.SUCCESS) {
                    // Swing le bras comme un vrai joueur
                    player.swingHand(Hand.MAIN_HAND)
                    logger.debug("Clic droit bloc - succes avec swing")
                } else {
                    // Si le bloc n'a pas consomme l'interaction, essayer d'utiliser l'item
                    val itemResult = interactionManager.interactItem(player, Hand.MAIN_HAND)
                    if (itemResult == ActionResult.SUCCESS) {
                        player.swingHand(Hand.MAIN_HAND)
                        logger.debug("Clic droit item - succes avec swing")
                    } else {
                        logger.debug("Clic droit - resultat: $result / item: $itemResult")
                    }
                }
            }
            HitResult.Type.ENTITY -> {
                // Pour les entites, on utilise interactItem
                val result = interactionManager.interactItem(player, Hand.MAIN_HAND)
                if (result == ActionResult.SUCCESS) {
                    player.swingHand(Hand.MAIN_HAND)
                }
                logger.debug("Clic droit entite - resultat: $result")
            }
            else -> {
                // Pas de cible, essayer d'utiliser l'item en l'air
                val result = interactionManager.interactItem(player, Hand.MAIN_HAND)
                if (result == ActionResult.SUCCESS) {
                    player.swingHand(Hand.MAIN_HAND)
                }
                logger.debug("Clic droit air - resultat: $result")
            }
        }
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
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun holdRightClick() {
        client.execute {
            val options = client.options
            KeyBinding.setKeyPressed(options.useKey.defaultKey, true)
            useKeyHeld = true
            logger.debug("Maintien clic droit")
        }
    }

    /**
     * Relache le clic droit.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun releaseRightClick() {
        client.execute {
            val options = client.options
            KeyBinding.setKeyPressed(options.useKey.defaultKey, false)
            useKeyHeld = false
            logger.debug("Relache clic droit")
        }
    }

    /**
     * Active l'accroupissement (sneak).
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun startSneaking() {
        client.execute {
            val options = client.options
            val player = client.player

            // Forcer l'etat de sneak directement sur le joueur
            player?.setSneaking(true)

            // Activer la touche sneak
            KeyBinding.setKeyPressed(options.sneakKey.defaultKey, true)
            sneakKeyHeld = true

            logger.debug("Debut accroupissement - isSneaking: ${player?.isSneaking}")
        }
    }

    /**
     * Desactive l'accroupissement.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun stopSneaking() {
        client.execute {
            val options = client.options
            val player = client.player

            // Desactiver l'etat de sneak directement sur le joueur
            player?.setSneaking(false)

            // Desactiver la touche sneak
            KeyBinding.setKeyPressed(options.sneakKey.defaultKey, false)
            sneakKeyHeld = false

            logger.debug("Fin accroupissement - isSneaking: ${player?.isSneaking}")
        }
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
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun closeScreen() {
        client.execute {
            client.setScreen(null)
            logger.debug("Fermeture ecran")
        }
    }

    /**
     * Appuie sur Escape.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun pressEscape() {
        client.execute {
            client.player?.closeHandledScreen()
            logger.debug("Touche Escape")
        }
    }

    /**
     * Simule une pression de touche unique.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    private fun pressKey(keyBinding: KeyBinding) {
        client.execute {
            KeyBinding.setKeyPressed(keyBinding.defaultKey, true)
            KeyBinding.onKeyPressed(keyBinding.defaultKey)

            // Relacher apres un tick
            client.execute {
                KeyBinding.setKeyPressed(keyBinding.defaultKey, false)
            }
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
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun dropStack() {
        client.execute {
            // Simuler Ctrl+Q
            // Maintenir Ctrl n'est pas directement supporte, on utilise dropSelectedItem avec control=true
            client.player?.dropSelectedItem(true)
            logger.debug("Drop stack")
        }
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
     * @param timeoutMs Timeout pour la detection du menu
     * @param stabilizationDelayMs Delai de stabilisation apres ouverture (default: 2000ms)
     * @return true si le coffre s'est ouvert, false sinon
     */
    fun openChestAndWait(timeoutMs: Long = 5000, stabilizationDelayMs: Long = 2000): Boolean {
        rightClick()
        return MenuDetector.waitForChestOpen(timeoutMs, stabilizationDelayMs = stabilizationDelayMs)
    }

    /**
     * Ouvre un menu simple avec clic droit et attend qu'il soit ouvert.
     * @param timeoutMs Timeout pour la detection du menu
     * @param stabilizationDelayMs Delai de stabilisation apres ouverture (default: 2000ms)
     * @return true si le menu s'est ouvert, false sinon
     */
    fun openMenuAndWait(timeoutMs: Long = 5000, stabilizationDelayMs: Long = 2000): Boolean {
        rightClick()
        return MenuDetector.waitForSimpleMenuOpen(timeoutMs, stabilizationDelayMs = stabilizationDelayMs)
    }

    /**
     * Relache toutes les touches maintenues.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun releaseAllKeys() {
        client.execute {
            if (useKeyHeld) {
                val options = client.options
                KeyBinding.setKeyPressed(options.useKey.defaultKey, false)
                useKeyHeld = false
            }
            if (sneakKeyHeld) {
                val options = client.options
                KeyBinding.setKeyPressed(options.sneakKey.defaultKey, false)
                sneakKeyHeld = false
            }
            if (attackKeyHeld) {
                val options = client.options
                KeyBinding.setKeyPressed(options.attackKey.defaultKey, false)
                attackKeyHeld = false
            }
            logger.debug("Toutes les touches relachees")
        }
    }

    /**
     * Utilise l'item en main sur le bloc vise.
     * Methode alternative plus directe que simuler un clic.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun useItemOnTarget() {
        client.execute {
            val interactionManager = client.interactionManager ?: return@execute
            val player = client.player ?: return@execute
            val hitResult = client.crosshairTarget

            if (hitResult != null && hitResult.type == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                val blockHitResult = hitResult as net.minecraft.util.hit.BlockHitResult
                interactionManager.interactBlock(player, Hand.MAIN_HAND, blockHitResult)
                logger.debug("Interaction avec bloc")
            }
        }
    }

    /**
     * Attaque/casse le bloc vise.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     */
    fun attackTarget() {
        client.execute {
            val interactionManager = client.interactionManager ?: return@execute
            val player = client.player ?: return@execute
            val hitResult = client.crosshairTarget

            if (hitResult != null && hitResult.type == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                val blockHitResult = hitResult as net.minecraft.util.hit.BlockHitResult
                interactionManager.attackBlock(blockHitResult.blockPos, blockHitResult.side)
                logger.debug("Attaque bloc")
            }
        }
    }

    /**
     * Clique sur un slot specifique dans le menu ouvert.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @param slotIndex Index du slot dans le menu (commence a 0)
     * @param button Bouton de la souris (0 = gauche, 1 = droit, 2 = milieu)
     * @param clickType Type de clic (PICKUP = clic normal)
     */
    fun clickSlot(slotIndex: Int, button: Int = 1) {
        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                logger.warn("Aucun menu ouvert pour cliquer sur le slot")
                return@execute
            }

            val handler = screen.screenHandler ?: return@execute
            val interactionManager = client.interactionManager ?: return@execute
            val player = client.player ?: return@execute

            // Verifier que le slot existe
            if (slotIndex < 0 || slotIndex >= handler.slots.size) {
                logger.warn("Index de slot invalide: $slotIndex (max: ${handler.slots.size - 1})")
                return@execute
            }

            // Effectuer le clic sur le slot
            // PICKUP = clic normal (0), QUICK_MOVE = shift+clic (1), etc.
            interactionManager.clickSlot(
                handler.syncId,
                slotIndex,
                button,
                net.minecraft.screen.slot.SlotActionType.PICKUP,
                player
            )
            logger.debug("Clic slot $slotIndex avec bouton $button")
        }
    }

    /**
     * Clique droit sur un slot specifique dans le menu ouvert.
     * @param slotIndex Index du slot dans le menu
     */
    fun rightClickSlot(slotIndex: Int) {
        clickSlot(slotIndex, button = 1)
    }

    /**
     * Clique gauche sur un slot specifique dans le menu ouvert.
     * @param slotIndex Index du slot dans le menu
     */
    fun leftClickSlot(slotIndex: Int) {
        clickSlot(slotIndex, button = 0)
    }

    /**
     * Shift+clic sur un slot specifique dans le menu ouvert.
     * Transfere l'item vers l'autre partie du menu (inventaire <-> coffre).
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @param slotIndex Index du slot dans le menu
     */
    fun shiftClickSlot(slotIndex: Int) {
        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                logger.warn("Aucun menu ouvert pour shift+cliquer sur le slot")
                return@execute
            }

            val handler = screen.screenHandler ?: return@execute
            val interactionManager = client.interactionManager ?: return@execute
            val player = client.player ?: return@execute

            // Verifier que le slot existe
            if (slotIndex < 0 || slotIndex >= handler.slots.size) {
                logger.warn("Index de slot invalide: $slotIndex (max: ${handler.slots.size - 1})")
                return@execute
            }

            // QUICK_MOVE = shift+clic pour transfert rapide
            interactionManager.clickSlot(
                handler.syncId,
                slotIndex,
                0,  // bouton gauche
                net.minecraft.screen.slot.SlotActionType.QUICK_MOVE,
                player
            )
            logger.debug("Shift+clic slot $slotIndex")
        }
    }
}
