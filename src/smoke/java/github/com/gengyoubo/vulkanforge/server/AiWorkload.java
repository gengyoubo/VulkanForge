package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.util.IdentityHashMap;
import java.util.Map;

/** Synthetic tests only: deliberately modifies a disposable copy of the world. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class AiWorkload {
    private static final Map<Mob, State> mobs = new IdentityHashMap<>();
    private static ServerLevel level;
    private static String scenario;
    private static int baseX, baseZ, floorY, chunkX, chunkZ;
    private static boolean addedTicket;
    private static long ticks, requests, nonNullPaths, reachablePaths, pathNodes, installedPaths, homeObservedTicks, jobObservedTicks;
    private static int homeOwners, jobOwners;

    private static final class State {
        final int index;
        net.minecraft.world.level.pathfinder.Path last;
        boolean destinationRight;
        boolean hadHome, hadJob;
        State(int index) { this.index = index; }
    }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        scenario = System.getProperty("serverBaseline.aiScenario", "");
        if (scenario.isEmpty()) return;
        if (!Boolean.getBoolean("vulkanforge.serverBaseline") || !Files.isRegularFile(java.nio.file.Path.of(".vulkanforge-ai-disposable")))
            throw new IllegalStateException("AI workloads require a marked disposable server directory");
        if (!scenario.equals("wander") && !scenario.equals("obstacles") && !scenario.equals("villager_poi") && !scenario.equals("villager_dense") && !scenario.equals("changed_wolf") && !scenario.equals("changed_dense")) throw new IllegalArgumentException("Unknown AI scenario: " + scenario);
        if (Boolean.getBoolean("serverBaseline.entityWorkload")) throw new IllegalStateException("Do not combine entity workloads");
        level = event.getServer().overworld();
        var spawn = level.getSharedSpawnPos();
        chunkX = spawn.getX() >> 4; chunkZ = spawn.getZ() >> 4;
        baseX = chunkX * 16; baseZ = chunkZ * 16;
        floorY = Math.min(120, level.getMaxBuildHeight() - 8);
        addedTicket = level.setChunkForced(chunkX, chunkZ, true);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            level.setBlock(new BlockPos(baseX + x, floorY, baseZ + z), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 1; dy <= 4; dy++) {
                boolean wall = x == 0 || x == 15 || z == 0 || z == 15 || (scenario.equals("obstacles") || scenario.startsWith("changed_")) && x == 7 && (z < 7 || z > 9);
                level.setBlock(new BlockPos(baseX + x, floorY + dy, baseZ + z), wall && dy <= 3 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        if (scenario.startsWith("villager_")) {
            level.setDayTime(1000);
            int poiCount = scenario.equals("villager_dense") ? 16 : 8;
            for (int i = 0; i < poiCount; i++) {
                int x = 2 + (i % 4) * 3, z = scenario.equals("villager_dense") ? 2 + (i / 4) * 3 : (i < 4 ? 11 : 6);
                BlockPos foot = new BlockPos(baseX + x, floorY + 1, baseZ + z);
                var bed = Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
                level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
                level.setBlock(foot.south(), bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
                int jobZ = scenario.equals("villager_dense") ? z + 2 : (i < 4 ? 9 : 4);
                level.setBlock(new BlockPos(baseX + x, floorY + 1, baseZ + jobZ), Blocks.COMPOSTER.defaultBlockState(), 3);
            }
        }
        int count = scenario.equals("villager_dense") ? 16 : scenario.equals("villager_poi") || scenario.equals("changed_wolf") ? 8 : 32;
        for (int i = 0; i < count; i++) {
            var type = scenario.startsWith("villager_") ? EntityType.VILLAGER : scenario.startsWith("changed_")
                    ? EntityType.byString("changed:dark_latex_wolf_male").orElseThrow(() -> new IllegalStateException("Changed entity is not registered")) : EntityType.COW;
            var entity = type.create(level);
            if (!(entity instanceof Mob mob)) throw new IllegalStateException("Workload entity is not a mob");
            mob.moveTo(baseX + 2.5 + i % 4, floorY + 1, baseZ + 2.5 + i / 4, 0, 0);
            mob.getRandom().setSeed(0xCAFE0000L + i);
            mob.setPersistenceRequired();
            if (!level.addFreshEntity(mob)) throw new IllegalStateException("Could not spawn workload mob");
            mobs.put(mob, new State(i));
        }
        ticks = requests = nonNullPaths = reachablePaths = pathNodes = installedPaths = homeObservedTicks = jobObservedTicks = 0;
        homeOwners = jobOwners = 0;
        VulkanForge.LOGGER.info("AI_WORKLOAD scenario={} mobs={} origin={},{},{} interval=80", scenario, mobs.size(), baseX, floorY, baseZ);
    }

    @SubscribeEvent public static void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        State state = mobs.get(mob);
        if (state == null) return;
        ticks++;
        if (mob instanceof Villager villager) {
            if (villager.getBrain().getMemory(MemoryModuleType.HOME).isPresent()) { homeObservedTicks++; if (!state.hadHome) { state.hadHome = true; homeOwners++; } }
            if (villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).isPresent()) { jobObservedTicks++; if (!state.hadJob) { state.hadJob = true; jobOwners++; } }
        }
        if ((scenario.equals("obstacles") || scenario.startsWith("changed_")) && mob.tickCount % 80 == state.index % 80) {
            state.destinationRight = !state.destinationRight;
            BlockPos target = new BlockPos(baseX + (state.destinationRight ? 13 : 2), floorY + 1, baseZ + 3 + state.index % 8);
            requests++;
            Path path = mob.getNavigation().createPath(target, 0);
            if (path != null) {
                nonNullPaths++; pathNodes += path.getNodeCount();
                if (path.canReach()) reachablePaths++;
                mob.getNavigation().moveTo(path, 1.0);
            }
        }
        Path current = mob.getNavigation().getPath();
        if (current != null && current != state.last) installedPaths++;
        state.last = current;
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (level == null) return;
        VulkanForge.LOGGER.info("AI_WORKLOAD_RESULT scenario={} ticks={} requests={} nonNull={} reachable={} pathNodes={} installed={}",
                scenario, ticks, requests, nonNullPaths, reachablePaths, pathNodes, installedPaths);
        if (scenario.startsWith("villager_")) VulkanForge.LOGGER.info("AI_POI_RESULT homeOwners={} jobOwners={} homeTicks={} jobTicks={}", homeOwners, jobOwners, homeObservedTicks, jobObservedTicks);
        mobs.keySet().forEach(Mob::discard);
        mobs.clear();
        if (addedTicket) level.setChunkForced(chunkX, chunkZ, false);
        level = null;
    }
}
