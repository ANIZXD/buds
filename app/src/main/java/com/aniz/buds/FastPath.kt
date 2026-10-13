package com.aniz.buds

import android.content.Context
import android.os.BatteryManager
import java.net.URLEncoder
import android.text.format.DateFormat
import java.util.Date

/** Something Buds decided to do: a line to speak, and optionally one action to run. */
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
 * the phone (with a lot more misheard-spelling tolerance, because speech-to-text
 * from earbud mics mangles words). Anything recognised here is answered with zero
 * network round-trips; anything it doesn't know returns null and gets handed to
 * the cloud brain.
 */
object FastPath {

    private val I = RegexOption.IGNORE_CASE

    /** Every command politely accepts "please" / "pls" on the end. */
    private fun combo(verbs: List<String>, targets: List<String>): Regex {
        val body = verbs.flatMap { v ->
            targets.map { t -> if (t.isEmpty()) v else "$v $t" }
        }.joinToString("|") { Regex.escape(it) }
        return Regex("^(?:$body)(?: please| pls| please do)?$", I)
    }

    /** Wake words, including the deliberate mishearing list ("bud" / "buts"). */
    private val WAKE = listOf(
        "hey buds", "hey bud", "ok buds", "okay buds", "hi buds",
        "hey buts", "buds", "bud", "buts"
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

    // ============================================================== patterns

    // ---------- media transport (the words everyone actually says) ----------
    private val NEXT = combo(
        verbs = listOf(
            "next", "nex", "nexy", "nest", "nexdt", "next up", "go next",
            "move on", "skip", "skyp", "give me the next", "play the next",
            "go to the next", "go to next", "next one"
        ),
        targets = listOf(
            "", "song", "the song", "track", "the track", "one", "the one",
            "this one", "it", "music", "the music", "tune", "the tune"
        )
    )

    private val PREV = combo(
        verbs = listOf(
            "previous", "previews", "pervious", "pevious", "prev", "prv",
            "last", "go back", "back", "go to the previous", "play the previous"
        ),
        targets = listOf(
            "", "song", "the song", "track", "the track", "one", "the one",
            "this one", "it", "music", "the music", "tune", "the tune"
        )
    )

    private val TOGGLE = combo(
        verbs = listOf(
            // play …
            "play", "ply", "plau", "pray", "pley", "start", "start playing",
            "resume", "resume music", "continue", "keep going", "unpause",
            "play the music", "play my music", "play music",
            // pause …
            "pause", "peus", "pasue", "paus", "pouse", "pauze", "paws", "pase",
            "pause the music", "pause music", "stop the music", "stop music",
            "stop the song", "stop playing", "hold on", "hold it", "wait",
            "music off", "stop the playback", "stop playback"
        ),
        targets = listOf("")
    )

    // ---------- volume ----------
    private val LOUDER = combo(
        verbs = listOf(
            "louder", "loud", "loudee", "lader", "loouder", "loude",
            "volume up", "volum up", "volume louder",
            "turn it up", "turn up", "turn the volume up", "turn volume up",
            "increase volume", "increase the volume", "raise volume",
            "raise the volume", "up the volume", "make it louder",
            "crank it up", "crank up", "volume high", "higher volume",
            "up the sound", "turn the music up"
        ),
        targets = listOf("")
    )

    private val QUIETER = combo(
        verbs = listOf(
            "quieter", "quiet", "queter", "volume down", "volum down",
            "turn it down", "turn down", "turn the volume down", "turn volume down",
            "decrease volume", "decrease the volume", "lower volume",
            "lower the volume", "down the volume", "make it quieter", "softer",
            "lower the sound", "volume low", "lower it", "turn the music down"
        ),
        targets = listOf("")
    )

    private val MUTE = combo(
        verbs = listOf(
            "mute", "mewt", "mutte", "muet", "moot", "mute it", "mute the music",
            "mute music", "mute the sound", "mute the volume", "mute the audio",
            "silence", "silent", "be quiet", "shhh", "shh", "quiet down",
            "turn the sound off", "sound off", "no sound", "no music"
        ),
        targets = listOf("")
    )

    private val UNMUTE = combo(
        verbs = listOf(
            "unmute", "unmeut", "unmoot", "un mute", "unmute it", "unmute the music",
            "unmute the sound", "turn off mute", "turn the sound on", "sound on",
            "sound back on", "speak up", "volume back", "bring the sound back"
        ),
        targets = listOf("")
    )

    private val VOLSET = Regex(
        "^(?:" +
            "(?:set|put|turn|make)?(?: the)? volume(?: to| at)? (\\d{1,3})(?: ?(?:percent|%))?|" +
            "(?:the )?volume(?: to| at)? (\\d{1,3})(?: ?(?:percent|%))?|" +
            "(?:turn|set)(?: the)? volume (?:to )?(max|maximum|full|half)|" +
            "volume (max|maximum|full|half)|" +
            "max(?:imum)? volume|full volume|half volume" +
            ")(?: please| pls)?$", I
    )

    // ---------- time / date / battery ----------
    private val TIME = combo(
        verbs = listOf(
            "what time is it", "whats the time", "what is the time", "what time",
            "whats time", "the time", "tell me the time", "current time",
            "what time do we have", "time now", "whats the time now", "the time now",
            "what is the current time", "time"
        ),
        targets = listOf("")
    )

    private val DATE_ = combo(
        verbs = listOf(
            "whats the date", "what is the date", "what date is it", "whats today",
            "what is today", "what day is it", "what day is today", "todays date",
            "today's date", "the date", "whats the date today", "date today", "date"
        ),
        targets = listOf("")
    )

    private val BATTERY = combo(
        verbs = listOf(
            "battery", "battery level", "battery percentage", "battery percent",
            "how much battery", "hows the battery", "how is the battery",
            "how much battery do i have", "how much battery is left",
            "whats the battery", "charge level", "phone battery", "battery left",
            "how charged is my phone", "how much charge", "whats my battery",
            "is my phone charged", "battery status"
        ),
        targets = listOf("")
    )

    private val WHO = combo(
        verbs = listOf(
            "who are you", "whats your name", "what is your name", "what are you",
            "what are you called", "your name", "who is this", "are you buds",
            "are you nova", "tell me your name"
        ),
        targets = listOf("")
    )

    private val HOW = combo(
        verbs = listOf(
            "how are you", "hows it going", "how is it going", "how are you doing",
            "how u doing", "how do you feel", "you okay", "you good", "whats up",
            "sup", "how are things", "howz it going", "hows everything"
        ),
        targets = listOf("")
    )

    // ---------- torch ----------
    private val TORCH_ON = combo(
        verbs = listOf(
            "turn on the flashlight", "turn on flashlight", "flashlight on", "torch on",
            "light on", "turn on the torch", "turn on torch", "flash on",
            "turn on the light", "turn the flashlight on", "switch on the torch",
            "flashlight", "torch", "turn the light on", "flash the torch",
            "give me some light", "need a light", "lights on"
        ),
        targets = listOf("")
    )

    private val TORCH_OFF = combo(
        verbs = listOf(
            "turn off the flashlight", "turn off flashlight", "flashlight off", "torch off",
            "light off", "turn off the torch", "turn off torch", "flash off",
            "turn off the light", "turn the flashlight off", "switch off the torch",
            "stop the flashlight", "put the torch away", "kill the light",
            "no more light", "turn the light off", "lights off"
        ),
        targets = listOf("")
    )

    // ---------- open / call / search ----------
    private val OPEN = Regex(
        "^(?:(?:open|openn|opend|open up|openup|launch|start|start up|go to|go into|" +
            "take me to|visit|pull up|navigate to|bring up|switch to|fire up|get me to|" +
            "go open|take me over to) (.+))$", I
    )

    private val CALL = Regex(
        "^(?:(?:call|dial|phone|ring|ring up|ring the|call up|call the|phone up|" +
            "give a call to|cawl|dil|telephone) (.+))$", I
    )

    private val SEARCH_ON = Regex(
        "^(?:(?:search|look|look up|find|google|search for)\\s+(?:for\\s+)?(.+?)\\s+(?:on|in)\\s+(\\S+))$", I
    )

    private val SEARCH = Regex(
        "^(?:(?:search|google|look up|look for|find|search for|google for|lookup|" +
            "find me|pull up|web search|search the web for|search about|look up for)\\s+(.+))$", I
    )

    private val SITE_SEARCH = Regex("^(\\S+)\\s+for\\s+(.+)$", I)

    // ---------- small talk ----------
    private val SOCIAL = mapOf(
        "hi" to "Hey.",
        "hello" to "Hey.",
        "hey" to "Hey.",
        "hello buds" to "Hey.",
        "good morning" to "Morning.",
        "morning" to "Morning.",
        "good afternoon" to "Afternoon.",
        "good evening" to "Evening.",
        "good night" to "Night.",
        "night" to "Night.",
        "thanks" to "Any time.",
        "thank you" to "Any time.",
        "thank you buds" to "Any time.",
        "thanks buds" to "Any time.",
        "thankyou" to "Any time.",
        "thx" to "Any time.",
        "good job" to "Appreciate it.",
        "well done" to "Appreciate it.",
        "nice" to "Thanks.",
        "nice one" to "Thanks.",
        "good" to "Good.",
        "great" to "Good.",
        "awesome" to "Thanks.",
        "cool" to "Cool.",
        "perfect" to "Perfect.",
        "love it" to "Glad you like it.",
        "youre the best" to "Flattery works.",
        "you rock" to "Right back at you.",
        "ok" to "Okay.",
        "okay" to "Okay.",
        "sounds good" to "Okay.",
        "got it" to "Got it.",
        "wow" to "Wow.",
        "bye" to "See you.",
        "goodbye" to "See you."
    )

    // ============================================================== matching

    /** What a phrase means, decided with zero I/O. Never touches the clock or battery. */
    internal class Match(val kind: String, val say: String, val act: Act?)

    internal fun classify(raw: String, resolveApp: (String) -> String? = { null }): Match? {
        val text = stripWake(raw).trim().trimEnd('.', '!', '?', ',').trim()
        if (text.isEmpty()) return Match("say", "Yeah?", null)
        val low = text.lowercase()

        SOCIAL[low]?.let { return Match("say", it, null) }

        if (NEXT.matches(low)) return Match("media", "Next.", act("media", "cmd" to "next"))
        if (PREV.matches(low)) return Match("media", "Back.", act("media", "cmd" to "prev"))
        if (TOGGLE.matches(low)) return Match("media", "", act("media", "cmd" to "toggle"))

        if (LOUDER.matches(low)) return Match("volume", "Up.", act("volume", "cmd" to "up"))
        if (QUIETER.matches(low)) return Match("volume", "Down.", act("volume", "cmd" to "down"))
        if (MUTE.matches(low)) return Match("volume", "", act("volume", "cmd" to "mute"))
        if (UNMUTE.matches(low)) return Match("volume", "", act("volume", "cmd" to "unmute"))
        VOLSET.find(low)?.let { m ->
            val target = when {
                m.groupValues[1].isNotBlank() -> m.groupValues[1]
                m.groupValues[2].isNotBlank() -> m.groupValues[2]
                m.value.contains("max") || m.value.contains("full") -> "100"
                else -> "50" // "half volume", "volume half", …
            }
            val n = target.toIntOrNull()?.coerceIn(0, 100) ?: return@let
            return Match("volume", "Volume $n.", act("volume", "cmd" to "set", "level" to "$n"))
        }

        if (TIME.matches(low)) return Match("time", "", null)
        if (DATE_.matches(low)) return Match("date", "", null)
        if (BATTERY.matches(low)) return Match("battery", "", null)
        if (WHO.matches(low)) return Match("say", "I'm Buds. I live on your phone, not in a browser tab, and you drive me from your earbuds.", null)
        if (HOW.matches(low)) return Match("say", "Running clean. What do you need?", null)

        if (TORCH_ON.matches(low)) return Match("flash", "Light on.", act("flashlight", "state" to "on"))
        if (TORCH_OFF.matches(low)) return Match("flash", "Light off.", act("flashlight", "state" to "off"))

        OPEN.find(text)?.let { m ->
            val spoken = m.groupValues[1].trim()
            val target = spoken.lowercase().removePrefix("the ").trim()
            resolveApp(target)?.let { pkg ->
                return Match("open", "Opening $spoken.", act("open_app", "pkg" to pkg, "label" to spoken))
            }
            Sites.urlFor(target)?.let { url ->
                return Match("open", "Opening $spoken.", act("open_url", "url" to url))
            }
            if (target.contains('.') && !target.contains(' ')) {
                val url = if (target.startsWith("http")) target else "https://$target"
                return Match("open", "Opening $spoken.", act("open_url", "url" to url))
            }
            return null // let the brain work out which app they mean
        }

        CALL.find(text)?.let { m ->
            val who = m.groupValues[1].trim()
            if (who.isNotEmpty()) return Match("call", "Calling $who.", act("call", "who" to who))
        }

        SEARCH_ON.find(text)?.let { m ->
            val q = m.groupValues[1].trim()
            val site = m.groupValues[2].trim()
            if (q.isNotEmpty() && Sites.isSearchSite(site)) {
                return Match("search", "Searching.", act("search", "query" to q, "site" to site))
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
            if (q.isNotEmpty()) return Match("search", "Searching.", act("search", "query" to q, "site" to site))
        }

        return null
    }

    /**
     * Returns a Decision for a spoken phrase, or null if the cloud should handle it.
     * Used from the service; keeps every command on-device.
     */
    fun decide(raw: String, ctx: Context): Decision? {
        val text = stripWake(raw).trim().trimEnd('.', '!', '?', ',').trim()
        if (text.isEmpty()) return Decision("Yeah?")
        val m = classify(raw) { Apps.find(ctx, it) } ?: return null
        return when (m.kind) {
            "time" -> Decision("It's " + DateFormat.getTimeFormat(ctx).format(Date()) + ".")
            "date" -> Decision("It's " + DateFormat.getDateFormat(ctx).format(Date()) + ".")
            "battery" -> {
                val pct = battery(ctx)
                Decision(if (pct >= 0) "Battery is at $pct percent." else "I can't read the battery.")
            }
            else -> Decision(m.say, m.act)
        }
    }

    private fun act(name: String, vararg pairs: Pair<String, String>) =
        Act(name, mutableMapOf(*pairs))

    private fun battery(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}