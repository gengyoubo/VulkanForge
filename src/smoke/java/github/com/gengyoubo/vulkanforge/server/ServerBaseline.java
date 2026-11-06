package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Development-only timing between Forge's server tick events, not the entire server loop. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class ServerBaseline {
    private static final boolean ENABLED = Boolean.getBoolean("vulkanforge.serverBaseline");
    private static BufferedWriter writer;
    private static long start;
    private static long beforeCalls, beforeCompleted, beforePops, beforeInclusive, beforeOuter;
    private static int beforeDepth;
    private static final long[] phaseCalls = new long[EntityPhaseCounter.COUNT], phaseCompleted = new long[EntityPhaseCounter.COUNT], phaseElapsed = new long[EntityPhaseCounter.COUNT];
    private static int tick;
    private static int warmup;
    private static int samples;

    @SubscribeEvent public static void started(ServerStartedEvent event) throws IOException {
        if (!ENABLED) return;
        warmup = Integer.getInteger("serverBaseline.warmupTicks", 1200);
        samples = Integer.getInteger("serverBaseline.sampleTicks", 6000);
        if (warmup < 0 || samples < 1 || (long) warmup + samples > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid server baseline tick counts");
        tick = 0;
        Path dir = Path.of("logs", "vulkanforge", "server-baseline");
        Files.createDirectories(dir);
        // Never silently overwrite an earlier run.
        Path output = dir.resolve("ticks-" + java.util.UUID.randomUUID() + ".csv");
        writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW);
        writer.write("sample,tick_duration_ns,search_instrumented,search_calls,search_completed,search_heap_pops,search_inclusive_ns,search_outer_ns,search_open_start,search_open_end,phase_instrumented");
        for (int i = 0; i < EntityPhaseCounter.COUNT; i++) writer.write(",phase" + i + "_calls,phase" + i + "_completed,phase" + i + "_ns");
        writer.write("\n");
        VulkanForge.LOGGER.info("Server baseline: warmup={} ticks, samples={} ticks, output={}", warmup, samples, output.toAbsolutePath());
    }

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) throws IOException {
        if (writer == null) return;
        if (event.phase == TickEvent.Phase.START) {
            start = System.nanoTime();
            beforeCalls = AiNodeCounter.searches(); beforeCompleted = AiNodeCounter.completed();
            beforePops = AiNodeCounter.poppedNodes(); beforeInclusive = AiNodeCounter.inclusiveNs();
            beforeOuter = AiNodeCounter.outerNs(); beforeDepth = AiNodeCounter.openDepth();
            for (int i = 0; i < EntityPhaseCounter.COUNT; i++) {
                phaseCalls[i] = EntityPhaseCounter.calls(i); phaseCompleted[i] = EntityPhaseCounter.completed(i); phaseElapsed[i] = EntityPhaseCounter.elapsed(i);
            }
        } else if (start != 0) {
            long elapsed = System.nanoTime() - start;
            start = 0;
            tick++;
            if (tick > warmup) {
                writer.write((tick - warmup) + "," + elapsed + "," + AiNodeCounter.isActive() + ","
                    + (AiNodeCounter.searches() - beforeCalls) + "," + (AiNodeCounter.completed() - beforeCompleted) + ","
                    + (AiNodeCounter.poppedNodes() - beforePops) + "," + (AiNodeCounter.inclusiveNs() - beforeInclusive) + ","
                    + (AiNodeCounter.outerNs() - beforeOuter) + "," + beforeDepth + "," + AiNodeCounter.openDepth() + "," + EntityPhaseCounter.isActive());
                for (int i = 0; i < EntityPhaseCounter.COUNT; i++) writer.write("," + (EntityPhaseCounter.calls(i) - phaseCalls[i])
                        + "," + (EntityPhaseCounter.completed(i) - phaseCompleted[i]) + "," + (EntityPhaseCounter.elapsed(i) - phaseElapsed[i]));
                writer.write("\n");
            }
            if (tick == warmup + samples) {
                writer.close();
                writer = null;
                VulkanForge.LOGGER.info("Server baseline complete: {} samples", samples);
                if (Boolean.getBoolean("serverBaseline.stopAfterSample"))
                    ServerLifecycleHooks.getCurrentServer().halt(false);
            }
        }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
            VulkanForge.LOGGER.warn("Server baseline stopped early after {} measured ticks", Math.max(0, tick - warmup));
        }
    }
}
