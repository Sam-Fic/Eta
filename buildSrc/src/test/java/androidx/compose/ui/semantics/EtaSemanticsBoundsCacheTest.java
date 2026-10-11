package androidx.compose.ui.semantics;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class EtaSemanticsBoundsCacheTest {
    @After public void clear() {
        assertFalse(EtaSemanticsBoundsCache.isActiveForTests());
        EtaSemanticsBoundsCache.setProbeForTests(null);
    }
    @Test public void identityAndIndependentInvocations() {
        Object first = new String("same"), second = new String("same"), rect = new Object();
        long[] closed = new long[4];
        EtaSemanticsBoundsCache.setProbeForTests((depth, requests, computations, entries) -> {
            closed[0] = depth; closed[1] = requests; closed[2] = computations; closed[3] = entries;
        });
        EtaSemanticsBoundsCache.begin();
        try {
            assertNull(EtaSemanticsBoundsCache.peek(first));
            EtaSemanticsBoundsCache.put(first, rect);
            assertSame(rect, EtaSemanticsBoundsCache.peek(first));
            assertNull(EtaSemanticsBoundsCache.peek(second));
        } finally { EtaSemanticsBoundsCache.end(); }
        assertArrayEquals(new long[]{1, 3, 2, 1}, closed);
        EtaSemanticsBoundsCache.begin();
        try { assertNull(EtaSemanticsBoundsCache.peek(first)); }
        finally { EtaSemanticsBoundsCache.end(); }
    }
    @Test public void nestedFailureRestoresParentAndCloses() {
        Object node = new Object(), parent = new Object(), child = new Object();
        EtaSemanticsBoundsCache.begin();
        try {
            EtaSemanticsBoundsCache.put(node, parent);
            try {
                EtaSemanticsBoundsCache.begin();
                try {
                    assertNull(EtaSemanticsBoundsCache.peek(node));
                    EtaSemanticsBoundsCache.put(node, child);
                    throw new IllegalArgumentException("fixture");
                } finally { EtaSemanticsBoundsCache.end(); }
            } catch (IllegalArgumentException expected) {
                assertEquals("fixture", expected.getMessage());
            }
            assertSame(parent, EtaSemanticsBoundsCache.peek(node));
        } finally { EtaSemanticsBoundsCache.end(); }
    }
    @Test public void threadsDoNotShareGeometry() throws Exception {
        Object node = new Object(), rect = new Object();
        Throwable[] failure = new Throwable[1];
        EtaSemanticsBoundsCache.begin();
        try {
            EtaSemanticsBoundsCache.put(node, rect);
            Thread other = new Thread(() -> {
                try {
                    assertFalse(EtaSemanticsBoundsCache.isActiveForTests());
                    EtaSemanticsBoundsCache.begin();
                    try { assertNull(EtaSemanticsBoundsCache.peek(node)); }
                    finally { EtaSemanticsBoundsCache.end(); }
                } catch (Throwable t) { failure[0] = t; }
            });
            other.start(); other.join();
            if (failure[0] != null) throw new AssertionError(failure[0]);
            assertSame(rect, EtaSemanticsBoundsCache.peek(node));
        } finally { EtaSemanticsBoundsCache.end(); }
    }
}
