package fr.nix.agribot.bucket

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.action.ActionManager
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
    var adaptiveDelayMs: Long = 500
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
     */
    fun refreshState() {
        state.waterBucketsCount = InventoryManager.countWaterBucketsInHotbar()
        state.emptyBucketsCount = InventoryManager.countEmptyBucketsInHotbar()
        state.totalBuckets = state.waterBucketsCount + state.emptyBucketsCount
        state.mode = getCurrentMode()

        logger.debug("Etat seaux: ${state.waterBucketsCount} pleins, ${state.emptyBucketsCount} vides, mode=${state.mode}")
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
     */
    fun hasWaterBuckets(): Boolean {
        refreshState()
        return state.waterBucketsCount > 0
    }

    /**
     * Verifie si on a des seaux vides a remplir.
     */
    fun hasEmptyBuckets(): Boolean {
        refreshState()
        return state.emptyBucketsCount > 0
    }

    /**
     * Verifie si on a besoin de remplir les seaux (plus de seaux d'eau).
     */
    fun needsRefill(): Boolean {
        refreshState()
        return state.waterBucketsCount == 0 && state.emptyBucketsCount > 0
    }

    /**
     * Remplit les seaux en main avec /eau.
     */
    fun fillBucketsWithCommand() {
        logger.info("Remplissage des seaux avec /eau")
        ChatManager.fillBuckets()
    }

    // Constantes pour le delai adaptatif
    private const val MIN_DELAY_MS = 300L      // Delai minimum entre seaux
    private const val MAX_DELAY_MS = 10000L    // Delai maximum (10 secondes)
    private const val CHECK_INTERVAL_MS = 100L // Intervalle de verification

    /**
     * Vide un seau d'eau dans la station (clic droit).
     * Attend que le seau soit consomme avec un delai adaptatif.
     * Le delai s'ajuste automatiquement selon le temps de reponse du serveur.
     * @return true si le seau a ete consomme, false si pas de seau disponible
     */
    fun pourWaterBucket(): Boolean {
        if (!InventoryManager.isHoldingWaterBucket()) {
            if (!selectWaterBucket()) {
                return false
            }
        }

        // Sauvegarder le nombre de seaux d'eau avant le clic
        val waterBucketsBefore = InventoryManager.countWaterBucketsInHotbar()
        val startTime = System.currentTimeMillis()

        // Faire le clic droit
        ActionManager.rightClick()

        // Attendre que le seau soit consomme (verification periodique)
        var elapsed = 0L
        while (elapsed < MAX_DELAY_MS) {
            Thread.sleep(CHECK_INTERVAL_MS)
            elapsed = System.currentTimeMillis() - startTime

            val waterBucketsAfter = InventoryManager.countWaterBucketsInHotbar()

            if (waterBucketsAfter < waterBucketsBefore) {
                // Seau consomme avec succes
                state.bucketsUsedThisStation++

                // Ajuster le delai adaptatif (avec une marge de securite de 20%)
                val newDelay = (elapsed * 1.2).toLong().coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
                if (newDelay != state.adaptiveDelayMs) {
                    logger.info("Delai adaptatif ajuste: ${state.adaptiveDelayMs}ms -> ${newDelay}ms")
                    state.adaptiveDelayMs = newDelay
                }

                logger.debug("Seau vide en ${elapsed}ms (${state.bucketsUsedThisStation} cette station)")
                return true
            }
        }

        // Timeout atteint - le seau n'a pas ete consomme apres 10 secondes
        // On augmente le delai au maximum et on continue quand meme
        logger.warn("Seau non consomme apres ${MAX_DELAY_MS}ms - augmentation du delai")
        state.adaptiveDelayMs = MAX_DELAY_MS
        state.bucketsUsedThisStation++
        return true
    }

    /**
     * Obtient le delai adaptatif actuel entre les seaux.
     */
    fun getAdaptiveDelay(): Long {
        return state.adaptiveDelayMs
    }

    /**
     * Reinitialise le delai adaptatif a sa valeur par defaut.
     */
    fun resetAdaptiveDelay() {
        state.adaptiveDelayMs = 500
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
     * Verifie le nombre de seaux dans l'inventaire et ajuste selon la plage horaire:
     * - Matin (6h30-11h30): doit avoir 1 seau, sinon deposer l'excedent
     * - Apres-midi/nuit: doit avoir 16 seaux, sinon recuperer du coffre
     */
    fun needsModeTransition(): Boolean {
        val currentMode = getCurrentMode()
        refreshState()
        val currentBuckets = state.totalBuckets
        val targetBuckets = getBucketsToKeep()

        logger.info("Verification seaux: mode=$currentMode, actuels=$currentBuckets, cible=$targetBuckets")

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
