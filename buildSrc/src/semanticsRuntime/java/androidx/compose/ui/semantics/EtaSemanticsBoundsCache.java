package androidx.compose.ui.semantics;

import java.util.IdentityHashMap;

/** Geometry lives only for one synchronous sort, never across traversals. */
public final class EtaSemanticsBoundsCache {
    private EtaSemanticsBoundsCache() {}
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<Scope>();
    private static final ThreadLocal<Probe> PROBE = new ThreadLocal<Probe>();

    public interface Probe {
        void onClose(int depth, long requests, long computations, int uniqueEntries);
    }
    private static final class Scope {
        final Scope previous;
        final int depth;
        final Probe probe;
        final IdentityHashMap<Object, Object> bounds = new IdentityHashMap<Object, Object>();
        long requests;
        long computations;
        Scope(Scope previous, Probe probe) {
            this.previous = previous;
            this.depth = previous == null ? 1 : previous.depth + 1;
            this.probe = probe;
        }
    }
    public static void begin() { ACTIVE.set(new Scope(ACTIVE.get(), PROBE.get())); }
    public static Object peek(Object node) {
        Scope scope = ACTIVE.get();
        if (scope == null) return null;
        Object value = scope.bounds.get(node);
        if (scope.probe != null) {
            scope.requests++;
            if (value == null) scope.computations++;
        }
        return value;
    }
    public static void put(Object node, Object bounds) {
        Scope scope = ACTIVE.get();
        if (scope != null && bounds != null) scope.bounds.put(node, bounds);
    }
    public static void end() {
        Scope scope = ACTIVE.get();
        if (scope == null) throw new IllegalStateException("Missing Compose semantics cache scope");
        int uniqueEntries = scope.bounds.size();
        scope.bounds.clear();
        if (scope.previous == null) ACTIVE.remove(); else ACTIVE.set(scope.previous);
        if (scope.probe != null) scope.probe.onClose(scope.depth, scope.requests, scope.computations, uniqueEntries);
    }
    public static void setProbeForTests(Probe probe) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Cannot change probe during a sort");
        if (probe == null) PROBE.remove(); else PROBE.set(probe);
    }
    public static boolean isActiveForTests() { return ACTIVE.get() != null; }
}
