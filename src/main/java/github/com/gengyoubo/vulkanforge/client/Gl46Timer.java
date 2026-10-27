package github.com.gengyoubo.vulkanforge.client;

import java.util.Arrays;

/**
 * Duration samples over the last {@link #WINDOW} frames. Callers supply either CPU elapsed time or explicit GPU timestamp
 * differences; a timer never mixes clocks. Reports use window percentiles and the lifetime worst sample.
 */
final class Gl46Timer {
    private static final int WINDOW = 4096;
    private final long[] samples = new long[WINDOW];
    private long frames, pending, worst;

    /** Time spent inside this frame so far. */
    void add(long nanos) { pending += nanos; }
    /** Closes the current frame's sample (skipped when the pass did nothing). */
    void frame() {
        if (pending == 0) return;
        commit();
    }
    private void commit() {
        samples[(int) (frames++ % WINDOW)] = pending;
        worst = Math.max(worst, pending);
        pending = 0;
    }
    /** One whole sample, e.g. a frame interval. */
    void sample(long nanos) { add(nanos); commit(); }
    void reset() { frames = pending = worst = 0; }
    /** {p50, p95, p99, max} in microseconds (max over the timer's lifetime, percentiles over the window). */
    double[] percentiles() {
        int count = (int) Math.min(frames, WINDOW);
        if (count == 0) return new double[4];
        long[] sorted = Arrays.copyOf(samples, count); Arrays.sort(sorted);
        return new double[]{sorted[(count - 1) / 2] / 1e3, sorted[(count - 1) * 95 / 100] / 1e3, sorted[(count - 1) * 99 / 100] / 1e3, worst / 1e3};
    }
    /** Samples in the window above {@code micros}. */
    long over(double micros) {
        int count = (int) Math.min(frames, WINDOW); long result = 0;
        for (int i = 0; i < count; i++) if (samples[i] > micros * 1e3) result++;
        return result;
    }
    long frames() { return frames; }
    @Override public String toString() { double[] p = percentiles(); return String.format("p50=%.1fus p95=%.1fus p99=%.1fus max=%.1fus", p[0], p[1], p[2], p[3]); }
}
