package fr.nix.agribot.bucket

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.chat.ChatListener
import fr.nix.agribot.chat.ChatManager
import fr.nix.agribot.inventory.InventoryManager
import org.slf4j.LoggerFactory

/**
 * Mode de gestion des seaux selon l'heure.
 */
enum class BucketMode {
    /** Mode normal (16 seaux) */
    NORMAL,
    /** Mode matin (1 seau, les autres dans le coffre) */
    MORNING,
    /** Recuperation des seaux apres 11h30 */
    RETRIEVE
}

/**
 * Etat du gestionnaire de seaux.
 */
data class BucketState(
    var mode: BucketMode = BucketMode.NORMAL,
    var totalBuckets: Int = 16,
    var waterBucketsCount: Int = 0,
    var emptyBucketsCount: Int = 0,
    var currentSlotIndex: Int = -1,
    var bucketsUsedThisStation: Int = 0,
    /** Delai adaptatif entre les seaux (s'ajuste selon le temps de consommation reel) */
    var adaptiveDelayMs: Long = 500,
    /** Indique si le premier seau de la session a ete utilise (pour ignorer le lag initial) */
    var firstBucketUsedThisSession: Boolean = false
)

/**
 * Gestionnaire des seaux pour le remplissage des stations.
 */
object BucketManager {
    private val logger = LoggerFactory.getLogger("agribot")

    val state = BucketState()

    private val config get() = AgriBotClient.config

    /**
     * Determine le mode de seaux actuel selon l'heure.
     */
    fun getCurrentMode(): BucketMode {
        val bucketMode = config.getBucketMode()
        return when (bucketMode) {
            "drop" -> BucketMode.MORNING
            "retrieve" -> BucketMode.RETRIEVE
            else -> BucketMode.NORMAL
        }
    }

    /**
     * Met a jour l'etat des seaux depuis l'inventaire.
     * OPTIMISE: utilise le cache d'InventoryManager et evite les logs debug.
     */
    fun refreshState() {
        state.waterBucketsCount = InventoryManager.countWaterBucketsInHotbar()
        state.emptyBucketsCount = InventoryManager.countEmptyBucketsInHotbar()
        state.totalBuckets = state.waterBucketsCount + state.emptyBucketsCount
        state.mode = getCurrentMode()

        // OPTIMISATION: log seulement si debug active
        if (logger.isDebugEnabled) {
            logger.debug("Etat seaux: {} pleins, {} vides, mode={}", state.waterBucketsCount, state.emptyBucketsCount, state.mode)
        }
    }

    /**
     * Prepare le remplissage d'une nouvelle station.
     */
    fun prepareForStation() {
        refreshState()
        state.bucketsUsedThisStation = 0
        ChatListener.resetStationFullDetection()
    }

    /**
     * Selectionne un seau d'eau pour le vider dans la station.
     * Cherche dans tous les slots de la hotbar.
     * @return true si un seau d'eau a ete selectionne
     */
    fun selectWaterBucket(): Boolean {
        // D'abord verifier si on tient deja un seau d'eau
        if (InventoryManager.isHoldingWaterBucket()) {
            state.currentSlotIndex = InventoryManager.getSelectedSlot()
            return true
        }

        // Sinon chercher un slot avec seau d'eau
        val waterSlots = InventoryManager.findWaterBucketSlots()
        if (waterSlots.isNotEmpty()) {
            val slot = waterSlots.first()
            InventoryManager.selectSlot(slot.slotIndex)
            state.currentSlotIndex = slot.slotIndex
            logger.info("Seau d'eau selectionne dans slot ${slot.slotIndex + 1}")
            return true
        }

        logger.warn("Aucun seau d'eau disponible")
        return false
    }

    /**
     * Selectionne des seaux vides pour les remplir avec /eau.
     * Cherche dans tous les slots de la hotbar.
     * @return true si des seaux vides ont ete selectionnes
     */
    fun selectEmptyBuckets(): Boolean {
        // D'abord verifier si on tient deja des seaux vides
        if (InventoryManager.isHoldingEmptyBucket()) {
            state.currentSlotIndex = InventoryManager.getSelectedSlot()
            return true
        }

        // Sinon chercher un slot avec seaux vides
        val emptySlots = InventoryManager.findEmptyBucketSlots()
        if (emptySlots.isNotEmpty()) {
            val slot = emptySlots.first()
            InventoryManager.selectSlot(slot.slotIndex)
            state.currentSlotIndex = slot.slotIndex
            logger.info("Seaux vides selectionnes dans slot ${slot.slotIndex + 1}")
            return true
        }

        logger.warn("Aucun seau vide disponible")
        return false
    }

    /**
     * Verifie si on a des seaux d'eau disponibles.
     * Optimise: utilise directement InventoryManager (cache hotbar).
     */
    fun hasWaterBuckets(): Boolean {
        return InventoryManager.countWaterBucketsInHotbar() > 0
    }

    /**
     * Verifie si on a des seaux vides a remplir.
     * Optimise: utilise directement InventoryManager (cache hotbar).
     */
    fun hasEmptyBuckets(): Boolean {
        return InventoryManager.countEmptyBucketsInHotbar() > 0
    }

    /**
     * Verifie si on a besoin de remplir les seaux (plus de seaux d'eau).
     * Optimise: utilise directement InventoryManager (cache hotbar).
     */
    fun needsRefill(): Boolean {
        return InventoryManager.countWaterBucketsInHotbar() == 0 &&
               InventoryManager.countEmptyBucketsInHotbar() > 0
    }

    /**
     * Remplit les seaux en main avec /eau.
     */
    fun fillBucketsWithCommand() {
        logger.info("Remplissage des seaux avec /eau")
        ChatManager.fillBuckets()
    }

    // NOTE: L'ancienne methode pourWaterBucket() a ete supprimee car elle utilisait
    // Thread.sleep() sur le thread principal, ce qui gelait le jeu entier.
    // Le remplissage d'eau est maintenant gere de maniere non-bloquante
    // par handleFillingWater() dans BotCore.kt (machine d'etat par ticks).

    /**
     * Obtient le delai adaptatif actuel entre les seaux.
     */
    fun getAdaptiveDelay(): Long {
        return state.adaptiveDelayMs
    }

    /**
     * Reinitialise le delai adaptatif a sa valeur par defaut.
     * Reinitialise egalement le flag du premier seau pour la prochaine session.
     */
    fun resetAdaptiveDelay() {
        state.adaptiveDelayMs = 500
        state.firstBucketUsedThisSession = false
    }

    /**
     * Verifie si la station est pleine.
     */
    fun isStationFull(): Boolean {
        return ChatListener.stationFullDetected
    }

    /**
     * Obtient le nombre de seaux a garder selon le mode.
     * Mode matin: garder 1 seau
     * Mode normal/retrieve: garder 16 seaux
     */
    fun getBucketsToKeep(): Int {
        return when (getCurrentMode()) {
            BucketMode.MORNING -> 1
            else -> 16
        }
    }

    /**
     * Calcule combien de seaux doivent etre deposes dans le coffre.
     */
    fun getBucketsToDeposit(): Int {
        refreshState()
        val toKeep = getBucketsToKeep()
        val toDeposit = state.totalBuckets - toKeep
        return maxOf(0, toDeposit)
    }

    /**
     * Calcule combien de seaux doivent etre recuperes du coffre.
     */
    fun getBucketsToRetrieve(): Int {
        refreshState()
        val target = 16
        val toRetrieve = target - state.totalBuckets
        return maxOf(0, toRetrieve)
    }

    /**
     * Verifie si une transition de mode seaux est necessaire.
     * Verifie le nombre de seaux dans l'inventaire COMPLET et ajuste selon la plage horaire:
     * - Matin (6h30-11h30): doit avoir 1 seau, sinon deposer l'excedent
     * - Apres-midi/nuit: doit avoir 16 seaux, sinon recuperer du coffre
     * Verifie aussi si la transition a deja ete effectuee pour la periode actuelle.
     */
    fun needsModeTransition(): Boolean {
        val currentMode = getCurrentMode()

        // Verifier si la transition a deja ete effectuee pour la periode actuelle
        val currentPeriod = config.getCurrentPeriod()
        if (config.lastTransitionPeriod == currentPeriod) {
            // Verifier que le nombre de seaux est effectivement correct
            // (le bot a pu etre redemarre avec un inventaire different)
            val actualBuckets = InventoryManager.countAllBucketsInFullInventory()
            val expectedBuckets = getBucketsToKeep()
            val bucketsCorrect = when (currentMode) {
                BucketMode.MORNING -> actualBuckets <= expectedBuckets
                else -> actualBuckets >= expectedBuckets
            }
            if (bucketsCorrect) {
                if (logger.isDebugEnabled) {
                    logger.debug("Transition deja effectuee pour la periode $currentPeriod (seaux OK: $actualBuckets)")
                }
                return false
            }
            // Nombre de seaux incorrect malgre la transition sauvegardee - forcer re-transition
            logger.info("Transition sauvegardee pour $currentPeriod mais seaux incorrects ($actualBuckets vs cible $expectedBuckets) - re-transition necessaire")
        }

        refreshState()
        // Utiliser le comptage de l'inventaire COMPLET (hotbar + inventaire principal)
        // car les seaux d'eau ne stackent pas et debordent dans l'inventaire principal
        val currentBuckets = InventoryManager.countAllBucketsInFullInventory()
        val targetBuckets = getBucketsToKeep()

        logger.info("Verification seaux: mode=$currentMode, actuels=$currentBuckets (inventaire complet), cible=$targetBuckets, periode=$currentPeriod")

        return when (currentMode) {
            BucketMode.MORNING -> {
                // Matin: on doit avoir 1 seau. Si on en a plus, deposer
                if (currentBuckets > targetBuckets) {
                    logger.info("Trop de seaux ($currentBuckets > $targetBuckets) - depot necessaire")
                    true
                } else {
                    logger.info("Nombre de seaux OK pour le matin ($currentBuckets)")
                    false
                }
            }
            BucketMode.RETRIEVE, BucketMode.NORMAL -> {
                // Apres-midi/nuit: on doit avoir 16 seaux. Si on en a moins, recuperer
                if (currentBuckets < targetBuckets) {
                    logger.info("Pas assez de seaux ($currentBuckets < $targetBuckets) - recuperation necessaire")
                    true
                } else {
                    logger.info("Nombre de seaux OK pour l'apres-midi/nuit ($currentBuckets)")
                    false
                }
            }
        }
    }

    /**
     * Sauvegarde le mode actuel (sans la periode de transition).
     */
    fun saveCurrentMode() {
        config.lastBucketMode = config.getBucketMode()
        config.save()
    }

    /**
     * Sauvegarde le mode actuel ET la periode de transition.
     * A appeler uniquement apres une vraie transition (MANAGING_BUCKETS).
     */
    fun saveTransitionComplete() {
        config.lastBucketMode = config.getBucketMode()
        config.lastTransitionPeriod = config.getCurrentPeriod()
        config.save()
        logger.info("Transition sauvegardee: mode=${config.lastBucketMode}, periode=${config.lastTransitionPeriod}")
    }

    /**
     * Log l'etat actuel des seaux.
     */
    fun logState() {
        refreshState()
        logger.info("=== Etat Seaux ===")
        logger.info("Mode: ${state.mode}")
        logger.info("Seaux d'eau: ${state.waterBucketsCount}")
        logger.info("Seaux vides: ${state.emptyBucketsCount}")
        logger.info("Total: ${state.totalBuckets}")
        logger.info("A garder: ${getBucketsToKeep()}")
        logger.info("==================")
    }
}
