package androidx.compose.ui.semantics;

import java.util.IdentityHashMap;

/** Transparency is shared only within a synchronous semantics query. */
public final class EtaSemanticsTransparencyCache {
    private EtaSemanticsTransparencyCache() {}
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<Scope>();
    private static final class Scope {
        final Scope previous;
        final IdentityHashMap<Object, Boolean> values = new IdentityHashMap<Object, Boolean>();
        long misses;
        Scope(Scope previous) { this.previous = previous; }
    }
    public static void begin() { ACTIVE.set(new Scope(ACTIVE.get())); }
    public static boolean active() { return ACTIVE.get() != null; }
    public static Boolean peek(Object node) {
        Scope scope = ACTIVE.get();
        if (scope == null) return null;
        Boolean value = scope.values.get(node);
        if (value == null) scope.misses++;
        return value;
    }
    public static boolean put(Object node, boolean value) {
        Scope scope = ACTIVE.get();
        if (scope != null) scope.values.put(node, value);
        return value;
    }
    /** A predicate can mutate the tree, including inside a nested query. */
    public static void invalidate() {
        for (Scope scope = ACTIVE.get(); scope != null; scope = scope.previous) scope.values.clear();
    }
    public static void end() {
        Scope scope = ACTIVE.get();
        if (scope == null) throw new IllegalStateException("Missing transparency scope");
        scope.values.clear();
        if (scope.previous == null) ACTIVE.remove(); else ACTIVE.set(scope.previous);
    }
    public static long missesForTests() { Scope s = ACTIVE.get(); return s == null ? 0 : s.misses; }
}
