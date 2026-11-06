package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Same-process synthetic comparison; run without profiling and outside the server timing interval. */
final class CollisionMicrobench {
    private static volatile long sink;
    private static final int BOXES = 256, REPEATS = 32, WARMUP = 8, ROUNDS = 12;

    static void run(ServerLevel level, Entity entity, int x, int y, int z) {
        boolean transformed = Boolean.getBoolean("vulkanforge.experimental.supportBlock");
        AABB[] boxes = new AABB[BOXES];
        Random random = new Random(0x12345678);
        SupportBlockCandidate.Counts counts = new SupportBlockCandidate.Counts();
        SupportBlockCandidate.Counts singleCache = new SupportBlockCandidate.Counts();
        for (int i = 0; i < boxes.length; i++) {
            double px = x - .5 + random.nextDouble() * 5, py = y - .3 + random.nextDouble() * 2;
            double pz = z - .5 + random.nextDouble() * 5;
            boxes[i] = new AABB(px, py, pz, px + .05 + random.nextDouble(), py + .05 + random.nextDouble(), pz + .05 + random.nextDouble());
            Optional<BlockPos> expected = SupportBlockParity.reference(level, entity, boxes[i]);
            if (!expected.equals(SupportBlockProbe.find(level, entity, boxes[i])) ||
                    !expected.equals(SupportBlockCandidate.find(level, entity, boxes[i], counts)) ||
                    !expected.equals(SupportBlockCandidate.find(level, entity, boxes[i], singleCache, false)))
                throw new AssertionError("Microbenchmark candidate differs at " + i);
        }
        var base = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean alloc = base instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported() ? b : null;
        if (alloc != null && !alloc.isThreadAllocatedMemoryEnabled()) alloc.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        StringBuilder out = new StringBuilder("transformed,round,variant,queries,elapsed_ns,allocated_bytes,checksum\n");
        for (int round = -WARMUP; round < ROUNDS; round++) {
            for (int slot = 0; slot < 4; slot++) {
                int mode = Math.floorMod(round + slot, 4);
                long allocBefore = alloc == null ? -1 : alloc.getThreadAllocatedBytes(thread);
                long begin = System.nanoTime();
                long checksum = execute(mode, level, entity, boxes);
                long elapsed = System.nanoTime() - begin;
                long bytes = alloc == null ? -1 : alloc.getThreadAllocatedBytes(thread) - allocBefore;
                sink = checksum;
                if (round >= 0) out.append(transformed).append(',').append(round + 1).append(',').append(new String[]{"vanilla", transformed ? "skip_move" : "vanilla_mapper", "candidate_one_chunk", "candidate_two_chunks"}[mode])
                        .append(',').append(BOXES * REPEATS).append(',').append(elapsed).append(',').append(bytes).append(',').append(checksum).append('\n');
            }
        }
        try {
            Path dir = Path.of("logs", "vulkanforge", "server-baseline"); Files.createDirectories(dir);
            Path file = dir.resolve("collision-microbench-" + UUID.randomUUID() + ".csv");
            Files.writeString(file, out.toString());
            VulkanForge.LOGGER.info("CollisionMicrobench output={} queries/round={} counters: cursor={} corners={} lookups={} chunkMisses={} states={} edgeRejects={} shapeCalls={} fullShapes={} otherShapes={} hits={}",
                    file.toAbsolutePath(), BOXES * REPEATS, counts.cursor, counts.corners, counts.lookups, counts.chunkMisses,
                    counts.states, counts.edgeRejects, counts.shapeCalls, counts.fullShapes, counts.otherShapes, counts.hits);
            VulkanForge.LOGGER.info("CollisionMicrobench modeled chunk cache misses: vanilla one-entry={} candidate two-entry={}", singleCache.chunkMisses, counts.chunkMisses);
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not save microbenchmark", e); }
    }

    private static long execute(int mode, ServerLevel level, Entity entity, AABB[] boxes) {
        long checksum = 0;
        for (int repeat = 0; repeat < REPEATS; repeat++) for (AABB box : boxes) {
            Optional<BlockPos> pos = mode == 0 ? SupportBlockParity.reference(level, entity, box) :
                    mode == 1 ? SupportBlockProbe.find(level, entity, box) : SupportBlockCandidate.find(level, entity, box, null, mode == 3);
            checksum = checksum * 31 + (pos.isPresent() ? pos.get().asLong() : 0);
        }
        return checksum;
    }
}
