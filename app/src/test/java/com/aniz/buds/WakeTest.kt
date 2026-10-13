package com.aniz.buds

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure wake-phrase detection tests — no device, no Context. */
class WakeTest {

    @Test
    fun wakesOnPhrases() {
        for (p in listOf(
            "hey buds", "hey bud", "hey buts", "ok buds", "okay buds",
            "buds", "bud", "buts",
            "hey buds what time is it", "hey buds,next song",
            "hey BUDS stop", "ok buds pause the music", "hi buds"
        )) {
            assertTrue("should wake: $p", Wake.spotted(p))
        }
    }

    @Test
    fun doesNotWakeOnOrdinarySpeech() {
        for (p in listOf(
            null, "", " ",
            "budget", "buddha", "a budding friendship", "abudhabi",
            "what time is it", "next song", "play some music", "set an alarm"
        )) {
            assertFalse("should NOT wake: $p", Wake.spotted(p))
        }
    }

    @Test
    fun wakePhrasesAreTheFastPathWakeList() {
        // Every wake form also strips from the front of a spoken command.
        for (p in Wake.phrases) {
            val text = FastPath.stripWake("$p next song").lowercase()
            assertTrue("stripWake('$p next song') => '$text'", text.startsWith("next song"))
        }
    }
}