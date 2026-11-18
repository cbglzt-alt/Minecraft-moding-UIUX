package dev.lyc.frameledger.measure;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one {@link Sweep} as alternating A/B blocks and reports the measured difference.
 *
 * <p><b>Why alternating instead of "measure A, then measure B".</b> A block of samples taken now
 * and a block taken ten seconds ago differ by more than the option under test: the sun moved, mobs
 * spawned, the chunk cache warmed up, the GPU may have thermally throttled. Alternating
 * A/B/A/B... spreads any such drift evenly across both arms, so drift cancels in the difference
 * instead of being attributed to the option.</p>
 *
 * <p><b>Why paired blocks rather than pooled samples.</b> Each A block is paired with the B block
 * that follows it. The statistic is the mean of per-pair differences, which is exactly the
 * quantity we want and is robust to the total number of samples per block varying.</p>
 *
 * <p><b>Why a confidence interval and a refusal to report.</b> A difference that is smaller than
 * the run-to-run noise is not a measurement, it is a coin flip. When the interval straddles zero
 * this reports "no measurable difference", which is a real and useful answer — and far better than
 * printing a number that would change sign if the player looked at a different wall.</p>
 *
 * <p>All methods are called from the render thread.</p>
 */
public final class SweepRun {
    /** Frames discarded at the start of each block before sampling, to let the change take effect. */
    private static final int SETTLE_FRAMES = 12;

    /** Frames sampled per block. Short enough that drift stays small, long enough to average noise. */
    private static final int SAMPLE_FRAMES = 40;

    /** Number of A/B pairs. Eight pairs is a compromise between confidence and patience. */
    private static final int PAIRS = 8;

    /** Two-sided 95% interval, Student's t for 7 degrees of freedom. */
    private static final double T_95_DF7 = 2.365;

    private final Sweep<?> sweep;
    private final Pass pass;

    private final List<Double> aSamples = new ArrayList<>(PAIRS);
    private final List<Double> bSamples = new ArrayList<>(PAIRS);

    /** Per-pair (A mean − B mean), accumulated as pairs complete. */
    private final List<Double> pairDeltas = new ArrayList<>(PAIRS);

    private int frameInBlock;
    private int pairIndex;
    private boolean onB;
    private boolean finished;

    private double aAccumulator;
    private int aCount;
    private double bAccumulator;
    private int bCount;

    public SweepRun(Sweep<?> sweep) {
        this.sweep = sweep;
        this.pass = sweep.watchedPass();
    }

    public Sweep<?> sweep() {
        return this.sweep;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public int pairIndex() {
        return this.pairIndex;
    }

    public int totalPairs() {
        return PAIRS;
    }

    /** Begins the sweep by applying arm A. */
    public void start() {
        this.sweep.applyA();
        this.onB = false;
        this.frameInBlock = 0;
    }

    /**
     * Advances one frame. Returns the arm currently being sampled, so the caller can decide
     * whether to feed a sample in.
     */
    public void tick(FrameTimer timer) {
        if (this.finished) {
            return;
        }

        this.frameInBlock++;

        boolean settling = this.frameInBlock <= SETTLE_FRAMES;
        if (!settling) {
            double sample = timer.lastFrameMillis(this.pass);
            if (sample >= 0.0) {
                if (this.onB) {
                    this.bAccumulator += sample;
                    this.bCount++;
                } else {
                    this.aAccumulator += sample;
                    this.aCount++;
                }
            }
        }

        int blockLength = SETTLE_FRAMES + SAMPLE_FRAMES;
        if (this.frameInBlock < blockLength) {
            return;
        }

        this.closeBlock();
    }

    private void closeBlock() {
        this.frameInBlock = 0;

        if (!this.onB) {
            // Finished an A block; switch to B.
            this.onB = true;
            this.sweep.applyB();
            return;
        }

        // Finished a B block: one complete pair.
        double aMean = this.aCount > 0 ? this.aAccumulator / this.aCount : Double.NaN;
        double bMean = this.bCount > 0 ? this.bAccumulator / this.bCount : Double.NaN;

        if (!Double.isNaN(aMean) && !Double.isNaN(bMean)) {
            this.aSamples.add(aMean);
            this.bSamples.add(bMean);
            this.pairDeltas.add(aMean - bMean);
        }

        this.aAccumulator = 0.0;
        this.bAccumulator = 0.0;
        this.aCount = 0;
        this.bCount = 0;
        this.pairIndex++;

        if (this.pairIndex >= PAIRS) {
            this.finished = true;
            this.sweep.restore();
            return;
        }

        // Next pair starts on arm A again, so every pair is measured A-then-B in the same order.
        this.onB = false;
        this.sweep.applyA();
    }

    /** Abandons the sweep and restores the player's original value. */
    public void cancel() {
        this.finished = true;
        this.sweep.restore();
    }

    /**
     * The measurement, or a result explicitly marked as inconclusive.
     */
    public Result result() {
        int n = this.pairDeltas.size();
        if (n < 3) {
            return Result.inconclusive(this.sweep.label(), this.pass, n);
        }

        double mean = 0.0;
        for (double d : this.pairDeltas) {
            mean += d;
        }
        mean /= n;

        if (n < 2) {
            return Result.inconclusive(this.sweep.label(), this.pass, n);
        }

        double variance = 0.0;
        for (double d : this.pairDeltas) {
            double diff = d - mean;
            variance += diff * diff;
        }
        variance /= (n - 1);
        double stderr = Math.sqrt(variance / n);

        // t critical depends on df; this table only needs to cover the small n we actually use.
        double t = criticalT(n - 1);
        double halfWidth = t * stderr;

        boolean conclusive = mean - halfWidth > 0.0;
        return new Result(this.sweep.label(), this.pass, mean, halfWidth, n, conclusive);
    }

    private static double criticalT(int df) {
        return switch (df) {
            case 7 -> T_95_DF7;
            case 6 -> 2.447;
            case 5 -> 2.571;
            case 4 -> 2.776;
            case 3 -> 3.182;
            case 2 -> 4.303;
            default -> T_95_DF7;
        };
    }

    /**
     * Outcome of a sweep.
     *
     * @param deltaMillis mean of (A − B): how much time arm A costs above arm B
     * @param halfWidth   half-width of the 95% interval
     * @param conclusive  whether the interval excludes zero, i.e. the difference is resolvable
     */
    public record Result(String label, Pass pass, double deltaMillis, double halfWidth,
                         int pairs, boolean conclusive) {

        static Result inconclusive(String label, Pass pass, int pairs) {
            return new Result(label, pass, Double.NaN, Double.NaN, pairs, false);
        }

        public boolean isMeasured() {
            return !Double.isNaN(this.deltaMillis);
        }
    }
}
