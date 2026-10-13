package com.aniz.buds

/**
 * The wake-word detector. Kept pure (no Android classes) so it can be
 * unit-tested on the JVM: it only answers "does this transcript contain the
 * wake phrase?".
 *
 * Phrases are matched on word boundaries, so ordinary words like "budget" or
 * "buddha" never wake Buds. Case and punctuation are ignored, and the common
 * mishears ("hey buts", "bud") are first-class wake words.
 */
object Wake {

    /** The exact phrases that wake Buds from idle. Also the wake words the fast
     *  path strips from the front of a command ("hey buds, next song"). */
    val phrases = listOf(
        "hey buds", "ok buds", "okay buds", "hi buds", "hey bud", "hey buts",
        "buds", "bud", "buts"
    )

    private val clean = Regex("[^a-z ]+")
    private val spot by lazy {
        Regex("\\b(?:" + phrases.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)
    }

    /** True when raw speech text contains a wake phrase anywhere in it. */
    fun spotted(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return spot.containsMatchIn(clean.replace(raw.lowercase(), " "))
    }
}