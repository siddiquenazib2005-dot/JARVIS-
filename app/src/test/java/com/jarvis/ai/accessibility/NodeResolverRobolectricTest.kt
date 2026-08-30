package com.jarvis.ai.accessibility

import android.app.Activity
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NodeResolverRobolectricTest {

    private fun withTree(vararg make: (Activity) -> android.view.View): AccessibilityNodeInfo {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val root = LinearLayout(activity)
        val views = make.map { it(activity) }
        views.forEach { root.addView(it) }
        activity.setContentView(root)
        root.layout(0, 0, 200, 200)
        views.forEach { it.layout(0, 0, 100, 50) }
        return root.createAccessibilityNodeInfo()!!
    }

    @Test
    fun resolve_exactText_findsButton() {
        val tree = withTree(
            { act -> Button(act).apply { text = "Open"; isClickable = true } }
        )
        val found = NodeResolver.resolve(tree, "Open")
        assertNotNull("expected to resolve 'Open'", found)
        assertEquals("Open", found!!.text.toString())
    }

    @Test
    fun resolve_partialText_findsButton() {
        val tree = withTree(
            { act -> Button(act).apply { text = "Open Settings"; isClickable = true } }
        )
        val found = NodeResolver.resolve(tree, "settings", partial = true)
        assertNotNull(found)
        assertEquals("Open Settings", found!!.text.toString())
    }

    @Test
    fun resolve_missingText_returnsNull() {
        val tree = withTree(
            { act -> Button(act).apply { text = "Open"; isClickable = true } }
        )
        val found = NodeResolver.resolve(tree, "Nonexistent")
        assertNull(found)
    }

    @Test
    fun resolveDetailed_exactReturnsFound() {
        val tree = withTree(
            { act -> Button(act).apply { text = "Open"; isClickable = true } }
        )
        val res = NodeResolver.resolveDetailed(tree, "Open")
        assertEquals(NodeResolver.Resolution.Found::class, res::class)
    }

    @Test
    fun resolveDetailed_ambiguousWhenTwoMatch() {
        val tree = withTree(
            { act -> Button(act).apply { text = "Open"; isClickable = true } },
            { act -> Button(act).apply { text = "Open"; isClickable = true } }
        )
        val res = NodeResolver.resolveDetailed(tree, "Open")
        assertEquals(NodeResolver.Resolution.Ambiguous::class, res::class)
    }

    @Test
    fun resolveDetailed_notFoundWhenMissing() {
        val tree = withTree(
            { act -> Button(act).apply { text = "Open"; isClickable = true } }
        )
        val res = NodeResolver.resolveDetailed(tree, "Nope")
        assertEquals(NodeResolver.Resolution.NotFound::class, res::class)
    }

    @Test
    fun findClickableAncestor_returnsClickableParent() {
        val tree = withTree(
            { act ->
                LinearLayout(act).apply {
                    isClickable = true
                    addView(TextView(act).apply { text = "Sub" })
                }
            }
        )
        val childNode = NodeResolver.resolve(tree, "Sub") ?: error("child not resolved")
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
        val tree = withTree(
            { act -> EditText(act) }
        )
        val found = NodeResolver.findEditable(tree)
        assertNotNull(found)
        assertEquals(true, found!!.className.toString().contains("EditText"))
    }

    @Test
    fun findEditable_byTargetText() {
        val tree = withTree(
            { act -> EditText(act).apply { setText("Search") } }
        )
        val found = NodeResolver.findEditable(tree, "search")
        assertNotNull("expected editable field matching 'search'", found)
        assertEquals(true, found!!.className.toString().contains("EditText"))
    }
}
