package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashSet;
import java.util.Set;

/** Opt-in synthetic workload. Only use in the isolated baseline world. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class EntityWorkload {
    private static final Set<Cow> cows = new HashSet<>();
    private static ServerLevel level;
    private static int chunkX, chunkZ;
    private static boolean addedTicket;
    private static long ticks;

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("vulkanforge.serverBaseline") || !Boolean.getBoolean("serverBaseline.entityWorkload")) return;
        level = event.getServer().overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        chunkX = spawn.getX() >> 4;
        chunkZ = spawn.getZ() >> 4;
        addedTicket = level.setChunkForced(chunkX, chunkZ, true);
        for (int i = 0; i < 160; i++) {
            int x = chunkX * 16 + 2 + i % 12, z = chunkZ * 16 + 2 + (i / 12) % 12;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            Cow cow = EntityType.COW.create(level);
            if (cow == null) throw new IllegalStateException("Could not create workload cow");
            cow.moveTo(x + 0.5, y, z + 0.5, 0, 0);
            cow.setPersistenceRequired();
            if (!level.addFreshEntity(cow)) throw new IllegalStateException("Could not add workload cow");
            cows.add(cow);
        }
        ticks = 0;
        if (Boolean.getBoolean("serverBaseline.parity")) SupportBlockParity.verify(level, cows.iterator().next(), chunkX, chunkZ);
        VulkanForge.LOGGER.info("Synthetic workload created: {} cows, forced chunk {},{}", cows.size(), chunkX, chunkZ);
    }

    @SubscribeEvent public static void livingTick(LivingEvent.LivingTickEvent event) {
        if (!cows.isEmpty() && event.getEntity() instanceof Cow cow && cows.contains(cow)) ticks++;
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (level == null) return;
        VulkanForge.LOGGER.info("Synthetic workload cow ticks: {}", ticks);
        cows.forEach(Cow::discard);
        cows.clear();
        if (addedTicket) level.setChunkForced(chunkX, chunkZ, false);
        level = null;
    }
}
