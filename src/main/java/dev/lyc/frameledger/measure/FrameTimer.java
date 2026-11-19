package dev.lyc.frameledger.measure;

import dev.lyc.frameledger.FrameLedgerClient;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;

/**
 * Attributes frame time to named {@link Pass} spans.
 *
 * <p>Two backends, chosen once at startup and reported honestly in the UI:</p>
 * <ul>
 *   <li>{@link Source#GPU} — {@code GL_TIMESTAMP} queries. Real GPU time.</li>
 *   <li>{@link Source#CPU} — {@code System.nanoTime} around the pass. Measures main-thread cost
 *       of submitting that pass, which is a different and generally smaller quantity than GPU
 *       time. It is labelled "CPU" everywhere so it can never be mistaken for GPU time.</li>
 * </ul>
 *
 * <p><b>Spans are summed, not unioned.</b> A logical pass is often several non-contiguous
 * segments. Vanilla {@code WorldRenderer.render} draws the first three terrain layers, then runs
 * the entity loop, then draws the remaining terrain layers. Taking the first start and last end as
 * a single span therefore swallows the entity pass into the terrain number and reports both:
 * terrain looked like ~79% of the frame while entities also reported time, so the rows overlapped
 * and summed past 100%. Every begin/end pair is now its own segment and the frame total is the sum
 * of that pass's segments, which is what "how much time did this pass take" actually means.</p>
 *
 * <p><b>Why {@code GL_TIMESTAMP} and not {@code GL_TIME_ELAPSED}:</b> elapsed queries cannot nest,
 * and block entities are drawn inside the world pass. Timestamps are absolute, so nesting is free
 * and each segment is a subtraction.</p>
 *
 * <p><b>Why a ring:</b> reading a query result blocks until the GPU reaches it. Results are read
 * {@code RING - 1} frames late and only when the driver reports them finished, so measurement never
 * stalls the pipeline it measures.</p>
 *
 * <p>Render thread only.</p>
 */
public final class FrameTimer {
    public enum Source {
        /** Real GPU timestamps. */
        GPU,
        /** Main-thread submit time. Not GPU time. */
        CPU,
        /** No timing available at all. */
        NONE
    }

    private static final int RING = 4;
    private static final int PASS_COUNT = Pass.values().length;

    /**
     * Segments one pass may record per frame on the GPU path. Terrain needs 8; 32 leaves room for
     * a busy entity loop. The CPU path has no such limit because it accumulates deltas directly,
     * so only the GPU fallback can undercount, and only at frame times nobody plays at.
     */
    private static final int MAX_SEGMENTS = 32;

    private static final int INTS_PER_PASS = MAX_SEGMENTS * 2;

    /** Per-frame EMA weight. One frame is far too noisy to put in front of a player. */
    private static final double ALPHA = 0.15;

    private static FrameTimer instance;

    /** {@code queries[slot][pass * INTS_PER_PASS + segment * 2]} = start, {@code + 1} = end. */
    private final int[][] queries = new int[RING][PASS_COUNT * INTS_PER_PASS];
    private final long[] smoothedNanos = new long[PASS_COUNT];
    private final boolean[] sampled = new boolean[PASS_COUNT];

    /**
     * Raw, unsmoothed value of the most recent frame. Needed by A/B sweeps: the EMA deliberately
     * blends neighbouring frames, which would smear one experimental block into the next and
     * destroy the very difference the sweep is trying to isolate.
     */
    private final long[] lastNanos = new long[PASS_COUNT];

    // ---------------------------------------------------------------- per-frame working state

    private final int[] segmentsThisFrame = new int[PASS_COUNT];

    /** CPU path: exact running total per pass; no segment cap, so this is never lossy. */
    private final long[] cpuAccumNanos = new long[PASS_COUNT];
    /** CPU path: start time of the currently open segment, or 0 when none is open. */
    private final long[] cpuOpenNanos = new long[PASS_COUNT];

    /** Single-element landing buffer for 64-bit query readouts; avoids per-frame allocation. */
    private final java.nio.LongBuffer scratch = org.lwjgl.BufferUtils.createLongBuffer(1);

    private Source source = Source.NONE;
    private int writeSlot;
    private boolean frameOpen;

    private FrameTimer() {
        this.resolveSource();
    }

    public static FrameTimer get() {
        if (instance == null) {
            instance = new FrameTimer();
        }
        return instance;
    }

    /** True when this backend actually produces numbers. */
    public boolean isActive() {
        return this.source != Source.NONE;
    }

    public Source source() {
        return this.source;
    }

    // ---------------------------------------------------------------- backend selection

    private void resolveSource() {
        boolean gpu = this.tryInitGpu();
        if (gpu) {
            this.source = Source.GPU;
            FrameLedgerClient.LOGGER.info("Backend: GPU timestamp queries");
        } else {
            this.source = Source.CPU;
            FrameLedgerClient.LOGGER.info(
                    "Backend: CPU submit timing (GPU timer queries unavailable on this context; "
                            + "values are main-thread cost, not GPU time)");
        }

        this.logCapabilities();
    }

    private boolean tryInitGpu() {
        if (!this.hasGl33Context()) {
            return false;
        }

        for (int slot = 0; slot < RING; slot++) {
            GL33C.glGenQueries(this.queries[slot]);
            for (int query : this.queries[slot]) {
                if (query == 0) {
                    // glGenQueries yields 0 when the context cannot create queries.
                    return false;
                }
            }
        }

        // Belt-and-braces: a context that claims 3.3 but rejects QUERY_COUNTER would otherwise
        // fail deep inside frame rendering.
        int probe = this.queries[0][0];
        drainGlErrors();
        GL33C.glQueryCounter(probe, GL33C.GL_TIMESTAMP);
        int error = GL11C.glGetError();
        if (error != GL11C.GL_NO_ERROR) {
            FrameLedgerClient.LOGGER.warn(
                    "glQueryCounter rejected on a 3.3 context (GL error 0x{}); using CPU timing",
                    Integer.toHexString(error));
            return false;
        }
        return true;
    }

    /**
     * A real GL 3.3+ context is required. Nothing weaker is trusted.
     *
     * <p>Both weaker checks lied on the development machine: {@code caps.OpenGL33} reflects
     * whether function pointers resolve rather than the context version, and
     * {@code GL_ARB_timer_query} plus a resolvable {@code glQueryCounter} was reported true on a
     * 3.2 context where a single probe succeeded and every subsequent counter failed with
     * {@code GL_INVALID_OPERATION: Query object not found}.</p>
     */
    private boolean hasGl33Context() {
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            if (!caps.OpenGL30) {
                return false;
            }
            int major = GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION);
            int minor = GL11C.glGetInteger(GL30C.GL_MINOR_VERSION);
            return major > 3 || (major == 3 && minor >= 3);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** One line so a user's log explains which backend was chosen and why. */
    private void logCapabilities() {
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            FrameLedgerClient.LOGGER.info(
                    "GL: version={}.{} arbTimerQuery={} source={}",
                    GL11C.glGetInteger(GL30C.GL_MAJOR_VERSION),
                    GL11C.glGetInteger(GL30C.GL_MINOR_VERSION),
                    caps.GL_ARB_timer_query,
                    this.source);
        } catch (Throwable t) {
            FrameLedgerClient.LOGGER.warn("GL capability probe failed", t);
        }
    }

    private static void drainGlErrors() {
        while (GL11C.glGetError() != GL11C.GL_NO_ERROR) {
            // clear pre-existing errors so a later check is attributable to our own call
        }
    }

    // ---------------------------------------------------------------- frame lifecycle

    public void beginFrame() {
        if (!this.isActive()) {
            return;
        }
        if (this.source == Source.GPU) {
            this.harvest();
        }
        java.util.Arrays.fill(this.segmentsThisFrame, 0);
        java.util.Arrays.fill(this.cpuAccumNanos, 0L);
        java.util.Arrays.fill(this.cpuOpenNanos, 0L);
        this.frameOpen = true;
    }

    public void endFrame() {
        if (this.source == Source.CPU) {
            this.harvestCpu();
        }
        this.frameOpen = false;
        this.writeSlot = (this.writeSlot + 1) % RING;
        this.onFrameComplete();
    }

    // ---------------------------------------------------------------- spans

    /**
     * Opens a segment of a pass.
     *
     * <p>Called once per draw, not once per pass. Every pair is timed separately and summed, so a
     * pass split into non-contiguous pieces is reported correctly instead of absorbing whatever
     * ran in the gap.</p>
     */
    public void beginSpan(Pass pass) {
        if (!this.isActive() || !this.frameOpen) {
            return;
        }
        int index = pass.ordinal();

        if (this.source == Source.GPU) {
            int segment = this.segmentsThisFrame[index];
            if (segment >= MAX_SEGMENTS) {
                return;
            }
            GL33C.glQueryCounter(
                    this.queries[this.writeSlot][index * INTS_PER_PASS + segment * 2],
                    GL33C.GL_TIMESTAMP);
        } else {
            this.cpuOpenNanos[index] = System.nanoTime();
        }
    }

    /** Closes the segment opened by the matching {@link #beginSpan}. */
    public void endSpan(Pass pass) {
        if (!this.isActive() || !this.frameOpen) {
            return;
        }
        int index = pass.ordinal();

        if (this.source == Source.GPU) {
            int segment = this.segmentsThisFrame[index];
            if (segment >= MAX_SEGMENTS) {
                return;
            }
            GL33C.glQueryCounter(
                    this.queries[this.writeSlot][index * INTS_PER_PASS + segment * 2 + 1],
                    GL33C.GL_TIMESTAMP);
            this.segmentsThisFrame[index] = segment + 1;
        } else {
            long start = this.cpuOpenNanos[index];
            if (start == 0L) {
                return;
            }
            long now = System.nanoTime();
            if (now > start) {
                this.cpuAccumNanos[index] += now - start;
            }
            this.cpuOpenNanos[index] = 0L;
            this.segmentsThisFrame[index]++;
        }
    }

    // ---------------------------------------------------------------- readout

    /** Reads the oldest slot only if every query in it completed. Never blocks. */
    private void harvest() {
        int oldest = (this.writeSlot + 1) % RING;
        int[] slot = this.queries[oldest];

        for (int query : slot) {
            if (GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
        }

        var buf = this.scratch;
        for (int index = 0; index < PASS_COUNT; index++) {
            long total = 0L;
            int base = index * INTS_PER_PASS;

            for (int segment = 0; segment < MAX_SEGMENTS; segment++) {
                int startQuery = slot[base + segment * 2];
                int endQuery = slot[base + segment * 2 + 1];

                buf.clear();
                GL33C.glGetQueryObjectui64v(startQuery, GL15C.GL_QUERY_RESULT, buf);
                long start = buf.get(0);

                buf.clear();
                GL33C.glGetQueryObjectui64v(endQuery, GL15C.GL_QUERY_RESULT, buf);
                long end = buf.get(0);

                long delta = end - start;
                if (delta > 0L) {
                    total += delta;
                }
            }

            this.record(index, total);
        }
    }

    private void harvestCpu() {
        for (int index = 0; index < PASS_COUNT; index++) {
            this.record(index, this.cpuAccumNanos[index]);
        }
    }

    private void record(int index, long deltaNanos) {
        // A pass that was never drawn reads as zero and is skipped rather than shown as 0.0 ms.
        if (deltaNanos <= 0) {
            return;
        }
        this.lastNanos[index] = deltaNanos;
        this.updateVariance(index, deltaNanos);
        this.smoothedNanos[index] = this.sampled[index]
                ? (long) (this.smoothedNanos[index] * (1.0 - ALPHA) + deltaNanos * ALPHA)
                : deltaNanos;
        this.sampled[index] = true;
    }

    // ---------------------------------------------------------------- diagnostics

    /** Frames to ignore after entering a world: shaders compile and textures upload there. */
    private static final int WARMUP_FRAMES = 200;

    /** After warm-up, log this many times, spaced out, so convergence is visible in the log. */
    private static final int LOG_COUNT = 3;
    private static final int LOG_INTERVAL_FRAMES = 400;

    private int frameCounter;
    private int logsWritten;

    /**
     * Called once per completed frame with the pass data for that frame.
     *
     * <p><b>Why the counter lives here.</b> An earlier version counted inside the per-pass record
     * callback, so with six instrumented passes the warm-up expired after a sixth of the intended
     * frames and the log reported a still-warming frame.</p>
     */
    private void onFrameComplete() {
        if (this.logsWritten >= LOG_COUNT) {
            return;
        }
        this.frameCounter++;
        if (this.frameCounter < WARMUP_FRAMES) {
            return;
        }
        int sinceWarmup = this.frameCounter - WARMUP_FRAMES;
        if (sinceWarmup % LOG_INTERVAL_FRAMES != 0) {
            return;
        }
        this.logsWritten++;

        StringBuilder sb = new StringBuilder();
        double attributed = 0.0;
        for (Pass pass : Pass.values()) {
            if (this.sampled[pass.ordinal()]) {
                double ms = this.millis(pass);
                attributed += ms;
                sb.append(String.format("%s=%.2f(cv%.2f) ", pass.label(), ms,
                        this.relativeVariability(pass)));
            }
        }
        FrameLedgerClient.LOGGER.info("Ledger ({}): {}| attributed={}ms unstable={}",
                this.source, sb, String.format("%.2f", attributed), this.hasUnstablePass());
    }

    public double millis(Pass pass) {
        return this.smoothedNanos[pass.ordinal()] / 1_000_000.0;
    }

    /**
     * Raw (unsmoothed) duration of the most recent frame for this pass, in milliseconds.
     * Returns a negative value when the pass produced no valid span that frame.
     */
    public double lastFrameMillis(Pass pass) {
        long nanos = this.lastNanos[pass.ordinal()];
        return nanos == 0L ? -1.0 : nanos / 1_000_000.0;
    }

    public boolean hasSample(Pass pass) {
        return this.sampled[pass.ordinal()];
    }

    /** Only the passes actually measured, so the HUD never shows placeholder rows. */
    public java.util.List<Pass> sampledPasses() {
        java.util.List<Pass> result = new java.util.ArrayList<>(PASS_COUNT);
        for (Pass pass : Pass.values()) {
            if (this.sampled[pass.ordinal()]) {
                result.add(pass);
            }
        }
        return result;
    }

    /**
     * Sum of every attributed span. Deliberately not "the frame": only instrumented time.
     *
     * <p>Segments of a pass are disjoint, and passes are drawn in distinct code paths, so this sum
     * no longer double-counts. It can still be less than the frame because uninstrumented work
     * (GUI, buffer uploads, chunk building) is excluded.</p>
     *
     * <p><b>This total is more trustworthy than the individual rows on the CPU backend.</b> CPU
     * timing measures submit time, and when the driver blocks waiting for the GPU, the wait lands
     * on whichever call happened to block. Observed behaviour: terrain and sky trade milliseconds
     * between samples while their sum stays flat, because the blocking point migrates. The total
     * still reflects real waiting; the split between passes does not.</p>
     */
    public double attributedMillis() {
        long total = 0L;
        for (int index = 0; index < PASS_COUNT; index++) {
            total += this.smoothedNanos[index];
        }
        return total / 1_000_000.0;
    }

    // ---------------------------------------------------------------- stability

    /**
     * Per-pass relative variability of the raw frame values, as a coefficient of variation.
     *
     * <p>Tracked with an exponentially weighted mean and second moment, so it costs two float
     * operations per sample and reflects recent frames rather than all history.</p>
     */
    private final double[] ewVariance = new double[PASS_COUNT];
    private final int[] varianceSamples = new int[PASS_COUNT];

    private static final double VARIANCE_ALPHA = 0.08;

    /**
     * Relative standard deviation for a pass, or 0 when there is not enough data.
     *
     * <p>Used to stop the UI asserting a breakdown it cannot support. A row whose frame-to-frame
     * spread is comparable to its own mean is not a measurement of that pass, it is a measurement
     * of something that moved.</p>
     */
    public double relativeVariability(Pass pass) {
        int index = pass.ordinal();
        if (this.varianceSamples[index] < 12) {
            return 0.0;
        }
        double mean = this.smoothedNanos[index];
        if (mean <= 0.0) {
            return 0.0;
        }
        double stdDev = Math.sqrt(Math.max(0.0, this.ewVariance[index]));
        return stdDev / mean;
    }

    /**
     * True when a pass's own spread is too large to attribute time to it confidently.
     *
     * <p>The threshold is deliberately generous. On the CPU backend terrain genuinely varies as
     * the player turns, so flagging normal variation as unreliable would make the indicator
     * useless noise; it is meant to catch the case where the number is meaningless, not merely
     * noisy.</p>
     */
    public boolean isUnstable(Pass pass) {
        return this.relativeVariability(pass) > 0.75;
    }

    /** Whether any pass is currently too unstable to attribute time to. */
    public boolean hasUnstablePass() {
        for (Pass pass : Pass.values()) {
            if (this.sampled[pass.ordinal()] && this.isUnstable(pass)) {
                return true;
            }
        }
        return false;
    }

    private void updateVariance(int index, long deltaNanos) {
        double value = deltaNanos;
        double mean = this.smoothedNanos[index];

        if (this.varianceSamples[index] == 0) {
            this.ewVariance[index] = 0.0;
        } else {
            double deviation = value - mean;
            this.ewVariance[index] = this.ewVariance[index] * (1.0 - VARIANCE_ALPHA)
                    + deviation * deviation * VARIANCE_ALPHA;
        }
        this.varianceSamples[index]++;
    }
}
