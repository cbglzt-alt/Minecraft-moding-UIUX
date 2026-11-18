package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.measure.FrameTimer;
import dev.lyc.frameledger.measure.Pass;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundaries plus the world passes we attribute time to.
 *
 * <p>Only timing calls are added: two per frame boundary, two per instrumented pass. Nothing here
 * changes what is rendered.</p>
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

    private static final String RENDER_CLOUDS =
            "renderClouds(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;"
            + "Lorg/joml/Matrix4f;FDDD)V";

    private static final String RENDER_SKY =
            "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/render/Camera;"
            + "ZLjava/lang/Runnable;)V";

    @Inject(method = RENDER, at = @At("HEAD"))
    private void frameledger$beginFrame(CallbackInfo ci) {
        FrameTimer.get().beginFrame();
    }

    @Inject(method = RENDER, at = @At("RETURN"))
    private void frameledger$endFrame(CallbackInfo ci) {
        FrameTimer.get().endFrame();
    }

    /**
     * Terrain. Vanilla calls this once per chunk layer (solid, cutout, translucent) back to back,
     * so the span opens on the first layer and closes after the last.
     */
    @Inject(method = RENDER_LAYER, at = @At("HEAD"))
    private void frameledger$terrainStart(RenderLayer layer, double x, double y, double z,
                                          Matrix4f positionMatrix, Matrix4f projectionMatrix,
                                          CallbackInfo ci) {
        FrameTimer.get().beginSpan(Pass.TERRAIN);
    }

    @Inject(method = RENDER_LAYER, at = @At("RETURN"))
    private void frameledger$terrainEnd(RenderLayer layer, double x, double y, double z,
                                        Matrix4f positionMatrix, Matrix4f projectionMatrix,
                                        CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.TERRAIN);
    }

    @Inject(method = RENDER_CLOUDS, at = @At("HEAD"))
    private void frameledger$cloudsStart(CallbackInfo ci) {
        FrameTimer.get().beginSpan(Pass.CLOUDS);
    }

    @Inject(method = RENDER_CLOUDS, at = @At("RETURN"))
    private void frameledger$cloudsEnd(CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.CLOUDS);
    }

    @Inject(method = RENDER_SKY, at = @At("HEAD"))
    private void frameledger$skyStart(CallbackInfo ci) {
        FrameTimer.get().beginSpan(Pass.SKY);
    }

    @Inject(method = RENDER_SKY, at = @At("RETURN"))
    private void frameledger$skyEnd(CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.SKY);
    }
}
