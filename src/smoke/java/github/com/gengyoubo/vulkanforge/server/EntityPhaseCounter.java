package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Inclusive, overlapping method durations. A development-only diagnostic. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class EntityPhaseCounter {
    static final int COUNT = 5;
    private static final long[] calls = new long[COUNT], completed = new long[COUNT], elapsed = new long[COUNT];
    private static boolean active;
    private static Thread serverThread;

    @SubscribeEvent public static void onStarted(ServerStartedEvent event) {
        active = Boolean.getBoolean("serverBaseline.countPhases"); serverThread = Thread.currentThread();
        java.util.Arrays.fill(calls, 0); java.util.Arrays.fill(completed, 0); java.util.Arrays.fill(elapsed, 0);
    }
    public static long begin(int kind) {
        if (!active || Thread.currentThread() != serverThread) return 0;
        calls[kind]++;
        return System.nanoTime();
    }
    public static void end(int kind, long start) {
        if (start == 0 || !active || Thread.currentThread() != serverThread) return;
        elapsed[kind] += System.nanoTime() - start; completed[kind]++;
    }
    static boolean isActive() { return active; }
    static long calls(int kind) { return calls[kind]; }
    static long completed(int kind) { return completed[kind]; }
    static long elapsed(int kind) { return elapsed[kind]; }
    @SubscribeEvent public static void onStopping(ServerStoppingEvent event) {
        if (active) for (int i = 0; i < COUNT; i++) VulkanForge.LOGGER.info("ENTITY_PHASE_RESULT id={} calls={} completed={} elapsedNs={}", i, calls[i], completed[i], elapsed[i]);
        active = false;
    }
}
