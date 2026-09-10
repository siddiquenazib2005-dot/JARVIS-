package com.jarvis.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Fuzzy contact lookup.
 *
 * The old resolveNumber() asked ContactsContract's CONTENT_FILTER_URI for the
 * raw spoken text and took the first row. That fails in three very common ways:
 *  - speech gives "nazeeb" for "Nazib", or "papa" for "Papa Home"
 *  - two contacts match and the first row silently wins (wrong person messaged)
 *  - a contact with two numbers picks whichever row came back first
 *
 * This resolver scores every candidate, returns them ranked, and reports when
 * the top two are too close to call so the caller can ask instead of guessing.
 * Messaging the wrong person is worse than one extra question.
 */
data class ContactMatch(
    val name: String,
    val number: String,
    val label: String,
    val score: Int
)

sealed interface ContactLookup {
    /** Query was already a phone number. */
    data class RawNumber(val number: String) : ContactLookup

    /** One confident match. */
    data class Single(val match: ContactMatch) : ContactLookup

    /** Several plausible matches: the caller must confirm. */
    data class Ambiguous(val matches: List<ContactMatch>) : ContactLookup

    /** Nothing matched, or READ_CONTACTS is not granted. */
    data class None(val reason: String) : ContactLookup
}

object ContactResolver {

    private const val CONFIDENT_SCORE = 80
    private const val TIE_WINDOW = 15

    fun lookup(context: Context, query: String): ContactLookup {
        val raw = query.trim()
        if (raw.isBlank()) return ContactLookup.None("no name given")

        // Digits win immediately: never touch contacts for an explicit number.
        if (raw.count { it.isDigit() } >= 6) {
            return ContactLookup.RawNumber(raw.filter { it.isDigit() || it == '+' })
        }

        if (!hasPermission(context)) {
            return ContactLookup.None("contacts permission is off")
        }

        val candidates = load(context, raw)
        if (candidates.isEmpty()) return ContactLookup.None("no contact matched")

        val ranked = candidates.sortedByDescending { it.score }
        val best = ranked.first()

        // One number for one person is never ambiguous, even on a weak match.
        val rivals = ranked.drop(1).filter {
            it.score >= best.score - TIE_WINDOW && !sameNumber(it.number, best.number)
        }

        return when {
            rivals.isEmpty() && best.score >= 40 -> ContactLookup.Single(best)
            rivals.isEmpty() -> ContactLookup.None("no close match for \"$raw\"")
            best.score >= CONFIDENT_SCORE && rivals.none { it.score >= CONFIDENT_SCORE } ->
                ContactLookup.Single(best)
            else -> ContactLookup.Ambiguous(ranked.take(4))
        }
    }

    /** Convenience for callers that genuinely cannot ask a question. */
    fun bestNumber(context: Context, query: String): String? =
        when (val result = lookup(context, query)) {
            is ContactLookup.RawNumber -> result.number
            is ContactLookup.Single -> result.match.number
            is ContactLookup.Ambiguous -> result.matches.first().number
            is ContactLookup.None -> null
        }

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    /** "Papa (Mobile) 98765..." for confirmation prompts. */
    fun describe(match: ContactMatch): String = buildString {
        append(match.name)
        if (match.label.isNotBlank()) append(" (").append(match.label).append(")")
        append(" — ").append(mask(match.number))
    }

    private fun mask(number: String): String =
        if (number.length <= 4) number else "…" + number.takeLast(4)

    private fun sameNumber(a: String, b: String): Boolean {
        val x = a.filter { it.isDigit() }.takeLast(9)
        val y = b.filter { it.isDigit() }.takeLast(9)
        return x.isNotBlank() && x == y
    }

    // ------------------------------------------------------------------
    // Loading + scoring
    // ------------------------------------------------------------------

    private fun load(context: Context, query: String): List<ContactMatch> {
        // Two passes: the provider's own filter (fast, handles most spellings)
        // and a broad scan (catches "nazeeb" vs "Nazib", nicknames, surnames).
        val fromFilter = query(
            context,
            Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query)
            ),
            query
        )
        val fromScan = query(
            context, ContactsContract.CommonDataKinds.Phone.CONTENT_URI, query
        )

        return (fromFilter + fromScan)
            .groupBy { it.name.lowercase() + "|" + it.number.filter { c -> c.isDigit() } }
            .map { entry -> entry.value.maxByOrNull { it.score }!! }
            .filter { it.score > 0 }
    }

    private fun query(context: Context, uri: Uri, needle: String): List<ContactMatch> =
        runCatching {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )
            val out = mutableListOf<ContactMatch>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                var scanned = 0
                while (cursor.moveToNext() && scanned < 2500) {
                    scanned++
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val number = cursor.getString(1)?.trim().orEmpty()
                    if (name.isBlank() || number.isBlank()) continue
                    val score = score(name, needle)
                    if (score <= 0) continue
                    out.add(
                        ContactMatch(
                            name = name,
                            number = number,
                            label = typeLabel(cursor.getInt(2), cursor.getString(3)),
                            score = score
                        )
                    )
                }
            }
            out
        }.getOrDefault(emptyList())

    private fun typeLabel(type: Int, custom: String?): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Main"
        else -> custom?.trim().orEmpty()
    }

    /**
     * 0-100. Exact name beats prefix beats word-start beats contains beats
     * a fuzzy edit-distance match, which is what rescues speech mishearings.
     */
    private fun score(name: String, needle: String): Int {
        val n = normalize(name)
        val q = normalize(needle)
        if (n.isBlank() || q.isBlank()) return 0

        if (n == q) return 100
        if (n.startsWith(q)) return 92

        val words = n.split(' ').filter { it.isNotBlank() }
        if (words.any { it == q }) return 90
        if (words.any { it.startsWith(q) }) return 82
        if (q.length >= 4 && n.contains(q)) return 70

        // Initials: "nb" for "Nazib Bhai".
        if (q.length in 2..4 && words.size >= q.length &&
            words.take(q.length).map { it.first() }.joinToString("") == q
        ) return 66

        val best = words.minOfOrNull { distance(it, q) } ?: return 0
        val tolerance = when {
            q.length <= 3 -> 0
            q.length <= 5 -> 1
            else -> 2
        }
        if (best <= tolerance) return 60 - best * 8
        return 0
    }

    private fun normalize(value: String): String = value.lowercase()
        .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Levenshtein, two-row variant. */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
