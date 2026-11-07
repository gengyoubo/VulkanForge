package github.com.gengyoubo.vulkanforge.server;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Small model of reflective host discovery, not the AE2 mod's full implementation. */
public final class TransferReflectionSimulation {
    private static final Object MISSING = new Object(), UNRESOLVED = new Object();
    private static final ThreadMXBean ALLOC = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
    private static volatile int sink;
    public static final class Missing { }
    public static final class PublicHost {
        Object host;
        public Object getHost() { return host; }
    }
    public static final class FieldHost { private Object host; }
    public static class FieldParent { private Object host; }
    public static final class InheritedField extends FieldParent { }
    public static final class Throwing { public Object getHost() { throw new IllegalStateException("fixture"); } }
    public static final class SelfHost {
        int calls;
        public Object getBlockEntity() { calls++; return this; }
    }
    static final class Plan {
        volatile Object block = UNRESOLVED, hostMethod = UNRESOLVED, hostField = UNRESOLVED, upgrades = UNRESOLVED;
        Object resolve(Class<?> c, int slot) {
            Object existing = slot == 0 ? block : slot == 1 ? hostMethod : slot == 2 ? hostField : upgrades;
            if (existing != UNRESOLVED) return existing;
            synchronized (this) {
                existing = slot == 0 ? block : slot == 1 ? hostMethod : slot == 2 ? hostField : upgrades;
                if (existing == UNRESOLVED) {
                    try {
                        if (slot == 2) { Field field = c.getDeclaredField("host"); field.setAccessible(true); existing = field; }
                        else existing = c.getMethod(slot == 0 ? "getBlockEntity" : slot == 1 ? "getHost" : "getUpgrades");
                    } catch (NoSuchMethodException | NoSuchFieldException ignored) { existing = MISSING; }
                    catch (Throwable transientFailure) { return UNRESOLVED; }
                    if (slot == 0) block = existing; else if (slot == 1) hostMethod = existing; else if (slot == 2) hostField = existing; else upgrades = existing;
                }
                return existing;
            }
        }
    }
    private static final ClassValue<Plan> PLANS = new ClassValue<>() { protected Plan computeValue(Class<?> type) { return new Plan(); } };
    static Object direct(Object target, int slot) {
        try {
            if (slot == 2) { Field field = target.getClass().getDeclaredField("host"); field.setAccessible(true); return field.get(target); }
            return target.getClass().getMethod(slot == 0 ? "getBlockEntity" : slot == 1 ? "getHost" : "getUpgrades").invoke(target);
        } catch (Throwable ignored) { return null; }
    }
    static Object cached(Object target, int slot) {
        try {
            Object member = PLANS.get(target.getClass()).resolve(target.getClass(), slot);
            if (member == MISSING || member == UNRESOLVED) return null;
            return slot == 2 ? ((Field) member).get(target) : ((Method) member).invoke(target);
        } catch (Throwable ignored) { return null; }
    }
    static Object probe(Object target, boolean cache) {
        for (int slot = 0; slot < 3; slot++) {
            Object result = cache ? cached(target, slot) : direct(target, slot);
            if (result != null) return result;
        }
        return null;
    }
    // Models the fallback for a host with no upgrade method; it retains every recursive invocation.
    static void walkSelf(Object target, boolean cache, int depth) {
        if (target == null || depth > 4) return;
        Object upgrades = cache ? cached(target, 3) : direct(target, 3);
        if (upgrades != null) throw new AssertionError("self fixture unexpectedly has upgrades");
        Object block = cache ? cached(target, 0) : direct(target, 0);
        walkSelf(block, cache, depth + 1);
        Object host = cache ? cached(target, 1) : direct(target, 1);
        walkSelf(host, cache, depth + 1);
        Object field = cache ? cached(target, 2) : direct(target, 2);
        walkSelf(field, cache, depth + 1);
    }
    static void verify() {
        Object first = new Object(), second = new Object();
        PublicHost publicHost = new PublicHost(); FieldHost fieldHost = new FieldHost(); InheritedField inherited = new InheritedField();
        ((FieldParent)inherited).host = first;
        Object[] fixtures = {new Missing(), publicHost, fieldHost, inherited, new Throwing()};
        for (int i = 0; i < 1000; i++) {
            publicHost.host = i % 3 == 0 ? null : i % 3 == 1 ? first : second;
            fieldHost.host = i % 2 == 0 ? first : second;
            for (Object fixture : fixtures) if (probe(fixture, false) != probe(fixture, true)) throw new AssertionError("reflection behavior differs");
        }
        if (probe(inherited, false) != null) throw new AssertionError("declared-field semantics changed");
        SelfHost self = new SelfHost(); walkSelf(self, false, 0); int expected = self.calls; self.calls = 0; walkSelf(self, true, 0);
        if (self.calls != expected || expected != 5) throw new AssertionError("self-recursion calls changed");
        System.out.println("REFLECTION_PARITY_PASS states=1000 fixtures=5 dynamic_values=true self_recursion_calls=" + expected);
    }
    record Timing(long ns, long allocated) { }
    static Timing run(Object fixture, boolean cache, int iterations) {
        long id = Thread.currentThread().getId(), before = ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(id), start = System.nanoTime(); int sum = 0;
        for (int i = 0; i < iterations; i++) {
            if (fixture instanceof SelfHost) walkSelf(fixture, cache, 0);
            else { Object value = probe(fixture, cache); if (value != null) sum += System.identityHashCode(value); }
        }
        if (fixture instanceof SelfHost self) sum += self.calls;
        long end = System.nanoTime(), after = ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(id); sink = sum;
        return new Timing((end - start) / iterations, before < 0 ? -1 : (after - before) / iterations);
    }
    static void bench(String name, Object fixture) {
        for (int i = 0; i < 3; i++) { run(fixture, false, 5000); run(fixture, true, 5000); }
        for (int round = 0; round < 6; round++) {
            Timing a, b;
            if ((round & 1) == 0) { a = run(fixture, false, 20000); b = run(fixture, true, 20000); }
            else { b = run(fixture, true, 20000); a = run(fixture, false, 20000); }
            System.out.printf("REFLECTION_BENCH scenario=%s round=%d original_ns=%d cached_ns=%d original_bytes=%d cached_bytes=%d%n",
                  name, round + 1, a.ns, b.ns, a.allocated, b.allocated);
        }
    }
    public static void main(String[] args) {
        if (ALLOC != null && !ALLOC.isThreadAllocatedMemoryEnabled()) ALLOC.setThreadAllocatedMemoryEnabled(true);
        verify(); PublicHost p = new PublicHost(); p.host = new Object(); FieldHost f = new FieldHost(); f.host = new Object();
        bench("missing", new Missing()); bench("public_host", p); bench("private_field", f); bench("self_reference", new SelfHost());
    }
}
