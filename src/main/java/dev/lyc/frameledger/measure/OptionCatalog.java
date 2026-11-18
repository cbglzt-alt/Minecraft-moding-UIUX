package dev.lyc.frameledger.measure;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The vanilla options worth measuring, and how to step each one down.
 *
 * <p><b>Selection rule: only options that take effect immediately.</b> An option that needs a
 * renderer or asset reload cannot be A/B'd frame-by-frame — flipping it mid-sweep would either do
 * nothing (so the sweep measures noise and reports "zero cost", which is wrong) or trigger a
 * reload hitch that lands in whichever arm happened to be active. Both failure modes produce a
 * confidently wrong number, so those options are excluded rather than measured badly.</p>
 *
 * <p>Each entry pairs an option with the pass most likely to carry its cost. Watching the right
 * pass is what makes the difference resolvable: changing particles and watching terrain would
 * bury the effect in unrelated noise.</p>
 */
public final class OptionCatalog {

    private OptionCatalog() {
    }

    /**
     * Builds the sweeps that are currently possible. Options already at their minimum are skipped,
     * because there is nothing to step down to.
     *
     * <p><b>Every entry here was verified to not reload the renderer or resources.</b> This was
     * checked against the bytecode rather than assumed: {@code ao} and {@code biomeBlendRadius}
     * look like ideal candidates but their change callbacks invoke {@code WorldRenderer.reload()},
     * so they are excluded. Measuring them frame-by-frame would fold a multi-frame reload hitch
     * into whichever arm was active and produce a confidently wrong number.</p>
     */
    public static List<Sweep<?>> build(GameOptions options) {
        List<Sweep<?>> sweeps = new ArrayList<>();

        add(sweeps, Sweep.stepDown(options.getParticles(), "Particles", Pass.PARTICLES,
                OptionCatalog::stepDownParticles));

        add(sweeps, Sweep.stepDown(options.getCloudRenderMode(), "Clouds", Pass.CLOUDS,
                OptionCatalog::stepDownClouds));

        add(sweeps, Sweep.stepDown(options.getEntityShadows(), "Entity shadows", Pass.ENTITIES,
                current -> !current));

        add(sweeps, Sweep.stepDown(options.getEntityDistanceScaling(), "Entity distance",
                Pass.ENTITIES,
                current -> Math.max(0.5, Math.round((current - 0.25) * 100.0) / 100.0)));

        add(sweeps, Sweep.stepDown(options.getMipmapLevels(), "Mipmap levels", Pass.TERRAIN,
                current -> Math.max(0, current - 1)));

        return sweeps;
    }

    private static ParticlesMode stepDownParticles(ParticlesMode current) {
        return switch (current) {
            case ALL -> ParticlesMode.DECREASED;
            case DECREASED -> ParticlesMode.MINIMAL;
            case MINIMAL -> ParticlesMode.MINIMAL;
        };
    }

    private static CloudRenderMode stepDownClouds(CloudRenderMode current) {
        return switch (current) {
            case FANCY -> CloudRenderMode.FAST;
            case FAST -> CloudRenderMode.OFF;
            case OFF -> CloudRenderMode.OFF;
        };
    }

    /**
     * Graphics mode is reported but not swept in this version: switching it reconfigures several
     * sub-options at once, so a single A/B difference could not be attributed to any one of them.
     */
    public static String describe(GraphicsMode mode) {
        return mode.name();
    }

    public static GameOptions options() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? null : client.options;
    }

    private static void add(List<Sweep<?>> target, Sweep<?> sweep) {
        if (sweep != null) {
            target.add(sweep);
        }
    }
}
