package fr.nix.agribot.gui

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.config.Plants
import fr.nix.agribot.test.TestActions
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

/**
 * Ecran de configuration du bot agricole.
 * Permet de configurer les 30 stations et les homes des coffres.
 */
class ConfigScreen : Screen(Text.literal("AgriBot - Configuration")) {

    private val stationFields = mutableListOf<TextFieldWidget>()
    private lateinit var coffreField: TextFieldWidget
    private lateinit var plantLeftButton: ButtonWidget
    private lateinit var plantRightButton: ButtonWidget
    private lateinit var boostField: TextFieldWidget
    private lateinit var waterDurationButton: ButtonWidget

    private var scrollOffset = 0
    private var visibleStations = 8  // Nombre de stations visibles a l'ecran (calculé dynamiquement)
    private val fieldHeight = 22
    private val fieldSpacing = 24
    private var stationsStartY = 143  // Position Y de départ des stations (calculée dans init)
    private var selectedWaterDurationIndex = 0
    private var selectedPlantIndex = 0
    private val plantNames = Plants.getNames()

    override fun init() {
        super.init()

        val config = AgriBotClient.config
        val centerX = width / 2
        val startY = 50

        // === Section Plante ===
        // Trouver l'index de la plante actuelle
        selectedPlantIndex = plantNames.indexOf(config.selectedPlant)
        if (selectedPlantIndex == -1) selectedPlantIndex = 0

        // Bouton fleche gauche
        plantLeftButton = ButtonWidget.builder(Text.literal("<")) { _ ->
            selectedPlantIndex = if (selectedPlantIndex > 0) selectedPlantIndex - 1 else plantNames.size - 1
        }.dimensions(centerX - 150, startY, 20, 20).build()
        addDrawableChild(plantLeftButton)

        // Bouton fleche droite
        plantRightButton = ButtonWidget.builder(Text.literal(">")) { _ ->
            selectedPlantIndex = (selectedPlantIndex + 1) % plantNames.size
        }.dimensions(centerX - 10, startY, 20, 20).build()
        addDrawableChild(plantRightButton)

        // Champ boost
        boostField = TextFieldWidget(textRenderer, centerX + 10, startY, 60, 20, Text.literal("Boost"))
        boostField.text = config.growthBoost.toInt().toString()
        boostField.setMaxLength(5)
        addDrawableChild(boostField)

        // === Section Coffre ===
        val coffreY = startY + 30

        // Home coffre pour depot/recuperation des seaux
        coffreField = TextFieldWidget(textRenderer, centerX - 100, coffreY, 200, 20, Text.literal("Coffre"))
        coffreField.text = config.homeCoffre
        coffreField.setMaxLength(30)
        addDrawableChild(coffreField)

        // === Section Duree eau ===
        val waterY = coffreY + 28

        // Trouver l'index de la duree actuelle
        selectedWaterDurationIndex = AgriConfig.WATER_DURATIONS.indexOf(config.waterDurationMinutes)
        if (selectedWaterDurationIndex == -1) selectedWaterDurationIndex = AgriConfig.WATER_DURATIONS.size - 1

        // Bouton pour changer la duree d'eau
        waterDurationButton = ButtonWidget.builder(Text.literal(AgriConfig.formatDuration(config.waterDurationMinutes))) { _ ->
            // Cycle vers la prochaine valeur
            selectedWaterDurationIndex = (selectedWaterDurationIndex + 1) % AgriConfig.WATER_DURATIONS.size
            val newDuration = AgriConfig.WATER_DURATIONS[selectedWaterDurationIndex]
            waterDurationButton.message = Text.literal(AgriConfig.formatDuration(newDuration))
        }.dimensions(centerX + 30, waterY, 70, 20).build()
        addDrawableChild(waterDurationButton)

        // === Section Stations (scrollable) ===
        stationsStartY = waterY + 35
        stationFields.clear()

        // Calculer le nombre de stations visibles en fonction de l'espace disponible
        val bottomMargin = 60  // Espace pour "Stations actives" (height-50) et boutons (height-30)
        val availableHeight = height - stationsStartY - bottomMargin
        visibleStations = maxOf(1, minOf(8, availableHeight / fieldSpacing))

        // Creer les 30 champs de stations (mais on n'affiche que visibleStations)
        for (i in 0 until 30) {
            val field = TextFieldWidget(
                textRenderer,
                centerX - 100,
                stationsStartY + (i * fieldSpacing),
                200,
                20,
                Text.literal("Station ${i + 1}")
            )
            field.text = config.stations.getOrElse(i) { "" }
            field.setMaxLength(30)
            stationFields.add(field)
        }

        // Ajouter seulement les champs visibles
        updateVisibleFields()

        // === Boutons ===
        val buttonY = height - 30

        // Bouton Sauvegarder
        addDrawableChild(ButtonWidget.builder(Text.literal("Sauvegarder")) { _ ->
            saveConfig()
            close()
        }.dimensions(centerX - 105, buttonY, 100, 20).build())

        // Bouton Annuler
        addDrawableChild(ButtonWidget.builder(Text.literal("Annuler")) { _ ->
            close()
        }.dimensions(centerX + 5, buttonY, 100, 20).build())

        // === Boutons de test (en haut a droite) ===
        val testButtonX = width - 110
        val testButtonWidth = 100

        // Test transition matin (deposer seaux)
        addDrawableChild(ButtonWidget.builder(Text.literal("Test Matin")) { _ ->
            close()
            TestActions.testTransitionMatin()
        }.dimensions(testButtonX, 15, testButtonWidth, 16).build())

        // Test transition apres-midi (recuperer seaux)
        addDrawableChild(ButtonWidget.builder(Text.literal("Test Aprem")) { _ ->
            close()
            TestActions.testTransitionApresMidi()
        }.dimensions(testButtonX, 33, testButtonWidth, 16).build())

        // Test remplissage eau
        addDrawableChild(ButtonWidget.builder(Text.literal("Test /eau")) { _ ->
            close()
            TestActions.testRemplissageEau()
        }.dimensions(testButtonX, 51, testButtonWidth, 16).build())
    }

    private fun updateVisibleFields() {
        // Retirer tous les champs de station
        stationFields.forEach { remove(it) }

        // Ajouter seulement les champs visibles selon le scroll
        for (i in 0 until visibleStations) {
            val stationIndex = scrollOffset + i
            if (stationIndex < 30) {
                val field = stationFields[stationIndex]
                field.y = stationsStartY + (i * fieldSpacing)
                addDrawableChild(field)
            }
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Fond sombre sans flou (fillGradient au lieu de renderBackground)
        context.fillGradient(0, 0, width, height, 0xC0101010.toInt(), 0xD0101010.toInt())

        val centerX = width / 2

        // Titre
        context.drawCenteredTextWithShadow(textRenderer, title, centerX, 15, 0xFFFFFF)

        // Label section Tests (en haut a droite)
        context.drawTextWithShadow(textRenderer, "Tests:", width - 110, 5, 0xFF5555)

        // Labels section Plante
        context.drawTextWithShadow(textRenderer, "Plante:", centerX - 150, 40, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer, "Boost %:", centerX + 10, 40, 0xAAAAAA)

        // Afficher le nom de la plante selectionnee au centre des fleches
        val selectedPlant = plantNames[selectedPlantIndex]
        val plantNameX = centerX - 150 + 20 + (110 - textRenderer.getWidth(selectedPlant)) / 2
        context.drawTextWithShadow(textRenderer, selectedPlant, plantNameX, 55, 0xFFFFFF)

        // Afficher le temps de croissance calcule
        val plantData = Plants.get(selectedPlant)
        val boost = boostField.text.toFloatOrNull() ?: 0f
        if (plantData != null) {
            val temps = plantData.tempsTotalCroissance(boost)
            val tempsStr = Plants.formatTemps(temps)
            context.drawTextWithShadow(textRenderer, "= $tempsStr", centerX + 80, 55, 0x55FF55)
        }

        // Label section Coffre
        context.drawTextWithShadow(textRenderer, "Home Coffre (seaux):", centerX - 100, 70, 0xAAAAAA)

        // Label section Duree eau
        context.drawTextWithShadow(textRenderer, "Duree eau stations:", centerX - 100, 103, 0xAAAAAA)

        // Titre section Stations
        val stationsTitleY = stationsStartY - 10
        context.drawTextWithShadow(textRenderer, "Stations", centerX - 100, stationsTitleY, 0xFFFF55)

        // Labels des stations visibles
        for (i in 0 until visibleStations) {
            val stationIndex = scrollOffset + i
            if (stationIndex < 30) {
                val y = stationsStartY + (i * fieldSpacing) + 5
                context.drawTextWithShadow(textRenderer, "${stationIndex + 1}.", centerX - 120, y, 0xAAAAAA)
            }
        }

        // Info en bas
        val activeCount = stationFields.count { it.text.isNotBlank() }
        context.drawCenteredTextWithShadow(textRenderer, "Stations actives: $activeCount/30", centerX, height - 50, 0x888888)

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        // Scroll pour les stations
        val newOffset = scrollOffset - verticalAmount.toInt()
        scrollOffset = newOffset.coerceIn(0, 30 - visibleStations)
        updateVisibleFields()
        return true
    }

    private fun saveConfig() {
        val config = AgriBotClient.config

        // Sauvegarder la plante et le boost
        config.selectedPlant = plantNames[selectedPlantIndex]
        config.growthBoost = boostField.text.toFloatOrNull() ?: 29f

        // Sauvegarder le coffre
        config.homeCoffre = coffreField.text

        // Sauvegarder la duree d'eau
        config.waterDurationMinutes = AgriConfig.WATER_DURATIONS[selectedWaterDurationIndex]

        // Sauvegarder les stations
        for (i in 0 until 30) {
            config.setStation(i, stationFields[i].text)
        }

        // Sauvegarder dans le fichier
        config.save()
    }

    override fun shouldPause(): Boolean = false
}
