package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Unformed structural components only, in an explicitly marked disposable world. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class MekanismWorkload {
    private static final List<BlockPos> positions = new ArrayList<>();
    private static ServerLevel level;
    private static int cx, cz;
    private static boolean ticket;

    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (!"structures".equals(System.getProperty("serverBaseline.mekScenario", ""))) return;
        if (!Boolean.getBoolean("vulkanforge.serverBaseline") || !Files.isRegularFile(java.nio.file.Path.of(".vulkanforge-mekanism-disposable")))
            throw new IllegalStateException("Mekanism workload requires an explicitly marked disposable world");
        level = event.getServer().overworld();
        var spawn = level.getSharedSpawnPos(); cx = spawn.getX() >> 4; cz = spawn.getZ() >> 4;
        int y = Math.min(120, level.getMaxBuildHeight() - 10), baseX = cx * 16, baseZ = cz * 16;
        ticket = level.setChunkForced(cx, cz, true);
        Block casing = block("induction_casing"), glass = block("structural_glass");
        for (int dy = 0; dy < 4; dy++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos pos = new BlockPos(baseX + x, y + dy, baseZ + z);
            level.setBlock(pos, ((x + z + dy) & 1) == 0 ? casing.defaultBlockState() : glass.defaultBlockState(), 2);
            if (level.getBlockEntity(pos) == null) throw new IllegalStateException("Missing block entity at " + pos);
            positions.add(pos);
        }
        verifyFrequencyFlags(level.getBlockEntity(positions.get(0)));
        int tracked = 0, empty = 0;
        for (BlockPos p : positions) {
            Object component = frequency(level.getBlockEntity(p));
            if (((Map<?, ?>) field(component.getClass(), "supportedFrequencies").get(component)).isEmpty()) empty++; else tracked++;
        }
        VulkanForge.LOGGER.info("MEK_WORKLOAD count={} emptyFrequency={} trackedFrequency={} origin={},{},{}", positions.size(), empty, tracked, baseX, y, baseZ);
    }

    private static Block block(String name) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("mekanism", name));
        if (block == null) throw new IllegalStateException("Missing Mekanism block " + name);
        return block;
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
        } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    private static Object frequency(BlockEntity tile) throws Exception {
        return Class.forName("mekanism.common.lib.frequency.IFrequencyHandler")
                .getMethod("getFrequencyComponent").invoke(tile);
    }
    private static void verifyFrequencyFlags(BlockEntity tile) throws Exception {
        Object component = frequency(tile);
        Class<?> type = component.getClass();
        var supported = (Map<?, ?>) field(type, "supportedFrequencies").get(component);
        if (!supported.isEmpty()) throw new IllegalStateException("Expected a frequency-free component in guard test");
        Field notify = field(type, "needsNotify"), save = field(type, "needsSave");
        var tick = type.getMethod("tickServer");
        for (int flags = 0; flags < 4; flags++) {
            notify.setBoolean(component, (flags & 1) != 0); save.setBoolean(component, (flags & 2) != 0);
            tick.invoke(component);
            if (notify.getBoolean(component) || save.getBoolean(component)) throw new AssertionError("Frequency flags did not clear: " + flags);
        }
        var freqType = Class.forName("mekanism.common.lib.frequency.FrequencyType");
        Object security = freqType.getField("SECURITY").get(null);
        type.getMethod("track", freqType, boolean.class, boolean.class, boolean.class).invoke(component, security, false, false, false);
        if (supported.isEmpty()) throw new AssertionError("Tracking not installed");
        save.setBoolean(component, true); tick.invoke(component);
        if (save.getBoolean(component)) throw new AssertionError("Tracked frequency skipped pending save");
        VulkanForge.LOGGER.info("MEK_FREQUENCY_FLAGS_PASS empty flag combinations and tracked pending save");
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        if (level == null) return;
        int present = 0;
        for (BlockPos p : positions) if (level.getBlockEntity(p) != null) present++;
        VulkanForge.LOGGER.info("MEK_WORKLOAD_RESULT expected={} present={}", positions.size(), present);
        if (ticket) level.setChunkForced(cx, cz, false);
        positions.clear(); level = null;
    }
}
