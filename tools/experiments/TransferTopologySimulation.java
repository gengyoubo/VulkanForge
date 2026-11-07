import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import mekanism.common.lib.distribution.IntegerSplitInfo;
import mekanism.common.lib.distribution.Target;

/** Model of stable network traversal; distribution uses Mekanism's unmodified Target and IntegerSplitInfo. */
public final class TransferTopologySimulation {
    static volatile long blackhole;
    static final class Tank {
        int amount, capacity;
        Tank(int capacity) { this.capacity = capacity; }
    }
    static final class Endpoint {
        final int id;
        final Tank tank;
        boolean valid = true, enabled = true;
        Endpoint(int id, Tank tank) { this.id = id; this.tank = tank; }
        int simulate(int amount) { return enabled ? Math.min(amount, tank.capacity - tank.amount) : 0; }
        int insert(int amount) { int n = simulate(amount); tank.amount += n; return n; }
    }
    static final class ResultTarget extends Target<Endpoint, Integer, Integer> {
        final StringBuilder trace;
        ResultTarget(int capacity, StringBuilder trace) { super(capacity); this.trace = trace; }
        protected Integer simulate(Endpoint e, Integer amount) {
            int n = e.simulate(amount); if (trace != null) trace.append('s').append(e.id).append(':').append(n).append(';'); return n;
        }
        protected void acceptAmount(Endpoint e, mekanism.common.lib.distribution.SplitInfo<Integer> split, Integer amount) {
            int n = e.insert(amount); if (trace != null) trace.append('e').append(e.id).append(':').append(n).append(';'); split.send(n);
        }
    }
    static final class Network {
        final LinkedHashMap<Integer, LinkedHashMap<Integer, Endpoint>> nodes = new LinkedHashMap<>();
        List<Endpoint> flat;
        void put(int pos, int side, Endpoint endpoint) { nodes.computeIfAbsent(pos, p -> new LinkedHashMap<>()).put(side, endpoint); flat = null; }
        void remove(int pos, int side) { var sides = nodes.get(pos); if (sides != null) { sides.remove(side); if (sides.isEmpty()) nodes.remove(pos); } flat = null; }
        void clear() { nodes.clear(); flat = null; }
        List<Endpoint> flattened() {
            if (flat == null) { var list = new ArrayList<Endpoint>(); for (var sides : nodes.values()) list.addAll(sides.values()); flat = list; }
            return flat;
        }
        void collect(ResultTarget target, Endpoint e, int amount) {
            if (e.valid) {
                int n = e.simulate(amount);
                if (target.trace != null) target.trace.append('p').append(e.id).append(':').append(n).append(';');
                if (n > 0) target.addHandler(e);
            }
        }
        int tick(boolean cached, int amount, StringBuilder trace) {
            ResultTarget target = new ResultTarget(nodes.size() * 2, trace);
            if (cached) { for (Endpoint e : flattened()) collect(target, e, amount); }
            else { for (Map<Integer, Endpoint> sides : nodes.values()) for (Endpoint e : sides.values()) collect(target, e, amount); }
            IntegerSplitInfo split = new IntegerSplitInfo(amount, target.getHandlerCount());
            if (target.getHandlerCount() == 0) return split.getTotalSent();
            target.sendPossible(amount, split);
            while (split.amountPerChanged) { split.amountPerChanged = false; target.shiftNeeded(split); }
            target.sendRemainingSplit(split);
            return split.getTotalSent();
        }
    }
    static Network make(int count, Tank[] tanks, Endpoint[] endpoints) {
        Network n = new Network();
        for (int i = 0; i < count; i++) { tanks[i] = new Tank(1000); endpoints[i] = new Endpoint(i, tanks[i]); n.put(i / 2, i % 2, endpoints[i]); }
        return n;
    }
    static void parity() {
        int count = 128; Tank[] a = new Tank[count], b = new Tank[count]; Endpoint[] ea = new Endpoint[count], eb = new Endpoint[count];
        Network baseline = make(count, a, ea), candidate = make(count, b, eb);
        // Two different sides share a tank, as on a multiblock; keep that relationship in both copies.
        ea[1] = new Endpoint(1, a[0]); eb[1] = new Endpoint(1, b[0]); baseline.put(0, 1, ea[1]); candidate.put(0, 1, eb[1]);
        Random random = new Random(0x4d454bL);
        for (int tick = 0; tick < 10000; tick++) {
            int id = random.nextInt(count), event = random.nextInt(9), pos = id / 2, side = id % 2;
            switch (event) {
                case 0 -> { baseline.remove(pos, side); candidate.remove(pos, side); }
                case 1 -> { baseline.put(pos, side, ea[id]); candidate.put(pos, side, eb[id]); }
                case 2 -> { ea[id].valid = !ea[id].valid; eb[id].valid = ea[id].valid; }
                case 3 -> { ea[id].enabled = !ea[id].enabled; eb[id].enabled = ea[id].enabled; }
                case 4 -> { int v = random.nextInt(1001); a[id].amount = Math.min(v, a[id].capacity); b[id].amount = Math.min(v, b[id].capacity); }
                case 5 -> { int v = random.nextInt(1001); a[id].capacity = v; b[id].capacity = v; a[id].amount = Math.min(a[id].amount, v); b[id].amount = Math.min(b[id].amount, v); }
                case 6 -> { // Simulate unloading then reloading the complete network.
                    baseline.clear(); candidate.clear();
                    for (int j = 0; j < count; j++) { baseline.put(j / 2, j % 2, ea[j]); candidate.put(j / 2, j % 2, eb[j]); }
                }
            }
            int amount = 1 + random.nextInt(2000); StringBuilder ta = new StringBuilder(), tb = new StringBuilder();
            int sentA = baseline.tick(false, amount, ta), sentB = candidate.tick(true, amount, tb);
            if (sentA != sentB || !ta.toString().equals(tb.toString())) throw new AssertionError("trace mismatch at tick " + tick);
            for (int j = 0; j < count; j++) if (a[j].amount != b[j].amount || a[j].amount > a[j].capacity) throw new AssertionError("tank mismatch at " + tick);
        }
        System.out.println("PARITY_PASS ticks=10000 shared_storage=true endpoint_updates_between_ticks=true");
    }
    static long bench(Network network, Tank[] tanks, boolean flat, int iterations, int invalidateEvery) {
        long start = System.nanoTime(), sum = 0;
        for (int i = 0; i < iterations; i++) {
            if (invalidateEvery != 0 && i % invalidateEvery == 0) network.flat = null;
            for (Tank t : tanks) t.amount = 0;
            sum += network.tick(flat, 10000, null);
        }
        blackhole = sum;
        return System.nanoTime() - start;
    }
    static void benchmark(int count, int iterations, int invalidateEvery) {
        Tank[] tanks = new Tank[count]; Endpoint[] endpoints = new Endpoint[count]; Network network = make(count, tanks, endpoints);
        for (int i = 0; i < 4; i++) { bench(network, tanks, false, iterations, invalidateEvery); bench(network, tanks, true, iterations, invalidateEvery); }
        for (int i = 0; i < 6; i++) {
            long a, b;
            if ((i & 1) == 0) { a = bench(network, tanks, false, iterations, invalidateEvery); b = bench(network, tanks, true, iterations, invalidateEvery); }
            else { b = bench(network, tanks, true, iterations, invalidateEvery); a = bench(network, tanks, false, iterations, invalidateEvery); }
            System.out.printf(java.util.Locale.ROOT, "BENCH endpoints=%d invalidateEvery=%d round=%d nested_ns=%.1f flat_ns=%.1f%n", count, invalidateEvery, i + 1, (double)a / iterations, (double)b / iterations);
        }
    }
    public static void main(String[] args) { parity(); benchmark(64, 80000, 0); benchmark(512, 12000, 0); benchmark(512, 12000, 32); benchmark(512, 12000, 1); }
}
