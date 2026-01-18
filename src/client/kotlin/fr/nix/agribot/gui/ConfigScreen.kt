package fr.nix.agribot.gui

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.bot.BotCore
import fr.nix.agribot.bot.BotState
import fr.nix.agribot.config.AgriConfig
import fr.nix.agribot.config.AutoResponseConfig
import fr.nix.agribot.config.Plants
import fr.nix.agribot.config.StatsConfig
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
class ConfigScreen(private val parent: Screen? = null) : Screen(Text.literal("AgriBot - Configuration")) {

    private val stationFields = mutableListOf<TextFieldWidget>()
    private lateinit var coffreField: TextFieldWidget
    private lateinit var backupField: TextFieldWidget
    private lateinit var grainesField: TextFieldWidget
    private lateinit var mouvementField: TextFieldWidget
    private lateinit var passwordField: TextFieldWidget
    private lateinit var plantLeftButton: ButtonWidget
    private lateinit var plantRightButton: ButtonWidget
    private lateinit var boostField: TextFieldWidget
    private lateinit var waterDurationButton: ButtonWidget

    private var scrollOffset = 0
    private var visibleStations = 8
    private val fieldHeight = 20
    private val fieldSpacing = 24
    private var stationsStartY = 0
    private var selectedWaterDurationIndex = 0
    private var selectedPlantIndex = 0
    private val plantNames = Plants.getNames()
    private var isBotActive = false

    // Constantes de layout
    companion object {
        private const val SECTION_SPACING = 10      // Espace entre sections
        private const val LABEL_FIELD_GAP = 2       // Espace entre label et champ
        private const val FIELD_WIDTH = 90          // Largeur standard des champs
        private const val FIELD_WIDTH_LARGE = 200   // Largeur des champs larges
        private const val LABEL_HEIGHT = 10         // Hauteur du texte label
    }

    override fun init() {
        super.init()

        val config = AgriBotClient.config
        val centerX = width / 2

        // === En-tete (Y: 5-40) ===
        val headerY = 5

        // Boutons Auto-Reponse et Stats (gauche)
        val autoResponseConfig = AutoResponseConfig.get()
        val autoResponseStatus = if (autoResponseConfig.enabled) "ON" else "OFF"
        val testModeStatus = if (autoResponseConfig.testModeActive) " [TEST]" else ""
        addDrawableChild(ButtonWidget.builder(Text.literal("Auto-Reponse $autoResponseStatus$testModeStatus")) { _ ->
            client?.setScreen(AutoResponseScreen(this))
        }.dimensions(10, headerY + 12, 130, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Stats")) { _ ->
            client?.setScreen(StatsScreen(this))
        }.dimensions(145, headerY + 12, 50, 20).build())

        // Boutons de test (droite)
        val testButtonX = width - 110
        val testButtonWidth = 100

        addDrawableChild(ButtonWidget.builder(Text.literal("Test Matin")) { _ ->
            close()
            TestActions.testTransitionMatin()
        }.dimensions(testButtonX, headerY + 12, testButtonWidth, 16).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Test Aprem")) { _ ->
            close()
            TestActions.testTransitionApresMidi()
        }.dimensions(testButtonX, headerY + 30, testButtonWidth, 16).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Test /eau")) { _ ->
            close()
            TestActions.testRemplissageEau()
        }.dimensions(testButtonX, headerY + 48, testButtonWidth, 16).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Test Planter")) { _ ->
            close()
            TestActions.testPlanter()
        }.dimensions(testButtonX, headerY + 66, testButtonWidth, 16).build())

        // === Section Plante (Y: 45) ===
        var currentY = 45
        isBotActive = BotCore.stateData.state != BotState.IDLE

        selectedPlantIndex = plantNames.indexOf(config.selectedPlant)
        if (selectedPlantIndex == -1) selectedPlantIndex = 0

        // Ligne label "Plante" et "Boost"
        // (labels dessines dans render())
        currentY += LABEL_HEIGHT + LABEL_FIELD_GAP

        // Boutons de navigation plante
        plantLeftButton = ButtonWidget.builder(Text.literal("<")) { _ ->
            if (!isBotActive) {
                selectedPlantIndex = if (selectedPlantIndex > 0) selectedPlantIndex - 1 else plantNames.size - 1
            }
        }.dimensions(centerX - 160, currentY, 20, fieldHeight).build()
        plantLeftButton.active = !isBotActive
        addDrawableChild(plantLeftButton)

        plantRightButton = ButtonWidget.builder(Text.literal(">")) { _ ->
            if (!isBotActive) {
                selectedPlantIndex = (selectedPlantIndex + 1) % plantNames.size
            }
        }.dimensions(centerX - 30, currentY, 20, fieldHeight).build()
        plantRightButton.active = !isBotActive
        addDrawableChild(plantRightButton)

        // Champ boost (a droite de la plante)
        boostField = TextFieldWidget(textRenderer, centerX + 20, currentY, 50, fieldHeight, Text.literal("Boost"))
        boostField.text = config.growthBoost.toInt().toString()
        boostField.setMaxLength(5)
        addDrawableChild(boostField)

        currentY += fieldHeight + SECTION_SPACING

        // === Section Coffres (3 colonnes) ===
        // Ligne de labels
        // (labels dessines dans render())
        currentY += LABEL_HEIGHT + LABEL_FIELD_GAP

        // Calcul positions pour 3 colonnes centrees
        val totalCoffreWidth = 3 * FIELD_WIDTH + 2 * 15  // 3 champs + 2 espaces de 15px
        val coffreStartX = centerX - totalCoffreWidth / 2

        coffreField = TextFieldWidget(textRenderer, coffreStartX, currentY, FIELD_WIDTH, fieldHeight, Text.literal("Coffre"))
        coffreField.text = config.homeCoffre
        coffreField.setMaxLength(30)
        addDrawableChild(coffreField)

        backupField = TextFieldWidget(textRenderer, coffreStartX + FIELD_WIDTH + 15, currentY, FIELD_WIDTH, fieldHeight, Text.literal("Backup"))
        backupField.text = config.homeBackup
        backupField.setMaxLength(30)
        addDrawableChild(backupField)

        grainesField = TextFieldWidget(textRenderer, coffreStartX + 2 * (FIELD_WIDTH + 15), currentY, FIELD_WIDTH, fieldHeight, Text.literal("Graines"))
        grainesField.text = config.homeGraines
        grainesField.setMaxLength(30)
        addDrawableChild(grainesField)

        currentY += fieldHeight + SECTION_SPACING

        // === Section Connexion (2 colonnes) ===
        // Ligne de labels
        currentY += LABEL_HEIGHT + LABEL_FIELD_GAP

        // Home mouvement (gauche) et Mot de passe (droite)
        val connexionFieldWidth = 140
        val connexionGap = 20
        val connexionStartX = centerX - connexionFieldWidth - connexionGap / 2

        mouvementField = TextFieldWidget(textRenderer, connexionStartX, currentY, connexionFieldWidth, fieldHeight, Text.literal("Mouvement"))
        mouvementField.text = config.homeMouvement
        mouvementField.setMaxLength(30)
        addDrawableChild(mouvementField)

        passwordField = TextFieldWidget(textRenderer, centerX + connexionGap / 2, currentY, connexionFieldWidth, fieldHeight, Text.literal("Password"))
        passwordField.text = config.loginPassword
        passwordField.setMaxLength(50)
        addDrawableChild(passwordField)

        currentY += fieldHeight + SECTION_SPACING

        // === Section Duree eau ===
        // Label et bouton sur la meme ligne
        selectedWaterDurationIndex = AgriConfig.WATER_DURATIONS.indexOf(config.waterDurationMinutes)
        if (selectedWaterDurationIndex == -1) selectedWaterDurationIndex = AgriConfig.WATER_DURATIONS.size - 1

        waterDurationButton = ButtonWidget.builder(Text.literal(AgriConfig.formatDuration(config.waterDurationMinutes))) { _ ->
            selectedWaterDurationIndex = (selectedWaterDurationIndex + 1) % AgriConfig.WATER_DURATIONS.size
            val newDuration = AgriConfig.WATER_DURATIONS[selectedWaterDurationIndex]
            waterDurationButton.message = Text.literal(AgriConfig.formatDuration(newDuration))
        }.dimensions(centerX + 10, currentY, 70, fieldHeight).build()
        addDrawableChild(waterDurationButton)

        currentY += fieldHeight + SECTION_SPACING + 5

        // === Section Stations (scrollable) ===
        stationsStartY = currentY + LABEL_HEIGHT + LABEL_FIELD_GAP + 5
        stationFields.clear()

        // Calculer le nombre de stations visibles
        val bottomMargin = 55
        val availableHeight = height - stationsStartY - bottomMargin
        visibleStations = maxOf(1, minOf(8, availableHeight / fieldSpacing))

        // Creer les 30 champs de stations
        for (i in 0 until 30) {
            val field = TextFieldWidget(
                textRenderer,
                centerX - FIELD_WIDTH_LARGE / 2,
                stationsStartY + (i * fieldSpacing),
                FIELD_WIDTH_LARGE,
                fieldHeight,
                Text.literal("Station ${i + 1}")
            )
            field.text = config.stations.getOrElse(i) { "" }
            field.setMaxLength(30)
            stationFields.add(field)
        }

        updateVisibleFields()

        // === Boutons bas ===
        val buttonY = height - 28

        addDrawableChild(ButtonWidget.builder(Text.literal("Sauvegarder")) { _ ->
            saveConfig()
            close()
        }.dimensions(centerX - 105, buttonY, 100, 20).build())

        addDrawableChild(ButtonWidget.builder(Text.literal("Annuler")) { _ ->
            close()
        }.dimensions(centerX + 5, buttonY, 100, 20).build())
    }

    private fun updateVisibleFields() {
        stationFields.forEach { remove(it) }

        for (i in 0 until visibleStations) {
            val stationIndex = scrollOffset + i
            if (stationIndex < 30) {
                val field = stationFields[stationIndex]
                field.y = stationsStartY + (i * fieldSpacing)
                addDrawableChild(field)
            }
        }
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fillGradient(0, 0, width, height, 0xC0101010.toInt(), 0xD0101010.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val centerX = width / 2

        // === Titre principal ===
        context.drawCenteredTextWithShadow(textRenderer, title, centerX, 8, 0xFFFFFF)

        // === Labels en-tete ===
        context.drawTextWithShadow(textRenderer, "Chat:", 10, 5, 0x55FF55)
        context.drawTextWithShadow(textRenderer, "Tests:", width - 110, 5, 0xFF5555)

        // === Section Plante ===
        var labelY = 45
        context.drawTextWithShadow(textRenderer, "Plante:", centerX - 160, labelY, 0xFFFF55)
        context.drawTextWithShadow(textRenderer, "Boost %:", centerX + 20, labelY, 0xFFFF55)

        // Nom de la plante (centre entre les fleches)
        val selectedPlant = plantNames[selectedPlantIndex]
        val plantDisplayX = centerX - 160 + 20 + (110 - textRenderer.getWidth(selectedPlant)) / 2
        context.drawTextWithShadow(textRenderer, selectedPlant, plantDisplayX, labelY + LABEL_HEIGHT + LABEL_FIELD_GAP + 5, 0xFFFFFF)

        // Temps de croissance calcule
        val plantData = Plants.get(selectedPlant)
        val boost = boostField.text.toFloatOrNull() ?: 0f
        if (plantData != null) {
            val temps = plantData.tempsTotalCroissance(boost)
            val tempsStr = Plants.formatTemps(temps)
            context.drawTextWithShadow(textRenderer, "= $tempsStr", centerX + 80, labelY + LABEL_HEIGHT + LABEL_FIELD_GAP + 5, 0x55FF55)
        }

        // Message d'avertissement si bot actif
        if (isBotActive) {
            context.drawCenteredTextWithShadow(textRenderer, "(Bot actif - arretez le bot pour changer de plante)", centerX, labelY + fieldHeight + LABEL_HEIGHT + LABEL_FIELD_GAP + 8, 0xFF5555)
        }

        // === Section Coffres ===
        labelY = 45 + fieldHeight + SECTION_SPACING + LABEL_HEIGHT + LABEL_FIELD_GAP
        val totalCoffreWidth = 3 * FIELD_WIDTH + 2 * 15
        val coffreStartX = centerX - totalCoffreWidth / 2

        context.drawTextWithShadow(textRenderer, "Coffre:", coffreStartX, labelY, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer, "Backup:", coffreStartX + FIELD_WIDTH + 15, labelY, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer, "Graines:", coffreStartX + 2 * (FIELD_WIDTH + 15), labelY, 0xAAAAAA)

        // === Section Connexion ===
        labelY += fieldHeight + SECTION_SPACING + LABEL_HEIGHT + LABEL_FIELD_GAP
        val connexionFieldWidth = 140
        val connexionGap = 20
        val connexionStartX = centerX - connexionFieldWidth - connexionGap / 2

        context.drawTextWithShadow(textRenderer, "Home mouvement:", connexionStartX, labelY, 0xAAAAAA)
        context.drawTextWithShadow(textRenderer, "Mot de passe:", centerX + connexionGap / 2, labelY, 0xAAAAAA)

        // === Section Duree eau ===
        labelY += fieldHeight + SECTION_SPACING + LABEL_HEIGHT + LABEL_FIELD_GAP
        context.drawTextWithShadow(textRenderer, "Duree eau stations:", centerX - 100, labelY + 5, 0xAAAAAA)

        // === Section Stations ===
        val stationsTitleY = stationsStartY - LABEL_HEIGHT - 5
        context.drawTextWithShadow(textRenderer, "Stations", centerX - FIELD_WIDTH_LARGE / 2, stationsTitleY, 0xFFFF55)

        // Indicateur de scroll
        if (scrollOffset > 0 || scrollOffset + visibleStations < 30) {
            val scrollInfo = "(${scrollOffset + 1}-${scrollOffset + visibleStations}/30)"
            context.drawTextWithShadow(textRenderer, scrollInfo, centerX + FIELD_WIDTH_LARGE / 2 - 50, stationsTitleY, 0x888888)
        }

        // Numeros des stations
        for (i in 0 until visibleStations) {
            val stationIndex = scrollOffset + i
            if (stationIndex < 30) {
                val y = stationsStartY + (i * fieldSpacing) + 5
                val label = "${stationIndex + 1}."
                context.drawTextWithShadow(textRenderer, label, centerX - FIELD_WIDTH_LARGE / 2 - 25, y, 0xAAAAAA)
            }
        }

        // === Info stations actives ===
        val activeCount = stationFields.count { it.text.isNotBlank() }
        context.drawCenteredTextWithShadow(textRenderer, "Stations actives: $activeCount/30", centerX, height - 48, 0x888888)

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val newOffset = scrollOffset - verticalAmount.toInt()
        scrollOffset = newOffset.coerceIn(0, 30 - visibleStations)
        updateVisibleFields()
        return true
    }

    private fun saveConfig() {
        val config = AgriBotClient.config

        config.selectedPlant = plantNames[selectedPlantIndex]
        config.growthBoost = boostField.text.toFloatOrNull() ?: 29f

        config.homeCoffre = coffreField.text
        config.homeBackup = backupField.text
        config.homeGraines = grainesField.text
        config.homeMouvement = mouvementField.text

        config.loginPassword = passwordField.text

        config.waterDurationMinutes = AgriConfig.WATER_DURATIONS[selectedWaterDurationIndex]

        for (i in 0 until 30) {
            config.setStation(i, stationFields[i].text)
        }

        config.save()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }
}
