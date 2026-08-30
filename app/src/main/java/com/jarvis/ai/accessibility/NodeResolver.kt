package com.jarvis.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Centralized, robust node resolution.
 *
 * Resolution priority ladder (higher score = safer/less ambiguous):
 *  1. resource ID            (95)
 *  2. exact visible text     (100)
 *  3. exact content desc     (92)
 *  4. case-insensitive/text  (covered by normalized exact)
 *  5. normalized containment (70 text / 60 desc)
 *  6. fuzzy (Dice)           (55 / 45)
 *  7. parent/child descendant match (40)
 *  8. class + text           (folded into containment)
 *  9. structural fallback    (20)
 *
 * It returns ALL reasonable candidates with scores, selects the safest, and
 * refuses ambiguous high-confidence ties (returns [Resolution.Ambiguous]) so the
 * caller can ask the user instead of blindly clicking the first match.
 *
 * All node-property access is wrapped in runCatching: a node can go STALE
 * mid-traversal (screen changed under us), and touching a stale node throws
 * IllegalStateException. That must never escape this object.
 */
object NodeResolver {

    data class Candidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val reason: String
    )

    sealed class Resolution {
        data class Found(val node: AccessibilityNodeInfo, val score: Int) : Resolution()
        data class Ambiguous(val candidates: List<Candidate>) : Resolution()
        object NotFound : Resolution()
    }

    /** Full ranked candidate list (for inspection / ambiguity detection). */
    fun resolveAll(
        root: AccessibilityNodeInfo?,
        target: String,
        partial: Boolean = true
    ): List<Candidate> {
        if (root == null) return emptyList()
        val needle = TextMatcher.normalize(target)
        if (needle.isEmpty()) return emptyList()

        val direct = mutableListOf<Candidate>()
        traverseDirect(root, needle, partial, direct)

        // Parent/child: if a descendant matches, the container is a weak candidate.
        val descendantMatches = mutableListOf<AccessibilityNodeInfo>()
        collectDescendantMatches(root, needle, descendantMatches)
        for (d in descendantMatches) {
            if (direct.none { it.node == d }) direct.add(Candidate(d, 40, "descendant"))
        }

        if (direct.isEmpty()) return emptyList()

        return direct
            .sortedWith(
                compareByDescending<Candidate> { c -> c.score }
                    .thenByDescending { c -> runCatching { c.node.isVisibleToUser }.getOrDefault(false).let { if (it) 1 else 0 } }
                    .thenByDescending { c -> runCatching { c.node.isEnabled }.getOrDefault(false).let { if (it) 1 else 0 } }
                    .thenBy { c -> visibleArea(c.node) }
            )
    }

    fun resolveDetailed(
        root: AccessibilityNodeInfo?,
        target: String,
        partial: Boolean = true
    ): Resolution {
        val all = resolveAll(root, target, partial)
        if (all.isEmpty()) return Resolution.NotFound
        val top = all.first()
        val tied = all.takeWhile { it.score == top.score }
        // Refuse to guess when several high-confidence nodes tie.
        if (top.score >= 80 && tied.size > 1) return Resolution.Ambiguous(tied)
        val chosen = findClickableAncestor(top.node) ?: top.node
        return Resolution.Found(chosen, top.score)
    }

    /** Convenience: best node or null (ambiguous -> null, caller treats as not found). */
    fun resolve(root: AccessibilityNodeInfo?, target: String, partial: Boolean = true): AccessibilityNodeInfo? =
        when (val r = resolveDetailed(root, target, partial)) {
            is Resolution.Found -> r.node
            else -> null
        }

    fun findById(root: AccessibilityNodeInfo?, viewId: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val id = viewId.lowercase()
        var best: AccessibilityNodeInfo? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            runCatching { node.viewIdResourceName }.getOrNull()?.lowercase()?.let { nid ->
                if (nid == id || nid.endsWith(":$id") || nid.endsWith("/$id")) best = node
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            repeat(childCount) {
                runCatching { node.getChild(it) }.getOrNull()?.let { stack.addLast(it) }
            }
        }
        return best
    }

    /** First editable field, optionally preferring one whose hint/text matches [hint]. */
    fun findEditable(root: AccessibilityNodeInfo?, hint: String? = null): AccessibilityNodeInfo? {
        if (root == null) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val editable = mutableListOf<AccessibilityNodeInfo>()
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val isEditable = runCatching { node.isEditable }.getOrDefault(false)
            val isEnabled = runCatching { node.isEnabled }.getOrDefault(false)
            if (isEditable && isEnabled) editable.add(node)
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            repeat(childCount) {
                runCatching { node.getChild(it) }.getOrNull()?.let { stack.addLast(it) }
            }
        }
        if (editable.isEmpty()) return null
        if (hint != null) {
            val n = TextMatcher.normalize(hint)
            editable.firstOrNull { candidate ->
                val h = nodeHint(candidate)
                h != null && TextMatcher.contains(n, h)
            }?.let { return it }
        }
        return editable.first()
    }

    fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            val clickable = runCatching { current!!.isClickable }.getOrDefault(false)
            val longClickable = runCatching { current!!.isLongClickable }.getOrDefault(false)
            if (clickable || longClickable) return current
            current = runCatching { current!!.parent }.getOrNull()
        }
        return null
    }

    fun center(node: AccessibilityNodeInfo): Pair<Int, Int> {
        val r = visibleBounds(node)
        return (r.left + r.right) / 2 to (r.top + r.bottom) / 2
    }

    private fun nodeHint(node: AccessibilityNodeInfo): String? =
        runCatching { node.text?.toString() ?: node.contentDescription?.toString() }.getOrNull()

    private fun traverseDirect(
        node: AccessibilityNodeInfo,
        needle: String,
        partial: Boolean,
        out: MutableList<Candidate>
    ) {
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val score = eval(node, needle, partial)
        if (score.first > 0) out.add(Candidate(node, score.first, score.second))
        repeat(childCount) {
            runCatching { node.getChild(it) }.getOrNull()?.let { traverseDirect(it, needle, partial, out) }
        }
    }

    /** Returns (score, reason); 0 means no match. All property access is guarded
     *  against IllegalStateException from a node going stale mid-traversal. */
    private fun eval(
        node: AccessibilityNodeInfo,
        needle: String,
        partial: Boolean
    ): Pair<Int, String> = runCatching {
        val text = node.text?.toString()?.let { TextMatcher.normalize(it) }
        val desc = node.contentDescription?.toString()?.let { TextMatcher.normalize(it) }
        val id = node.viewIdResourceName?.lowercase()

        if (id != null) {
            val seg = id.substringAfter("/")
            if (id == needle || seg == needle || id.endsWith(":$needle")) {
                return@runCatching 95 to "id"
            }
        }
        if (text == needle) return@runCatching 100 to "exact-text"
        if (desc == needle) return@runCatching 92 to "exact-desc"
        if (partial) {
            if (text != null && text.contains(needle)) return@runCatching 70 to "text-contains"
            if (desc != null && desc.contains(needle)) return@runCatching 60 to "desc-contains"
        }
        text?.let {
            val f = TextMatcher.fuzzyScore(needle, it)
            if (f >= 0.85) return@runCatching 55 to "fuzzy-text"
            if (f >= 0.6) return@runCatching 45 to "fuzzy-text-weak"
        }
        desc?.let {
            val f = TextMatcher.fuzzyScore(needle, it)
            if (f >= 0.85) return@runCatching 55 to "fuzzy-desc"
            if (f >= 0.6) return@runCatching 45 to "fuzzy-desc-weak"
        }
        // Structural fallback: interactive node with any text but no direct match.
        if ((text != null || desc != null) && isInteractiveClass(node)) return@runCatching 20 to "structural"
        0 to ""
    }.getOrDefault(0 to "")

    private fun isInteractiveClass(node: AccessibilityNodeInfo): Boolean = runCatching {
        val c = node.className?.toString()?.lowercase() ?: return@runCatching false
        c.contains("button") || c.contains("textview") || c.contains("edittext") ||
            c.contains("imagebutton") || c.contains("checkbox") || c.contains("switch") ||
            c.contains("viewgroup")
    }.getOrDefault(false)

    private fun collectDescendantMatches(
        node: AccessibilityNodeInfo,
        needle: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        // Only enqueue containers that themselves don't strongly match.
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        val hasChildMatch = (0 until childCount).any { i ->
            val child = runCatching { node.getChild(i) }.getOrNull() ?: return@any false
            matchesRecursively(child, needle)
        }
        if (hasChildMatch) out.add(node)
        repeat(childCount) {
            runCatching { node.getChild(it) }.getOrNull()?.let { collectDescendantMatches(it, needle, out) }
        }
    }

    private fun matchesRecursively(node: AccessibilityNodeInfo, needle: String): Boolean = runCatching {
        val text = node.text?.toString()?.let { TextMatcher.normalize(it) }
        val desc = node.contentDescription?.toString()?.let { TextMatcher.normalize(it) }
        if (text == needle || desc == needle) return@runCatching true
        if (text != null && text.contains(needle)) return@runCatching true
        if (desc != null && desc.contains(needle)) return@runCatching true
        val childCount = node.childCount
        var found = false
        repeat(childCount) {
            if (!found) {
                runCatching { node.getChild(it) }.getOrNull()?.let {
                    if (matchesRecursively(it, needle)) found = true
                }
            }
        }
        found
    }.getOrDefault(false)

    fun visibleArea(node: AccessibilityNodeInfo): Int {
        val r = visibleBounds(node)
        return (r.width() * r.height()).coerceAtLeast(0)
    }

    fun visibleBounds(node: AccessibilityNodeInfo): android.graphics.Rect {
        return runCatching {
            android.graphics.Rect().also { node.getBoundsInScreen(it) }
        }.getOrDefault(android.graphics.Rect(0, 0, 0, 0))
    }
}