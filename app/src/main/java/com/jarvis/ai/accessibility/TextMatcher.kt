package com.jarvis.ai.accessibility

/**
 * Pure, framework-free text matching used by [NodeResolver] and unit-tested on the JVM.
 *
 * Matching ladder (highest confidence first):
 *  1. exact normalized equality
 *  2. substring containment
 *  3. Dice-coefficient fuzzy match (handles typos / localized labels)
 */
object TextMatcher {
    fun normalize(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+"), " ")

    /** Normalized equality or containment. */
    fun contains(query: String, candidate: String): Boolean {
        val nq = normalize(query)
        val nc = normalize(candidate)
        return nq == nc || nc.contains(nq) || nq.contains(nc)
    }

    /** Dice coefficient in [0,1] over character bigrams. */
    fun fuzzyScore(a: String, b: String): Double {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        val ba = bigrams(na)
        val bb = bigrams(nb)
        if (ba.isEmpty() && bb.isEmpty()) return 0.0
        val overlap = ba.intersect(bb).size
        return (2.0 * overlap) / (ba.size + bb.size)
    }

    /** True when [candidate] matches [query] at or above [threshold]. */
    fun matches(query: String, candidate: String, threshold: Double = 0.6): Boolean {
        val nq = normalize(query)
        val nc = normalize(candidate)
        if (nq == nc) return true
        if (nc.contains(nq) || nq.contains(nc)) return true
        return fuzzyScore(nq, nc) >= threshold
    }

    private fun bigrams(s: String): Set<String> {
        val clean = s.replace(" ", "")
        if (clean.length <= 1) return setOf(clean)
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }.toSet()
    }
}
