package dev.lyc.frameledger.mixin;

import dev.lyc.frameledger.cull.BlockEntityCuller;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the frustum actually used to cull terrain, so block-entity culling tests against
 * exactly the same volume the rest of the world is tested against.
 *
 * <p>Taking the frustum from {@code setupTerrain} rather than rebuilding one from the camera
 * guarantees the two are consistent: a block entity cannot be culled by a marginally different
 * projection than the chunk containing it, which would show up as entities vanishing at the screen
 * edge while their terrain remains.</p>
 */
@Mixin(WorldRenderer.class)
public abstract class FrustumCaptureMixin {

    @Inject(
            method = "setupTerrain(Lnet/minecraft/client/render/Camera;"
                    + "Lnet/minecraft/client/render/Frustum;ZZ)V",
            at = @At("HEAD")
    )
    private void frameledger$captureFrustum(Camera camera, Frustum frustum,
                                            boolean spectator, boolean debug,
                                            CallbackInfo ci) {
        // setupTerrain is handed the frustum the terrain pass will use, already positioned at the
        // camera. Reusing it guarantees block entities are tested against exactly the same volume.
        BlockEntityCuller.updateView(frustum);
    }
}
