package com.aniz.buds

import android.content.Context
import android.content.SharedPreferences

/** All the app's settings, in one tiny place. */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("buds", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) { sp.edit().putString("apiKey", v).apply() }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v).apply() }

    /** Read Buds' answers out loud. */
    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) { sp.edit().putBoolean("speak", v).apply() }

    /** Answer the common commands on the phone itself, with no network at all. */
    var fastPath: Boolean
        get() = sp.getBoolean("fast", true)
        set(v) { sp.edit().putBoolean("fast", v).apply() }

    /** Little blip so you know it started listening without looking. */
    var chime: Boolean
        get() = sp.getBoolean("chime", true)
        set(v) { sp.edit().putBoolean("chime", v).apply() }

    /** Recent conversation, as a JSON array, so follow-ups still make sense. */
    var history: String
        get() = sp.getString("history", "") ?: ""
        set(v) { sp.edit().putString("history", v).apply() }

    companion object {
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    }
}

/** Tiny in-memory transcript so the app can show what was said. */
object BudsLog {

    private val lines = ArrayList<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun add(line: String) {
        synchronized(lines) {
            lines.add(line)
            if (lines.size > 300) lines.removeAt(0)
        }
        listener?.invoke(line)
    }

    fun all(): List<String> = synchronized(lines) { ArrayList(lines) }
}
