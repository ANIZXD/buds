package com.aniz.buds

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The cloud brain. Groq's OpenAI-compatible endpoint, one round trip per utterance,
 * and the model answers with a single small JSON object so the reply is both
 * something to say and something to do.
 */
object Brain {

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    data class Turn(val role: String, val content: String)

    class Device(val time: String, val date: String, val battery: Int, val apps: List<String>)

    fun devicePrompt(d: Device): String = buildString {
        appendLine("You are Buds, a hands-free assistant living on the user's Android phone.")
        appendLine("The user talks to you through their earbuds and you answer out loud, so replies are SPOKEN, not read.")
        appendLine()
        appendLine("Reply with ONE JSON object and nothing else:")
        appendLine("""{"say":"<short spoken reply, max 2 short sentences, no markdown, no emoji spam>","action":"<none|open_app|open_url|search|set_alarm|set_timer|call|send_sms|media|volume|flashlight>","args":{}}""")
        appendLine()
        appendLine("args by action:")
        appendLine("""  open_app    {"app":"instagram"}              - app as the user would name it""")
        appendLine("""  open_url    {"url":"https://..."}""")
        appendLine("""  search      {"query":"lofi beats","site":"youtube"}   - site optional, default google""")
        appendLine("""  set_alarm   {"hour":7,"minute":30,"label":"wake up"}  - 24h clock""")
        appendLine("""  set_timer   {"seconds":300}""")
        appendLine("""  call        {"who":"mum"}""")
        appendLine("""  send_sms    {"who":"mum","text":"..."}""")
        appendLine("""  media       {"cmd":"next|prev|toggle"}""")
        appendLine("""  volume      {"cmd":"up|down|mute|unmute|set","level":40}""")
        appendLine("""  flashlight  {"state":"on|off"}""")
        appendLine()
        appendLine("Rules:")
        appendLine("- Keep \"say\" SHORT. One or two sentences. It is spoken aloud.")
        appendLine("- Answer questions in \"say\" with action \"none\". Be direct and a little dry-witted. Never pretend to be a search engine and never say you cannot help.")
        appendLine("- If the user is just chatting, chat back briefly.")
        appendLine("- Work out relative times from the device clock below (\"in 20 minutes\", \"tomorrow at 7\").")
        appendLine("- Never invent phone numbers. Use call/send_sms with a name and let the phone look it up.")
        appendLine("- Only use an action when the user actually asked for it.")
        appendLine()
        appendLine("Device clock: ${d.time} on ${d.date}. Battery: ${d.battery}%.")
        if (d.apps.isNotEmpty()) {
            appendLine("Apps installed (use these exact names): " + d.apps.take(120).joinToString(", "))
        }
    }

    fun simplePrompt(): String =
        "You are Buds, a phone assistant. Reply with ONE JSON object and nothing else: " +
            """{"say":"short spoken reply","action":"none","args":{}}"""

    /** Blocking - always call this off the main thread. */
    fun think(apiKey: String, model: String, system: String, turns: List<Turn>, user: String): Decision {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for (t in turns) messages.put(JSONObject().put("role", t.role).put("content", t.content))
        messages.put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.4)
            .put("max_tokens", 400)
            .put("messages", messages)

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        if (code !in 200..299) return Decision(friendlyError(code, raw))

        val content = try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } catch (t: Throwable) {
            return Decision("The brain sent something I couldn't read.")
        }
        return parse(content)
    }

    /** Tolerant parsing: the model is told to send JSON, but we cope if it wraps it in prose. */
    fun parse(content: String): Decision {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val o = JSONObject(content.substring(start, end + 1))
                val say = clean(o.optString("say", ""))
                val action = o.optString("action", "none").trim()
                val args = mutableMapOf<String, String>()
                o.optJSONObject("args")?.let { j ->
                    for (k in j.keys()) args[k] = j.get(k).toString()
                }
                if (action.isNotEmpty() && action != "none") return Decision(say, Act(action, args))
                return Decision(say.ifBlank { "Done." })
            } catch (_: Throwable) {
                // fall through and just say whatever came back
            }
        }
        return Decision(clean(content))
    }

    private fun clean(s: String): String =
        s.replace("```", "").replace(Regex("^[\"']|[\"']$"), "").trim()

    private fun friendlyError(code: Int, raw: String): String = when (code) {
        401, 403 -> "My Groq key isn't being accepted. Open the app and check it."
        429 -> "I'm being rate limited. Give it a second."
        else -> "I couldn't reach the brain - HTTP $code."
    }
}
