package fr.nix.agribot.test

import fr.nix.agribot.action.ActionManager
import fr.nix.agribot.bucket.BucketManager
import fr.nix.agribot.chat.ChatManager
import fr.nix.agribot.inventory.InventoryManager
import fr.nix.agribot.menu.MenuDetector
import fr.nix.agribot.AgriBotClient
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

/**
 * Actions de test pour verifier les mecanismes du bot.
 */
object TestActions {
    private val logger = LoggerFactory.getLogger("agribot-test")
    private val config get() = AgriBotClient.config

    /**
     * Test de la transition matin : depose 15 seaux dans le coffre (garde 1).
     * Se teleporte au coffre, ouvre le coffre, depose les seaux, ferme le coffre.
     */
    fun testTransitionMatin() {
        thread(name = "test-transition-matin") {
            logger.info("=== TEST TRANSITION MATIN ===")

            try {
                // Etape 1: TP au coffre
                logger.info("TP vers coffre: ${config.homeCoffre}")
                ChatManager.teleportToHome(config.homeCoffre)
                Thread.sleep(config.delayAfterTeleport.toLong())

                // Etape 2: Ouvrir le coffre
                logger.info("Ouverture coffre...")
                ActionManager.rightClick()

                if (!MenuDetector.waitForChestOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
                    logger.error("Echec ouverture coffre")
                    return@thread
                }
                logger.info("Coffre ouvert")

                // Etape 3: Deposer 15 seaux (garder 1)
                val bucketSlots = InventoryManager.findBucketSlotsInChestMenu()
                val toDeposit = maxOf(0, bucketSlots.size - 1)  // Garder 1 seau
                val slotsToDeposit = bucketSlots.take(toDeposit)

                logger.info("Depot de ${slotsToDeposit.size} seaux (sur ${bucketSlots.size})")
                for (slot in slotsToDeposit) {
                    ActionManager.shiftClickSlot(slot)
                    Thread.sleep(100)
                }

                Thread.sleep(500)

                // Etape 4: Fermer le coffre
                ActionManager.closeScreen()
                logger.info("Coffre ferme")
                logger.info("=== TEST TRANSITION MATIN TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test transition matin", e)
            }
        }
    }

    /**
     * Test de la transition apres-midi : recupere tous les seaux du coffre.
     * Se teleporte au coffre, ouvre le coffre, recupere les seaux, ferme le coffre.
     */
    fun testTransitionApresMidi() {
        thread(name = "test-transition-aprem") {
            logger.info("=== TEST TRANSITION APRES-MIDI ===")

            try {
                // Etape 1: TP au coffre
                logger.info("TP vers coffre: ${config.homeCoffre}")
                ChatManager.teleportToHome(config.homeCoffre)
                Thread.sleep(config.delayAfterTeleport.toLong())

                // Etape 2: Ouvrir le coffre
                logger.info("Ouverture coffre...")
                ActionManager.rightClick()

                if (!MenuDetector.waitForChestOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
                    logger.error("Echec ouverture coffre")
                    return@thread
                }
                logger.info("Coffre ouvert")

                // Etape 3: Recuperer tous les seaux du coffre
                val bucketSlots = InventoryManager.findBucketSlotsInChest()
                logger.info("Recuperation de ${bucketSlots.size} seaux du coffre")

                for (slot in bucketSlots) {
                    ActionManager.shiftClickSlot(slot)
                    Thread.sleep(100)
                }

                Thread.sleep(500)

                // Etape 4: Fermer le coffre
                ActionManager.closeScreen()
                logger.info("Coffre ferme")
                logger.info("=== TEST TRANSITION APRES-MIDI TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test transition apres-midi", e)
            }
        }
    }

    /**
     * Test du remplissage d'eau : selectionne les seaux vides et execute /eau.
     */
    fun testRemplissageEau() {
        thread(name = "test-remplissage") {
            logger.info("=== TEST REMPLISSAGE EAU ===")

            try {
                // Selectionner les seaux vides
                if (!BucketManager.selectEmptyBuckets()) {
                    logger.warn("Pas de seaux vides a remplir")
                    return@thread
                }

                Thread.sleep(200)

                // Executer /eau
                logger.info("Execution de /eau")
                BucketManager.fillBucketsWithCommand()

                Thread.sleep(3000)

                // Verifier le resultat
                BucketManager.refreshState()
                val waterCount = BucketManager.state.waterBucketsCount
                logger.info("Remplissage termine: $waterCount seaux d'eau")
                logger.info("=== TEST REMPLISSAGE EAU TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test remplissage", e)
            }
        }
    }
}
