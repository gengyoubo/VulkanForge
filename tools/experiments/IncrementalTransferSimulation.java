import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Models fastutil outer ordering and EnumMap side ordering; no Minecraft world is modified. */
public final class IncrementalTransferSimulation {
    enum Side { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    enum Mode { NESTED, REBUILD, INCREMENTAL, ADAPTIVE }
    record Pos(int x, int z) { }
    static final class Group {
        TransferTopologySimulation.Endpoint[] endpoints;
        Group(EnumMap<Side, TransferTopologySimulation.Endpoint> sides) { refresh(sides); }
        void refresh(EnumMap<Side, TransferTopologySimulation.Endpoint> sides) { endpoints = sides.values().toArray(TransferTopologySimulation.Endpoint[]::new); }
    }
    static final class Network {
        final Object2ObjectOpenHashMap<Pos, EnumMap<Side, TransferTopologySimulation.Endpoint>> nodes = new Object2ObjectOpenHashMap<>();
        final Mode mode;
        ArrayList<Group> groups;
        HashMap<Pos, Group> byPosition;
        int stable;
        Network(Mode mode) { this.mode = mode; }
        void changed(Pos pos, boolean structural) {
            if (structural) { groups = null; byPosition = null; stable = 0; }
            else if (mode == Mode.REBUILD) { groups = null; byPosition = null; }
            else if (groups != null && mode != Mode.NESTED) {
                Group group = byPosition.get(pos);
                if (group == null) throw new AssertionError("missing group");
                group.refresh(nodes.get(pos));
            }
        }
        void put(Pos pos, Side side, TransferTopologySimulation.Endpoint e) {
            var sides = nodes.get(pos); boolean structural = sides == null;
            if (structural) { sides = new EnumMap<>(Side.class); nodes.put(pos, sides); }
            sides.put(side, e); changed(pos, structural);
        }
        void remove(Pos pos, Side side) {
            var sides = nodes.get(pos); if (sides == null || sides.remove(side) == null) return;
            boolean structural = sides.isEmpty(); if (structural) nodes.remove(pos); changed(pos, structural);
        }
        void clear() { nodes.clear(); groups = null; byPosition = null; stable = 0; }
        void rebuild() {
            groups = new ArrayList<>(nodes.size()); byPosition = mode == Mode.REBUILD ? null : new HashMap<>();
            for (var entry : nodes.entrySet()) { Group group = new Group(entry.getValue()); groups.add(group); if (byPosition != null) byPosition.put(entry.getKey(), group); }
        }
        int tick(int amount, StringBuilder trace) {
            var target = new TransferTopologySimulation.ResultTarget(nodes.size() * 2, trace);
            boolean useGroups = mode != Mode.NESTED && (mode != Mode.ADAPTIVE || (nodes.size() >= 32 && stable >= 16));
            if (useGroups) {
                if (groups == null) rebuild();
                for (Group group : groups) for (var e : group.endpoints) collect(target, e, amount);
            } else {
                for (var sides : nodes.values()) for (var e : sides.values()) collect(target, e, amount);
            }
            stable++;
            var split = new mekanism.common.lib.distribution.IntegerSplitInfo(amount, target.getHandlerCount());
            if (target.getHandlerCount() == 0) return split.getTotalSent();
            target.sendPossible(amount, split);
            while (split.amountPerChanged) { split.amountPerChanged = false; target.shiftNeeded(split); }
            target.sendRemainingSplit(split); return split.getTotalSent();
        }
        static void collect(TransferTopologySimulation.ResultTarget t, TransferTopologySimulation.Endpoint e, int amount) {
            if (!e.valid) return;
            int n = e.simulate(amount);
            if (t.trace != null) t.trace.append('p').append(e.id).append(':').append(n).append(';');
            if (n > 0) t.addHandler(e);
        }
    }
    static final class World {
        final Network network;
        final TransferTopologySimulation.Tank[] tanks;
        final TransferTopologySimulation.Endpoint[] endpoints;
        World(Mode mode, int count) {
            network = new Network(mode); tanks = new TransferTopologySimulation.Tank[count]; endpoints = new TransferTopologySimulation.Endpoint[count];
            for (int i = 0; i < count; i++) { tanks[i] = new TransferTopologySimulation.Tank(1000); endpoints[i] = new TransferTopologySimulation.Endpoint(i, tanks[i]); }
            endpoints[1] = new TransferTopologySimulation.Endpoint(1, tanks[0]);
            for (int i = 0; i < count; i++) network.put(new Pos(i / 2, 0), Side.values()[i % 2], endpoints[i]);
        }
    }
    static void parity() {
        int count = 128; World[] worlds = new World[Mode.values().length];
        for (Mode mode : Mode.values()) worlds[mode.ordinal()] = new World(mode, count);
        Random r = new Random(841996L);
        for (int step = 0; step < 20000; step++) {
            int id = r.nextInt(count), event = r.nextInt(12), val = r.nextInt(1001), amount = r.nextInt(2000) + 1;
            for (World w : worlds) {
                Pos pos = new Pos(id / 2, 0); Side side = Side.values()[id % 2];
                switch (event) {
                    case 0 -> w.network.remove(pos, side);
                    case 1, 2 -> w.network.put(pos, side, w.endpoints[id]);
                    case 3 -> w.endpoints[id].valid = !w.endpoints[id].valid;
                    case 4 -> w.endpoints[id].enabled = !w.endpoints[id].enabled;
                    case 5 -> w.tanks[id].amount = Math.min(val, w.tanks[id].capacity);
                    case 6 -> { w.tanks[id].capacity = val; w.tanks[id].amount = Math.min(val, w.tanks[id].amount); }
                    case 7 -> { // Bulk rebuild representing changed membership, not actual Mekanism merge/split.
                        w.network.clear(); for (int j = 0; j < count; j++) w.network.put(new Pos(j / 2, 0), Side.values()[j % 2], w.endpoints[j]);
                    }
                }
            }
            String expected = null; int sent = -1;
            for (World w : worlds) {
                StringBuilder trace = new StringBuilder(); int current = w.network.tick(amount, trace);
                if (expected == null) { expected = trace.toString(); sent = current; }
                else if (sent != current || !expected.equals(trace.toString())) throw new AssertionError("trace mismatch " + w.network.mode + " step=" + step);
                for (int j = 0; j < count; j++) if (w.tanks[j].amount != worlds[0].tanks[j].amount) throw new AssertionError("tank mismatch");
            }
        }
        System.out.println("PARITY_PASS steps=20000 modes=4 hash_outer=true sorted_sides=true");
    }
    static long bench(Mode mode, int count, int period, int steps, boolean structural) {
        World w = new World(mode, count); long sum = 0; long start = System.nanoTime();
        for (int i = 0; i < steps; i++) {
            if (period != 0 && i % period == 0) {
                int id = (((i / period) / 2) % (count / 2)) * 2 + 1;
                Pos pos = structural ? new Pos(1000000 + id, 0) : new Pos(id / 2, 0);
                boolean add = (((i / period) & 1) == 0) == structural;
                if (add) w.network.put(pos, Side.UP, w.endpoints[id]); else w.network.remove(pos, Side.UP);
            }
            for (var tank : w.tanks) tank.amount = 0;
            sum += w.network.tick(10000, null);
        }
        TransferTopologySimulation.blackhole = sum; return System.nanoTime() - start;
    }
    static void benchmark(int count, int period, int steps, boolean structural) {
        for (Mode m : Mode.values()) for (int i = 0; i < 3; i++) bench(m, count, period, steps, structural);
        for (int round = 0; round < 6; round++) {
            Mode[] modes = Mode.values();
            for (int k = 0; k < modes.length; k++) {
                Mode mode = modes[(k + round) % modes.length];
                double ns = (double) bench(mode, count, period, steps, structural) / steps;
                System.out.printf(java.util.Locale.ROOT, "BENCH endpoints=%d period=%d structural=%s round=%d mode=%s ns=%.1f%n", count, period, structural, round + 1, mode, ns);
            }
        }
    }
    public static void main(String[] args) { parity(); benchmark(16, 0, 70000, false); benchmark(16, 1, 70000, false); benchmark(64, 0, 40000, false); benchmark(512, 0, 6000, false); benchmark(512, 32, 6000, false); benchmark(512, 1, 6000, false); benchmark(512, 32, 6000, true); benchmark(512, 1, 6000, true); }
}
