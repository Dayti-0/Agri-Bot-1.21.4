package fr.nix.agribot.menu

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.ScreenHandler
import org.slf4j.LoggerFactory

/**
 * Gestionnaire de detection de menus pour le bot.
 * Detecte si un menu (coffre, station d'agriculture, etc.) est ouvert.
 */
object MenuDetector {
    private val logger = LoggerFactory.getLogger("agribot")

    private val client: MinecraftClient
        get() = MinecraftClient.getInstance()

    /**
     * Type de menu detecte.
     */
    enum class MenuType {
        NONE,           // Aucun menu ouvert
        CHEST,          // Coffre ou container generique
        HOPPER,         // Hopper
        DISPENSER,      // Distributeur/dropper
        FURNACE,        // Fourneau
        CRAFTING,       // Table de craft
        BREWING,        // Alambic
        ENCHANTING,     // Table d'enchantement
        ANVIL,          // Enclume
        BEACON,         // Balise
        GENERIC,        // Menu generique (sans titre specifique)
        UNKNOWN         // Menu inconnu (HandledScreen)
    }

    /**
     * Verifie si un menu est actuellement ouvert.
     */
    fun isMenuOpen(): Boolean {
        return client.currentScreen is HandledScreen<*>
    }

    /**
     * Detecte le type de menu actuellement ouvert.
     */
    fun detectMenuType(): MenuType {
        val screen = client.currentScreen ?: return MenuType.NONE

        // Verifier si c'est un HandledScreen (menu avec inventaire)
        if (screen !is HandledScreen<*>) {
            return MenuType.NONE
        }

        // Obtenir le ScreenHandler
        val handler = screen.screenHandler ?: return MenuType.UNKNOWN

        // Detection par type de ScreenHandler
        return when (handler) {
            is GenericContainerScreenHandler -> {
                // Container generique (coffre, barrel, shulker box, etc.)
                detectContainerType(handler)
            }
            is net.minecraft.screen.HopperScreenHandler -> MenuType.HOPPER
            is net.minecraft.screen.FurnaceScreenHandler -> MenuType.FURNACE
            is net.minecraft.screen.BlastFurnaceScreenHandler -> MenuType.FURNACE
            is net.minecraft.screen.SmokerScreenHandler -> MenuType.FURNACE
            is net.minecraft.screen.CraftingScreenHandler -> MenuType.CRAFTING
            is net.minecraft.screen.BrewingStandScreenHandler -> MenuType.BREWING
            is net.minecraft.screen.EnchantmentScreenHandler -> MenuType.ENCHANTING
            is net.minecraft.screen.AnvilScreenHandler -> MenuType.ANVIL
            is net.minecraft.screen.BeaconScreenHandler -> MenuType.BEACON
            is net.minecraft.screen.Generic3x3ContainerScreenHandler -> MenuType.DISPENSER
            else -> MenuType.UNKNOWN
        }
    }

    /**
     * Detecte le type specifique de container generique.
     */
    private fun detectContainerType(handler: GenericContainerScreenHandler): MenuType {
        // Les containers generiques incluent:
        // - Coffres (27 ou 54 slots)
        // - Barrels (27 slots)
        // - Shulker boxes (27 slots)
        // - Stations d'agriculture custom (peuvent varier)

        val rows = handler.rows

        return when {
            rows == 3 || rows == 6 -> MenuType.CHEST // Coffre simple ou double
            else -> MenuType.GENERIC // Autre container generique
        }
    }

    /**
     * Verifie si un coffre ou container generique est ouvert.
     * Utile pour verifier avant de faire des operations sur un coffre.
     */
    fun isChestOrContainerOpen(): Boolean {
        val menuType = detectMenuType()
        return menuType == MenuType.CHEST || menuType == MenuType.GENERIC
    }

    /**
     * Verifie si un menu simple (sans titre specifique) est ouvert.
     * Cela peut etre un coffre, une station d'agriculture, etc.
     */
    fun isSimpleMenuOpen(): Boolean {
        val screen = client.currentScreen
        if (screen !is HandledScreen<*>) return false

        // Verifier si c'est un menu avec handler
        val handler = screen.screenHandler ?: return false

        // Un menu simple est typiquement un GenericContainerScreenHandler
        return handler is GenericContainerScreenHandler
    }

    /**
     * Verifie si le menu est completement charge (slots synchronises).
     * @return true si le menu est charge et pret a etre utilise
     */
    fun isMenuFullyLoaded(): Boolean {
        val screen = client.currentScreen
        if (screen !is HandledScreen<*>) return false

        val handler = screen.screenHandler ?: return false

        // Verifier que les slots sont synchronises
        // Un menu est considere charge si tous les slots sont initialises
        val slots = handler.slots
        if (slots.isEmpty()) return false

        // Verifier que le syncId est valide (> 0 signifie synchronise avec le serveur)
        return handler.syncId > 0
    }

    /**
     * Attend qu'un menu soit ouvert.
     *
     * @param timeoutMs Temps maximum d'attente en millisecondes
     * @param checkIntervalMs Intervalle entre chaque verification en millisecondes
     * @param stabilizationDelayMs Delai supplementaire apres detection pour s'assurer que le menu est charge (default: 2000ms)
     * @return true si un menu s'est ouvert, false si timeout
     */
    fun waitForMenuOpen(
        timeoutMs: Long = 5000,
        checkIntervalMs: Long = 50,
        stabilizationDelayMs: Long = 2000
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isMenuOpen()) {
                val menuType = detectMenuType()
                logger.debug("Menu detecte: $menuType")

                // Attendre que le menu soit completement charge
                if (stabilizationDelayMs > 0) {
                    logger.debug("Attente stabilisation menu (${stabilizationDelayMs}ms)...")
                    Thread.sleep(stabilizationDelayMs)

                    // Verifier que le menu est toujours ouvert et charge
                    if (isMenuFullyLoaded()) {
                        logger.debug("Menu completement charge et pret")
                        return true
                    } else {
                        logger.warn("Menu non completement charge apres stabilisation")
                    }
                } else {
                    return true
                }
            }
            Thread.sleep(checkIntervalMs)
        }

        logger.warn("Timeout: aucun menu detecte apres ${timeoutMs}ms")
        return false
    }

    /**
     * Attend qu'un coffre ou container soit ouvert.
     *
     * @param timeoutMs Temps maximum d'attente en millisecondes
     * @param checkIntervalMs Intervalle entre chaque verification en millisecondes
     * @param stabilizationDelayMs Delai supplementaire apres detection pour s'assurer que le menu est charge (default: 2000ms)
     * @return true si un coffre s'est ouvert, false si timeout
     */
    fun waitForChestOpen(
        timeoutMs: Long = 5000,
        checkIntervalMs: Long = 50,
        stabilizationDelayMs: Long = 2000
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isChestOrContainerOpen()) {
                logger.debug("Coffre/container detecte")

                // Attendre que le menu soit completement charge
                if (stabilizationDelayMs > 0) {
                    logger.debug("Attente stabilisation coffre (${stabilizationDelayMs}ms)...")
                    Thread.sleep(stabilizationDelayMs)

                    // Verifier que le menu est toujours ouvert
                    // On ne verifie plus isMenuFullyLoaded() car c'est trop strict
                    // et peut retourner false meme si le menu est fonctionnel
                    if (isChestOrContainerOpen()) {
                        logger.debug("Coffre ouvert et pret")
                        return true
                    } else {
                        logger.warn("Coffre ferme pendant stabilisation")
                    }
                } else {
                    return true
                }
            }
            Thread.sleep(checkIntervalMs)
        }

        logger.warn("Timeout: aucun coffre detecte apres ${timeoutMs}ms")
        return false
    }

    /**
     * Attend qu'un menu simple soit ouvert.
     *
     * @param timeoutMs Temps maximum d'attente en millisecondes
     * @param checkIntervalMs Intervalle entre chaque verification en millisecondes
     * @param stabilizationDelayMs Delai supplementaire apres detection pour s'assurer que le menu est charge (default: 2000ms)
     * @return true si un menu simple s'est ouvert, false si timeout
     */
    fun waitForSimpleMenuOpen(
        timeoutMs: Long = 5000,
        checkIntervalMs: Long = 50,
        stabilizationDelayMs: Long = 2000
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isSimpleMenuOpen()) {
                logger.debug("Menu simple detecte")

                // Attendre que le menu soit completement charge
                if (stabilizationDelayMs > 0) {
                    logger.debug("Attente stabilisation menu simple (${stabilizationDelayMs}ms)...")
                    Thread.sleep(stabilizationDelayMs)

                    // Verifier que le menu est toujours ouvert
                    // On ne verifie plus isMenuFullyLoaded() car c'est trop strict
                    // et peut retourner false meme si le menu est fonctionnel
                    if (isSimpleMenuOpen()) {
                        logger.debug("Menu simple ouvert et pret")
                        return true
                    } else {
                        logger.warn("Menu simple ferme pendant stabilisation")
                    }
                } else {
                    return true
                }
            }
            Thread.sleep(checkIntervalMs)
        }

        logger.warn("Timeout: aucun menu simple detecte apres ${timeoutMs}ms")
        return false
    }

    /**
     * Obtient le nombre de slots du menu actuellement ouvert.
     * Utile pour determiner la taille d'un coffre ou d'une station.
     *
     * @return Nombre de slots, ou 0 si aucun menu n'est ouvert
     */
    fun getMenuSlotCount(): Int {
        val screen = client.currentScreen
        if (screen !is HandledScreen<*>) return 0

        val handler = screen.screenHandler ?: return 0
        return handler.slots.size
    }

    /**
     * Obtient des informations detaillees sur le menu ouvert.
     */
    fun getMenuInfo(): MenuInfo? {
        val screen = client.currentScreen ?: return null
        if (screen !is HandledScreen<*>) return null

        val handler = screen.screenHandler ?: return null
        val menuType = detectMenuType()
        val slotCount = handler.slots.size
        val title = screen.title?.string ?: "Sans titre"

        return MenuInfo(menuType, slotCount, title)
    }

    /**
     * Classe pour stocker les informations sur un menu.
     */
    data class MenuInfo(
        val type: MenuType,
        val slotCount: Int,
        val title: String
    ) {
        override fun toString(): String {
            return "MenuInfo(type=$type, slots=$slotCount, title='$title')"
        }
    }

    /**
     * Affiche des informations de debug sur le menu actuel.
     */
    fun debugCurrentMenu() {
        val info = getMenuInfo()
        if (info != null) {
            logger.info("=== Menu actuel ===")
            logger.info("Type: ${info.type}")
            logger.info("Slots: ${info.slotCount}")
            logger.info("Titre: ${info.title}")
            logger.info("==================")
        } else {
            logger.info("Aucun menu ouvert")
        }
    }
}
