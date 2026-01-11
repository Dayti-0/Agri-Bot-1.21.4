package fr.nix.agribot.gui

import fr.nix.agribot.chat.AutoResponseManager
import fr.nix.agribot.config.AutoResponseConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

/**
 * Ecran de configuration du systeme de reponse automatique.
 */
class AutoResponseScreen(private val parent: Screen?) : Screen(Text.literal("AgriBot - Reponse Automatique")) {

    private lateinit var apiKeyField: TextFieldWidget
    private lateinit var usernameField: TextFieldWidget
    private lateinit var windowDurationField: TextFieldWidget
    private lateinit var enabledButton: ButtonWidget
    private lateinit var testModeButton: ButtonWidget
    private lateinit var damnFriendsField: TextFieldWidget

    private var isEnabled: Boolean = true
    private var isTestMode: Boolean = false

    override fun init() {
        super.init()

        val config = AutoResponseConfig.get()
        val centerX = width / 2
        var currentY = 40

        isEnabled = config.enabled
        isTestMode = config.testModeActive

        // === Section API Key ===
        val apiKeyLabel = "Cle API Mistral:"
        apiKeyField = TextFieldWidget(textRenderer, centerX - 150, currentY + 12, 300, 20, Text.literal("API Key"))
        apiKeyField.text = config.mistralApiKey
        apiKeyField.setMaxLength(100)
        addDrawableChild(apiKeyField)
        currentY += 45

        // === Section Pseudo ===
        usernameField = TextFieldWidget(textRenderer, centerX - 150, currentY + 12, 200, 20, Text.literal("Username"))
        usernameField.text = config.playerUsername.ifEmpty {
            MinecraftClient.getInstance().session?.username ?: ""
        }
        usernameField.setMaxLength(30)
        addDrawableChild(usernameField)
        currentY += 45

        // === Section Duree fenetre ===
        windowDurationField = TextFieldWidget(textRenderer, centerX - 150, currentY + 12, 60, 20, Text.literal("Duration"))
        windowDurationField.text = config.detectionWindowSeconds.toString()
        windowDurationField.setMaxLength(5)
        addDrawableChild(windowDurationField)
        currentY += 45

        // === Section Amis "damn" ===
        damnFriendsField = TextFieldWidget(textRenderer, centerX - 150, currentY + 12, 300, 20, Text.literal("Damn Friends"))
        damnFriendsField.text = config.damnFriends.joinToString(", ")
        damnFriendsField.setMaxLength(200)
        addDrawableChild(damnFriendsField)
        currentY += 55

        // === Bouton Activer/Desactiver ===
        enabledButton = ButtonWidget.builder(Text.literal(if (isEnabled) "Active" else "Desactive")) { _ ->
            isEnabled = !isEnabled
            updateEnabledButtonText()
        }.dimensions(centerX - 150, currentY, 145, 20).build()
        addDrawableChild(enabledButton)

        // === Bouton Mode Test ===
        testModeButton = ButtonWidget.builder(Text.literal(if (isTestMode) "Test: ON" else "Test: OFF")) { _ ->
            isTestMode = !isTestMode
            updateTestModeButtonText()
        }.dimensions(centerX + 5, currentY, 145, 20).build()
        addDrawableChild(testModeButton)
        currentY += 50

        // === Info Mode Test ===
        // (affiche dans render())

        // === Boutons bas de page ===
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
    }

    private fun updateEnabledButtonText() {
        enabledButton.message = Text.literal(if (isEnabled) "Active" else "Desactive")
    }

    private fun updateTestModeButtonText() {
        testModeButton.message = Text.literal(if (isTestMode) "Test: ON" else "Test: OFF")
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fillGradient(0, 0, width, height, 0xC0101010.toInt(), 0xD0101010.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val centerX = width / 2

        // Titre
        context.drawCenteredTextWithShadow(textRenderer, title, centerX, 15, 0xFFFFFF)

        // Labels
        var currentY = 40
        context.drawTextWithShadow(textRenderer, "Cle API Mistral:", centerX - 150, currentY, 0xAAAAAA)
        currentY += 45

        context.drawTextWithShadow(textRenderer, "Pseudo du joueur:", centerX - 150, currentY, 0xAAAAAA)
        currentY += 45

        context.drawTextWithShadow(textRenderer, "Fenetre de detection (secondes):", centerX - 150, currentY, 0xAAAAAA)
        currentY += 45

        context.drawTextWithShadow(textRenderer, "Amis 'damn neige' (separes par virgule):", centerX - 150, currentY, 0xAAAAAA)
        currentY += 55

        // Labels pour les boutons
        context.drawTextWithShadow(textRenderer, "Systeme:", centerX - 150, currentY - 12, 0x888888)
        context.drawTextWithShadow(textRenderer, "Mode test:", centerX + 5, currentY - 12, 0x888888)
        currentY += 50

        // Info mode test
        if (isTestMode) {
            context.drawCenteredTextWithShadow(
                textRenderer,
                "Mode test actif: tapez 'msg:votre message' dans le chat",
                centerX,
                currentY,
                0xFFFF55
            )
            currentY += 12
            context.drawCenteredTextWithShadow(
                textRenderer,
                "pour tester la detection sans envoyer de reponse.",
                centerX,
                currentY,
                0xFFFF55
            )
        }

        // Info temps restant si fenetre active
        if (AutoResponseManager.isInDetectionWindow()) {
            val remaining = AutoResponseManager.getRemainingDetectionTime()
            context.drawCenteredTextWithShadow(
                textRenderer,
                "Fenetre de detection active: ${remaining}s restantes",
                centerX,
                height - 60,
                0x55FF55
            )
        }

        // Statut API
        val apiStatus = if (apiKeyField.text.isNotBlank()) {
            Pair("API configuree", 0x55FF55)
        } else {
            Pair("API non configuree", 0xFF5555)
        }
        context.drawCenteredTextWithShadow(textRenderer, apiStatus.first, centerX, height - 48, apiStatus.second)

        super.render(context, mouseX, mouseY, delta)
    }

    private fun saveConfig() {
        val config = AutoResponseConfig.get()

        config.mistralApiKey = apiKeyField.text.trim()
        config.playerUsername = usernameField.text.trim()
        config.detectionWindowSeconds = windowDurationField.text.toIntOrNull() ?: 120
        config.enabled = isEnabled

        // Parser les amis damn
        config.damnFriends.clear()
        damnFriendsField.text.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { config.damnFriends.add(it) }

        // Activer/desactiver le mode test via le manager
        if (isTestMode != config.testModeActive) {
            AutoResponseManager.setTestMode(isTestMode)
        }

        config.save()
    }

    override fun close() {
        client?.setScreen(parent)
    }

    override fun shouldPause(): Boolean = false
}
