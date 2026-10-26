package github.com.gengyoubo.vulkanforge.client;

import java.util.Arrays;

/**
 * Per-frame CPU time of one GL 4.6 pass over the last {@link #WINDOW} frames. Driver queue stalls make means noisy, so reports
 * use percentiles. Measures the CPU cost of issuing GL commands, not GPU time.
 */
final class Gl46Timer {
    private static final int WINDOW = 2048;
    private final long[] samples = new long[WINDOW];
    private long frames, pending;

    /** Time spent inside this frame so far. */
    void add(long nanos) { pending += nanos; }
    /** Closes the current frame's sample (skipped when the pass did nothing). */
    void frame() {
        if (pending == 0) return;
        samples[(int) (frames++ % WINDOW)] = pending;
        pending = 0;
    }
    /** {p50, p95} in microseconds. */
    double[] percentiles() {
        int count = (int) Math.min(frames, WINDOW);
        if (count == 0) return new double[2];
        long[] sorted = Arrays.copyOf(samples, count); Arrays.sort(sorted);
        return new double[]{sorted[(count - 1) / 2] / 1e3, sorted[(count - 1) * 95 / 100] / 1e3};
    }
    @Override public String toString() { double[] p = percentiles(); return String.format("p50=%.1fus p95=%.1fus", p[0], p[1]); }
}
