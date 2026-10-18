package io.github.mangi.eta.ui.semantics

import android.app.Application
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeTransparencyEquivalenceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private val cache by lazy { Class.forName("androidx.compose.ui.semantics.EtaSemanticsTransparencyCache") }
    private val coordinator by lazy { Class.forName("androidx.compose.ui.node.NodeCoordinator") }
    private val original by lazy { TransparencyBaseline() }
    private fun cache(name: String): Any? = invoke(cache.getMethod(name), null)
    private fun render(overlap: Boolean = false) {
        compose.setContent {
            view = LocalView.current
            Layout(modifier = Modifier.graphicsLayer { alpha = 1f }.testTag("root").semantics { isTraversalGroup = true },
                content = {
                    repeat(12) { i ->
                        Layout(modifier = Modifier.testTag("child$i").semantics { contentDescription = "child$i" }, content = {}) { _, _ ->
                            layout(20, 20) {}
                        }
                    }
                }) { ms, _ ->
                val ps = ms.map { it.measure(Constraints.fixed(20, 20)) }
                layout(300, 240) { ps.forEachIndexed { i, p -> p.place(if (overlap) 0 else i * 22, 20) } }
            }
        }
        compose.waitForIdle()
    }
    private fun root() = compose.onNodeWithTag("root", useUnmergedTree = true).fetchSemanticsNode()
    private fun coord(node: SemanticsNode): Any = requireNotNull(invoke(
        SemanticsNode::class.java.getMethod("findCoordinatorToGetBounds\$ui"), node))
    private fun transparent(c: Any, memo: Boolean): Boolean = invoke(
        coordinator.getMethod(if (memo) "eta\$isTransparent" else "isTransparent"), c) as Boolean
    private fun semanticTransparent(n: SemanticsNode, baseline: Boolean): Boolean =
        if (baseline) transparent(coord(n), false) else invoke(
            SemanticsNode::class.java.getMethod("isTransparent\$ui"), n) as Boolean
    private fun layer(c: Any, alpha: Float?) {
        val block: (GraphicsLayerScope.() -> Unit)? = alpha?.let { value -> { this.alpha = value } }
        invoke(coordinator.getMethod("updateLayerBlock", kotlin.jvm.functions.Function1::class.java, Boolean::class.javaPrimitiveType), c, block, true)
    }
    private fun sort(r: SemanticsNode, baseline: Boolean, focus: (SemanticsNode) -> Boolean): List<Int> {
        val c = if (baseline) original.sort else Class.forName("androidx.compose.ui.semantics.SemanticsSortKt")
        val method = c.getDeclaredMethod("subtreeSortedByGeometryGrouping", SemanticsNode::class.java,
            kotlin.jvm.functions.Function1::class.java, kotlin.jvm.functions.Function1::class.java, List::class.java)
        val visible: (SemanticsNode) -> Boolean = { true }
        @Suppress("UNCHECKED_CAST")
        return (invoke(method, null, r, visible, focus, r.children) as List<SemanticsNode>).map { it.id }
    }
    @Test fun sharedAncestorComputedOnceAndRealLayerWritesInvalidateNestedScopes() {
        render(); val r = root()
        compose.runOnIdle {
            val parent = coord(r); val children = r.children.map(::coord)
            cache("begin")
            try {
                children.forEach { assertEquals(transparent(it, false), transparent(it, true)) }
                val first = cache("missesForTests") as Long
                val parentGetter = coordinator.getMethod("getWrappedBy\$ui")
                val unique = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
                val repeatedWalks = children.sumOf { child ->
                    var current: Any? = child
                    var depth = 0L
                    while (current != null) { depth++; unique.add(current); current = invoke(parentGetter, current) }
                    depth
                }
                assertTrue(first > 0L)
                assertEquals(unique.size.toLong(), first)
                assertTrue("Shared ancestors must reduce cold computations", first < repeatedWalks)
                repeat(3) { children.forEach { assertFalse(transparent(it, true)) } }
                assertEquals(first, cache("missesForTests"))
                cache("begin")
                try {
                    children.forEach { transparent(it, true) }
                    layer(parent, 0f)
                    children.forEach { assertTrue(transparent(it, false)); assertTrue(transparent(it, true)) }
                } finally { cache("end") }
                children.forEach { assertTrue(transparent(it, true)) }
                layer(parent, 1f)
                children.forEach { assertFalse(transparent(it, true)) }
                layer(parent, 0f); assertTrue(transparent(parent, true))
                layer(parent, null); assertFalse(transparent(parent, true))
                layer(parent, 1f); assertFalse(transparent(parent, true))
                val child = children.first()
                val getParent = coordinator.getMethod("getWrappedBy\$ui")
                val setParent = coordinator.getMethod("setWrappedBy\$ui", coordinator)
                val prior = invoke(getParent, child)
                layer(parent, 0f); assertTrue(transparent(child, true))
                try {
                    invoke(setParent, child, null)
                    assertEquals(transparent(child, false), transparent(child, true))
                    assertFalse(transparent(child, true))
                } finally { invoke(setParent, child, prior) }
                assertTrue(transparent(child, true))
            } finally { layer(parent, 1f); cache("end") }
            assertEquals(false, cache("active"))
        }
    }
    @Test fun predicateMutationReentryAndExceptionsPreserveOfficialOrderAndCleanup() {
        render(); val r = root()
        compose.runOnIdle {
            val parent = coord(r)
            fun round(baseline: Boolean): Pair<List<Int>, List<Int>> {
                layer(parent, 1f)
                val calls = mutableListOf<Int>()
                val focus: (SemanticsNode) -> Boolean = { n ->
                    if (!baseline) assertEquals(true, cache("active"))
                    calls += n.id
                    if (calls.size == 2) {
                        // Reenter the public query while the outer query already has memoized ancestors.
                        sort(r, baseline) {
                            layer(parent, 0f)
                            !semanticTransparent(it, baseline)
                        }
                    }
                    !semanticTransparent(n, baseline)
                }
                return sort(r, baseline, focus) to calls
            }
            try {
                val official = round(true)
                val patched = round(false)
                assertEquals(official, patched)
                assertEquals(1, patched.first.size)
                assertEquals(false, cache("active"))
                val fault = IllegalStateException("predicate")
                try { sort(r, false) { transparent(coord(it), true); throw fault }; fail("Expected predicate failure") }
                catch (e: IllegalStateException) { assertSame(fault, e) }
                assertEquals(false, cache("active"))
                layer(parent, 1f)
                var nested = false
                sort(r, false) { n ->
                    if (!nested) {
                        assertFalse(semanticTransparent(n, false))
                        nested = true
                        try {
                            sort(r, false) {
                                layer(parent, 0f)
                                assertTrue(semanticTransparent(it, false))
                                throw fault
                            }
                            fail("Expected nested failure")
                        } catch (e: IllegalStateException) { assertSame(fault, e) }
                        assertEquals(true, cache("active"))
                        assertTrue(semanticTransparent(n, false))
                    }
                    !semanticTransparent(n, false)
                }
                assertTrue(nested)
                assertEquals(false, cache("active"))
            } finally { layer(parent, 1f) }
        }
    }
    @Test fun uncoveredNodesBoundsAndIgnoreOrderMatchOfficialAcrossTransparencyChanges() {
        render(overlap = true); val r = root()
        compose.runOnIdle {
            val ownerMethod = view.javaClass.getMethod("getSemanticsOwner").apply { isAccessible = true }
            val owner = requireNotNull(invoke(ownerMethod, view))
            fun collect(baseline: Boolean): Pair<Map<Int, String>, List<Int>> {
                val c = if (baseline) original.owner else Class.forName("androidx.compose.ui.semantics.SemanticsOwnerKt")
                val method = c.declaredMethods.single { it.name == "getAllUncoveredSemanticsNodesToIntObjectMap" }
                val visited = mutableListOf<Int>()
                val ignore: (SemanticsNode) -> Boolean = {
                    if (!baseline) assertEquals(true, cache("active"))
                    visited += it.id; false
                }
                @Suppress("UNCHECKED_CAST")
                val nodes = invoke(method, null, owner, -1, ignore) as androidx.collection.IntObjectMap<Any>
                val result = mutableMapOf<Int, String>()
                nodes.forEach { key, value ->
                    val node = invoke(value.javaClass.getMethod("getSemanticsNode"), value) as SemanticsNode
                    val bounds = invoke(value.javaClass.getMethod("getAdjustedBounds"), value)
                    result[key] = "${node.id}:$bounds"
                }
                return result to visited
            }
            val parent = coord(r)
            try {
                for (alpha in listOf(1f, 0f, 0.5f)) {
                    layer(parent, alpha)
                    val official = collect(true)
                    assertEquals(official, collect(false))
                    val visibleChildren = official.first.values.count { value -> r.children.any { value.startsWith("${it.id}:") } }
                    assertEquals(if (alpha == 0f) 12 else 1, visibleChildren)
                    assertEquals(false, cache("active"))
                }
                val entry = Class.forName("androidx.compose.ui.semantics.SemanticsOwnerKt")
                    .declaredMethods.single { it.name == "getAllUncoveredSemanticsNodesToIntObjectMap" }
                val fault = IllegalStateException("ignore predicate")
                val ignore: (SemanticsNode) -> Boolean = { throw fault }
                try { invoke(entry, null, owner, -1, ignore); fail("Expected ignore failure") }
                catch (e: IllegalStateException) { assertSame(fault, e) }
                assertEquals(false, cache("active"))
            } finally { layer(parent, 1f) }
        }
    }
}

private fun invoke(m: Method, target: Any?, vararg args: Any?): Any? {
    try { return m.invoke(target, *args) }
    catch (e: InvocationTargetException) { throw e.targetException }
}
private class TransparencyBaseline {
    val sort: Class<*>
    val owner: Class<*>
    init {
        val bytes = File(requireNotNull(System.getProperty("eta.compose.semantics.original"))).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == "2a4e13582e201f7d52d20c6b268ecd3a90545d516ea57dba843e9662cdbf7340")
        val classes = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val simple = e.name.substringAfterLast('/').removeSuffix(".class")
                if (e.name.startsWith("androidx/compose/ui/semantics/") && e.name.endsWith(".class") &&
                    (simple.startsWith("SemanticsSortKt") || simple == "SemanticsOwnerKt" ||
                        simple in listOf("LtrBoundsComparator", "RtlBoundsComparator", "TopBottomBoundsComparator")))
                    classes[e.name.removeSuffix(".class").replace('/', '.')] = zip.readBytes()
            }
        }
        val loader = object : ClassLoader(SemanticsNode::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
                val c = findLoadedClass(name) ?: classes[name]?.let { defineClass(name, it, 0, it.size) } ?: super.loadClass(name, false)
                if (resolve) resolveClass(c)
                c
            }
        }
        sort = Class.forName("androidx.compose.ui.semantics.SemanticsSortKt", true, loader)
        owner = Class.forName("androidx.compose.ui.semantics.SemanticsOwnerKt", true, loader)
    }
}
