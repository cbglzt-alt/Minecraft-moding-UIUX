package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.cull.BlockEntityCuller;
import dev.lyc.frameledger.measure.FrameTimer;
import dev.lyc.frameledger.measure.Pass;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block-entity pass: times it, and culls it.
 *
 * <p><b>Why this is the right seam.</b> Both the vanilla renderer and Sodium funnel every
 * block-entity draw through this method, so one hook covers both paths. Sodium's own
 * {@code BlockEntityRenderPredicate} extension point was considered and rejected for this: it is
 * evaluated from the chunk meshing task, i.e. at section rebuild time, so a camera-distance or
 * frustum test there goes stale as the player turns. Per-frame visibility has to be decided per
 * frame.</p>
 *
 * <p>{@code cancellable} so a culled entity can skip the whole draw rather than being timed and
 * then discarded. The timer still brackets only what is actually drawn, which is what keeps the
 * measured cost honest.</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(
            method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void frameledger$beforeRender(BlockEntity blockEntity, float tickDelta,
                                          MatrixStack matrices,
                                          VertexConsumerProvider vertexConsumers,
                                          CallbackInfo ci) {
        if (!BlockEntityCuller.shouldRender(blockEntity)) {
            ci.cancel();
            return;
        }
        FrameTimer.get().beginSpan(Pass.BLOCK_ENTITIES);
    }

    @Inject(
            method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("RETURN")
    )
    private void frameledger$afterRender(BlockEntity blockEntity, float tickDelta,
                                         MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers,
                                         CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.BLOCK_ENTITIES);
    }
}
