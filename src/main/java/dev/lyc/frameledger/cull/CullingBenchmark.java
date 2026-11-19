package dev.lyc.frameledger.cull;

import dev.lyc.frameledger.FrameLedgerClient;
import dev.lyc.frameledger.measure.FrameTimer;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures whether block-entity culling actually improves frame time on this machine, in this
 * scene.
 *
 * <p><b>Why this cannot be eyeballed.</b> Toggling {@code F11} and watching an FPS counter gives
 * two numbers taken seconds apart, with the world drifting in between: mobs spawn, chunks load,
 * the sun moves, the GPU may throttle. Any difference is as likely to be that drift as the
 * culling. This runs alternating blocks instead, so drift lands equally on both arms and cancels
 * in the difference — the same reasoning as the per-option sweeps.</p>
 *
 * <p><b>What it measures.</b> Wall-clock frame interval, not a pass timer. A culled draw is never
 * started, so it produces no span to measure; only the gap between frames can show the saving.</p>
 *
 * <p><b>When it legitimately reports nothing.</b> If the view contains few block entities, or they
 * are all on screen already, culling cannot help and the interval will straddle zero. Reporting
 * "no measurable difference" there is the correct answer, and far more useful than a number that
 * would change if the player turned around.</p>
 */
public final class CullingBenchmark {
    private static final int SETTLE_FRAMES = 20;
    private static final int SAMPLE_FRAMES = 50;
    private static final int PAIRS = 6;

    /** Two-sided 95% interval, Student's t. */
    private static final double T_95_DF5 = 2.571;
    private static final double T_95_DF4 = 2.776;

    private static Run active;

    private CullingBenchmark() {
    }

    public static boolean isRunning() {
        return active != null;
    }

    public static Run activeRun() {
        return active;
    }

    /** Starts a measurement. Returns false when one is already running. */
    public static boolean start() {
        if (active != null) {
            return false;
        }
        active = new Run();
        active.beginArm(true);
        FrameLedgerClient.LOGGER.info("Culling benchmark started");
        return true;
    }

    public static void cancel() {
        if (active == null) {
            return;
        }
        // Leave culling on: that is the module's default and the state the player expects.
        BlockEntityCuller.setEnabled(true);
        active = null;
        FrameLedgerClient.LOGGER.info("Culling benchmark cancelled");
    }

    /** Advances one frame. */
    public static void tick(FrameTimer timer) {
        if (active == null) {
            return;
        }
        if (active.tick(timer)) {
            Result result = active.result();
            logResult(result);
            BlockEntityCuller.setEnabled(true);
            lastResult = result;
            active = null;
        }
    }

    private static Result lastResult;

    public static Result lastResult() {
        return lastResult;
    }

    /** Discards the last result, e.g. when the scene it described no longer exists. */
    public static void clearResult() {
        lastResult = null;
        cancel();
    }

    private static void logResult(Result result) {
        if (!result.measured()) {
            FrameLedgerClient.LOGGER.info("Culling benchmark: not enough samples");
        } else if (!result.conclusive()) {
            FrameLedgerClient.LOGGER.info(
                    "Culling benchmark: no measurable difference ({} +/- {} ms/frame)",
                    fmt(result.deltaMillis()), fmt(result.halfWidth()));
        } else {
            FrameLedgerClient.LOGGER.info(
                    "Culling benchmark: {}+/-{} ms/frame saved ({} pairs, {} fps -> {} fps)",
                    fmt(result.deltaMillis()), fmt(result.halfWidth()), result.pairs(),
                    fmt(result.fpsOff()), fmt(result.fpsOn()));
        }
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /**
     * One benchmark run: alternating culling-off / culling-on blocks.
     */
    public static final class Run {
        private final List<Double> pairDeltas = new ArrayList<>(PAIRS);
        private double offAccumulator;
        private double onAccumulator;
        private int offCount;
        private int onCount;

        private int frameInBlock;
        private int pairIndex;
        private boolean cullingOn;

        private double offFrameMillisTotal;
        private int offFrameMillisSamples;
        private double onFrameMillisTotal;
        private int onFrameMillisSamples;

        /** Totals seen during the "on" arm, used to explain an inconclusive result. */
        private long onCulledTotal;
        private long onConsideredTotal;
        private int onStatFrames;

        private void beginArm(boolean on) {
            cullingOn = on;
            BlockEntityCuller.setEnabled(on);
            frameInBlock = 0;
        }

        public int pairIndex() {
            return pairIndex;
        }

        public int totalPairs() {
            return PAIRS;
        }

        /** @return true when the run is complete */
        boolean tick(FrameTimer timer) {
            frameInBlock++;

            if (frameInBlock > SETTLE_FRAMES) {
                double interval = timer.frameIntervalMillis();
                if (interval > 0.0) {
                    if (cullingOn) {
                        onAccumulator += interval;
                        onCount++;
                        onCulledTotal += BlockEntityCuller.culledCount();
                        onConsideredTotal += BlockEntityCuller.consideredCount();
                        onStatFrames++;
                    } else {
                        offAccumulator += interval;
                        offCount++;
                    }
                }
            }

            if (frameInBlock < SETTLE_FRAMES + SAMPLE_FRAMES) {
                return false;
            }

            if (!cullingOn) {
                // Finished an "off" block; run the "on" block next.
                beginArm(true);
                return false;
            }

            // Finished an "on" block: one complete pair.
            if (offCount > 0 && onCount > 0) {
                double offMean = offAccumulator / offCount;
                double onMean = onAccumulator / onCount;
                pairDeltas.add(offMean - onMean);

                offFrameMillisTotal += offMean;
                offFrameMillisSamples++;
                onFrameMillisTotal += onMean;
                onFrameMillisSamples++;
            }

            offAccumulator = 0.0;
            onAccumulator = 0.0;
            offCount = 0;
            onCount = 0;
            pairIndex++;

            if (pairIndex >= PAIRS) {
                return true;
            }

            beginArm(false);
            return false;
        }

        Result result() {
            int n = pairDeltas.size();
            if (n < 3) {
                return new Result(false, Double.NaN, Double.NaN, n,
                        Double.NaN, Double.NaN, false, 0L, 0L, 0.0);
            }

            double mean = 0.0;
            for (double d : pairDeltas) {
                mean += d;
            }
            mean /= n;

            double variance = 0.0;
            for (double d : pairDeltas) {
                double diff = d - mean;
                variance += diff * diff;
            }
            variance /= (n - 1);
            double stderr = Math.sqrt(variance / n);

            double t = switch (n - 1) {
                case 5 -> T_95_DF5;
                case 4 -> T_95_DF4;
                default -> T_95_DF4;
            };
            double halfWidth = t * stderr;

            double offMean = offFrameMillisSamples > 0
                    ? offFrameMillisTotal / offFrameMillisSamples : Double.NaN;
            double onMean = onFrameMillisSamples > 0
                    ? onFrameMillisTotal / onFrameMillisSamples : Double.NaN;

            // Conclusive only when the interval sits entirely above zero, i.e. culling is
            // measurably faster. A negative delta means it was slower, which is also worth acting
            // on but must not be reported as a saving.
            boolean conclusive = mean - halfWidth > 0.0;

            double culledFraction = onConsideredTotal > 0
                    ? (double) onCulledTotal / (double) onConsideredTotal : 0.0;

            return new Result(true, mean, halfWidth, n, fps(offMean), fps(onMean), conclusive,
                    onConsideredTotal, onCulledTotal, culledFraction);
        }

        private static double fps(double frameMillis) {
            return frameMillis > 0.0 ? 1000.0 / frameMillis : Double.NaN;
        }
    }

    /**
     * @param deltaMillis    mean (off − on) frame interval: milliseconds saved per frame by culling
     * @param conclusive     whether the 95% interval excludes zero
     * @param considered     block entities the culler was asked about, summed over the "on" arm
     * @param culled         how many of those it skipped
     * @param culledFraction culled / considered, used to explain an inconclusive result
     */
    public record Result(boolean measured, double deltaMillis, double halfWidth, int pairs,
                         double fpsOff, double fpsOn, boolean conclusive,
                         long considered, long culled, double culledFraction) {
    }
}
