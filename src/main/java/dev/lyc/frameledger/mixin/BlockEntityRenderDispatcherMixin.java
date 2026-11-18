package dev.lyc.frameledger.mixin;

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
 * Block-entity pass.
 *
 * <p>Same accumulate-into-one-span approach as entities: per-BE draws are bracketed and the timer
 * unions them into a single span per frame.</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(
            method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("HEAD")
    )
    private void frameledger$beStart(BlockEntity blockEntity, float tickDelta,
                                     MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                     CallbackInfo ci) {
        FrameTimer.get().beginSpan(Pass.BLOCK_ENTITIES);
    }

    @Inject(
            method = "render(Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;)V",
            at = @At("RETURN")
    )
    private void frameledger$beEnd(BlockEntity blockEntity, float tickDelta,
                                   MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                   CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.BLOCK_ENTITIES);
    }
}
