package com.jarvis.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Item 8, option B: "My World" as location-tagged memory.
 *
 * AURIX remembers WHERE something happened, not just what.
 * "remember this place as gym" pins the current coordinates under a name, and
 * later "where is gym" or "navigate to gym" resolves it with no cloud account.
 *
 * Storage is plain SharedPreferences JSON, matching every other AURIX store,
 * so the feature works offline. Coordinates never leave the device except
 * inside a maps intent the owner explicitly asked for.
 */
class MyWorldStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val note: String,
        val savedAt: Long
    )

    /** Pins the current location under [rawName], with an optional note. */
    fun remember(rawName: String, note: String = ""): String {
        val name = normalise(rawName)
        if (name.length < 2) {
            return "Give the place a name, sir - for example: remember this place as gym."
        }
        if (!hasLocationPermission()) {
            return "I need Location access to pin a place, sir. " +
                "Grant it under Permissions and access, then try again."
        }
        val fix = lastKnown()
            ?: return "I cannot get a location fix right now, sir. " +
                "Turn GPS on, step near a window, and ask me again."

        val place = Place(name, fix.latitude, fix.longitude, note.trim(), System.currentTimeMillis())
        persist(all().filterNot { it.name == name } + place)

        val suffix = if (note.isBlank()) "" else " Noted: " + note.trim() + "."
        return "Pinned this spot as \"" + name + "\", sir." + suffix +
            " Say \"navigate to " + name + "\" any time."
    }

    /** Describes one saved place, or explains that it is unknown. */
    fun where(rawName: String): String {
        val name = normalise(rawName)
        val place = all().firstOrNull { it.name == name }
            ?: return "I have not pinned \"" + name + "\" yet, sir. " +
                "Stand there and say: remember this place as " + name + "."
        val note = if (place.note.isBlank()) "" else " - " + place.note
        return "\"" + place.name + "\" is at " +
            format(place.latitude) + ", " + format(place.longitude) + note + ", sir."
    }

    /** Lists everything in the owner's world. */
    fun list(): String {
        val places = all()
        if (places.isEmpty()) {
            return "Your world is empty, sir. Say \"remember this place as home\" to start it."
        }
        val lines = places
            .sortedByDescending { it.savedAt }
            .joinToString("\n") { place ->
                val note = if (place.note.isBlank()) "" else " (" + place.note + ")"
                "- " + place.name + note
            }
        return "Your world has " + places.size + " pinned place" +
            (if (places.size == 1) "" else "s") + ", sir:\n" + lines
    }

    fun forget(rawName: String): String {
        val name = normalise(rawName)
        val existing = all()
        val remaining = existing.filterNot { it.name == name }
        if (remaining.size == existing.size) return "\"" + name + "\" was not in your world, sir."
        persist(remaining)
        return "Removed \"" + name + "\" from your world, sir."
    }

    /** Returns "lat,lon" for a pinned place so the maps action can route to it. */
    fun coordinatesFor(rawName: String): String? {
        val name = normalise(rawName)
        val place = all().firstOrNull { it.name == name } ?: return null
        return format(place.latitude) + "," + format(place.longitude)
    }

    fun all(): List<Place> {
        val raw = prefs.getString(KEY_PLACES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Place(
                    name = item.optString("name"),
                    latitude = item.optDouble("lat"),
                    longitude = item.optDouble("lon"),
                    note = item.optString("note"),
                    savedAt = item.optLong("at")
                ).takeIf { it.name.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(places: List<Place>) {
        val array = JSONArray()
        places.takeLast(MAX_PLACES).forEach { place ->
            array.put(
                JSONObject()
                    .put("name", place.name)
                    .put("lat", place.latitude)
                    .put("lon", place.longitude)
                    .put("note", place.note)
                    .put("at", place.savedAt)
            )
        }
        prefs.edit().putString(KEY_PLACES, array.toString()).apply()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Takes the freshest of the available providers. A live fix would need a
     * callback and a foreground wait, which is the wrong trade for pinning a
     * place the owner is already standing in.
     */
    private fun lastKnown(): Location? {
        val manager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        }.getOrNull()
    }

    private fun normalise(value: String): String =
        value.lowercase().trim().trim('.', ',', '!', '?').replace(Regex("\\s+"), " ")

    private fun format(value: Double): String = String.format("%.5f", value)

    private companion object {
        const val PREFS_NAME = "aurix_my_world"
        const val KEY_PLACES = "places_json"
        const val MAX_PLACES = 60
    }
}
