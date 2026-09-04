package com.jarvis.ai.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Conscrypt is force-disabled: its uber jar carries no linux-aarch_64 JNI
 * library, so on ARM64 hosts the provider init dies with UnsatisfiedLinkError
 * when enabled. These specs only exercise node traversal — the JVM default
 * security provider is fully sufficient.
 *
 * Node trees are built SYNTHETICALLY with [AccessibilityNodeInfo.obtain] +
 * [AccessibilityNodeInfo.addChild]: Robolectric's shadow implements the full
 * child/parent graph in memory, whereas inflating a real View hierarchy does
 * not propagate text into createAccessibilityNodeInfo() children — which made
 * every text lookup fail even though NodeResolver's traversal logic is
 * identical on-device.
 */
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NodeResolverRobolectricTest {

    private fun node(
        text: String? = null,
        className: String = "android.view.View",
        clickable: Boolean = false,
        editable: Boolean = false
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        text?.let { this.text = it }
        setClassName(className)
        this.isClickable = clickable
        this.isEditable = editable
        isEnabled = true
        isVisibleToUser = true
        setBoundsInScreen(Rect(0, 0, 100, 50))
    }

    /** Builds root -> children wiring and returns the root. */
    private fun tree(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain()
        root.isVisibleToUser = true
        root.setBoundsInScreen(Rect(0, 0, 200, 200))
        // android-35's compile stub lacks addChild(AccessibilityNodeInfo); the
        // Robolectric shadow exposes it publicly and wires child -> parent.
        children.forEach { shadowOf(root).addChild(it) }
        return root
    }

    @Test
    fun resolve_exactText_findsButton() {
        val root = tree(
            node(text = "Open", className = "android.widget.Button", clickable = true)
        )
        val found = NodeResolver.resolve(root, "Open")
        assertNotNull("expected to resolve 'Open'", found)
        assertEquals("Open", found!!.text.toString())
    }

    @Test
    fun resolve_partialText_findsButton() {
        val root = tree(
            node(text = "Open Settings", className = "android.widget.Button", clickable = true)
        )
        val found = NodeResolver.resolve(root, "settings", partial = true)
        assertNotNull(found)
        assertEquals("Open Settings", found!!.text.toString())
    }

    @Test
    fun resolve_missingText_returnsNull() {
        val root = tree(
            node(text = "Open", className = "android.widget.Button", clickable = true)
        )
        val found = NodeResolver.resolve(root, "Nonexistent")
        assertNull(found)
    }

    @Test
    fun resolveDetailed_exactReturnsFound() {
        val root = tree(
            node(text = "Open", className = "android.widget.Button", clickable = true)
        )
        val res = NodeResolver.resolveDetailed(root, "Open")
        assertEquals(NodeResolver.Resolution.Found::class, res::class)
    }

    @Test
    fun resolveDetailed_ambiguousWhenTwoMatch() {
        val root = tree(
            node(text = "Open", className = "android.widget.Button", clickable = true),
            node(text = "Open", className = "android.widget.Button", clickable = true)
        )
        val res = NodeResolver.resolveDetailed(root, "Open")
        assertEquals(NodeResolver.Resolution.Ambiguous::class, res::class)
    }

    @Test
    fun resolveDetailed_notFoundWhenMissing() {
        val root = tree(
            node(text = "Open", className = "android.widget.Button", clickable = true)
        )
        // "Save" shares no bigrams with "Open" (fuzzy matcher must not fire);
        // "Nope" was avoided because it is a near-anagram of "Open" and
        // legitimately scores as a weak fuzzy candidate.
        val res = NodeResolver.resolveDetailed(root, "Save")
        assertEquals(NodeResolver.Resolution.NotFound::class, res::class)
    }

    @Test
    fun findClickableAncestor_returnsClickableParent() {
        val container = node(className = "android.widget.LinearLayout", clickable = true)
        val child = node(text = "Sub", className = "android.widget.TextView")
        shadowOf(container).addChild(child)
        val root = tree(container)
        val childNode = NodeResolver.resolve(root, "Sub") ?: error("child not resolved")
        val found = NodeResolver.findClickableAncestor(childNode)
        assertNotNull(found)
        assertEquals(true, found!!.isClickable)
        assertEquals(true, found.className.toString().contains("LinearLayout"))
    }

    @Test
    fun center_returnsBoundsMidpoint() {
        val node = AccessibilityNodeInfo.obtain()
        node.setBoundsInScreen(Rect(0, 0, 100, 50))
        val (cx, cy) = NodeResolver.center(node)
        assertEquals(50, cx)
        assertEquals(25, cy)
    }

    @Test
    fun findEditable_findsEditText() {
        val root = tree(
            node(className = "android.widget.EditText", editable = true)
        )
        val found = NodeResolver.findEditable(root)
        assertNotNull(found)
        assertEquals(true, found!!.className.toString().contains("EditText"))
    }

    @Test
    fun findEditable_byTargetText() {
        val root = tree(
            node(text = "Search", className = "android.widget.EditText", editable = true)
        )
        val found = NodeResolver.findEditable(root, "search")
        assertNotNull("expected editable field matching 'search'", found)
        assertEquals(true, found!!.className.toString().contains("EditText"))
    }
}

