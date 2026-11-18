package dev.lyc.frameledger.measure;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

/**
 * GPU frame-time ledger built on {@code GL_TIMESTAMP} queries.
 *
 * <p><b>Why timestamps and not {@code GL_TIME_ELAPSED}:</b> elapsed queries cannot nest, but our
 * spans do (block entities are drawn inside the world pass). Timestamps are absolute markers, so
 * nesting costs nothing and each span is just a subtraction.</p>
 *
 * <p><b>Why a ring:</b> reading a query result blocks until the GPU reaches it. We only read from
 * a slot that is {@code RING - 1} frames old and only when the driver reports it finished, so the
 * measurement never stalls the pipeline it is measuring.</p>
 *
 * <p><b>Why spans accumulate:</b> a logical pass is not one draw call. Terrain is three contiguous
 * chunk layers, so a span keeps the first start and the last end recorded in a frame. Because the
 * layers run back-to-back, that union equals their sum.</p>
 *
 * <p>Render thread only.</p>
 */
public final class GpuFrameTimer {
    private static final int RING = 4;
    private static final int PASS_COUNT = Pass.values().length;

    /** Per-frame EMA weight. One frame of GPU time is far too noisy to put in front of a player. */
    private static final double ALPHA = 0.15;

    private static GpuFrameTimer instance;

    /** {@code queries[ringSlot][pass * 2]} = start, {@code + 1} = end. */
    private final int[][] queries;
    private final long[] smoothedNanos = new long[PASS_COUNT];
    private final long[] latestNanos = new long[PASS_COUNT];
    private final boolean[] sampled = new boolean[PASS_COUNT];
    private final boolean[] openedThisFrame = new boolean[PASS_COUNT];

    /** Single-element landing buffer for 64-bit query readouts; avoids per-frame allocation. */
    private final java.nio.LongBuffer scratch = org.lwjgl.BufferUtils.createLongBuffer(1);

    private int writeSlot;
    private final boolean supported;
    private boolean frameOpen;

    private GpuFrameTimer() {
        this.queries = new int[RING][PASS_COUNT * 2];
        this.supported = probeSupport();
        if (this.supported) {
            for (int slot = 0; slot < RING; slot++) {
                GL33C.glGenQueries(this.queries[slot]);
            }
        }
    }

    public static GpuFrameTimer get() {
        if (instance == null) {
            instance = new GpuFrameTimer();
        }
        return instance;
    }

    private static boolean probeSupport() {
        try {
            var caps = org.lwjgl.opengl.GL.getCapabilities();
            return caps.OpenGL33 || caps.GL_ARB_timer_query;
        } catch (Throwable ignored) {
            // No GL context yet, or a driver that does not expose timer queries.
            return false;
        }
    }

    public boolean isSupported() {
        return this.supported;
    }

    public void beginFrame() {
        if (!this.supported) {
            return;
        }
        this.harvest();
        java.util.Arrays.fill(this.openedThisFrame, false);
        this.frameOpen = true;
    }

    public void endFrame() {
        this.frameOpen = false;
        this.writeSlot = (this.writeSlot + 1) % RING;
    }

    /** Starts a span. Repeat calls within one frame are ignored so multi-call passes stay one span. */
    public void beginSpan(Pass pass) {
        if (!this.supported || !this.frameOpen) {
            return;
        }
        int pass5 = pass.ordinal();
        if (this.openedThisFrame[pass5]) {
            return;
        }
        this.openedThisFrame[pass5] = true;
        GL33C.glQueryCounter(this.queries[this.writeSlot][pass5 * 2], GL33C.GL_TIMESTAMP);
    }

    /** Ends a span. Repeated calls overwrite the end marker, which is what we want for layered passes. */
    public void endSpan(Pass pass) {
        if (!this.supported || !this.frameOpen) {
            return;
        }
        int pass5 = pass.ordinal();
        if (!this.openedThisFrame[pass5]) {
            return;
        }
        GL33C.glQueryCounter(this.queries[this.writeSlot][pass5 * 2 + 1], GL33C.GL_TIMESTAMP);
    }

    /** Reads the oldest slot only if every query in it has completed. Never blocks. */
    private void harvest() {
        int oldest = (this.writeSlot + 1) % RING;
        int[] slot = this.queries[oldest];

        for (int query : slot) {
            if (GL15C.glGetQueryObjecti(query, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return;
            }
        }

        // LWJGL's 64-bit query readout writes into a buffer rather than returning the value.
        var scratch = this.scratch;
        for (int pass5 = 0; pass5 < PASS_COUNT; pass5++) {
            scratch.clear();
            GL33C.glGetQueryObjectui64v(slot[pass5 * 2], GL15C.GL_QUERY_RESULT, scratch);
            long start = scratch.get(0);

            scratch.clear();
            GL33C.glGetQueryObjectui64v(slot[pass5 * 2 + 1], GL15C.GL_QUERY_RESULT, scratch);
            long end = scratch.get(0);

            long delta = end - start;

            // A span that never closed (or never opened) reads as zero/garbage and is skipped.
            if (delta <= 0) {
                continue;
            }

            this.latestNanos[pass5] = delta;
            this.smoothedNanos[pass5] = this.sampled[pass5]
                    ? (long) (this.smoothedNanos[pass5] * (1.0 - ALPHA) + delta * ALPHA)
                    : delta;
            this.sampled[pass5] = true;
        }
    }

    public double millis(Pass pass) {
        return this.smoothedNanos[pass.ordinal()] / 1_000_000.0;
    }

    public double latestMillis(Pass pass) {
        return this.latestNanos[pass.ordinal()] / 1_000_000.0;
    }

    public boolean hasSample(Pass pass) {
        return this.sampled[pass.ordinal()];
    }

    /** Only the passes we have actually measured, so the HUD never shows placeholder rows. */
    public java.util.List<Pass> sampledPasses() {
        java.util.List<Pass> result = new java.util.ArrayList<>(PASS_COUNT);
        for (Pass pass : Pass.values()) {
            if (this.sampled[pass.ordinal()]) {
                result.add(pass);
            }
        }
        return result;
    }

    /** Sum of every span we attribute. Deliberately not "the frame": only instrumented time. */
    public double attributedMillis() {
        long total = 0L;
        for (int pass5 = 0; pass5 < PASS_COUNT; pass5++) {
            total += this.smoothedNanos[pass5];
        }
        return total / 1_000_000.0;
    }
}
