package dev.lyc.frameledger.measure;

import dev.lyc.frameledger.FrameLedgerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives sweeps, one at a time, from client ticks.
 *
 * <p><b>Explicit and cancellable by design.</b> A sweep visibly changes the player's settings for
 * a few seconds. That has to be something the player asked for, bounded in scope, and abortable —
 * not a background process quietly rewriting their configuration. Escape cancels and restores.</p>
 *
 * <p>Only one sweep runs at a time: two concurrent sweeps would both be changing options and
 * neither measurement would mean anything.</p>
 */
public final class SweepController {
    private static SweepRun current;
    private static SweepRun.Result lastResult;
    private static final List<SweepRun.Result> COMPLETED = new ArrayList<>();

    private SweepController() {
    }

    public static boolean isRunning() {
        return current != null;
    }

    public static SweepRun activeRun() {
        return current;
    }

    public static SweepRun.Result lastResult() {
        return lastResult;
    }

    public static List<SweepRun.Result> completed() {
        return COMPLETED;
    }

    public static int availableCount() {
        GameOptions options = OptionCatalog.options();
        return options == null ? 0 : OptionCatalog.build(options).size();
    }

    /** Starts a sweep for the next not-yet-measured option. Returns false when there is nothing to do. */
    public static boolean startNext() {
        if (current != null) {
            return false;
        }
        GameOptions options = OptionCatalog.options();
        if (options == null) {
            return false;
        }

        List<Sweep<?>> candidates = OptionCatalog.build(options);
        for (Sweep<?> sweep : candidates) {
            if (!alreadyMeasured(sweep.label())) {
                current = new SweepRun(sweep);
                current.start();
                FrameLedgerClient.LOGGER.info("Sweep started: {}", sweep.label());
                return true;
            }
        }
        return false;
    }

    private static boolean alreadyMeasured(String label) {
        for (SweepRun.Result result : COMPLETED) {
            if (result.label().equals(label)) {
                return true;
            }
        }
        return false;
    }

    /** Advances the active sweep by one frame of sampling. */
    public static void tick() {
        if (current == null) {
            return;
        }
        FrameTimer timer = FrameTimer.get();
        if (!timer.isActive()) {
            cancel();
            return;
        }

        current.tick(timer);

        if (current.isFinished()) {
            lastResult = current.result();
            COMPLETED.add(lastResult);
            logResult(lastResult);
            current = null;
        }
    }

    private static void logResult(SweepRun.Result result) {
        if (!result.isMeasured()) {
            FrameLedgerClient.LOGGER.info("Sweep {}: not enough samples", result.label());
        } else if (!result.conclusive()) {
            FrameLedgerClient.LOGGER.info(
                    "Sweep {}: no measurable difference ({} +- {} ms)",
                    result.label(),
                    fmt(result.deltaMillis()), fmt(result.halfWidth()));
        } else {
            FrameLedgerClient.LOGGER.info(
                    "Sweep {}: {} ms +/- {} (95%, {} pairs)",
                    result.label(),
                    fmt(result.deltaMillis()), fmt(result.halfWidth()), result.pairs());
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** Aborts the active sweep and restores the player's settings. */
    public static void cancel() {
        if (current == null) {
            return;
        }
        current.cancel();
        current = null;
        FrameLedgerClient.LOGGER.info("Sweep cancelled, settings restored");
    }

    /**
     * Restores and clears everything. Called when leaving the world, so a sweep in progress can
     * never leave the player's options modified after they quit.
     */
    public static void resetForWorldExit() {
        cancel();
        lastResult = null;
        COMPLETED.clear();
    }

    public static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }
}
