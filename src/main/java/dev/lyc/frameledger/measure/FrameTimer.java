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
 * <p><b>Why a CPU fallback exists at all:</b> Minecraft commonly runs a GL 3.2 core context on
 * NVIDIA drivers, where the driver advertises {@code GL_ARB_timer_query} but every counter issued
 * during real rendering fails. Refusing to show anything there would leave the mod inert on a
 * large share of machines. The fallback keeps the attribution feature usable and states plainly
 * that it is CPU-side.</p>
 *
 * <p><b>Why {@code GL_TIMESTAMP} and not {@code GL_TIME_ELAPSED}:</b> elapsed queries cannot nest,
 * and block entities are drawn inside the world pass. Timestamps are absolute, so nesting is free
 * and each span is a subtraction.</p>
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

    /** Per-frame EMA weight. One frame is far too noisy to put in front of a player. */
    private static final double ALPHA = 0.15;

    private static FrameTimer instance;

    /** {@code queries[slot][pass * 2]} = start, {@code + 1} = end. Unused in CPU mode. */
    private final int[][] queries = new int[RING][PASS_COUNT * 2];
    private final long[] smoothedNanos = new long[PASS_COUNT];
    private final boolean[] sampled = new boolean[PASS_COUNT];
    private final boolean[] openedThisFrame = new boolean[PASS_COUNT];

    /**
     * Raw, unsmoothed value of the most recent frame. Needed by A/B sweeps: the EMA deliberately
     * blends neighbouring frames, which would smear one experimental block into the next and
     * destroy the very difference the sweep is trying to isolate.
     */
    private final long[] lastNanos = new long[PASS_COUNT];

    /** CPU-mode start timestamps for the current frame. */
    private final long[] cpuStartNanos = new long[PASS_COUNT];
    private final long[] cpuEndNanos = new long[PASS_COUNT];

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
        java.util.Arrays.fill(this.openedThisFrame, false);
        this.frameOpen = true;
    }

    public void endFrame() {
        if (this.source == Source.CPU) {
            this.harvestCpu();
        }
        this.frameOpen = false;
        this.writeSlot = (this.writeSlot + 1) % RING;
    }

    // ---------------------------------------------------------------- spans

    /** Opens a span. Repeat calls within one frame are ignored, so multi-call passes stay one span. */
    public void beginSpan(Pass pass) {
        if (!this.isActive() || !this.frameOpen) {
            return;
        }
        int index = pass.ordinal();
        if (this.openedThisFrame[index]) {
            return;
        }
        this.openedThisFrame[index] = true;

        if (this.source == Source.GPU) {
            GL33C.glQueryCounter(this.queries[this.writeSlot][index * 2], GL33C.GL_TIMESTAMP);
        } else {
            this.cpuStartNanos[index] = System.nanoTime();
        }
    }

    /** Closes a span. Repeat calls overwrite the end marker, which is what layered passes want. */
    public void endSpan(Pass pass) {
        if (!this.isActive() || !this.frameOpen) {
            return;
        }
        int index = pass.ordinal();
        if (!this.openedThisFrame[index]) {
            return;
        }

        if (this.source == Source.GPU) {
            GL33C.glQueryCounter(this.queries[this.writeSlot][index * 2 + 1], GL33C.GL_TIMESTAMP);
        } else {
            this.cpuEndNanos[index] = System.nanoTime();
        }
    }

    // ---------------------------------------------------------------- readout

    /** Reads the oldest slot only if every query completed. Never blocks. */
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
            buf.clear();
            GL33C.glGetQueryObjectui64v(slot[index * 2], GL15C.GL_QUERY_RESULT, buf);
            long start = buf.get(0);

            buf.clear();
            GL33C.glGetQueryObjectui64v(slot[index * 2 + 1], GL15C.GL_QUERY_RESULT, buf);
            long end = buf.get(0);

            this.record(index, end - start);
        }
    }

    private void harvestCpu() {
        for (int index = 0; index < PASS_COUNT; index++) {
            if (!this.openedThisFrame[index]) {
                continue;
            }
            long start = this.cpuStartNanos[index];
            long end = this.cpuEndNanos[index];
            // A span that opened but never closed leaves end == 0 from a previous frame; the
            // monotonic clock makes end > start a reliable liveness test.
            if (end > start) {
                this.record(index, end - start);
            }
        }
    }

    private void record(int index, long deltaNanos) {
        // A span that never closed (or never opened) reads as zero or negative and is skipped.
        if (deltaNanos <= 0) {
            return;
        }
        this.lastNanos[index] = deltaNanos;
        this.smoothedNanos[index] = this.sampled[index]
                ? (long) (this.smoothedNanos[index] * (1.0 - ALPHA) + deltaNanos * ALPHA)
                : deltaNanos;
        this.sampled[index] = true;
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

    /** Sum of every attributed span. Deliberately not "the frame": only instrumented time. */
    public double attributedMillis() {
        long total = 0L;
        for (int index = 0; index < PASS_COUNT; index++) {
            total += this.smoothedNanos[index];
        }
        return total / 1_000_000.0;
    }
}
