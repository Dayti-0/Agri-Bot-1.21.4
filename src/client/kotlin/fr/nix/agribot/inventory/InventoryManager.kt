package fr.nix.agribot.inventory

import net.minecraft.client.MinecraftClient
import net.minecraft.item.Items
import org.slf4j.LoggerFactory

/**
 * Gestionnaire d'inventaire pour la hotbar.
 */
object InventoryManager {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    /**
     * Selectionne un slot de la hotbar (0-8).
     * @param slot Numero du slot (0 = premier slot, 8 = dernier)
     */
    fun selectSlot(slot: Int) {
        val player = client.player ?: return

        if (slot in 0..8) {
            player.inventory.selectedSlot = slot
            logger.debug("Slot selectionne: ${slot + 1}")
        } else {
            logger.warn("Slot invalide: $slot (doit etre entre 0 et 8)")
        }
    }

    /**
     * Selectionne le slot 1 (index 0) - generalement les graines.
     */
    fun selectSeedsSlot() {
        selectSlot(9) // Slot 0 = touche 0 dans le jeu (10eme slot visuel)
    }

    /**
     * Selectionne le slot des seaux (1 ou 2 selon la config).
     * @param slotNumber 1 ou 2
     */
    fun selectBucketSlot(slotNumber: Int) {
        when (slotNumber) {
            1 -> selectSlot(0) // Premier slot
            2 -> selectSlot(1) // Deuxieme slot
            else -> selectSlot(0)
        }
    }

    /**
     * Recupere le slot actuellement selectionne (0-8).
     */
    fun getSelectedSlot(): Int {
        return client.player?.inventory?.selectedSlot ?: 0
    }

    /**
     * Verifie si le slot actuel contient un seau d'eau.
     */
    fun isHoldingWaterBucket(): Boolean {
        val player = client.player ?: return false
        val heldItem = player.inventory.mainHandStack
        return heldItem.item == Items.WATER_BUCKET
    }

    /**
     * Verifie si le slot actuel contient un seau vide.
     */
    fun isHoldingEmptyBucket(): Boolean {
        val player = client.player ?: return false
        val heldItem = player.inventory.mainHandStack
        return heldItem.item == Items.BUCKET
    }

    /**
     * Compte le nombre de seaux d'eau dans la hotbar.
     */
    fun countWaterBucketsInHotbar(): Int {
        val player = client.player ?: return 0
        var count = 0

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (stack.item == Items.WATER_BUCKET) {
                count += stack.count
            }
        }

        return count
    }

    /**
     * Compte le nombre de seaux vides dans la hotbar.
     */
    fun countEmptyBucketsInHotbar(): Int {
        val player = client.player ?: return 0
        var count = 0

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (stack.item == Items.BUCKET) {
                count += stack.count
            }
        }

        return count
    }

    /**
     * Compte le nombre total de seaux (pleins + vides) dans un slot specifique.
     * @param slot Index du slot (0-8)
     */
    fun countBucketsInSlot(slot: Int): Int {
        val player = client.player ?: return 0

        if (slot !in 0..8) return 0

        val stack = player.inventory.getStack(slot)
        return if (stack.item == Items.WATER_BUCKET || stack.item == Items.BUCKET) {
            stack.count
        } else {
            0
        }
    }

    /**
     * Verifie si le joueur est accroupi.
     */
    fun isSneaking(): Boolean {
        return client.player?.isSneaking ?: false
    }

    /**
     * Recupere le nom de l'item en main.
     */
    fun getHeldItemName(): String {
        val player = client.player ?: return "none"
        val heldItem = player.inventory.mainHandStack
        return heldItem.item.toString()
    }
}
