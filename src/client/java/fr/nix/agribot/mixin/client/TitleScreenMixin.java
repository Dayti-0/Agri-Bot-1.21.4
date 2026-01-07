package fr.nix.agribot.mixin.client;

import fr.nix.agribot.AgriBotClient;
import fr.nix.agribot.bot.AutoStartManager;
import fr.nix.agribot.config.AgriConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin pour ajouter un bouton AgriBot sur l'ecran titre.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addAgriBotButton(CallbackInfo ci) {
        // Ajouter le bouton AgriBot en bas a gauche
        int buttonWidth = 100;
        int buttonHeight = 20;
        int x = 10;
        int y = this.height - 30;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("AgriBot"), button -> {
            connectAndStartBot();
        }).dimensions(x, y, buttonWidth, buttonHeight).build());
    }

    private void connectAndStartBot() {
        MinecraftClient client = MinecraftClient.getInstance();
        AgriConfig config = AgriBotClient.Companion.getConfig();

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
