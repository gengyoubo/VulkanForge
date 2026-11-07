package github.com.gengyoubo.vulkanforge.server;

import com.sun.management.ThreadMXBean;
import github.com.gengyoubo.vulkanforge.VulkanForge;
import github.com.gengyoubo.vulkanforge.ae2experiment.Ae2ControllerReflectionFastPath;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/** Isolated real AE2 controller and AE2 Overclocked comparison. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class Ae2ControllerReflectionVerification {
    private static final ThreadMXBean ALLOC = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
    private static volatile double sink;
    public static final class OtherHost {
        private final Object host = new Object();
        public Object getHost() { return host; }
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (!"ae2-controller-verify".equals(System.getProperty("serverBaseline.mekScenario", ""))) return;
        if (!Boolean.getBoolean("vulkanforge.serverBaseline") || !Boolean.getBoolean("serverBaseline.ae2ControllerReflection") ||
              !Files.isRegularFile(Path.of(".vulkanforge-ae2-disposable"))) throw new IllegalStateException("Disposable AE2 verification world required");
        ServerLevel level = event.getServer().overworld(); BlockPos spawn = level.getSharedSpawnPos();
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("ae2", "controller"));
        if (block == null) throw new AssertionError("Missing AE2 controller block");
        BlockPos posA = new BlockPos((spawn.getX() >> 4) * 16 + 2, 120, (spawn.getZ() >> 4) * 16 + 2), posB = posA.east(3);
        level.setBlock(posA, block.defaultBlockState(), 2); level.setBlock(posB, block.defaultBlockState(), 2);
        BlockEntity a = level.getBlockEntity(posA), b = level.getBlockEntity(posB);
        if (a == null || b == null || !a.getClass().getName().equals("appeng.blockentity.networking.ControllerBlockEntity") || a.getClass() != b.getClass())
            throw new AssertionError("Exact controller block entities not created");
        Class<?> runtime = Class.forName("moakiee.support.EnergyCardRuntime");
        Method publicCheck = runtime.getMethod("getInstalledEnergyCards", Object.class);
        Method recursive = runtime.getDeclaredMethod("getInstalledEnergyCards", Object.class, int.class); recursive.setAccessible(true);
        Method invoke = runtime.getDeclaredMethod("tryInvokeNoArg", Object.class, String.class); invoke.setAccessible(true);
        Method field = runtime.getDeclaredMethod("tryGetField", Object.class, String.class); field.setAccessible(true);
        Method upgrades = runtime.getDeclaredMethod("tryGetInstalledFromGetUpgrades", Object.class); upgrades.setAccessible(true);
        for (Object obj : new Object[] {a, new OtherHost(), new Object()}) {
            for (int i = 0; i < 4; i++) {
                Method method = i == 0 ? upgrades : i == 3 ? field : invoke;
                Object[] params = i == 0 ? new Object[] {obj} : new Object[] {obj, i == 1 ? "getBlockEntity" : "getHost"};
                if (i == 3) params[1] = "host";
                Ae2ControllerReflectionFastPath.setEnabled(false); Object original = method.invoke(null, params);
                Ae2ControllerReflectionFastPath.setEnabled(true); Object fast = method.invoke(null, params);
                if (original != fast && (original == null || !original.equals(fast))) throw new AssertionError("Reflection lookup differs: " + i);
            }
        }
        long before = Ae2ControllerReflectionFastPath.hits();
        Ae2ControllerReflectionFastPath.setEnabled(false); Object original = recursive.invoke(null, a, 0); Object originalPublic = publicCheck.invoke(null, a);
        Ae2ControllerReflectionFastPath.setEnabled(true); Object fast = recursive.invoke(null, a, 0); Object fastPublic = publicCheck.invoke(null, a);
        if (!original.equals(fast) || !originalPublic.equals(fastPublic) || Ae2ControllerReflectionFastPath.hits() == before)
            throw new AssertionError("Controller recursive result differs or no negative lookup was optimized");
        if (invoke.invoke(null, a, "getBlockEntity") != a || Ae2ControllerReflectionFastPath.skipUpgrades(new OtherHost()))
            throw new AssertionError("Self reference or non-target behavior changed");
        Class<?> action = Class.forName("appeng.api.config.Actionable");
        Object simulate = action.getField("SIMULATE").get(null), modulate = action.getField("MODULATE").get(null);
        Method inject = a.getClass().getMethod("injectAEPower", double.class, action), current = a.getClass().getMethod("getAECurrentPower");
        for (int i = 0; i < 10; i++) {
            Object mode = i % 2 == 0 ? simulate : modulate;
            Ae2ControllerReflectionFastPath.setEnabled(false); Object r1 = inject.invoke(a, 100.0 + i, mode), s1 = current.invoke(a);
            Ae2ControllerReflectionFastPath.setEnabled(true); Object r2 = inject.invoke(b, 100.0 + i, mode), s2 = current.invoke(b);
            if (!r1.equals(r2) || !s1.equals(s2)) throw new AssertionError("Energy behavior differs at " + i);
        }
        VulkanForge.LOGGER.info("AE2_CONTROLLER_REFLECTION_PASS recursive={}, public={}, hits={}, simulated_and_real_energy_steps=10", original, originalPublic, Ae2ControllerReflectionFastPath.hits());
        Ae2ControllerReflectionFastPath.setCountHits(false);
        try {
            bench("recursive", recursive, a, null, 0);
            bench("public", publicCheck, a, null, 1);
            bench("energy_simulate", inject, a, simulate, 2);
        } finally { Ae2ControllerReflectionFastPath.setEnabled(true); Ae2ControllerReflectionFastPath.setCountHits(true); }
    }
    private static void bench(String scenario, Method method, Object target, Object action, int kind) throws Exception {
        for (int i = 0; i < 4; i++) { time(method, target, action, kind, false, 2000); time(method, target, action, kind, true, 2000); }
        for (int round = 0; round < 6; round++) {
            long[] control, candidate;
            if ((round & 1) == 0) { control = time(method, target, action, kind, false, 5000); candidate = time(method, target, action, kind, true, 5000); }
            else { candidate = time(method, target, action, kind, true, 5000); control = time(method, target, action, kind, false, 5000); }
            VulkanForge.LOGGER.info("AE2_CONTROLLER_BENCH scenario={} round={} control_ns={} candidate_ns={} control_bytes={} candidate_bytes={}", scenario, round + 1, control[0], candidate[0], control[1], candidate[1]);
        }
    }
    private static long[] time(Method method, Object target, Object action, int kind, boolean enabled, int n) throws Exception {
        Ae2ControllerReflectionFastPath.setEnabled(enabled);
        if (ALLOC != null && !ALLOC.isThreadAllocatedMemoryEnabled()) ALLOC.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().getId(), mem = ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(id), start = System.nanoTime(); double total = 0;
        for (int i = 0; i < n; i++) {
            Object value = kind == 0 ? method.invoke(null, target, 0) : kind == 1 ? method.invoke(null, target) : method.invoke(target, 100.0, action);
            total += ((Number)value).doubleValue();
        }
        long elapsed = System.nanoTime() - start, end = ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(id); sink = total;
        return new long[] {elapsed / n, mem < 0 ? -1 : (end - mem) / n};
    }
}
