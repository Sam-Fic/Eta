package io.github.mangi.eta.ui.semantics

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
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
class ComposeSemanticsSortEquivalenceTest {
    @get:Rule val compose = createComposeRule()
    private val paths by lazy { SortPaths() }
    private val hook by lazy { CacheProbe() }
    private fun render(state: State<N>, direction: State<LayoutDirection> = mutableStateOf(LayoutDirection.Ltr)) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalLayoutDirection provides direction.value) {
                SortNode(state.value)
            }
        }
        compose.waitForIdle()
    }
    private fun root() = compose.onNodeWithTag("fixture", useUnmergedTree = true).fetchSemanticsNode()
    private fun eq(r: SemanticsNode, expected: List<String>, input: List<SemanticsNode> = r.children) {
        val official = paths.original(r, input)
        assertEquals(expected, official.map(::tag))
        assertEquals(official.map { it.id }, paths.runtime(r, input).map { it.id })
    }
    @Test fun ltrRtlRowsAndStableTraversalIndices() {
        val state = mutableStateOf(tree(N("n:BR", 100, 70), N("n:TR", 100), N("n:TL"), N("n:BL", y = 70)))
        val direction = mutableStateOf(LayoutDirection.Ltr)
        render(state, direction)
        for (rtl in listOf(false, true)) {
            compose.runOnIdle { direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
            compose.waitForIdle(); val r = root()
            compose.runOnIdle { eq(r, if (rtl) listOf("n:TR", "n:TL", "n:BR", "n:BL") else listOf("n:TL", "n:TR", "n:BL", "n:BR")) }
        }
        compose.runOnIdle { state.value = tree(N("n:late", index = 3f), N("n:default", 120), N("n:zero", 60, index = 0f), N("n:early", 180, 80, index = -2f)) }
        compose.waitForIdle(); val r = root()
        compose.runOnIdle { eq(r, listOf("n:early", "n:zero", "n:default", "n:late")) }
    }
    @Test fun overlapUsesIntersectionAndGeometryRecomputes() {
        val state = mutableStateOf(tree(N("n:A", 160), N("n:B", 80, 10), N("n:C", 0, 20)))
        render(state); val r = root(); val input = r.children
        compose.runOnIdle { eq(r, listOf("n:B", "n:A", "n:C"), input) }
        compose.runOnIdle { state.value = tree(N("n:A", 0), N("n:B", 80, 10), N("n:C", 160, 20)) }
        compose.waitForIdle()
        compose.runOnIdle { eq(r, listOf("n:A", "n:B", "n:C"), input) }
    }
    @Test fun boundsTieUsesZAndPlacementOrder() {
        val state = mutableStateOf(tree(N("n:high", z = 4f), N("n:low", z = -1f)))
        render(state); var r = root()
        compose.runOnIdle { eq(r, listOf("n:low", "n:high"), r.children.reversed()) }
        compose.runOnIdle { state.value = tree(N("n:first"), N("n:second"), N("n:third")).copy(order = listOf(2, 0, 1)) }
        compose.waitForIdle(); r = root()
        compose.runOnIdle { eq(r, listOf("n:third", "n:first", "n:second"), r.children.reversed()) }
    }
    @Test fun nestedGroupsAndFailureClearPopulatedCache() {
        render(mutableStateOf(nested())); val r = root()
        compose.runOnIdle {
            val expected = listOf("n:sibling", "n:inner", "n:C", "n:B", "n:D", "n:A")
            eq(r, expected)
            var retained: Map<*, *>? = null
            val fault = IllegalStateException("fixture")
            val focus: (SemanticsNode) -> Boolean = {
                if (tag(it) == "n:inner") {
                    retained = hook.currentMap(); assertTrue(retained!!.isNotEmpty()); throw fault
                }
                focusable(it)
            }
            try { paths.runtime(r, r.children, focus); fail("Expected sort exception") }
            catch (e: IllegalStateException) { assertSame(fault, e) }
            assertFalse(hook.active()); assertNotNull(retained); assertTrue(retained!!.isEmpty())
            eq(r, expected)
        }
    }
    @Test fun oneComputationPerNodePerSortNotAcrossRounds() {
        render(mutableStateOf(tree(*List(24) { N("n:$it", 240 - it * 10) }.toTypedArray())))
        val r = root()
        compose.runOnIdle {
            val expected = paths.original(r, r.children).map { it.id }
            val closed = mutableListOf<Close>()
            hook.set { closed += it }
            try {
                for (input in listOf(r.children, r.children.reversed())) {
                    closed.clear()
                    assertEquals(expected, paths.runtime(r, input).map { it.id })
                    assertFalse(hook.active())
                    val sample = closed.single()
                    assertEquals(1, sample.depth); assertEquals(24, sample.entries)
                    assertEquals(24L, sample.computations)
                    assertTrue(sample.requests > sample.computations)
                }
            } finally { hook.set(null) }
        }
    }
}
private data class N(val tag: String, val x: Int = 0, val y: Int = 0, val w: Int = 20, val h: Int = 20,
    val z: Float = 0f, val index: Float? = null, val group: Boolean = false,
    val kids: List<N> = emptyList(), val order: List<Int>? = null)
private fun tree(vararg kids: N) = N("fixture", w = 320, h = 220, kids = kids.toList())
private fun nested() = tree(
    N("g:outer", w = 140, h = 100, group = true, kids = listOf(
        N("n:A", index = 10f),
        N("n:inner", x = 40, w = 50, h = 80, index = -1f, group = true,
            kids = listOf(N("n:C", y = 30, index = -50f), N("n:B", index = 50f))),
        N("n:D", x = 90, index = 0f))), N("n:sibling", x = 180, index = -2f))
@Composable private fun SortNode(n: N) {
    Layout(modifier = Modifier.testTag(n.tag).zIndex(n.z).semantics {
        isTraversalGroup = n.group
        if (n.tag.startsWith("n:")) contentDescription = n.tag
        n.index?.let { traversalIndex = it }
    }, content = { n.kids.forEach { child -> key(child.tag) { SortNode(child) } } }) { ms, _ ->
        val ps = ms.mapIndexed { i, m -> m.measure(Constraints.fixed(n.kids[i].w, n.kids[i].h)) }
        layout(n.w, n.h) {
            (n.order ?: n.kids.indices.toList()).forEach { i -> ps[i].place(n.kids[i].x, n.kids[i].y) }
        }
    }
}
private fun tag(n: SemanticsNode) = n.config.getOrElse(SemanticsProperties.TestTag) { "" }
private fun focusable(n: SemanticsNode) = tag(n).startsWith("n:")
private class SortPaths {
    private val parent = requireNotNull(SemanticsNode::class.java.classLoader)
    private val official: Method
    private val patched: Method
    init {
        val bytes = File(requireNotNull(System.getProperty("eta.compose.semantics.original"))).readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == "5ebbd3d09bdb44d8681fc2287191a60930905bdea363fa2787318800c8260375")
        val classes = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val simple = e.name.substringAfterLast('/').removeSuffix(".class")
                if (e.name.startsWith("androidx/compose/ui/semantics/") && e.name.endsWith(".class") &&
                    (simple.startsWith("SemanticsSortKt") || simple in listOf("LtrBoundsComparator", "RtlBoundsComparator", "TopBottomBoundsComparator")))
                    classes[e.name.removeSuffix(".class").replace('/', '.')] = z.readBytes()
                z.closeEntry()
            }
        }
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
                val c = findLoadedClass(name) ?: classes[name]?.let { defineClass(name, it, 0, it.size) } ?: super.loadClass(name, false)
                if (resolve) resolveClass(c)
                c
            }
        }
        val name = "androidx.compose.ui.semantics.SemanticsSortKt"
        val original = Class.forName(name, true, loader)
        val runtime = Class.forName(name, true, parent)
        assertNotSame(original, runtime)
        assertTrue(runtime.declaredMethods.any { it.name == "eta\$sortByGeometryGroupings\$body" })
        official = entry(original); patched = entry(runtime)
    }
    private fun entry(c: Class<*>) = c.getDeclaredMethod("subtreeSortedByGeometryGrouping", SemanticsNode::class.java,
        kotlin.jvm.functions.Function1::class.java, kotlin.jvm.functions.Function1::class.java, List::class.java)
    fun original(r: SemanticsNode, input: List<SemanticsNode>) = call(official, r, input, ::focusable)
    fun runtime(r: SemanticsNode, input: List<SemanticsNode>, focus: (SemanticsNode) -> Boolean = ::focusable) = call(patched, r, input, focus)
    @Suppress("UNCHECKED_CAST")
    private fun call(m: Method, r: SemanticsNode, input: List<SemanticsNode>, focus: (SemanticsNode) -> Boolean): List<SemanticsNode> {
        val visible: (SemanticsNode) -> Boolean = { true }
        try { return m.invoke(null, r, visible, focus, input) as List<SemanticsNode> }
        catch (e: InvocationTargetException) { throw e.targetException }
    }
}
private data class Close(val depth: Int, val requests: Long, val computations: Long, val entries: Int)
private class CacheProbe {
    private val cache = Class.forName("androidx.compose.ui.semantics.EtaSemanticsBoundsCache", true, SemanticsNode::class.java.classLoader)
    private val probe = Class.forName("androidx.compose.ui.semantics.EtaSemanticsBoundsCache\$Probe", true, cache.classLoader)
    fun active() = cache.getMethod("isActiveForTests").invoke(null) as Boolean
    fun set(receive: ((Close) -> Unit)?) {
        val listener = receive?.let { block -> Proxy.newProxyInstance(probe.classLoader, arrayOf(probe)) { obj, m, args ->
            when (m.name) {
                "onClose" -> { val a = requireNotNull(args); block(Close((a[0] as Number).toInt(), (a[1] as Number).toLong(), (a[2] as Number).toLong(), (a[3] as Number).toInt())); null }
                "toString" -> "test probe"
                "hashCode" -> System.identityHashCode(obj)
                "equals" -> obj === args?.get(0)
                else -> error(m.name)
            }
        } }
        cache.getMethod("setProbeForTests", probe).invoke(null, listener)
    }
    fun currentMap(): Map<*, *> {
        val active = cache.getDeclaredField("ACTIVE").apply { isAccessible = true }
        val scope = (active.get(null) as ThreadLocal<*>).get() ?: error("No active cache")
        return scope.javaClass.getDeclaredField("bounds").apply { isAccessible = true }.get(scope) as Map<*, *>
    }
}
