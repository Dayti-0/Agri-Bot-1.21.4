package fr.nix.agribot.mixin.client;

import fr.nix.agribot.AgriBotClient;
import fr.nix.agribot.bot.AutoStartManager;
import fr.nix.agribot.bot.BotCore;
import fr.nix.agribot.bot.BotState;
import fr.nix.agribot.config.AgriConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin pour ajouter un bouton AgriBot sur l'ecran multijoueur.
 */
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void renderSessionTimer(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        // Afficher le temps restant avant la prochaine session si le bot est en pause
        BotState currentState = BotCore.INSTANCE.getStateData().getState();

        if (currentState == BotState.PAUSED) {
            long pauseEndTime = BotCore.INSTANCE.getStateData().getPauseEndTime();
            long currentTime = System.currentTimeMillis();
            long remainingMs = pauseEndTime - currentTime;

            if (remainingMs > 0) {
                // Formater le temps restant
                long totalSeconds = remainingMs / 1000;
                long hours = totalSeconds / 3600;
                long minutes = (totalSeconds % 3600) / 60;
                long seconds = totalSeconds % 60;

                String timeText;
                if (hours > 0) {
                    timeText = String.format("Prochaine session: %dh %02dm %02ds", hours, minutes, seconds);
                } else if (minutes > 0) {
                    timeText = String.format("Prochaine session: %dm %02ds", minutes, seconds);
                } else {
                    timeText = String.format("Prochaine session: %ds", seconds);
                }

                // Dessiner le texte en haut a gauche avec un fond semi-transparent
                MinecraftClient client = MinecraftClient.getInstance();
                int textWidth = client.textRenderer.getWidth(timeText);

                // Fond semi-transparent
                context.fill(8, 8, 16 + textWidth, 22, 0x80000000);

                // Texte en vert
                context.drawText(client.textRenderer, timeText, 12, 12, 0x55FF55, true);
            }
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addAgriBotButton(CallbackInfo ci) {
        // Ajouter le bouton AgriBot en haut a droite
        int buttonWidth = 100;
        int buttonHeight = 20;
        int x = this.width - buttonWidth - 10;
        int y = 10;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("AgriBot"), button -> {
            connectAndStartBot();
        }).dimensions(x, y, buttonWidth, buttonHeight).build());
    }

    private void connectAndStartBot() {
        MinecraftClient client = MinecraftClient.getInstance();
        AgriConfig config = AgriBotClient.INSTANCE.getConfig();

        // Verifier que le mot de passe est configure
        if (config.getLoginPassword().isEmpty()) {
            // Afficher un message d'erreur (le bouton ne fait rien sans mot de passe)
            return;
        }

        String serverAddress = config.getServerAddress();

        // Creer les infos du serveur
        ServerInfo serverInfo = new ServerInfo(
            "SurvivalWorld",
            serverAddress,
            ServerInfo.ServerType.OTHER
        );

        // Parser l'adresse
        ServerAddress address = ServerAddress.parse(serverAddress);

        // Activer le demarrage automatique du bot
        AutoStartManager.INSTANCE.setAutoStartEnabled(true);

        // Se connecter au serveur
        ConnectScreen.connect(
            this,
            client,
            address,
            serverInfo,
            false,
            null
        );
    }
}
