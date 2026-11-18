package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.measure.FrameTimer;
import dev.lyc.frameledger.measure.Pass;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity pass.
 *
 * <p>Vanilla has no single "render all entities" method to bracket, so the span is opened and
 * closed around each individual entity draw instead. {@link FrameTimer#beginSpan} keeps the first
 * start of a frame and {@link FrameTimer#endSpan} keeps the last end, so consecutive entity draws
 * accumulate into one span.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(
            method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD")
    )
    private void frameledger$entityStart(Entity entity, double x, double y, double z,
                                         float yaw, float tickDelta, MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers, int light,
                                         CallbackInfo ci) {
        FrameTimer.get().beginSpan(Pass.ENTITIES);
    }

    @Inject(
            method = "render(Lnet/minecraft/entity/Entity;DDDFFLnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("RETURN")
    )
    private void frameledger$entityEnd(Entity entity, double x, double y, double z,
                                       float yaw, float tickDelta, MatrixStack matrices,
                                       VertexConsumerProvider vertexConsumers, int light,
                                       CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.ENTITIES);
    }
}
