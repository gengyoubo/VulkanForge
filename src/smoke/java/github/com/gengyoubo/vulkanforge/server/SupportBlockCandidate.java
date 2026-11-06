package github.com.gengyoubo.vulkanforge.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Cursor3D;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Optional;

/** Development-only support-position experiment; not a general replacement for BlockCollisions. */
final class SupportBlockCandidate {
    static final class Counts {
        long cursor, corners, lookups, chunkMisses, states, edgeRejects, shapeCalls, fullShapes, otherShapes, hits;
    }

    static Optional<BlockPos> find(CollisionGetter world, Entity entity, AABB box, Counts counts) {
        return find(world, entity, box, counts, true);
    }

    static Optional<BlockPos> find(CollisionGetter world, Entity entity, AABB box, Counts counts, boolean twoChunkCache) {
        CollisionContext context = CollisionContext.of(entity);
        Cursor3D cursor = new Cursor3D(Mth.floor(box.minX - 1.0E-7D) - 1, Mth.floor(box.minY - 1.0E-7D) - 1,
                Mth.floor(box.minZ - 1.0E-7D) - 1, Mth.floor(box.maxX + 1.0E-7D) + 1,
                Mth.floor(box.maxY + 1.0E-7D) + 1, Mth.floor(box.maxZ + 1.0E-7D) + 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        VoxelShape entityShape = null;
        BlockGetter first = null, second = null;
        long firstKey = 0, secondKey = 0;
        int bestX = 0, bestY = 0, bestZ = 0;
        boolean found = false;
        double bestDistance = Double.MAX_VALUE;
        while (cursor.advance()) {
            if (counts != null) counts.cursor++;
            int x = cursor.nextX(), y = cursor.nextY(), z = cursor.nextZ(), type = cursor.getNextType();
            if (type == 3) { if (counts != null) counts.corners++; continue; }
            if (counts != null) counts.lookups++;
            int cx = SectionPos.blockToSectionCoord(x), cz = SectionPos.blockToSectionCoord(z);
            long key = ChunkPos.asLong(cx, cz);
            BlockGetter chunk;
            if (first != null && key == firstKey) chunk = first;
            else if (twoChunkCache && second != null && key == secondKey) chunk = second;
            else {
                if (counts != null) counts.chunkMisses++;
                chunk = world.getChunkForCollisions(cx, cz);
                second = first; secondKey = firstKey; first = chunk; firstKey = key;
            }
            if (chunk == null) continue;
            pos.set(x, y, z);
            if (counts != null) counts.states++;
            var state = chunk.getBlockState(pos);
            if (type == 1 && !state.hasLargeCollisionShape() || type == 2 && !state.is(Blocks.MOVING_PISTON)) {
                if (counts != null) counts.edgeRejects++;
                continue;
            }
            if (counts != null) counts.shapeCalls++;
            VoxelShape shape = state.getCollisionShape(world, pos, context);
            if (shape == Shapes.block()) {
                if (counts != null) counts.fullShapes++;
                if (!box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) continue;
            } else {
                if (counts != null) counts.otherShapes++;
                VoxelShape moved = shape.move(x, y, z);
                if (moved.isEmpty()) continue;
                if (entityShape == null) entityShape = Shapes.create(box);
                if (!Shapes.joinIsNotEmpty(moved, entityShape, BooleanOp.AND)) continue;
            }
            if (counts != null) counts.hits++;
            double distance = pos.distToCenterSqr(entity.position());
            // Vec3i.compareTo uses subtraction in Y, Z, X order; preserve that even at integer extremes.
            int cmp = found ? (bestY != y ? bestY - y : bestZ != z ? bestZ - z : bestX - x) : -1;
            if (distance < bestDistance || distance == bestDistance && (!found || cmp < 0)) {
                bestX = x; bestY = y; bestZ = z; found = true; bestDistance = distance;
            }
        }
        return found ? Optional.of(new BlockPos(bestX, bestY, bestZ)) : Optional.empty();
    }
}
