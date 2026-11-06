package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Compares the experimental path against the original iterator and mapper. */
final class SupportBlockParity {
    static void verify(ServerLevel level, Entity entity, int cx, int cz) {
        int x = cx * 16 + 5, z = cz * 16 + 5;
        int y = Math.min(level.getMaxBuildHeight() - 5, entity.blockPosition().getY() + 8);
        BlockState[] shapes = {Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.HONEY_BLOCK.defaultBlockState(),
                Blocks.PISTON.defaultBlockState(), Blocks.SOUL_SAND.defaultBlockState()};
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> old = new ArrayList<>();
        var initialPosition = entity.position();
        try {
            for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) {
                BlockPos pos = new BlockPos(x + dx, y, z + dz);
                positions.add(pos); old.add(level.getBlockState(pos));
                level.setBlock(pos, shapes[(dx * 4 + dz) % shapes.length], 2);
            }
            entity.setPos(x + 2.0, y + 1.0, z + 2.0);
            Random rng = new Random(0x5eedL);
            for (int i = 0; i < 2000; i++) {
                double px = x - 0.5 + rng.nextDouble() * 5.0, py = y - 0.3 + rng.nextDouble() * 2.0;
                double pz = z - 0.5 + rng.nextDouble() * 5.0;
                AABB box = new AABB(px, py, pz, px + .05 + rng.nextDouble(), py + .05 + rng.nextDouble(), pz + .05 + rng.nextDouble());
                Optional<BlockPos> expected = reference(level, entity, box);
                Optional<BlockPos> actual = level.findSupportingBlock(entity, box);
                if (!expected.equals(actual)) throw new AssertionError("Support block mismatch at " + box + ": " + expected + " vs " + actual);
            }
            if (Boolean.getBoolean("vulkanforge.experimental.supportBlock") && SupportBlockProbe.skippedMoves() == 0)
                throw new AssertionError("Experimental path did not skip any shape moves");
            VulkanForge.LOGGER.info("SupportBlockParity passed: 2000 mixed-shape queries; skipped moves={}", SupportBlockProbe.skippedMoves());
        } finally {
            entity.setPos(initialPosition);
            for (int i = 0; i < positions.size(); i++) level.setBlock(positions.get(i), old.get(i), 2);
        }
    }

    private static Optional<BlockPos> reference(ServerLevel level, Entity entity, AABB box) {
        BlockPos best = null;
        double distance = Double.MAX_VALUE;
        BlockCollisions<BlockPos> iter = new BlockCollisions<>(level, entity, box, false, (pos, shape) -> pos);
        while (iter.hasNext()) {
            BlockPos candidate = iter.next();
            double next = candidate.distToCenterSqr(entity.position());
            if (next < distance || next == distance && (best == null || best.compareTo(candidate) < 0)) {
                best = candidate.immutable();
                distance = next;
            }
        }
        return Optional.ofNullable(best);
    }
}
