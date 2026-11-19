package dev.lyc.frameledger.cull;

import dev.lyc.frameledger.FrameLedgerClient;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Frustum culling for block entities.
 *
 * <p><b>The gap this fills.</b> Vanilla already rejects block entities beyond a spherical distance
 * through {@code BlockEntityRenderer.isInRenderDistance} — a straight {@code isInRange} test, 64
 * blocks by default. It never tests the view frustum. A chest twenty blocks behind the player is
 * drawn every frame: the matrix is pushed, the position translated, the renderer dispatched, and
 * the geometry submitted before anything discards it.</p>
 *
 * <p>That cost is CPU work on the render thread, which is exactly what this mod measures. It also
 * means the saving is real on any renderer, with or without Sodium.</p>
 *
 * <p><b>Why a box, not a point.</b> Testing the block position as a point would pop entities out at
 * the screen edge. The box is the block's full extent, so an entity is kept while any part of its
 * cell is still on screen.</p>
 */
public final class BlockEntityCuller {
    /** Added to the block's own bounds so partially-visible models do not flicker at the edge. */
    private static final double MARGIN = 0.5;

    private static boolean enabled = true;

    /** The frustum from this frame's terrain pass, or null before the first one. */
    private static Frustum frustum;

    /** Counters for A/B measurement, reset each frame so the figure reflects the current view. */
    private static long considered;
    private static long culled;

    /** Snapshot of the previous frame, published at frame start. */
    private static long lastConsidered;
    private static long lastCulled;

    /** Blocks beyond this are already rejected by vanilla before we are consulted. */
    private static final double VANILLA_DISTANCE = 64.0;

    private BlockEntityCuller() {
    }

    /** Called once per frame with the frustum the rest of the world is culled against. */
    public static void updateView(Frustum frameFrustum) {
        frustum = frameFrustum;

        // Publish the completed frame and start a new one. A per-frame figure answers "how much
        // am I saving right now", which is what a player looking at a base full of chests wants;
        // a session total would keep averaging in whatever they looked at ten minutes ago.
        lastConsidered = considered;
        lastCulled = culled;
        considered = 0L;
        culled = 0L;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        resetCounters();
    }

    public static void toggle() {
        enabled = !enabled;
        resetCounters();
    }

    public static void resetCounters() {
        considered = 0L;
        culled = 0L;
    }

    public static long consideredCount() {
        return lastConsidered;
    }

    public static long culledCount() {
        return lastCulled;
    }

    /**
     * Decides whether a block entity should be drawn this frame.
     *
     * <p>Returns {@code true} (keep) whenever anything about the test is unavailable, so a failure
     * of this optimisation can never make the world lose geometry. Skipping a draw is only ever
     * allowed to be a deliberate, provable decision.</p>
     *
     * <p><b>Coordinates.</b> The box is built in world space. {@code Frustum.isVisible(Box)}
     * subtracts the camera position internally (it holds it from {@code setPosition}), so doing
     * that subtraction here as well moves the box twice and reports almost everything as culled.
     * An earlier version made exactly that mistake and culled 42 of 43 block entities, including
     * ones in plain view.</p>
     */
    public static boolean shouldRender(BlockEntity blockEntity) {
        considered++;
        firstCallSeen();

        if (!enabled || frustum == null) {
            return true;
        }

        BlockPos pos = blockEntity.getPos();

        Box box = new Box(
                pos.getX() - MARGIN, pos.getY() - MARGIN, pos.getZ() - MARGIN,
                pos.getX() + 1.0 + MARGIN, pos.getY() + 1.0 + MARGIN, pos.getZ() + 1.0 + MARGIN);

        if (frustum.isVisible(box)) {
            return true;
        }

        culled++;
        return false;
    }

    /** Fraction of considered block entities that were culled last frame, or 0 when none were. */
    public static double culledFraction() {
        return lastConsidered == 0L ? 0.0 : (double) lastCulled / (double) lastConsidered;
    }

    private static boolean firstCall;

    /**
     * Logs once when the culler is first consulted.
     *
     * <p>Distinguishes "the hook is not reached" from "the view genuinely contains no block
     * entities", which otherwise look identical in the counters and mean very different things.
     * The counters read 0/0 in both cases, and only one of them is a bug.</p>
     */
    private static void firstCallSeen() {
        if (firstCall) {
            return;
        }
        firstCall = true;
        FrameLedgerClient.LOGGER.info("Block entity culler active (frustum={})",
                frustum != null ? "ready" : "null");
    }
}
