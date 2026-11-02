package com.example.arfas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Item 5c: one question saved to check online later (scrubbed before it is stored). */
data class SavedQuestion(val id: Long, val asked: String, val display: String, val visitId: String, val at: Long)

/**
 * Item 5c: "Save to check later" queue. Small SharedPreferences list (no new database):
 * the scrubbed question, the visit it belongs to, and when it was saved. The screen
 * runs the queue when the app is open with internet + key, and delivers each answer
 * into that visit's thread with a "saved answer ready" badge.
 */
object ArogyaOnlineQueue {
    private const val PREFS = "arogya_online_queue"
    private const val KEY = "queue"
    const val MAX = 20

    fun list(context: Context): List<SavedQuestion> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                SavedQuestion(o.getLong("id"), o.getString("asked"), o.optString("display", o.getString("asked")),
                    o.optString("visitId", ""), o.optLong("at", 0L))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context, items: List<SavedQuestion>) {
        val a = JSONArray()
        items.forEach { q ->
            a.put(JSONObject().put("id", q.id).put("asked", q.asked).put("display", q.display)
                .put("visitId", q.visitId).put("at", q.at))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /** Adds a scrubbed question (deduped per visit); oldest drops past [MAX]. */
    fun add(context: Context, asked: String, display: String, visitId: String): Boolean {
        val q = asked.trim()
        if (q.isEmpty()) return false
        val cur = list(context).filterNot { it.asked.equals(q, ignoreCase = true) && it.visitId == visitId }
        store(context, (cur + SavedQuestion(System.currentTimeMillis(), q, display.ifBlank { q }, visitId, System.currentTimeMillis())).takeLast(MAX))
        return true
    }

    fun remove(context: Context, id: Long) {
        store(context, list(context).filterNot { it.id == id })
    }
}
