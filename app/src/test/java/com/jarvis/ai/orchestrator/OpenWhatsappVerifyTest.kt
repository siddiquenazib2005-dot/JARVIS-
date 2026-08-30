package com.jarvis.ai.orchestrator

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake [AppLookup] — pure JVM, no Android Context subclassing required. */
class FakeAppLookup(private val apps: List<ResolvedApp>) : AppLookup {
    override fun launchIntentFor(packageName: String): Intent? =
        if (apps.any { it.packageName == packageName }) Intent() else null

    override fun launcherActivities(): List<ResolvedApp> = apps
    override fun installedApps(): List<ResolvedApp> = apps
}

class OpenWhatsappVerifyTest {

    private val lookup = FakeAppLookup(
        listOf(
            ResolvedApp("com.whatsapp", "WhatsApp"),
            ResolvedApp("com.android.chrome", "Google Chrome"),
            ResolvedApp("com.google.android.youtube", "YouTube"),
            ResolvedApp("com.google.android.apps.maps", "Google Maps"),
            ResolvedApp("org.thoughtcrime.securesms", "Signal"),
            ResolvedApp("com.example.notes", "My Notes")
        )
    )

    private fun resolve(q: String) = PackageResolver.resolve(q, lookup)

    // --- Requirement 3: the exact human utterances resolve ---
    @Test fun whatsapp_lowercase_resolves() = assertEquals("com.whatsapp", resolve("whatsapp"))

    @Test fun whatsapp_mixed_case_resolves() = assertEquals("com.whatsapp", resolve("WhatsApp"))

    @Test fun open_whatsapp_prefix_resolves() = assertEquals("com.whatsapp", resolve("open whatsapp"))

    // --- Requirement 4/8: other common apps resolve via label ---
    @Test fun chrome_resolves_via_label() = assertEquals("com.android.chrome", resolve("chrome"))

    @Test fun youtube_resolves_via_label() =
        assertEquals("com.google.android.youtube", resolve("youtube"))

    @Test fun maps_resolves_via_label() =
        assertEquals("com.google.android.apps.maps", resolve("maps"))

    @Test fun signal_resolves_via_label() =
        assertEquals("org.thoughtcrime.securesms", resolve("signal"))

    // --- Requirement 4: explicit package name is accepted (no hardcode needed) ---
    @Test fun explicit_package_resolves() = assertEquals("com.whatsapp", resolve("com.whatsapp"))

    // --- Requirement 6: fuzzy package-name fallback when label does not contain query ---
    @Test fun fuzzy_package_fallback() = assertEquals("org.thoughtcrime.securesms", resolve("secure"))

    // --- Unknown app must fail cleanly, not crash ---
    @Test fun unknown_app_fails() = assertNull(resolve("this app definitely does not exist zzz"))

    // --- Regression: one app with a broken/empty label must NOT empty the list ---
    @Test fun one_broken_label_does_not_kill_resolution() {
        val lookupWithBroken = FakeAppLookup(
            listOf(
                ResolvedApp("com.whatsapp", "WhatsApp"),
                ResolvedApp("com.broken.label", ""),   // degraded label (loadLabel fallback)
                ResolvedApp("com.mobile.notes", ""),   // indistinguishable label
                ResolvedApp("com.android.chrome", "Google Chrome")
            )
        )
        assertEquals("com.whatsapp", PackageResolver.resolve("whatsapp", lookupWithBroken))
        assertEquals("com.android.chrome", PackageResolver.resolve("chrome", lookupWithBroken))
        // Package-name substring match works even with blank labels.
        assertEquals("com.mobile.notes", PackageResolver.resolve("notes", lookupWithBroken))
    }

    // --- Classifier feeds resolver for close too ---
    @Test fun classifier_close_command_end_to_end() {
        val classification = IntentClassifier.classifyIntent("close whatsapp")
        assertEquals("DEVICE_AUTOMATION", classification.intent)
        val raw = (classification.parameters["command"] as? String) ?: ""
        assertTrue(raw.isNotBlank())
    }

    // --- Requirement 10: classifier produces the exact params the resolver consumes ---
    @Test fun classifier_feeds_resolver_end_to_end() {
        val classification = IntentClassifier.classifyIntent("open whatsapp")
        assertEquals("SYSTEM_COMMAND", classification.intent)
        assertEquals("whatsapp", classification.parameters["command"])

        val pkg = PackageResolver.resolve(
            classification.parameters["command"] as String,
            lookup
        )
        assertEquals("com.whatsapp", pkg)
    }
}
