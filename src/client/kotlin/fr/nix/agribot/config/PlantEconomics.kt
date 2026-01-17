package fr.nix.agribot.config

/**
 * Donnees economiques d'une plante.
 *
 * @param cost Cout d'une graine pour une station
 * @param revenue Revenu total de la recolte pour une station (une session complete)
 */
data class PlantEconomicsData(
    val cost: Double,    // Cout de la graine
    val revenue: Double  // Revenu de la recolte
) {
    /**
     * Calcule le profit net pour une station.
     */
    val profit: Double get() = revenue - cost

    /**
     * Calcule le ratio de profit (profit / cout).
     */
    val profitRatio: Double get() = if (cost > 0) profit / cost else 0.0
}

/**
 * Donnees economiques de toutes les plantes.
 * Prix des graines et revenus de recolte pour une session complete par station.
 */
object PlantEconomics {
    private val plants = mapOf(
        "Laitue" to PlantEconomicsData(cost = 45.0, revenue = 90.0),
        "Glycine glacee" to PlantEconomicsData(cost = 45.0, revenue = 157.5),
        "Oignons" to PlantEconomicsData(cost = 135.0, revenue = 315.0),
        "Fleur du brasier" to PlantEconomicsData(cost = 90.0, revenue = 337.5),
        "Concombre" to PlantEconomicsData(cost = 135.0, revenue = 315.0),
        "Vixen" to PlantEconomicsData(cost = 112.5, revenue = 414.0),
        "Tomates" to PlantEconomicsData(cost = 235.8, revenue = 607.2),
        "Lune Akari" to PlantEconomicsData(cost = 607.5, revenue = 1012.5),
        "Ail" to PlantEconomicsData(cost = 225.0, revenue = 675.0),
        "Piment de cayenne" to PlantEconomicsData(cost = 450.0, revenue = 1152.0),
        "Graine de l'enfer" to PlantEconomicsData(cost = 270.0, revenue = 1485.0),
        "Zucchini" to PlantEconomicsData(cost = 900.0, revenue = 112.5),
        "Nenuphar" to PlantEconomicsData(cost = 2300.0, revenue = 6152.16),
        "Venus attrape-mouche" to PlantEconomicsData(cost = 1350.0, revenue = 3672.0),
        "Poivron" to PlantEconomicsData(cost = 1912.5, revenue = 3894.0),
        "Brocoli" to PlantEconomicsData(cost = 1292.5, revenue = 12600.0),
        "Pois" to PlantEconomicsData(cost = 1575.0, revenue = 3242.88),
        "Wazabi" to PlantEconomicsData(cost = 1125.0, revenue = 2835.0),
        "Chou" to PlantEconomicsData(cost = 1462.5, revenue = 7452.0),
        "Orchidee Abyssale" to PlantEconomicsData(cost = 7200.0, revenue = 12141.0),
        "Ame gelee" to PlantEconomicsData(cost = 6300.0, revenue = 16020.0),
        "Courgette" to PlantEconomicsData(cost = 2300.0, revenue = 4800.0),
        "Plume de lave" to PlantEconomicsData(cost = 2800.0, revenue = 12839.68),
        "Pommes des tenebres" to PlantEconomicsData(cost = 3375.0, revenue = 17280.0),
        "Coeur du vide" to PlantEconomicsData(cost = 7200.0, revenue = 11088.0),
        "Iris Pyrobrase" to PlantEconomicsData(cost = 3200.0, revenue = 7760.0)
    )

    /**
     * Recupere les donnees economiques d'une plante par son nom.
     * Effectue une recherche insensible aux accents pour plus de robustesse.
     */
    fun get(name: String): PlantEconomicsData? {
        // Recherche exacte d'abord
        plants[name]?.let { return it }

        // Recherche insensible aux accents
        val normalizedName = normalizeString(name)
        return plants.entries.find { normalizeString(it.key) == normalizedName }?.value
    }

    /**
     * Retourne la liste de tous les noms de plantes.
     */
    fun getNames(): List<String> = plants.keys.toList()

    /**
     * Retourne toutes les plantes triees par profit decroissant.
     */
    fun getAllSortedByProfit(): List<Pair<String, PlantEconomicsData>> {
        return plants.toList().sortedByDescending { it.second.profit }
    }

    /**
     * Normalise une chaine en retirant les accents.
     */
    private fun normalizeString(str: String): String {
        return str.lowercase()
            .replace("é", "e")
            .replace("è", "e")
            .replace("ê", "e")
            .replace("ë", "e")
            .replace("à", "a")
            .replace("â", "a")
            .replace("ä", "a")
            .replace("ù", "u")
            .replace("û", "u")
            .replace("ü", "u")
            .replace("ô", "o")
            .replace("ö", "o")
            .replace("î", "i")
            .replace("ï", "i")
            .replace("ç", "c")
            .replace("'", "'")
    }
}
