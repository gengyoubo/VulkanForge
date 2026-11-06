package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashMap;
import java.util.Map;

/** Per-Goal class diagnostics; the map lookup itself is outside the timed method body. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class GoalDetailCounter {
    private record Key(String type, int operation) { }
    private static final class Count { long calls, ns; }
    private static final Map<Key, Count> data = new HashMap<>();
    private static boolean active;
    private static Thread serverThread;

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        data.clear(); serverThread = Thread.currentThread(); active = Boolean.getBoolean("serverBaseline.goalDetails");
    }
    public static long begin() { return active && Thread.currentThread() == serverThread ? System.nanoTime() : 0; }
    public static void end(WrappedGoal goal, int kind, long start) {
        if (start == 0 || !active || Thread.currentThread() != serverThread) return;
        long elapsed = System.nanoTime() - start;
        Count count = data.computeIfAbsent(new Key(goal.getGoal().getClass().getName(), kind), k -> new Count());
        count.calls++; count.ns += elapsed;
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (!active) return;
        data.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().ns, a.getValue().ns)).forEach(e ->
                VulkanForge.LOGGER.info("GOAL_DETAIL type={} op={} calls={} elapsedNs={}", e.getKey().type(), e.getKey().operation(), e.getValue().calls, e.getValue().ns));
        active = false;
    }
}
