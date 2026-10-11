package eta.build;

import androidx.compose.ui.semantics.EtaSemanticsTransparencyCache;
import org.junit.Test;
import static org.junit.Assert.*;

public class EtaSemanticsTransparencyCacheTest {
    @Test public void nestedMutationInvalidatesParentAndDoesNotRetainAcrossQueries() {
        Object node = new Object();
        assertFalse(EtaSemanticsTransparencyCache.active());
        EtaSemanticsTransparencyCache.begin();
        try {
            EtaSemanticsTransparencyCache.put(node, false);
            assertEquals(Boolean.FALSE, EtaSemanticsTransparencyCache.peek(node));
            EtaSemanticsTransparencyCache.begin();
            try {
                assertNull(EtaSemanticsTransparencyCache.peek(node));
                EtaSemanticsTransparencyCache.put(node, false);
                EtaSemanticsTransparencyCache.invalidate();
                assertNull(EtaSemanticsTransparencyCache.peek(node));
                EtaSemanticsTransparencyCache.put(node, true);
            } finally { EtaSemanticsTransparencyCache.end(); }
            assertNull(EtaSemanticsTransparencyCache.peek(node));
        } finally { EtaSemanticsTransparencyCache.end(); }
        assertFalse(EtaSemanticsTransparencyCache.active());
        EtaSemanticsTransparencyCache.begin();
        try { assertNull(EtaSemanticsTransparencyCache.peek(node)); }
        finally { EtaSemanticsTransparencyCache.end(); }
    }
    @Test public void identityAndThreadIsolation() throws Exception {
        Object a = new String("equal"), b = new String("equal");
        EtaSemanticsTransparencyCache.begin();
        try {
            EtaSemanticsTransparencyCache.put(a, true);
            assertNull(EtaSemanticsTransparencyCache.peek(b));
            boolean[] isolated = {false};
            Thread t = new Thread(() -> isolated[0] = !EtaSemanticsTransparencyCache.active());
            t.start(); t.join(); assertTrue(isolated[0]);
            assertEquals(Boolean.TRUE, EtaSemanticsTransparencyCache.peek(a));
        } finally { EtaSemanticsTransparencyCache.end(); }
    }
}
