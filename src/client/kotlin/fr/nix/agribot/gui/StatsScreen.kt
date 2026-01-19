package fr.nix.agribot.gui

import fr.nix.agribot.AgriBotClient
import fr.nix.agribot.config.PlantEconomics
import fr.nix.agribot.config.Plants
import fr.nix.agribot.config.StatsConfig
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Ecran d'affichage des statistiques du bot agricole.
 * Affiche les statistiques globales et par plante.
 */
class StatsScreen(private val parent: Screen?) : Screen(Text.literal("AgriBot - Statistiques")) {

    private var scrollOffset = 0
    private var maxScrollOffset = 0
    private val lineHeight = 12

    override fun init() {
        super.init()

        val centerX = width / 2
        val buttonY = height - 30

        // Bouton Reinitialiser
        addDrawableChild(ButtonWidget.builder(Text.literal("Reinitialiser")) { _ ->
            StatsConfig.get().reset()
        }.dimensions(centerX - 105, buttonY, 100, 20).build())

        // Bouton Fermer
        addDrawableChild(ButtonWidget.builder(Text.literal("Fermer")) { _ ->
            close()
        }.dimensions(centerX + 5, buttonY, 100, 20).build())

        // Calculer le scroll maximum
        val stats = StatsConfig.get()
        val plantCount = stats.plantStats.size
        val totalLines = 15 + (plantCount * 2)  // Estimation des lignes (1 ligne par plante)
        val visibleLines = (height - 100) / lineHeight
        maxScrollOffset = maxOf(0, totalLines - visibleLines)
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        context.fillGradient(0, 0, width, height, 0xC0101010.toInt(), 0xD0101010.toInt())
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val centerX = width / 2
        val stats = StatsConfig.get()
        val config = AgriBotClient.config
        val selectedPlant = config.selectedPlant

        // Titre
        context.drawCenteredTextWithShadow(textRenderer, title, centerX, 10, 0xFFFFFF)

        // Zone de contenu scrollable
        var currentY = 30 - (scrollOffset * lineHeight)

        // === Section Statistiques Globales ===
        if (currentY > 15 && currentY < height - 60) {
            context.drawTextWithShadow(textRenderer, "--- Statistiques Globales ---", centerX - 100, currentY, 0xFFFF55)
        }
        currentY += lineHeight + 4

        // Sessions totales
        if (currentY > 15 && currentY < height - 60) {
            context.drawTextWithShadow(textRenderer, "Sessions completes:", centerX - 100, currentY, 0xAAAAAA)
            context.drawTextWithShadow(textRenderer, stats.formatNumber(stats.totalSessions), centerX + 60, currentY, 0xFFFFFF)
        }
        currentY += lineHeight

        // Stations completees
        if (currentY > 15 && currentY < height - 60) {
            context.drawTextWithShadow(textRenderer, "Stations traitees:", centerX - 100, currentY, 0xAAAAAA)
            context.drawTextWithShadow(textRenderer, stats.formatNumber(stats.totalStationsCompleted), centerX + 60, currentY, 0xFFFFFF)
        }
        currentY += lineHeight + 4

        // Revenu total
        if (currentY > 15 && currentY < height - 60) {
            context.drawTextWithShadow(textRenderer, "Revenu total:", centerX - 100, currentY, 0xAAAAAA)
            context.drawTextWithShadow(textRenderer, "${stats.formatNumber(stats.totalRevenue)} $", centerX + 60, currentY, 0x55FF55)
        }
        currentY += lineHeight

        // Cout total
        if (currentY > 15 && currentY < height - 60) {
            context.drawTextWithShadow(textRenderer, "Cout graines:", centerX - 100, currentY, 0xAAAAAA)
            context.drawTextWithShadow(textRenderer, "-${stats.formatNumber(stats.totalCost)} $", centerX + 60, currentY, 0xFF5555)
        }
        currentY += lineHeight

        // Profit total
        if (currentY > 15 && currentY < height - 60) {
            val profitColor = if (stats.totalProfit >= 0) 0x55FF55 else 0xFF5555
            context.drawTextWithShadow(textRenderer, "Profit net:", centerX - 100, currentY, 0xAAAAAA)
            context.drawTextWithShadow(textRenderer, "${stats.formatNumber(stats.totalProfit)} $", centerX + 60, currentY, profitColor)
        }
        currentY += lineHeight + 10

        // === Section Plante Actuelle ===
        if (currentY > 15 && currentY < height - 60) {
            context.drawTextWithShadow(textRenderer, "--- Plante Actuelle: $selectedPlant ---", centerX - 100, currentY, 0x55FFFF)
        }
        currentY += lineHeight + 4

        val plantEconomics = PlantEconomics.get(selectedPlant)
        val plantStats = stats.plantStats[selectedPlant]

        if (plantEconomics != null) {
            // Info economique de la plante
            if (currentY > 15 && currentY < height - 60) {
                context.drawTextWithShadow(textRenderer, "Cout graine/station:", centerX - 100, currentY, 0x888888)
                context.drawTextWithShadow(textRenderer, "${plantEconomics.cost} $", centerX + 70, currentY, 0xAAAAAA)
            }
            currentY += lineHeight

            if (currentY > 15 && currentY < height - 60) {
                context.drawTextWithShadow(textRenderer, "Revenu/station:", centerX - 100, currentY, 0x888888)
                context.drawTextWithShadow(textRenderer, "${plantEconomics.revenue} $", centerX + 70, currentY, 0xAAAAAA)
            }
            currentY += lineHeight

            if (currentY > 15 && currentY < height - 60) {
                val profitColor = if (plantEconomics.profit >= 0) 0x55FF55 else 0xFF5555
                context.drawTextWithShadow(textRenderer, "Profit/station:", centerX - 100, currentY, 0x888888)
                context.drawTextWithShadow(textRenderer, "${plantEconomics.profit} $", centerX + 70, currentY, profitColor)
            }
            currentY += lineHeight + 4
        }

        // Stats pour cette plante
        if (plantStats != null) {
            if (currentY > 15 && currentY < height - 60) {
                context.drawTextWithShadow(textRenderer, "Sessions avec $selectedPlant:", centerX - 100, currentY, 0xAAAAAA)
                context.drawTextWithShadow(textRenderer, stats.formatNumber(plantStats.sessions), centerX + 80, currentY, 0xFFFFFF)
            }
            currentY += lineHeight

            if (currentY > 15 && currentY < height - 60) {
                context.drawTextWithShadow(textRenderer, "Stations avec $selectedPlant:", centerX - 100, currentY, 0xAAAAAA)
                context.drawTextWithShadow(textRenderer, stats.formatNumber(plantStats.stations), centerX + 80, currentY, 0xFFFFFF)
            }
            currentY += lineHeight

            if (currentY > 15 && currentY < height - 60) {
                val profitColor = if (plantStats.profit >= 0) 0x55FF55 else 0xFF5555
                context.drawTextWithShadow(textRenderer, "Profit $selectedPlant:", centerX - 100, currentY, 0xAAAAAA)
                context.drawTextWithShadow(textRenderer, "${stats.formatNumber(plantStats.profit)} $", centerX + 80, currentY, profitColor)
            }
            currentY += lineHeight + 10
        } else {
            if (currentY > 15 && currentY < height - 60) {
                context.drawTextWithShadow(textRenderer, "Aucune statistique pour $selectedPlant", centerX - 100, currentY, 0x888888)
            }
            currentY += lineHeight + 10
        }

        // === Section Historique par Plante ===
        if (stats.plantStats.isNotEmpty()) {
            if (currentY > 15 && currentY < height - 60) {
                context.drawTextWithShadow(textRenderer, "--- Historique par Plante ---", centerX - 100, currentY, 0xFF55FF)
            }
            currentY += lineHeight + 4

            // Trier par profit decroissant
            val sortedPlants = stats.plantStats.entries.sortedByDescending { it.value.profit }

            for ((plantName, pStats) in sortedPlants) {
                if (currentY > 15 && currentY < height - 60) {
                    val profitColor = if (pStats.profit >= 0) 0x55FF55 else 0xFF5555
                    // Format: Plante Xs/Yst / profit $
                    val line = "$plantName ${pStats.sessions}s/${pStats.stations}st / ${stats.formatNumber(pStats.profit)} $"
                    context.drawTextWithShadow(textRenderer, line, centerX - 100, currentY, profitColor)
                }
                currentY += lineHeight + 2
            }
        }

        // Indicateur de scroll si necessaire
        if (maxScrollOffset > 0) {
            val scrollPercent = if (maxScrollOffset > 0) scrollOffset.toFloat() / maxScrollOffset else 0f
            val scrollBarHeight = 50
            val scrollBarY = 30 + ((height - 100 - scrollBarHeight) * scrollPercent).toInt()

            // Barre de scroll
            context.fill(width - 8, 30, width - 4, height - 60, 0x40FFFFFF)
            context.fill(width - 7, scrollBarY, width - 5, scrollBarY + scrollBarHeight, 0xFFAAAAAA.toInt())

            // Instructions de scroll
            context.drawTextWithShadow(textRenderer, "Scroll: molette", width - 90, height - 55, 0x666666)
        }

        super.render(context, mouseX, mouseY, delta)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val newOffset = scrollOffset - verticalAmount.toInt()
        scrollOffset = newOffset.coerceIn(0, maxScrollOffset)
        return true
    }

    override fun close() {
        client?.setScreen(parent)
    }

    override fun shouldPause(): Boolean = false
}
