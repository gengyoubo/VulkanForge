package github.com.gengyoubo.vulkanforge.lab;

import java.util.Locale;
import java.util.function.Consumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import static github.com.gengyoubo.vulkanforge.lab.VulkanContext.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/** Timestamp pairs owned by frame slots. Collect only after the slot's existing fence has completed. */
final class GpuTimers implements AutoCloseable {
    private final VulkanContext context;
    private final String[] labels;
    private final boolean[] pending;
    private final boolean[][] recorded;
    private final long[] samples;
    private final double[] total, maximum;
    private final int validBits, queriesPerSlot;
    private final double period;
    private long pool;

    GpuTimers(VulkanContext context, int slots, Consumer<String> log, String... labels) {
        this.context = context; this.labels = labels.clone();
        pending = new boolean[slots]; recorded = new boolean[slots][labels.length];
        samples = new long[labels.length]; total = new double[labels.length]; maximum = new double[labels.length];
        queriesPerSlot = labels.length * 2;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(context.physical, properties);
            period = properties.limits().timestampPeriod();
            var count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physical, count, families);
            validBits = families.get(context.queueFamily).timestampValidBits();
            if (validBits > 0 && period > 0 && properties.limits().timestampComputeAndGraphics()) {
                var handle = stack.mallocLong(1);
                check(vkCreateQueryPool(context.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                        .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(slots * queriesPerSlot), null, handle), "create GPU timestamps");
                pool = handle.get(0);
            }
            log.accept("GPU_TIMESTAMPS=" + (enabled() ? "ENABLED" : "UNAVAILABLE") + " validBits=" + validBits + " periodNs=" + period);
        }
    }

    boolean enabled() { return pool != 0; }
    long samples(int metric) { return samples[metric]; }
    void begin(VkCommandBuffer command, int slot) {
        if (!enabled()) return;
        if (pending[slot]) throw new IllegalStateException("Timestamp slot reused before collection");
        java.util.Arrays.fill(recorded[slot], false);
        vkCmdResetQueryPool(command, pool, slot * queriesPerSlot, queriesPerSlot);
    }
    void start(VkCommandBuffer command, int slot, int metric, long stage) {
        if (enabled()) vkCmdWriteTimestamp2(command, stage, pool, slot * queriesPerSlot + metric * 2);
    }
    void end(VkCommandBuffer command, int slot, int metric, long stage) {
        if (!enabled()) return;
        vkCmdWriteTimestamp2(command, stage, pool, slot * queriesPerSlot + metric * 2 + 1);
        recorded[slot][metric] = true;
    }
    void submitted(int slot) { if (enabled()) pending[slot] = true; }

    void collect(int slot) {
        if (!enabled() || !pending[slot]) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int metric = 0; metric < labels.length; metric++) if (recorded[slot][metric]) {
                // value/availability pairs, no WAIT_BIT and no new host/GPU synchronization.
                var values = stack.callocLong(4);
                int result = vkGetQueryPoolResults(context.device, pool, slot * queriesPerSlot + metric * 2, 2,
                        values, 16, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (result == VK_NOT_READY) continue;
                check(result, "read GPU timestamps");
                if (values.get(1) == 0 || values.get(3) == 0) continue;
                double millis = elapsedTicks(values.get(0), values.get(2), validBits) * period / 1_000_000.0;
                samples[metric]++; total[metric] += millis; maximum[metric] = Math.max(maximum[metric], millis);
            }
        }
        pending[slot] = false;
    }
    static long elapsedTicks(long start, long end, int bits) {
        return bits == 64 ? end - start : (end - start) & ((1L << bits) - 1);
    }
    void collectAll() { for (int slot = 0; slot < pending.length; slot++) collect(slot); }
    void report(Consumer<String> log) {
        for (int metric = 0; metric < labels.length; metric++) {
            if (samples[metric] == 0) continue;
            log.accept(String.format(Locale.ROOT, "GPU_%s_MS samples=%d mean=%.6f max=%.6f", labels[metric], samples[metric],
                    total[metric] / samples[metric], maximum[metric]));
        }
    }
    @Override public void close() {
        if (pool != 0) { vkDestroyQueryPool(context.device, pool, null); pool = 0; }
    }
}
