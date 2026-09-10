package com.jarvis.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * SOS configuration.
 *
 * Before this, sos() took a nullable contact from the caller and every caller
 * passed null, so an emergency always ended as an empty message picker. The
 * contact now lives in prefs and survives restarts, which is the whole point of
 * an emergency shortcut.
 */
object SosStore {

    private const val PREFS_NAME = "aurix_sos"
    private const val KEY_NAME = "contact_name"
    private const val KEY_NUMBER = "contact_number"

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun contactName(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun contactNumber(context: Context): String? =
        prefs(context).getString(KEY_NUMBER, null)?.takeIf { it.isNotBlank() }

    fun save(context: Context, name: String, number: String) {
        prefs(context).edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_NUMBER, number.trim())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_NAME).remove(KEY_NUMBER).apply()
    }

    /**
     * Last known coarse location as a maps link, or null.
     *
     * Deliberately uses getLastKnownLocation only: requesting a fresh fix takes
     * seconds and can fail indoors, and an SOS must go out immediately. A
     * slightly stale location beats a delayed message.
     */
    fun locationLink(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val best: Location? = runCatching {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time }
        }.getOrNull()

        val location = best ?: return null
        return "https://maps.google.com/?q=" + location.latitude + "," + location.longitude
    }
}
