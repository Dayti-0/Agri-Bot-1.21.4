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
     *
     * Methode: Les seaux sont stackes, donc on doit:
     * 1. Clic gauche sur le stack pour le prendre
     * 2. 15 clics droits sur le premier slot du coffre pour deposer 15 seaux
     * 3. Clic gauche sur le premier slot de la hotbar pour reposer le seau restant
     */
    fun testTransitionMatin() {
        thread(name = "test-transition-matin") {
            logger.info("=== TEST TRANSITION MATIN ===")
            ChatManager.showActionBar("Test: Transition Matin", "e")

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
                    ChatManager.showActionBar("Echec ouverture coffre!", "c")
                    return@thread
                }
                logger.info("Coffre ouvert")

                // Etape 3: Trouver le stack de seaux
                val bucketStack = InventoryManager.findBucketStackInChestMenu()
                if (bucketStack == null) {
                    logger.error("Aucun seau trouve dans l'inventaire")
                    ChatManager.showActionBar("Aucun seau trouve!", "c")
                    ActionManager.closeScreen()
                    return@thread
                }

                val totalBuckets = bucketStack.count
                val toDeposit = maxOf(0, totalBuckets - 1)  // Garder 1 seau
                logger.info("Trouvé $totalBuckets seaux, depot de $toDeposit")

                if (toDeposit == 0) {
                    logger.warn("Pas assez de seaux pour deposer")
                    ChatManager.showActionBar("Pas assez de seaux!", "c")
                    ActionManager.closeScreen()
                    return@thread
                }

                // Etape 4: Trouver un slot vide dans le coffre
                val emptyChestSlot = InventoryManager.findEmptySlotInChest()
                if (emptyChestSlot == -1) {
                    logger.error("Aucun slot vide dans le coffre")
                    ChatManager.showActionBar("Coffre plein!", "c")
                    ActionManager.closeScreen()
                    return@thread
                }
                logger.info("Slot vide trouve dans le coffre: $emptyChestSlot")

                // Etape 5: Clic gauche sur le slot des seaux pour les prendre
                val originalSlot = bucketStack.slotIndex
                logger.info("Prise du stack de seaux (slot $originalSlot)")
                ActionManager.leftClickSlot(originalSlot)
                Thread.sleep(150)

                // Etape 6: Clics droits sur le slot vide du coffre pour deposer
                logger.info("Depot de $toDeposit seaux dans le coffre (slot $emptyChestSlot)")
                for (i in 1..toDeposit) {
                    ActionManager.rightClickSlot(emptyChestSlot)
                    Thread.sleep(100)
                }

                Thread.sleep(200)

                // Etape 7: Remettre le seau restant dans le slot ORIGINAL
                logger.info("Remise du seau restant dans le slot original ($originalSlot)")
                ActionManager.leftClickSlot(originalSlot)
                Thread.sleep(150)

                // Etape 8: Fermer le coffre
                ActionManager.closeScreen()
                logger.info("Coffre ferme")

                ChatManager.showActionBar("Matin OK: $toDeposit seaux deposes", "a")
                logger.info("=== TEST TRANSITION MATIN TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test transition matin", e)
                ChatManager.showActionBar("Erreur: ${e.message}", "c")
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
            ChatManager.showActionBar("Test: Transition Aprem", "e")

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
                    ChatManager.showActionBar("Echec ouverture coffre!", "c")
                    return@thread
                }
                logger.info("Coffre ouvert")

                // Etape 3: Recuperer tous les seaux du coffre
                val bucketSlots = InventoryManager.findBucketSlotsInChest()
                logger.info("Recuperation de ${bucketSlots.size} seaux du coffre")

                for (slot in bucketSlots) {
                    ActionManager.shiftClickSlot(slot)
                    Thread.sleep(150)  // Augmente pour laisser le temps a client.execute
                }

                Thread.sleep(1000)  // Augmente pour s'assurer que tous les transferts sont termines

                // Etape 4: Fermer le coffre
                ActionManager.closeScreen()
                logger.info("Coffre ferme")

                ChatManager.showActionBar("Aprem OK: ${bucketSlots.size} seaux recuperes", "a")
                logger.info("=== TEST TRANSITION APRES-MIDI TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test transition apres-midi", e)
                ChatManager.showActionBar("Erreur: ${e.message}", "c")
            }
        }
    }

    /**
     * Test du remplissage d'eau : selectionne les seaux vides et execute /eau.
     */
    fun testRemplissageEau() {
        thread(name = "test-remplissage") {
            logger.info("=== TEST REMPLISSAGE EAU ===")
            ChatManager.showActionBar("Test: Remplissage /eau", "e")

            try {
                // Selectionner les seaux vides
                if (!BucketManager.selectEmptyBuckets()) {
                    logger.warn("Pas de seaux vides a remplir")
                    ChatManager.showActionBar("Pas de seaux vides!", "c")
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
                ChatManager.showActionBar("Eau OK: $waterCount seaux remplis", "a")
                logger.info("=== TEST REMPLISSAGE EAU TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test remplissage", e)
                ChatManager.showActionBar("Erreur: ${e.message}", "c")
            }
        }
    }

    /**
     * Test complet de plantation : TP vers station, recolte si necessaire, puis plante.
     * Sequence: TP -> Selection graines -> Ouverture station -> Recolte -> Fermeture -> Plantation
     */
    fun testPlanter() {
        thread(name = "test-planter") {
            logger.info("=== TEST PLANTER ===")
            ChatManager.showActionBar("Test: Planter", "e")

            try {
                // Verifier qu'il y a au moins une station configuree
                val stations = config.getActiveStations()
                if (stations.isEmpty()) {
                    logger.error("Aucune station configuree")
                    ChatManager.showActionBar("Aucune station configuree!", "c")
                    return@thread
                }

                val stationName = stations[0]
                logger.info("Station de test: $stationName")

                // Etape 1: TP vers la premiere station
                logger.info("TP vers station: $stationName")
                ChatManager.teleportToHome(stationName)
                Thread.sleep(config.delayAfterTeleport.toLong())

                // Etape 2: Selectionner le slot des graines
                logger.info("Selection slot graines...")
                if (!InventoryManager.selectSeedsSlotAuto()) {
                    logger.warn("Aucun slot de graines trouve, utilisation du slot actuel")
                }
                Thread.sleep(200)

                // Etape 3: Ouvrir la station (clic droit)
                logger.info("Ouverture station...")
                ActionManager.rightClick()

                if (!MenuDetector.waitForSimpleMenuOpen(timeoutMs = 5000, stabilizationDelayMs = 2000)) {
                    logger.error("Echec ouverture station")
                    ChatManager.showActionBar("Echec ouverture station!", "c")
                    return@thread
                }
                logger.info("Station ouverte")

                // Etape 4: Verifier s'il y a un melon a recolter
                val melonSlot = InventoryManager.findMelonSlotInMenu()
                if (melonSlot >= 0) {
                    logger.info("Melon detecte au slot $melonSlot - recolte...")
                    ActionManager.leftClickSlot(melonSlot)
                    Thread.sleep(500)

                    // Verifier que le melon a disparu
                    val melonAfter = InventoryManager.findMelonSlotInMenu()
                    if (melonAfter < 0) {
                        logger.info("Melon recolte avec succes")
                    } else {
                        logger.warn("Melon encore present apres recolte")
                    }
                } else {
                    logger.info("Pas de melon a recolter")
                }

                // Etape 5: Fermer le menu de station
                logger.info("Fermeture menu station...")
                ActionManager.pressEscape()
                Thread.sleep(300)

                // Etape 6: Planter la graine (sneak + clic droit)
                logger.info("Plantation - sneak + clic droit")
                ActionManager.startSneaking()
                Thread.sleep(150)  // Attendre que le sneak soit bien actif
                ActionManager.rightClick()
                Thread.sleep(150)  // Attendre que l'action soit executee
                ActionManager.stopSneaking()

                ChatManager.showActionBar("Planter OK: $stationName", "a")
                logger.info("=== TEST PLANTER TERMINE ===")

            } catch (e: Exception) {
                logger.error("Erreur test planter", e)
                ChatManager.showActionBar("Erreur: ${e.message}", "c")
                // S'assurer de relacher sneak en cas d'erreur
                ActionManager.stopSneaking()
            }
        }
    }
}
