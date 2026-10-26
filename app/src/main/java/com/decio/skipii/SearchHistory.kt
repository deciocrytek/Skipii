package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * What was searched in Skipii, newest first (local only, never sent to the account). A repeat
 * moves to the top; matching ignores case, the latest spelling wins.
 */
object SearchHistory {
    private const val KEY = "queries"
    private const val MAX = 50
    private var prefs: SharedPreferences? = null

    var entries by mutableStateOf<List<String>>(emptyList())
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
        val json = prefs?.getString(KEY, null) ?: return
        entries = runCatching {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        }.getOrElse { emptyList() }
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        save(listOf(q) + entries.filterNot { it.equals(q, ignoreCase = true) })
    }

    fun remove(query: String) = save(entries - query)

    fun clear() = save(emptyList())

    /** Entries containing [query] (all of them for a blank query). */
    fun matching(query: String): List<String> {
        val q = query.trim()
        return if (q.isEmpty()) entries else entries.filter { it.contains(q, ignoreCase = true) }
    }

    private fun save(list: List<String>) {
        entries = list.take(MAX)
        prefs?.edit()?.putString(KEY, JSONArray(entries).toString())?.apply()
    }
}
