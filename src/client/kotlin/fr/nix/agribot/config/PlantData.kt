package fr.nix.agribot.config

import kotlin.math.ceil

/**
 * Donnees d'une plante avec ses temps de croissance.
 *
 * @param tempsTige Temps de croissance de la tige en minutes
 * @param tempsFruit Temps de croissance des fruits en minutes (0 si pas de fruits additionnels)
 * @param nbFruits Nombre de fruits (1 si fruit unique/pas de fruits)
 * @param seedType Type d'item utilise comme graine (ex: "wheat_seeds", "pumpkin_seeds")
 */
data class PlantData(
    val tempsTige: Int,
    val tempsFruit: Int,
    val nbFruits: Int,
    val seedType: String
) {
    /**
     * Determine le type de clic pour la recolte.
     * - Plantes a 1 fruit (tempsFruit == 0) -> clic gauche
     * - Plantes avec tige + fruits (tempsFruit > 0) -> clic droit
     */
    val harvestClickType: ClickType
        get() = if (tempsFruit > 0) ClickType.RIGHT else ClickType.LEFT

    /**
     * Calcule le temps total de croissance en minutes avec le boost applique.
     *
     * Formules:
     * - Pour nbFruits > 1: (tempsTige / (1 + boost/100)) + (nbFruits * tempsFruit)
     *   Le boost s'applique uniquement a la tige, les fruits restent inchanges.
     * - Pour nbFruits == 1: (tempsTige + tempsFruit) / (1 + boost/100)
     *   Le boost s'applique a l'ensemble du temps.
     *
     * @param boost Pourcentage de boost de croissance (0-100+)
     * @return Temps total de croissance en minutes, arrondi a l'entier superieur
     */
    fun tempsTotalCroissance(boost: Float = 0f): Int {
        val boostEffectif = maxOf(0f, boost)
        val diviseur = 1 + boostEffectif / 100

        val total = if (nbFruits > 1) {
            // Boost uniquement sur la tige, fruits inchanges
            val tempsTigeBooste = tempsTige / diviseur
            val tempsFruitsTotal = nbFruits * tempsFruit
            tempsTigeBooste + tempsFruitsTotal
        } else {
            // Boost sur l'ensemble (tige + fruit eventuel)
            (tempsTige + tempsFruit) / diviseur
        }

        return ceil(total).toInt()
    }

    /**
     * Calcule le temps total de croissance en secondes.
     */
    fun tempsTotalEnSecondes(boost: Float = 0f): Int {
        return tempsTotalCroissance(boost) * 60
    }
}

enum class ClickType {
    LEFT,
    RIGHT
}

/**
 * Registre de toutes les plantes disponibles.
 */
object Plants {
    private val plants: Map<String, PlantData> = mapOf(
        "Concombre" to PlantData(tempsTige = 80, tempsFruit = 0, nbFruits = 1, seedType = "wheat_seeds"),
        "Oignons" to PlantData(tempsTige = 40, tempsFruit = 0, nbFruits = 1, seedType = "pumpkin_seeds"),
        "Laitue" to PlantData(tempsTige = 10, tempsFruit = 0, nbFruits = 1, seedType = "wheat_seeds"),
        "Pois" to PlantData(tempsTige = 480, tempsFruit = 80, nbFruits = 3, seedType = "wheat_seeds"),
        "Tomates" to PlantData(tempsTige = 60, tempsFruit = 20, nbFruits = 2, seedType = "pumpkin_seeds"),
        "Poivron" to PlantData(tempsTige = 120, tempsFruit = 120, nbFruits = 4, seedType = "pumpkin_seeds"),
        "Zucchini" to PlantData(tempsTige = 320, tempsFruit = 0, nbFruits = 1, seedType = "wheat_seeds"),
        "Ail" to PlantData(tempsTige = 120, tempsFruit = 0, nbFruits = 1, seedType = "pumpkin_seeds"),
        "Glycine glacee" to PlantData(tempsTige = 20, tempsFruit = 0, nbFruits = 1, seedType = "blue_dye"),
        "Wazabi" to PlantData(tempsTige = 600, tempsFruit = 0, nbFruits = 1, seedType = "melon_seeds"),
        "Courgette" to PlantData(tempsTige = 1200, tempsFruit = 0, nbFruits = 1, seedType = "wheat_seeds"),
        "Piment de cayenne" to PlantData(tempsTige = 240, tempsFruit = 0, nbFruits = 1, seedType = "melon_seeds"),
        "Vixen" to PlantData(tempsTige = 60, tempsFruit = 0, nbFruits = 1, seedType = "wheat_seeds"),
        "Lune Akari" to PlantData(tempsTige = 40, tempsFruit = 45, nbFruits = 1, seedType = "sunflower"),
        "Nenuphar" to PlantData(tempsTige = 300, tempsFruit = 120, nbFruits = 3, seedType = "lily_of_the_valley"),
        "Chou" to PlantData(tempsTige = 240, tempsFruit = 160, nbFruits = 3, seedType = "wheat_seeds"),
        "Plume de lave" to PlantData(tempsTige = 120, tempsFruit = 240, nbFruits = 4, seedType = "blaze_powder"),
        "Fleur du brasier" to PlantData(tempsTige = 40, tempsFruit = 0, nbFruits = 1, seedType = "beetroot_seeds"),
        "Brocoli" to PlantData(tempsTige = 120, tempsFruit = 80, nbFruits = 3, seedType = "wheat_seeds"),
        "Iris Pyrobrase" to PlantData(tempsTige = 1440, tempsFruit = 0, nbFruits = 1, seedType = "blaze_powder"),
        "Venus attrape-mouche" to PlantData(tempsTige = 300, tempsFruit = 60, nbFruits = 1, seedType = "wheat_seeds"),
        "Graine de l'enfer" to PlantData(tempsTige = 190, tempsFruit = 0, nbFruits = 1, seedType = "wither_rose"),
        "Ame gelee" to PlantData(tempsTige = 80, tempsFruit = 180, nbFruits = 5, seedType = "snowball"),
        "Pommes des tenebres" to PlantData(tempsTige = 360, tempsFruit = 180, nbFruits = 4, seedType = "green_dye"),
        "Coeur du vide" to PlantData(tempsTige = 960, tempsFruit = 0, nbFruits = 1, seedType = "chorus_fruit"),
        "Orchidee Abyssale" to PlantData(tempsTige = 360, tempsFruit = 100, nbFruits = 3, seedType = "pumpkin_seeds")
    )

    /**
     * Recupere une plante par son nom.
     */
    fun get(name: String): PlantData? = plants[name]

    /**
     * Recupere la liste des noms de plantes triee.
     */
    fun getNames(): List<String> = plants.keys.sorted()

    /**
     * Verifie si une plante existe.
     */
    fun exists(name: String): Boolean = plants.containsKey(name)

    /**
     * Formate un temps en minutes en format lisible (Xh Ym).
     */
    fun formatTemps(minutes: Int): String {
        val heures = minutes / 60
        val mins = minutes % 60

        return when {
            heures > 0 && mins > 0 -> "${heures}h ${mins}m"
            heures > 0 -> "${heures}h"
            else -> "${mins}m"
        }
    }
}
