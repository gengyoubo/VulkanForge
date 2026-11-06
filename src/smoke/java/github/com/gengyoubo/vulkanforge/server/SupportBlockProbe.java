package github.com.gengyoubo.vulkanforge.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;
import java.util.function.BiFunction;

/** Opt-in experiment: only the support-position mapper ignores its shape argument. */
public final class SupportBlockProbe {
    private static final BiFunction<BlockPos.MutableBlockPos, VoxelShape, BlockPos> POSITION = (pos, shape) -> pos;
    private static final boolean COUNT = Boolean.getBoolean("serverBaseline.parity");
    private static long skippedMoves;

    public static VoxelShape moveFullBlock(VoxelShape shape, double x, double y, double z, BiFunction<?, ?, ?> provider) {
        if (provider == POSITION) {
            if (COUNT) skippedMoves++;
            return shape;
        }
        return shape.move(x, y, z);
    }

    public static long skippedMoves() { return skippedMoves; }

    public static Optional<BlockPos> find(CollisionGetter world, Entity entity, AABB box) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockCollisions<BlockPos> collisions = new BlockCollisions<>(world, entity, box, false, POSITION);
        while (collisions.hasNext()) {
            BlockPos pos = collisions.next();
            double distance = pos.distToCenterSqr(entity.position());
            if (distance < bestDistance || distance == bestDistance && (best == null || best.compareTo(pos) < 0)) {
                best = pos.immutable();
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }
}
