package com.tharunbirla.librecuts.utils

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * CapCut-style recent-projects registry for the home screen.
 *
 * LibreCuts saves projects (.lcprj) wherever the user picks via the system
 * file picker, so there is no fixed "drafts folder" to scan. Instead this
 * registry remembers every project the user saves or opens (name, file Uri,
 * last-opened time) in SharedPreferences, most-recent-first, capped so the
 * list stays small. Entries are only ever created from explicit user
 * actions (saving a project, opening one) — nothing is scanned.
 */
data class DraftEntry(
    val uri: String,
    val name: String,
    val sourceName: String,
    val lastOpenedAt: Long,
    /** Uri of the source video, used for the home-screen thumbnail. */
    val sourceUri: String? = null
)

object DraftRegistry {
    private const val PREFS_NAME = "aio_vd_drafts"
    private const val KEY_DRAFTS = "drafts_json"
    const val MAX_DRAFTS = 20

    private val gson = Gson()
    private val listType = object : TypeToken<List<DraftEntry>>() {}.type

    fun list(context: Context): List<DraftEntry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DRAFTS, null) ?: return emptyList()
        return try {
            val entries: List<DraftEntry> = gson.fromJson(json, listType) ?: return emptyList()
            entries.sortedByDescending { it.lastOpenedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Add a project to recents (or bump it to the top if already listed). */
    fun register(context: Context, uri: Uri, name: String, sourceName: String, sourceUri: Uri? = null) {
        register(context, uri.toString(), name, sourceName, sourceUri?.toString())
    }

    fun register(context: Context, uriString: String, name: String, sourceName: String, sourceUriString: String? = null) {
        val now = System.currentTimeMillis()
        val updated = list(context)
            .filterNot { it.uri == uriString }
            .toMutableList()
        updated.add(0, DraftEntry(uriString, name.ifBlank { sourceName }, sourceName, now, sourceUriString))
        save(context, updated.take(MAX_DRAFTS))
    }

    /** Refresh the "last opened" time of an existing entry. No-op if unknown. */
    fun touch(context: Context, uri: Uri) {
        val uriString = uri.toString()
        val current = list(context)
        val entry = current.find { it.uri == uriString } ?: return
        val updated = current.filterNot { it.uri == uriString }.toMutableList()
        updated.add(0, entry.copy(lastOpenedAt = System.currentTimeMillis()))
        save(context, updated.take(MAX_DRAFTS))
    }

    fun rename(context: Context, uri: Uri, newName: String) {
        val uriString = uri.toString()
        val updated = list(context).map {
            if (it.uri == uriString) it.copy(name = newName) else it
        }
        save(context, updated)
    }

    fun remove(context: Context, uri: Uri) {
        val uriString = uri.toString()
        save(context, list(context).filterNot { it.uri == uriString })
    }

    private fun save(context: Context, entries: List<DraftEntry>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFTS, gson.toJson(entries))
            .apply()
    }
}
