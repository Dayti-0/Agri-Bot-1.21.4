package fr.nix.agribot.inventory

import net.minecraft.client.MinecraftClient
import net.minecraft.item.Items
import org.slf4j.LoggerFactory

/**
 * Donnees d'un slot contenant des seaux.
 */
data class BucketSlotInfo(
    val slotIndex: Int,
    val count: Int,
    val isFull: Boolean  // true = seaux d'eau, false = seaux vides
)

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
     * Selectionne le slot des graines (slot 9 = touche 0).
     */
    fun selectSeedsSlot() {
        selectSlot(9)
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
     * Verifie si le slot actuel contient un seau (plein ou vide).
     */
    fun isHoldingAnyBucket(): Boolean {
        return isHoldingWaterBucket() || isHoldingEmptyBucket()
    }

    /**
     * Trouve tous les slots contenant des seaux d'eau dans la hotbar.
     * @return Liste des slots avec seaux d'eau (tries par index)
     */
    fun findWaterBucketSlots(): List<BucketSlotInfo> {
        val player = client.player ?: return emptyList()
        val result = mutableListOf<BucketSlotInfo>()

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (stack.item == Items.WATER_BUCKET) {
                result.add(BucketSlotInfo(i, stack.count, isFull = true))
            }
        }

        return result
    }

    /**
     * Trouve tous les slots contenant des seaux vides dans la hotbar.
     * @return Liste des slots avec seaux vides (tries par index)
     */
    fun findEmptyBucketSlots(): List<BucketSlotInfo> {
        val player = client.player ?: return emptyList()
        val result = mutableListOf<BucketSlotInfo>()

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (stack.item == Items.BUCKET) {
                result.add(BucketSlotInfo(i, stack.count, isFull = false))
            }
        }

        return result
    }

    /**
     * Trouve tous les slots contenant des seaux (pleins ou vides) dans la hotbar.
     * @return Liste des slots avec seaux (tries par index)
     */
    fun findAllBucketSlots(): List<BucketSlotInfo> {
        val player = client.player ?: return emptyList()
        val result = mutableListOf<BucketSlotInfo>()

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            when (stack.item) {
                Items.WATER_BUCKET -> result.add(BucketSlotInfo(i, stack.count, isFull = true))
                Items.BUCKET -> result.add(BucketSlotInfo(i, stack.count, isFull = false))
            }
        }

        return result
    }

    /**
     * Trouve le premier slot contenant un seau d'eau et le selectionne.
     * @return true si un slot a ete trouve et selectionne, false sinon
     */
    fun selectFirstWaterBucketSlot(): Boolean {
        val slots = findWaterBucketSlots()
        if (slots.isNotEmpty()) {
            selectSlot(slots.first().slotIndex)
            logger.info("Slot seau d'eau selectionne: ${slots.first().slotIndex + 1}")
            return true
        }
        logger.warn("Aucun seau d'eau trouve dans la hotbar")
        return false
    }

    /**
     * Trouve le premier slot contenant un seau vide et le selectionne.
     * @return true si un slot a ete trouve et selectionne, false sinon
     */
    fun selectFirstEmptyBucketSlot(): Boolean {
        val slots = findEmptyBucketSlots()
        if (slots.isNotEmpty()) {
            selectSlot(slots.first().slotIndex)
            logger.info("Slot seau vide selectionne: ${slots.first().slotIndex + 1}")
            return true
        }
        logger.warn("Aucun seau vide trouve dans la hotbar")
        return false
    }

    /**
     * Compte le nombre total de seaux d'eau dans la hotbar.
     */
    fun countWaterBucketsInHotbar(): Int {
        return findWaterBucketSlots().sumOf { it.count }
    }

    /**
     * Compte le nombre total de seaux vides dans la hotbar.
     */
    fun countEmptyBucketsInHotbar(): Int {
        return findEmptyBucketSlots().sumOf { it.count }
    }

    /**
     * Compte le nombre total de seaux (pleins + vides) dans la hotbar.
     */
    fun countAllBucketsInHotbar(): Int {
        return countWaterBucketsInHotbar() + countEmptyBucketsInHotbar()
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

    /**
     * Recupere le nombre d'items dans le slot actuel.
     */
    fun getHeldItemCount(): Int {
        val player = client.player ?: return 0
        return player.inventory.mainHandStack.count
    }

    /**
     * Trouve le premier slot contenant des graines (ou tout item qui n'est pas un seau).
     * @return Index du slot (0-8), ou -1 si aucun slot trouve
     */
    fun findSeedsSlot(): Int {
        val player = client.player ?: return -1

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            // Chercher un slot non vide qui ne contient pas de seaux
            if (!stack.isEmpty && stack.item != Items.WATER_BUCKET && stack.item != Items.BUCKET) {
                logger.debug("Graines trouvees dans le slot ${i + 1}")
                return i
            }
        }

        logger.warn("Aucun slot avec graines trouve dans la hotbar")
        return -1
    }

    /**
     * Selectionne le slot contenant les graines.
     * @return true si un slot a ete trouve et selectionne, false sinon
     */
    fun selectSeedsSlotAuto(): Boolean {
        val slotIndex = findSeedsSlot()
        if (slotIndex >= 0) {
            selectSlot(slotIndex)
            logger.info("Slot graines selectionne: ${slotIndex + 1}")
            return true
        }
        logger.warn("Impossible de trouver les graines dans la hotbar")
        return false
    }
}
