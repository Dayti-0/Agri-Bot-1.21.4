package fr.nix.agribot.inventory

import fr.nix.agribot.config.AgriConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.item.Item
import net.minecraft.item.Items
import net.minecraft.screen.GenericContainerScreenHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Donnees d'un slot contenant des seaux.
 */
data class BucketSlotInfo(
    val slotIndex: Int,
    val count: Int,
    val isFull: Boolean  // true = seaux d'eau, false = seaux vides
)

/**
 * Donnees d'un stack de seaux dans le menu coffre.
 */
data class BucketStackInfo(
    val slotIndex: Int,
    val count: Int,
    val chestSize: Int  // Taille du coffre pour calculer les autres slots
)

/**
 * Gestionnaire d'inventaire pour la hotbar.
 * Optimise avec cache pour eviter les scans repetes de la hotbar.
 */
object InventoryManager {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    // === CACHE HOTBAR ===
    // Le cache est invalide a chaque tick pour garantir la fraicheur des donnees
    // tout en evitant les scans multiples dans le meme tick

    private var cacheTickId: Long = -1
    private var cachedWaterBucketSlots: List<BucketSlotInfo> = emptyList()
    private var cachedEmptyBucketSlots: List<BucketSlotInfo> = emptyList()
    private var cachedWaterBucketCount: Int = 0
    private var cachedEmptyBucketCount: Int = 0

    // Compteur de tick global pour invalider le cache
    private var globalTickCounter: Long = 0

    /**
     * Doit etre appele une fois par tick pour permettre l'invalidation du cache.
     * Appele automatiquement par BotCore.
     */
    fun onTick() {
        globalTickCounter++
    }

    /**
     * Scanne la hotbar une seule fois par tick et met en cache les resultats.
     */
    private fun ensureCacheValid() {
        if (cacheTickId == globalTickCounter) return // Cache valide

        // Invalider et rescanner
        cacheTickId = globalTickCounter

        val player = client.player
        if (player == null) {
            cachedWaterBucketSlots = emptyList()
            cachedEmptyBucketSlots = emptyList()
            cachedWaterBucketCount = 0
            cachedEmptyBucketCount = 0
            return
        }

        // Scan unique de la hotbar
        val waterSlots = mutableListOf<BucketSlotInfo>()
        val emptySlots = mutableListOf<BucketSlotInfo>()
        var waterCount = 0
        var emptyCount = 0

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            when (stack.item) {
                Items.WATER_BUCKET -> {
                    waterSlots.add(BucketSlotInfo(i, stack.count, isFull = true))
                    waterCount += stack.count
                }
                Items.BUCKET -> {
                    emptySlots.add(BucketSlotInfo(i, stack.count, isFull = false))
                    emptyCount += stack.count
                }
            }
        }

        cachedWaterBucketSlots = waterSlots
        cachedEmptyBucketSlots = emptySlots
        cachedWaterBucketCount = waterCount
        cachedEmptyBucketCount = emptyCount
    }

    /**
     * Mapping des types de graines vers les items Minecraft.
     */
    private val seedTypeToItem: Map<String, Item> = mapOf(
        "wheat_seeds" to Items.WHEAT_SEEDS,
        "pumpkin_seeds" to Items.PUMPKIN_SEEDS,
        "melon_seeds" to Items.MELON_SEEDS,
        "beetroot_seeds" to Items.BEETROOT_SEEDS,
        "blue_dye" to Items.BLUE_DYE,
        "green_dye" to Items.GREEN_DYE,
        "sunflower" to Items.SUNFLOWER,
        "lily_of_the_valley" to Items.LILY_OF_THE_VALLEY,
        "blaze_powder" to Items.BLAZE_POWDER,
        "wither_rose" to Items.WITHER_ROSE,
        "snowball" to Items.SNOWBALL,
        "chorus_fruit" to Items.CHORUS_FRUIT
    )

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
     * Optimise: utilise le cache hotbar.
     * @return Liste des slots avec seaux d'eau (tries par index)
     */
    fun findWaterBucketSlots(): List<BucketSlotInfo> {
        ensureCacheValid()
        return cachedWaterBucketSlots
    }

    /**
     * Trouve tous les slots contenant des seaux vides dans la hotbar.
     * Optimise: utilise le cache hotbar.
     * @return Liste des slots avec seaux vides (tries par index)
     */
    fun findEmptyBucketSlots(): List<BucketSlotInfo> {
        ensureCacheValid()
        return cachedEmptyBucketSlots
    }

    /**
     * Trouve tous les slots contenant des seaux (pleins ou vides) dans la hotbar.
     * Optimise: utilise le cache hotbar.
     * @return Liste des slots avec seaux (tries par index)
     */
    fun findAllBucketSlots(): List<BucketSlotInfo> {
        ensureCacheValid()
        return cachedWaterBucketSlots + cachedEmptyBucketSlots
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
     * Optimise: utilise le cache hotbar.
     */
    fun countWaterBucketsInHotbar(): Int {
        ensureCacheValid()
        return cachedWaterBucketCount
    }

    /**
     * Compte le nombre total de seaux vides dans la hotbar.
     * Optimise: utilise le cache hotbar.
     */
    fun countEmptyBucketsInHotbar(): Int {
        ensureCacheValid()
        return cachedEmptyBucketCount
    }

    /**
     * Compte le nombre total de seaux (pleins + vides) dans la hotbar.
     * Optimise: utilise le cache hotbar.
     */
    fun countAllBucketsInHotbar(): Int {
        ensureCacheValid()
        return cachedWaterBucketCount + cachedEmptyBucketCount
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
     * Trouve le premier slot contenant le type de graine specifie.
     * @param seedType Type de graine a chercher (ex: "wheat_seeds")
     * @return Index du slot (0-8), ou -1 si aucun slot trouve
     */
    fun findSeedsSlot(seedType: String): Int {
        val player = client.player ?: return -1
        val targetItem = seedTypeToItem[seedType]

        if (targetItem == null) {
            logger.warn("Type de graine inconnu: $seedType")
            return -1
        }

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (!stack.isEmpty && stack.item == targetItem) {
                logger.debug("Graines ($seedType) trouvees dans le slot ${i + 1}")
                return i
            }
        }

        logger.warn("Aucun slot avec graines ($seedType) trouve dans la hotbar")
        return -1
    }

    /**
     * Selectionne le slot contenant les graines pour la plante selectionnee.
     * Utilise le type de graine configure dans PlantData.
     * @return true si un slot a ete trouve et selectionne, false sinon
     */
    fun selectSeedsSlotAuto(): Boolean {
        val config = AgriConfig.get()
        val plantData = config.getSelectedPlantData()

        if (plantData == null) {
            logger.warn("Aucune plante selectionnee ou plante inconnue: ${config.selectedPlant}")
            return false
        }

        val seedType = plantData.seedType
        val slotIndex = findSeedsSlot(seedType)

        if (slotIndex >= 0) {
            selectSlot(slotIndex)
            logger.info("Slot graines ($seedType) selectionne: ${slotIndex + 1} pour ${config.selectedPlant}")
            return true
        }

        logger.warn("Impossible de trouver les graines ($seedType) pour ${config.selectedPlant} dans la hotbar")
        return false
    }

    /**
     * Trouve le slot contenant un bloc de melon dans le menu ouvert.
     * Utile pour detecter les fruits a recolter dans une station.
     * @return Index du slot dans le menu, ou -1 si aucun melon trouve
     */
    fun findMelonSlotInMenu(): Int {
        val screen = client.currentScreen
        if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
            logger.warn("Aucun menu ouvert pour chercher le melon")
            return -1
        }

        val handler = screen.screenHandler ?: return -1

        // Parcourir tous les slots du menu
        for (i in 0 until handler.slots.size) {
            val slot = handler.slots[i]
            val stack = slot.stack

            // Verifier si c'est un bloc de melon
            if (!stack.isEmpty && stack.item == Items.MELON) {
                logger.debug("Bloc de melon trouve dans le slot $i du menu")
                return i
            }
        }

        logger.warn("Aucun bloc de melon trouve dans le menu")
        return -1
    }

    /**
     * Trouve le premier slot vide dans le coffre.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @return Index du premier slot vide, ou -1 si aucun slot vide
     */
    fun findEmptySlotInChest(): Int {
        val future = CompletableFuture<Int>()

        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                logger.warn("Aucun menu ouvert pour chercher un slot vide")
                future.complete(-1)
                return@execute
            }

            val handler = screen.screenHandler
            if (handler == null) {
                future.complete(-1)
                return@execute
            }

            val chestSize = when (handler) {
                is GenericContainerScreenHandler -> handler.rows * 9
                else -> 27
            }

            // Parcourir les slots du coffre pour trouver un slot vide
            for (i in 0 until chestSize) {
                val slot = handler.slots[i]
                if (slot.stack.isEmpty) {
                    logger.debug("Slot vide trouve dans le coffre: $i")
                    future.complete(i)
                    return@execute
                }
            }

            logger.warn("Aucun slot vide dans le coffre")
            future.complete(-1)
        }

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Erreur lors de la recherche d'un slot vide: ${e.message}")
            -1
        }
    }

    /**
     * Trouve le premier stack de seaux dans l'inventaire joueur quand le coffre est ouvert.
     * Retourne les informations sur le slot, le nombre de seaux, et la taille du coffre.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @return BucketStackInfo ou null si aucun seau trouve
     */
    fun findBucketStackInChestMenu(): BucketStackInfo? {
        val future = CompletableFuture<BucketStackInfo?>()

        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                logger.warn("Aucun menu ouvert pour chercher les seaux")
                future.complete(null)
                return@execute
            }

            val handler = screen.screenHandler
            if (handler == null) {
                future.complete(null)
                return@execute
            }

            val chestSize = when (handler) {
                is GenericContainerScreenHandler -> handler.rows * 9
                else -> 27
            }

            // Parcourir les slots de l'inventaire du joueur dans le menu
            for (i in chestSize until handler.slots.size) {
                val slot = handler.slots[i]
                val stack = slot.stack

                if (!stack.isEmpty && (stack.item == Items.WATER_BUCKET || stack.item == Items.BUCKET)) {
                    logger.info("Stack de ${stack.count} seaux trouve dans le slot $i")
                    future.complete(BucketStackInfo(i, stack.count, chestSize))
                    return@execute
                }
            }

            future.complete(null)
        }

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Erreur lors de la recherche du stack de seaux: ${e.message}")
            null
        }
    }

    /**
     * Compte le nombre total de seaux dans l'inventaire du joueur quand le coffre est ouvert.
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @return Nombre total de seaux (pleins + vides) dans l'inventaire joueur
     */
    fun countBucketsInPlayerInventoryInChestMenu(): Int {
        val future = CompletableFuture<Int>()

        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                future.complete(0)
                return@execute
            }

            val handler = screen.screenHandler
            if (handler == null) {
                future.complete(0)
                return@execute
            }

            val chestSize = when (handler) {
                is GenericContainerScreenHandler -> handler.rows * 9
                else -> 27
            }

            var count = 0
            // Parcourir les slots de l'inventaire du joueur dans le menu
            for (i in chestSize until handler.slots.size) {
                val slot = handler.slots[i]
                val stack = slot.stack

                if (!stack.isEmpty && (stack.item == Items.WATER_BUCKET || stack.item == Items.BUCKET)) {
                    count += stack.count
                }
            }

            future.complete(count)
        }

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Erreur lors du comptage des seaux: ${e.message}")
            0
        }
    }

    /**
     * Trouve tous les slots contenant des seaux (pleins ou vides) dans le menu du coffre ouvert.
     * Retourne les slots de l'inventaire du joueur (pas ceux du coffre).
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @return Liste des index de slots contenant des seaux dans le menu
     */
    fun findBucketSlotsInChestMenu(): List<Int> {
        val future = CompletableFuture<List<Int>>()

        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                logger.warn("Aucun menu ouvert pour chercher les seaux")
                future.complete(emptyList())
                return@execute
            }

            val handler = screen.screenHandler
            if (handler == null) {
                future.complete(emptyList())
                return@execute
            }

            val result = mutableListOf<Int>()

            // Determiner ou commence l'inventaire du joueur dans le menu
            // Pour un coffre simple (27 slots): inventaire joueur = slots 27-62
            // Pour un grand coffre (54 slots): inventaire joueur = slots 54-89
            val chestSize = when (handler) {
                is GenericContainerScreenHandler -> handler.rows * 9
                else -> 27  // Par defaut, coffre simple
            }

            // Parcourir les slots de l'inventaire du joueur dans le menu
            for (i in chestSize until handler.slots.size) {
                val slot = handler.slots[i]
                val stack = slot.stack

                if (!stack.isEmpty && (stack.item == Items.WATER_BUCKET || stack.item == Items.BUCKET)) {
                    result.add(i)
                    logger.debug("Seau trouve dans le slot $i du menu")
                }
            }

            future.complete(result)
        }

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Erreur lors de la recherche des seaux: ${e.message}")
            emptyList()
        }
    }

    /**
     * Trouve tous les slots contenant des seaux dans le coffre (pas l'inventaire joueur).
     * Thread-safe: peut etre appele depuis n'importe quel thread.
     * @return Liste des index de slots contenant des seaux dans le coffre
     */
    fun findBucketSlotsInChest(): List<Int> {
        val future = CompletableFuture<List<Int>>()

        client.execute {
            val screen = client.currentScreen
            if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
                logger.warn("Aucun menu ouvert pour chercher les seaux dans le coffre")
                future.complete(emptyList())
                return@execute
            }

            val handler = screen.screenHandler
            if (handler == null) {
                future.complete(emptyList())
                return@execute
            }

            val result = mutableListOf<Int>()

            // Determiner la taille du coffre
            val chestSize = when (handler) {
                is GenericContainerScreenHandler -> handler.rows * 9
                else -> 27  // Par defaut, coffre simple
            }

            // Parcourir les slots du coffre uniquement
            for (i in 0 until chestSize) {
                val slot = handler.slots[i]
                val stack = slot.stack

                if (!stack.isEmpty && (stack.item == Items.WATER_BUCKET || stack.item == Items.BUCKET)) {
                    result.add(i)
                    logger.debug("Seau trouve dans le coffre slot $i")
                }
            }

            future.complete(result)
        }

        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.error("Erreur lors de la recherche des seaux dans le coffre: ${e.message}")
            emptyList()
        }
    }

    /**
     * Trouve le slot contenant des barreaux de fer dans le menu ouvert.
     * Si les barreaux de fer sont presents sans melon, cela indique qu'il n'y a pas de pousse.
     * @return Index du slot dans le menu, ou -1 si aucun barreau de fer trouve
     */
    fun findIronBarsSlotInMenu(): Int {
        val screen = client.currentScreen
        if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
            logger.warn("Aucun menu ouvert pour chercher les barreaux de fer")
            return -1
        }

        val handler = screen.screenHandler ?: return -1

        // Parcourir tous les slots du menu
        for (i in 0 until handler.slots.size) {
            val slot = handler.slots[i]
            val stack = slot.stack

            // Verifier si c'est des barreaux de fer
            if (!stack.isEmpty && stack.item == Items.IRON_BARS) {
                logger.debug("Barreaux de fer trouves dans le slot $i du menu")
                return i
            }
        }

        logger.debug("Aucun barreau de fer trouve dans le menu")
        return -1
    }

    // ==================== METHODES POUR LA CONNEXION AUTOMATIQUE ====================

    /**
     * Trouve le slot contenant une boussole dans la hotbar.
     * @return Index du slot (0-8), ou -1 si aucune boussole trouvee
     */
    fun findCompassSlotInHotbar(): Int {
        val player = client.player ?: return -1

        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (!stack.isEmpty && stack.item == Items.COMPASS) {
                logger.debug("Boussole trouvee dans le slot ${i + 1}")
                return i
            }
        }

        logger.debug("Aucune boussole trouvee dans la hotbar")
        return -1
    }

    /**
     * Verifie si le joueur a une boussole dans la hotbar.
     */
    fun hasCompassInHotbar(): Boolean {
        return findCompassSlotInHotbar() >= 0
    }

    /**
     * Selectionne la boussole dans la hotbar.
     * @return true si la boussole a ete selectionnee, false sinon
     */
    fun selectCompass(): Boolean {
        val slot = findCompassSlotInHotbar()
        if (slot >= 0) {
            selectSlot(slot)
            logger.info("Boussole selectionnee (slot ${slot + 1})")
            return true
        }
        logger.warn("Impossible de selectionner la boussole: non trouvee")
        return false
    }

    /**
     * Trouve le slot contenant une hache en netherite dans le menu ouvert.
     * Utilise pour detecter l'option de connexion au serveur de jeu.
     * @return Index du slot dans le menu, ou -1 si aucune hache trouvee
     */
    fun findNetheriteAxeSlotInMenu(): Int {
        val screen = client.currentScreen
        if (screen !is net.minecraft.client.gui.screen.ingame.HandledScreen<*>) {
            logger.warn("Aucun menu ouvert pour chercher la hache en netherite")
            return -1
        }

        val handler = screen.screenHandler ?: return -1

        // Parcourir tous les slots du menu
        for (i in 0 until handler.slots.size) {
            val slot = handler.slots[i]
            val stack = slot.stack

            // Verifier si c'est une hache en netherite
            if (!stack.isEmpty && stack.item == Items.NETHERITE_AXE) {
                logger.debug("Hache en netherite trouvee dans le slot $i du menu")
                return i
            }
        }

        logger.debug("Aucune hache en netherite trouvee dans le menu")
        return -1
    }

    /**
     * Verifie si le joueur a une carte dans l'inventaire.
     * Une carte presente apres /login indique un captcha.
     * @return true si une carte est presente
     */
    fun hasMapInInventory(): Boolean {
        val player = client.player ?: return false

        // Verifier la hotbar
        for (i in 0..8) {
            val stack = player.inventory.getStack(i)
            if (!stack.isEmpty && stack.item == Items.FILLED_MAP) {
                logger.debug("Carte trouvee dans la hotbar (slot ${i + 1})")
                return true
            }
        }

        // Verifier l'inventaire principal
        for (i in 9..35) {
            val stack = player.inventory.getStack(i)
            if (!stack.isEmpty && stack.item == Items.FILLED_MAP) {
                logger.debug("Carte trouvee dans l'inventaire (slot $i)")
                return true
            }
        }

        return false
    }
}
