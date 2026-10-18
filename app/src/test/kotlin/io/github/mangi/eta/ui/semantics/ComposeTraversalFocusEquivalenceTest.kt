package io.github.mangi.eta.ui.semantics

import android.app.Application
import android.view.View
import androidx.collection.IntObjectMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import java.security.MessageDigest
import androidx.collection.MutableIntIntMap
import androidx.collection.MutableIntObjectMap
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.*
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeTraversalFocusEquivalenceTest {
    @get:Rule val compose = createComposeRule()
    private val target = "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat_androidKt"
    private lateinit var view: View
    private val runtime by lazy { Class.forName(target) }
    private val official by lazy {
        val file = File(requireNotNull(System.getProperty("eta.compose.semantics.original")))
        assertEquals("2a4e13582e201f7d52d20c6b268ecd3a90545d516ea57dba843e9662cdbf7340",
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        val classes = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(file.readBytes())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.removeSuffix(".class").replace('/', '.')
                if (name == target || name.startsWith(target + "$")) classes[name] = z.readBytes()
            }
        }
        val loader = object : ClassLoader(runtime.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
                val c = findLoadedClass(name) ?: if (name in classes) classes.getValue(name).let { defineClass(name, it, 0, it.size) }
                    else super.loadClass(name, false)
                if (resolve) resolveClass(c)
                c
            }
        }
        Class.forName(target, true, loader)
    }
    @Composable private fun Node(tag: String, x: Int = 0, group: Boolean = false, merge: Boolean = false,
                                 alpha: Float = 1f, hidden: Boolean = false, index: Float = 0f, stateOnly: Boolean = false,
                                 content: @Composable () -> Unit = {}) {
        Layout(content = content, modifier = Modifier.testTag(tag).graphicsLayer { this.alpha = alpha }.semantics(mergeDescendants = merge) {
            isTraversalGroup = group
            if (stateOnly) {
                role = Role.Switch
                toggleableState = ToggleableState.On
                editableText = AnnotatedString("")
            } else contentDescription = tag
            traversalIndex = index
            if (hidden) hideFromAccessibility()
        }) { ms, _ ->
            val ps = ms.map { it.measure(Constraints.fixed(200, 40)) }
            layout(300, 200) { ps.forEachIndexed { i, p -> p.place(x, i * 45) } }
        }
    }
    private fun map(root: SemanticsNode, included: Set<String>): MutableIntObjectMap<Any> {
        val adjusted = Class.forName("androidx.compose.ui.semantics.AdjustedSemanticsNode")
        val ctor = adjusted.getConstructor(SemanticsNode::class.java, IntRect::class.java, Boolean::class.javaPrimitiveType)
        val result = MutableIntObjectMap<Any>()
        result[-1] = ctor.newInstance(root, IntRect(0, 0, 300, 200), false)
        fun visit(n: SemanticsNode) {
            if (n.config.getOrElse(SemanticsProperties.TestTag) { "" } in included)
                result[n.id] = ctor.newInstance(n, IntRect(0, 0, 300, 200), false)
            n.children.forEach(::visit)
        }
        root.children.forEach(::visit)
        return result
    }
    private fun traversal(c: Class<*>, nodes: IntObjectMap<Any>): Pair<Map<Int, Int>, Map<Int, Int>> {
        val before = MutableIntIntMap(); val after = MutableIntIntMap()
        before[123] = 456; after[456] = 123
        val m = c.declaredMethods.single { it.name == "setTraversalValues" }.apply { isAccessible = true }
        try { m.invoke(null, nodes, before, after, RuntimeEnvironment.getApplication().resources) }
        catch (e: InvocationTargetException) { throw e.targetException }
        fun copy(m: MutableIntIntMap) = mutableMapOf<Int, Int>().also { out -> m.forEach { k, v -> out[k] = v } }
        return copy(before) to copy(after)
    }
    @Test fun productionTraversalMatchesWithExcludedGroupsVisibleChildrenAndStateChanges() {
        val rtl = mutableStateOf(false); val alpha = mutableFloatStateOf(1f)
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Node("fixture") {
                    Node("outside-group", group = true) { Node("inside-child", index = -1f) }
                    Node("outside-plain") { Node("visible-descendant") }
                    Node("plain", x = 50)
                    Node("state-only", stateOnly = true)
                    Node("transparent", alpha = alpha.floatValue)
                    Node("hidden", hidden = true)
                    Node("merge", merge = true) { Node("merged-child") }
                }
            }
        }
        for (direction in listOf(false, true)) for (a in listOf(1f, 0f)) {
            compose.runOnIdle { rtl.value = direction; alpha.floatValue = a }
            compose.waitForIdle()
            val root = compose.onNodeWithTag("fixture", useUnmergedTree = true).fetchSemanticsNode()
            compose.runOnIdle {
                for (include in listOf(setOf("inside-child", "visible-descendant", "plain", "state-only", "transparent", "hidden", "merge"),
                        setOf("outside-group", "inside-child", "visible-descendant", "plain", "state-only", "transparent", "merge"), emptySet())) {
                    val nodes = map(root, include)
                    val expected = traversal(official, nodes)
                    if (include.isNotEmpty()) assertTrue("Fixture must produce traversal edges", expected.first.isNotEmpty())
                    assertEquals(expected, traversal(runtime, nodes))
                    if (include.isEmpty()) assertTrue(expected.first.isEmpty())
                }
            }
        }
    }
    @Test fun actualUncoveredMapPreservesProductionTraversal() {
        compose.setContent {
            view = LocalView.current
            Node("fixture") { Node("first"); Node("second", stateOnly = true); Node("third") }
        }
        compose.runOnIdle {
            val owner = view.javaClass.getMethod("getSemanticsOwner").apply { isAccessible = true }.invoke(view)
            val method = Class.forName("androidx.compose.ui.semantics.SemanticsOwnerKt")
                .declaredMethods.single { it.name == "getAllUncoveredSemanticsNodesToIntObjectMap" }
            val ignore: (SemanticsNode) -> Boolean = { false }
            @Suppress("UNCHECKED_CAST")
            val nodes = method.invoke(null, owner, -1, ignore) as IntObjectMap<Any>
            val expected = traversal(official, nodes)
            assertTrue("Real uncovered tree must produce traversal edges", expected.first.isNotEmpty())
            assertEquals(expected, traversal(runtime, nodes))
        }
    }

    @Test fun presentNodeKeepsOfficialFocusabilityPredicateAndOldBridgeIsGone() {
        compose.setContent { Node("fixture") { Node("plain") } }
        val root = compose.onNodeWithTag("fixture", useUnmergedTree = true).fetchSemanticsNode()
        compose.runOnIdle {
            val n = root.children.single()
            val runtimeFocus = runtime.declaredMethods.single { it.name == "isScreenReaderFocusable" }.apply { isAccessible = true }
            val officialFocus = official.declaredMethods.single { it.name == "isScreenReaderFocusable" }.apply { isAccessible = true }
            val resources = RuntimeEnvironment.getApplication().resources
            for (z in listOf(false, true)) {
                assertEquals("official and patched must agree on focusability",
                    officialFocus.invoke(null, n, resources, z), runtimeFocus.invoke(null, n, resources, z))
            }
            assertEquals(true, runtimeFocus.invoke(null, n, resources, false))
            // Upstream 1.13.0-alpha01 ships the containsKey guard itself; the old eta bridge must be gone.
            assertTrue("old eta bridge must not exist", runtime.declaredMethods.none { it.name == "eta\$visibleScreenReaderFocusable" })
        }
    }
}
