package com.aniz.nova

import android.content.Context
import android.os.BatteryManager
import java.net.URLEncoder
import android.text.format.DateFormat
import java.util.Date

/** Something Nova decided to do: a line to speak, and optionally one action to run. */
data class Decision(val say: String, val act: Act? = null)

/** One executable action. */
data class Act(val name: String, val args: MutableMap<String, String> = mutableMapOf())

/** Site shortcuts and search engines, lifted from NOVA so the vocabulary stays familiar. */
object Sites {

    private val SITE = linkedMapOf(
        "youtube" to "https://www.youtube.com",
        "yt" to "https://www.youtube.com",
        "gmail" to "https://mail.google.com",
        "mail" to "https://mail.google.com",
        "drive" to "https://drive.google.com",
        "calendar" to "https://calendar.google.com",
        "maps" to "https://maps.google.com",
        "google maps" to "https://maps.google.com",
        "translate" to "https://translate.google.com",
        "whatsapp" to "https://web.whatsapp.com",
        "instagram" to "https://www.instagram.com",
        "insta" to "https://www.instagram.com",
        "spotify" to "https://open.spotify.com",
        "reddit" to "https://www.reddit.com",
        "github" to "https://github.com",
        "netflix" to "https://www.netflix.com",
        "amazon" to "https://www.amazon.com",
        "flipkart" to "https://www.flipkart.com",
        "wikipedia" to "https://en.wikipedia.org",
        "linkedin" to "https://www.linkedin.com",
        "telegram" to "https://web.telegram.org",
        "chatgpt" to "https://chatgpt.com",
        "claude" to "https://claude.ai",
        "twitter" to "https://twitter.com",
        "x" to "https://twitter.com",
        "facebook" to "https://www.facebook.com"
    )

    private val ENGINE = linkedMapOf(
        "google" to "https://www.google.com/search?q=",
        "youtube" to "https://www.youtube.com/results?search_query=",
        "amazon" to "https://www.amazon.com/s?k=",
        "wikipedia" to "https://en.wikipedia.org/w/index.php?search=",
        "maps" to "https://www.google.com/maps/search/",
        "spotify" to "https://open.spotify.com/search/",
        "github" to "https://github.com/search?q=",
        "reddit" to "https://www.reddit.com/search/?q=",
        "flipkart" to "https://www.flipkart.com/search?q=",
        "netflix" to "https://www.netflix.com/search?q="
    )

    fun urlFor(site: String): String? = SITE[site.lowercase().trim()]

    fun isSearchSite(site: String): Boolean = ENGINE.containsKey(site.lowercase().trim())

    fun searchUrl(site: String, query: String): String {
        val base = ENGINE[site.lowercase().trim()] ?: ENGINE.getValue("google")
        return base + URLEncoder.encode(query, "UTF-8")
    }
}

/**
 * The instant, offline command layer - the genuinely good part of NOVA, moved onto
 * the phone. Anything recognised here is answered with zero network round-trips;
 * anything it doesn't know returns null and gets handed to the cloud brain.
 */
object FastPath {

    private val I = RegexOption.IGNORE_CASE

    /** Wake words, including NOVA's deliberate mishearing list. */
    private val WAKE = listOf(
        "hey nova", "hey noah", "ok nova", "okay nova", "hi nova",
        "innova", "in nova", "a nova", "eh nova", "noah", "noa", "nova"
    )

    fun stripWake(raw: String): String {
        var t = raw.trim()
        val l = t.lowercase()
        for (w in WAKE) {
            if (l == w) return ""
            if (l.startsWith("$w ") || l.startsWith("$w,")) {
                t = t.substring(w.length).trimStart(' ', ',', '.', '!', '?')
                break
            }
        }
        return t
    }

    // ---------- patterns ----------
    private val NEXT = Regex("^(?:next|skip)(?: (?:song|track|one|this one))?$", I)
    private val PREV = Regex("^(?:previous|prev|last)(?: (?:song|track|one))?$", I)
    private val TOGGLE = Regex("^(?:play|pause|play pause|pause music|play music|resume|resume music|stop (?:the )?(?:music|song|playback))$", I)

    private val LOUDER = Regex("^(?:louder|volume up|turn (?:it|the volume) up|increase (?:the )?volume|up the volume|volume louder)$", I)
    private val QUIETER = Regex("^(?:quieter|volume down|turn (?:it|the volume) down|decrease (?:the )?volume|lower (?:the )?volume|down the volume)$", I)
    private val MUTE = Regex("^(?:mute|mute (?:the )?(?:music|sound|audio)|silence)$", I)
    private val UNMUTE = Regex("^(?:unmute|unmute (?:the )?(?:music|sound|audio))$", I)
    private val VOLSET = Regex("^(?:set )?volume (?:to )?(\\d{1,3})(?: ?(?:percent|%))?$", I)

    private val TIME = Regex("^(?:what(?:'s| is)? the time|what time is it|tell me the time|the time)$", I)
    private val DATE_ = Regex("^(?:what(?:'s| is)? (?:the date|today(?:'s date)?)|what day is it|what(?:'s| is) today)$", I)
    private val BATTERY = Regex("^(?:battery|battery level|how(?:'s| is) the battery|how much battery(?: is left| do i have)?)$", I)
    private val WHO = Regex("^(?:who are you|what(?:'s| is) your name|what are you)$", I)
    private val HOW = Regex("^(?:how are you|how(?:'s| is) it going|how(?:'s| are) you doing)$", I)

    private val TORCH_ON = Regex("^(?:turn on (?:the )?(?:flashlight|torch|light)|flashlight on|torch on|light on)$", I)
    private val TORCH_OFF = Regex("^(?:turn off (?:the )?(?:flashlight|torch|light)|flashlight off|torch off|light off)$", I)

    private val OPEN = Regex("^(?:open|launch|start|go to|go into|take me to|visit|pull up|navigate to|bring up|switch to) (.+)$", I)
    private val CALL = Regex("^(?:call|dial|phone|ring up|ring) (.+)$", I)
    private val SEARCH_ON = Regex("^(?:search|look)\\s+(?:for\\s+)?(.+?)\\s+(?:on|in)\\s+(\\S+)$", I)
    private val SEARCH = Regex("^(?:search|google|look up|look for|find|search for|google for)\\s+(.+)$", I)
    private val SITE_SEARCH = Regex("^(\\S+)\\s+for\\s+(.+)$", I)

    private val SOCIAL = mapOf(
        "hi" to "Hey.",
        "hello" to "Hey.",
        "hey" to "Hey.",
        "good morning" to "Morning.",
        "good afternoon" to "Afternoon.",
        "good evening" to "Evening.",
        "good night" to "Night.",
        "thanks" to "Any time.",
        "thank you" to "Any time.",
        "thank you nova" to "Any time.",
        "good job" to "Appreciate it.",
        "nice" to "Thanks."
    )

    /**
     * Returns a Decision if the phrase is a known command, or null if the cloud
     * should handle it. Never does any I/O beyond reading the clock and battery.
     */
    fun decide(raw: String, ctx: Context): Decision? {
        val text = stripWake(raw).trim().trimEnd('.', '!', '?', ',').trim()
        if (text.isEmpty()) return Decision("Yeah?")

        val low = text.lowercase()

        SOCIAL[low]?.let { return Decision(it) }

        if (NEXT.matches(low)) return Decision("Next.", act("media", "cmd" to "next"))
        if (PREV.matches(low)) return Decision("Back.", act("media", "cmd" to "prev"))
        if (TOGGLE.matches(low)) return Decision("", act("media", "cmd" to "toggle"))

        if (LOUDER.matches(low)) return Decision("Up.", act("volume", "cmd" to "up"))
        if (QUIETER.matches(low)) return Decision("Down.", act("volume", "cmd" to "down"))
        if (MUTE.matches(low)) return Decision("", act("volume", "cmd" to "mute"))
        if (UNMUTE.matches(low)) return Decision("", act("volume", "cmd" to "unmute"))
        VOLSET.find(low)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return@let
            return Decision("Volume $n.", act("volume", "cmd" to "set", "level" to "$n"))
        }

        if (TIME.matches(low)) return Decision("It's " + DateFormat.getTimeFormat(ctx).format(Date()) + ".")
        if (DATE_.matches(low)) return Decision("It's " + DateFormat.getDateFormat(ctx).format(Date()) + ".")
        if (BATTERY.matches(low)) {
            val pct = battery(ctx)
            return Decision(if (pct >= 0) "Battery is at $pct percent." else "I can't read the battery.")
        }
        if (WHO.matches(low)) return Decision("I'm Nova. I live on your phone, not in a browser tab, and you drive me from your earbuds.")
        if (HOW.matches(low)) return Decision("Running clean. What do you need?")

        if (TORCH_ON.matches(low)) return Decision("Light on.", act("flashlight", "state" to "on"))
        if (TORCH_OFF.matches(low)) return Decision("Light off.", act("flashlight", "state" to "off"))

        OPEN.find(text)?.let { m ->
            val spoken = m.groupValues[1].trim()
            val target = spoken.lowercase().removePrefix("the ").trim()
            Apps.find(ctx, target)?.let { pkg ->
                return Decision("Opening $spoken.", act("open_app", "pkg" to pkg, "label" to spoken))
            }
            Sites.urlFor(target)?.let { url ->
                return Decision("Opening $spoken.", act("open_url", "url" to url))
            }
            if (target.contains('.') && !target.contains(' ')) {
                val url = if (target.startsWith("http")) target else "https://$target"
                return Decision("Opening $spoken.", act("open_url", "url" to url))
            }
            return null // let the brain work out which app they mean
        }

        CALL.find(text)?.let { m ->
            val who = m.groupValues[1].trim()
            if (who.isNotEmpty()) return Decision("Calling $who.", act("call", "who" to who))
        }

        SEARCH_ON.find(text)?.let { m ->
            val q = m.groupValues[1].trim()
            val site = m.groupValues[2].trim()
            if (q.isNotEmpty() && Sites.isSearchSite(site)) {
                return Decision("Searching.", act("search", "query" to q, "site" to site))
            }
        }

        SEARCH.find(text)?.let { m ->
            var q = m.groupValues[1].trim()
            var site = "google"
            SITE_SEARCH.find(q)?.let { sm ->
                val maybe = sm.groupValues[1].lowercase()
                if (Sites.isSearchSite(maybe)) {
                    site = maybe
                    q = sm.groupValues[2].trim()
                }
            }
            if (q.isNotEmpty()) return Decision("Searching.", act("search", "query" to q, "site" to site))
        }

        return null
    }

    private fun act(name: String, vararg pairs: Pair<String, String>) =
        Act(name, mutableMapOf(*pairs))

    private fun battery(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
