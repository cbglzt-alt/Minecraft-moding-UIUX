package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.LedgerState;
import dev.lyc.frameledger.gui.LedgerHud;
import dev.lyc.frameledger.gui.SweepHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the ledger over the HUD.
 *
 * <p>Injected at {@code TAIL} of the HUD render so we are the last thing drawn and cannot be
 * covered by vanilla widgets. This runs in the GUI pass, so it never perturbs the world spans
 * the ledger reports.</p>
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    /** Rough height the ledger block occupies, so the sweep panel can sit below it. */
    private static final int LEDGER_BLOCK_HEIGHT = 96;

    @Inject(
            method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("TAIL")
    )
    private void frameledger$drawLedger(DrawContext context, RenderTickCounter tickCounter,
                                        CallbackInfo ci) {
        if (!LedgerState.isEnabled()) {
            return;
        }
        LedgerHud.render(context, LedgerState.isExpanded());
        SweepHud.render(context, 4 + LEDGER_BLOCK_HEIGHT);
    }
}
