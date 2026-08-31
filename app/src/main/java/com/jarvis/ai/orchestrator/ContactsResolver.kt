package com.jarvis.ai.orchestrator

import android.content.Context
import android.provider.ContactsContract

/**
 * Resolves a spoken contact name ("Ritik", "mummy") to a phone number using
 * the device Contacts provider. Requires android.permission.READ_CONTACTS
 * (runtime-granted, checked by the caller before this is used).
 *
 * All cursor access is wrapped in runCatching + use{} so a locked/corrupt
 * contacts provider on some OEM ROMs never crashes the caller — it just
 * yields no match, same failure shape as "contact not found".
 */
object ContactsResolver {

    data class ContactMatch(val displayName: String, val phoneNumber: String)

    /**
     * Best-effort resolution: exact (case-insensitive) name match wins; else
     * the first contact whose display name *contains* [name]. Returns null on
     * no match or on any provider error.
     */
    fun resolvePhoneNumber(context: Context, name: String): String? =
        resolveContact(context, name)?.phoneNumber

    fun resolveContact(context: Context, name: String): ContactMatch? = runCatching {
        val needle = name.trim().lowercase()
        if (needle.isEmpty()) return@runCatching null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var exact: ContactMatch? = null
        var partial: ContactMatch? = null

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numberIdx < 0) return@use

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                val normalized = displayName.trim().lowercase()

                if (normalized == needle) {
                    exact = ContactMatch(displayName, number)
                    return@use // exact match found, stop scanning
                }
                if (partial == null && normalized.contains(needle)) {
                    partial = ContactMatch(displayName, number)
                }
            }
        }

        exact ?: partial
    }.getOrNull()
}
