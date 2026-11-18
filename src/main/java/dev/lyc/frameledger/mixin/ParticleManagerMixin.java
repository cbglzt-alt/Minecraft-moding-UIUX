package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.measure.FrameTimer;
import dev.lyc.frameledger.measure.Pass;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Particle pass. */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    @Inject(
            method = "renderParticles(Lnet/minecraft/client/render/LightmapTextureManager;"
                    + "Lnet/minecraft/client/render/Camera;F)V",
            at = @At("HEAD")
    )
    private void frameledger$particlesStart(LightmapTextureManager lightmapTextureManager,
                                            Camera camera, float tickDelta, CallbackInfo ci) {
        FrameTimer.get().beginSpan(Pass.PARTICLES);
    }

    @Inject(
            method = "renderParticles(Lnet/minecraft/client/render/LightmapTextureManager;"
                    + "Lnet/minecraft/client/render/Camera;F)V",
            at = @At("RETURN")
    )
    private void frameledger$particlesEnd(LightmapTextureManager lightmapTextureManager,
                                          Camera camera, float tickDelta, CallbackInfo ci) {
        FrameTimer.get().endSpan(Pass.PARTICLES);
    }
}
