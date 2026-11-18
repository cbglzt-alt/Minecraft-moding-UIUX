package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.measure.SweepController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels an in-progress sweep when the player opens the pause menu.
 *
 * <p>Opening the menu is the natural "make it stop" gesture, and it also invalidates the
 * measurement: the world stops rendering behind the menu, so the samples would be meaningless.
 * Cancelling here restores the player's settings immediately rather than leaving the world with
 * altered options while the menu is open.</p>
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {

    @Inject(method = "init()V", at = @At("HEAD"))
    private void frameledger$cancelSweepOnPause(CallbackInfo ci) {
        if (SweepController.isRunning()) {
            SweepController.cancel();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(
                        net.minecraft.text.Text.literal("[FrameLedger] Measurement cancelled"),
                        true);
            }
        }
    }
}
