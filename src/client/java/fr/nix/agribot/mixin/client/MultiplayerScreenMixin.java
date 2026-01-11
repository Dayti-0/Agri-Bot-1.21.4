package fr.nix.agribot.mixin.client;

import fr.nix.agribot.AgriBotClient;
import fr.nix.agribot.bot.PreConnectionTimer;
import fr.nix.agribot.config.AgriConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin pour ajouter un bouton AgriBot sur l'ecran multijoueur.
 */
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    @Unique
    private ButtonWidget timerButton;

    protected MultiplayerScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addAgriBotButton(CallbackInfo ci) {
        AgriConfig config = AgriBotClient.INSTANCE.getConfig();

        // Bouton AgriBot en haut a droite
        int agriBotButtonWidth = 100;
        int buttonHeight = 20;
        int agriBotX = this.width - agriBotButtonWidth - 10;
        int y = 10;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("AgriBot"), button -> {
            connectAndStartBot();
        }).dimensions(agriBotX, y, agriBotButtonWidth, buttonHeight).build());

        // Bouton minuteur a gauche du bouton AgriBot
        int timerButtonWidth = 50;
        int timerX = agriBotX - timerButtonWidth - 5;

        timerButton = ButtonWidget.builder(Text.literal(AgriConfig.Companion.formatStartupDelay(config.getStartupDelayMinutes())), button -> {
            // Ajouter 10 minutes
            int currentDelay = config.getStartupDelayMinutes();
            config.setStartupDelayMinutes(currentDelay + 10);
            config.save();
            timerButton.setMessage(Text.literal(AgriConfig.Companion.formatStartupDelay(config.getStartupDelayMinutes())));
        }).dimensions(timerX, y, timerButtonWidth, buttonHeight).build();
        this.addDrawableChild(timerButton);

        // Bouton reset (0) a gauche du bouton minuteur
        int resetButtonWidth = 20;
        int resetX = timerX - resetButtonWidth - 5;

        this.addDrawableChild(ButtonWidget.builder(Text.literal("0"), button -> {
            config.setStartupDelayMinutes(0);
            config.save();
            timerButton.setMessage(Text.literal(AgriConfig.Companion.formatStartupDelay(0)));
        }).dimensions(resetX, y, resetButtonWidth, buttonHeight).build());
    }

    private void connectAndStartBot() {
        AgriConfig config = AgriBotClient.INSTANCE.getConfig();

        // Verifier que le mot de passe est configure
        if (config.getLoginPassword().isEmpty()) {
            // Afficher un message d'erreur (le bouton ne fait rien sans mot de passe)
            return;
        }

        // Demarrer le timer de pre-connexion (gere le delai et la connexion)
        int delayMinutes = config.getStartupDelayMinutes();
        PreConnectionTimer.INSTANCE.startTimer(delayMinutes, this);
    }
}
