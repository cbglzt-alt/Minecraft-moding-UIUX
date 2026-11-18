package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.measure.GpuFrameTimer;
import dev.lyc.frameledger.measure.Pass;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundaries and the terrain span.
 *
 * <p>Only {@code glQueryCounter} calls are emitted here, so the added work on the hot path is two
 * queries per frame plus two per chunk layer.</p>
 */
@Mixin(WorldRenderer.class)
public abstract class LevelRendererMixin {

    private static final String RENDER =
            "render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;"
            + "Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";

    private static final String RENDER_LAYER =
            "renderLayer(Lnet/minecraft/client/render/RenderLayer;DDD"
            + "Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void frameledger$beginFrame(CallbackInfo ci) {
        GpuFrameTimer.get().beginFrame();
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void frameledger$endFrame(CallbackInfo ci) {
        GpuFrameTimer.get().endFrame();
    }

    /**
     * Terrain. Vanilla calls this once per chunk layer (solid, cutout, translucent) back to back,
     * so the span opens on the first layer and closes after the last one.
     */
    @Inject(method = RENDER_LAYER, at = @At("HEAD"))
    private void frameledger$terrainStart(RenderLayer layer, double x, double y, double z,
                                          Matrix4f positionMatrix, Matrix4f projectionMatrix,
                                          CallbackInfo ci) {
        GpuFrameTimer.get().beginSpan(Pass.TERRAIN);
    }

    @Inject(method = RENDER_LAYER, at = @At("RETURN"))
    private void frameledger$terrainEnd(RenderLayer layer, double x, double y, double z,
                                        Matrix4f positionMatrix, Matrix4f projectionMatrix,
                                        CallbackInfo ci) {
        GpuFrameTimer.get().endSpan(Pass.TERRAIN);
    }
}
