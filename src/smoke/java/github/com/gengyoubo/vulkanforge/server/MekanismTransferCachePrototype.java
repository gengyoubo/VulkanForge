package github.com.gengyoubo.vulkanforge.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Development-only: caches the outer iteration order, retaining the live per-side maps. */
public final class MekanismTransferCachePrototype {
    private static final Map<Collection<?>, State> STATES = new WeakHashMap<>();
    private static final int MIN_POSITIONS = 32, STABLE_CALLS = 16;
    private static long dirtySignals, snapshots, reused;
    private static boolean enabled = true;
    private static final class State {
        ArrayList<Object> order;
        Collection<?> view;
        boolean dirty;
        int stable;
    }
    private MekanismTransferCachePrototype() { }

    /** Called before cache mutation, so even a failed or partially completed mutation is rechecked. */
    public static void markDirty(Collection<?> original) {
        State state = STATES.get(original);
        if (state != null) state.dirty = true;
        dirtySignals++;
    }

    /** Only call at the three test-instrumented network emit sites; public Mekanism APIs stay unchanged. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Collection<?> select(Collection<?> original) {
        if (!enabled) return original;
        State state = STATES.computeIfAbsent(original, ignored -> new State());
        if (state.dirty) {
            state.dirty = false;
            if (state.order == null) state.stable = 0;
            else if (!sameIdentityOrder(original, state.order)) {
                state.order = null; state.view = null; state.stable = 0;
            }
        }
        if (original.size() < MIN_POSITIONS) { state.order = null; state.view = null; state.stable = 0; return original; }
        if (state.order == null && ++state.stable >= STABLE_CALLS) {
            state.order = new ArrayList<>(original);
            state.view = Collections.unmodifiableList(state.order);
            snapshots++;
        }
        if (state.view == null) return original;
        reused++;
        return (Collection) state.view;
    }
    private static boolean sameIdentityOrder(Collection<?> original, ArrayList<Object> snapshot) {
        if (original.size() != snapshot.size()) return false;
        Iterator<?> it = original.iterator();
        for (Object entry : snapshot) if (!it.hasNext() || it.next() != entry) return false;
        return !it.hasNext();
    }
    public static long dirtySignals() { return dirtySignals; }
    public static long snapshots() { return snapshots; }
    public static long reused() { return reused; }
    static void setEnabledForVerification(boolean value) { enabled = value; }
}
