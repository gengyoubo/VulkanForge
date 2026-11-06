package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Times server-thread PathFinder calls and counts heap pops during the isolated test. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class AiNodeCounter {
    private static boolean active;
    private static Thread serverThread;
    private static long searches, completed, popped, elapsedNs, outerElapsedNs, maxSearchNs, maxPops;
    private static final long[] nestedPops = new long[64];
    private static int depth;

    @SubscribeEvent public static void onStarted(ServerStartedEvent event) {
        active = Boolean.getBoolean("serverBaseline.countNodes"); serverThread = Thread.currentThread();
        searches = completed = popped = elapsedNs = outerElapsedNs = maxSearchNs = maxPops = 0; depth = 0;
    }
    public static long started() {
        if (!active || Thread.currentThread() != serverThread) return 0;
        searches++;
        if (depth < nestedPops.length) nestedPops[depth] = 0;
        depth++;
        return System.nanoTime();
    }
    public static void popped() {
        if (!active || Thread.currentThread() != serverThread) return;
        popped++;
        if (depth > 0 && depth <= nestedPops.length) nestedPops[depth - 1]++;
    }
    public static void finished(long start) {
        if (start == 0 || !active || Thread.currentThread() != serverThread) return;
        long duration = System.nanoTime() - start;
        completed++; elapsedNs += duration; maxSearchNs = Math.max(maxSearchNs, duration);
        if (depth == 1) outerElapsedNs += duration;
        if (depth > 0 && depth <= nestedPops.length) maxPops = Math.max(maxPops, nestedPops[depth - 1]);
        if (depth > 0) depth--;
    }
    static boolean isActive() { return active; }
    static long searches() { return searches; }
    static long completed() { return completed; }
    static long poppedNodes() { return popped; }
    static long inclusiveNs() { return elapsedNs; }
    static long outerNs() { return outerElapsedNs; }
    static int openDepth() { return depth; }
    @SubscribeEvent public static void onStopping(ServerStoppingEvent event) {
        if (active) VulkanForge.LOGGER.info("AI_SEARCH_RESULT searches={} completed={} heapPops={} elapsedNs={} maxSearchNs={} maxPops={} openDepth={}", searches, completed, popped, elapsedNs, maxSearchNs, maxPops, depth);
        active = false;
    }
}
