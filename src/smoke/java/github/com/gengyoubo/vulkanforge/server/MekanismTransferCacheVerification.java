package github.com.gengyoubo.vulkanforge.server;

import github.com.gengyoubo.vulkanforge.VulkanForge;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.common.Mod;

/** Tests the actual Mekanism fluid emitter and network cache in a marked development server. */
@Mod.EventBusSubscriber(modid = VulkanForge.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class MekanismTransferCacheVerification {
    private static final StringBuilder TRACE = new StringBuilder();
    private static int stored;
    private static boolean traceEnabled = true;
    private static final class Handler implements IFluidHandler {
        final int id;
        Handler(int id) { this.id = id; }
        public int getTanks() { return 1; }
        public FluidStack getFluidInTank(int tank) { return stored == 0 ? FluidStack.EMPTY : new FluidStack(Fluids.WATER, stored); }
        public int getTankCapacity(int tank) { return 600; }
        public boolean isFluidValid(int tank, FluidStack stack) { return stack.getFluid() == Fluids.WATER; }
        public int fill(FluidStack stack, FluidAction action) {
            int n = isFluidValid(0, stack) ? Math.min(stack.getAmount(), 600 - stored) : 0;
            if (traceEnabled) TRACE.append(id).append(':').append(action).append(':').append(n).append(';');
            if (action == FluidAction.EXECUTE) stored += n;
            return n;
        }
        public FluidStack drain(FluidStack resource, FluidAction action) { return FluidStack.EMPTY; }
        public FluidStack drain(int maxDrain, FluidAction action) { return FluidStack.EMPTY; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
        } catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(name);
    }
    @SuppressWarnings("unchecked")
    private static Map<BlockPos, Map<Direction, LazyOptional<IFluidHandler>>> map(Object cache) throws Exception {
        return (Map<BlockPos, Map<Direction, LazyOptional<IFluidHandler>>>) field(cache.getClass(), "cachedAcceptors").get(cache);
    }
    private static void add(Object cache, int pos, int id) throws Exception {
        var sides = new EnumMap<Direction, LazyOptional<IFluidHandler>>(Direction.class);
        sides.put(Direction.NORTH, LazyOptional.of(() -> new Handler(id)));
        map(cache).put(new BlockPos(pos, 80, 0), sides);
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if (!"transfer-cache-verify".equals(System.getProperty("serverBaseline.mekScenario", ""))) return;
        if (!Boolean.getBoolean("vulkanforge.serverBaseline") || !Boolean.getBoolean("serverBaseline.transferCache") ||
              !Files.isRegularFile(Path.of(".vulkanforge-mekanism-disposable"))) throw new IllegalStateException("Disposable transfer-cache test required");
        Class<?> fluidType = Class.forName("mekanism.common.content.network.FluidNetwork");
        Class<?> cacheType = Class.forName("mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache");
        // Also force transform verification for the other supported resource types.
        Class.forName("mekanism.common.content.network.EnergyNetwork");
        Class.forName("mekanism.common.content.network.BoxedChemicalNetwork");
        Object network = fluidType.getConstructor(UUID.class).newInstance(UUID.randomUUID());
        Object cache = field(fluidType, "acceptorCache").get(network);
        var tick = fluidType.getDeclaredMethod("tickEmit", FluidStack.class); tick.setAccessible(true);
        for (int i = 0; i < 40; i++) add(cache, i, i);
        if (map(cache).values() != map(cache).values()) throw new AssertionError("Mekanism values view is not stable");
        String baseline = sample(tick, network, false), candidate = null;
        for (int i = 0; i < 24; i++) candidate = sample(tick, network, true);
        if (!baseline.startsWith("600|") || !baseline.equals(candidate) || MekanismTransferCachePrototype.snapshots() == 0 || MekanismTransferCachePrototype.reused() == 0)
            throw new AssertionError("Fluid sender trace differs or cache did not activate");
        map(cache).get(new BlockPos(0, 80, 0)).get(Direction.NORTH).invalidate();
        if (!sample(tick, network, false).equals(sample(tick, network, true))) throw new AssertionError("Trace differs after capability invalidation");
        long signals = MekanismTransferCachePrototype.dirtySignals();
        Object existing = cacheType.getConstructor().newInstance(); add(existing, 1, 101);
        cacheType.getMethod("adoptAcceptors", cacheType).invoke(cache, existing);
        if (MekanismTransferCachePrototype.dirtySignals() <= signals || !sample(tick, network, false).equals(sample(tick, network, true)))
            throw new AssertionError("Trace differs after existing side replacement");
        signals = MekanismTransferCachePrototype.dirtySignals();
        Object other = cacheType.getConstructor().newInstance(); add(other, 100, 100);
        cacheType.getMethod("adoptAcceptors", cacheType).invoke(cache, other);
        if (MekanismTransferCachePrototype.dirtySignals() <= signals) throw new AssertionError("Missing adopt invalidation");
        baseline = sample(tick, network, false); candidate = sample(tick, network, true);
        if (!baseline.equals(candidate)) throw new AssertionError("Trace differs after topology change");
        signals = MekanismTransferCachePrototype.dirtySignals();
        cacheType.getMethod("deregister").invoke(cache);
        if (MekanismTransferCachePrototype.dirtySignals() <= signals || !sample(tick, network, true).startsWith("0|"))
            throw new AssertionError("Deregistration did not invalidate the transfer cache");
        VulkanForge.LOGGER.info("MEK_TRANSFER_CACHE_PASS fluid traces, shared tank, invalidation, adopt and deregister; snapshots={} reuse={}",
              MekanismTransferCachePrototype.snapshots(), MekanismTransferCachePrototype.reused());
        performance(fluidType, tick, 40);
        performance(fluidType, tick, 256);
    }
    private static String sample(java.lang.reflect.Method tick, Object network, boolean enabled) throws Exception {
        MekanismTransferCachePrototype.setEnabledForVerification(enabled); stored = 0; TRACE.setLength(0);
        int sent = (Integer) tick.invoke(network, new FluidStack(Fluids.WATER, 1000));
        if (stored != sent) throw new AssertionError("Fluid not conserved");
        return sent + "|" + TRACE;
    }
    private static void performance(Class<?> fluidType, java.lang.reflect.Method tick, int endpoints) throws Exception {
        Object network = fluidType.getConstructor(UUID.class).newInstance(UUID.randomUUID());
        Object cache = field(fluidType, "acceptorCache").get(network);
        for (int i = 0; i < endpoints; i++) add(cache, i, i);
        traceEnabled = false;
        try {
            for (int i = 0; i < 6; i++) { time(tick, network, false, 5000); time(tick, network, true, 5000); }
            for (int round = 0; round < 6; round++) {
                long control, optimized;
                if ((round & 1) == 0) { control = time(tick, network, false, 10000); optimized = time(tick, network, true, 10000); }
                else { optimized = time(tick, network, true, 10000); control = time(tick, network, false, 10000); }
                VulkanForge.LOGGER.info("MEK_TRANSFER_BENCH endpoints={} round={} control_ns={} candidate_ns={}", endpoints, round + 1, control / 10000, optimized / 10000);
            }
        } finally { traceEnabled = true; }
    }
    private static long time(java.lang.reflect.Method tick, Object network, boolean enabled, int iterations) throws Exception {
        MekanismTransferCachePrototype.setEnabledForVerification(enabled);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        long start = System.nanoTime(); int total = 0;
        for (int i = 0; i < iterations; i++) { stored = 0; total += (Integer) tick.invoke(network, water); }
        if (total != iterations * 600) throw new AssertionError("Benchmark transfer mismatch");
        return System.nanoTime() - start;
    }
}
