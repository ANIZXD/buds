package com.aniz.buds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline command grammar tests. These run on the JVM (no device, no Context)
 * because classify() is pure: nothing here touches the clock, battery or apps.
 */
class FastPathTest {

    private fun kind(s: String): String? = FastPath.classify(s)?.kind
    private fun actName(s: String): String? = FastPath.classify(s)?.act?.name
    private fun args(s: String): Map<String, String> = FastPath.classify(s)?.act?.args ?: emptyMap()
    private fun say(s: String): String = FastPath.classify(s)?.say ?: ""

    // ------------------------------------------------------------- media

    @Test
    fun mediaNext() {
        for (p in listOf(
            "next", "next song", "next track", "next one", "next up",
            "skip", "skip song", "skip the track", "go next", "move on",
            "nexy", "nex", "nest", "nexdt", "go to the next song",
            "play the next song", "give me the next song", "next song please",
            "hey buds next song", "next the song"
        )) {
            assertEquals("kinds: $p", "media", kind(p))
            assertEquals("act: $p", "media", actName(p))
            assertEquals("cmd: $p", "next", args(p)["cmd"])
        }
    }

    @Test
    fun mediaPrev() {
        for (p in listOf(
            "previous", "previous song", "prev", "previews track",
            "pervious", "last song", "last one", "go back", "back",
            "play the previous song"
        )) {
            assertEquals("kinds: $p", "media", kind(p))
            assertEquals("cmd: $p", "prev", args(p)["cmd"])
        }
    }

    @Test
    fun mediaToggle() {
        for (p in listOf(
            "play", "ply", "plau", "start", "start playing", "resume",
            "continue", "keep going", "play the music", "play music",
            "pause", "peus", "pasue", "paus", "pouse", "pauze", "paws",
            "pause the music", "stop the music", "stop music", "music off",
            "hold on", "wait", "stop the playback"
        )) {
            assertEquals("kinds: $p", "media", kind(p))
            assertEquals("cmd: $p", "toggle", args(p)["cmd"])
        }
    }

    @Test
    fun mediaNeedsNetwork() {
        // These should NOT be answers on-device - they should go to the brain.
        assertNull(kind("play despacito"))
        assertNull(kind("next episode of something good"))
        assertNull(kind("pause the podcast and remind me later"))
    }

    // ------------------------------------------------------------- volume

    @Test
    fun volumeUp() {
        for (p in listOf(
            "louder", "loud", "loudee", "lader", "volume up", "volum up",
            "turn it up", "turn up", "turn the volume up", "increase volume",
            "raise the volume", "up the volume", "make it louder",
            "crank it up", "volume high", "turn the music up"
        )) {
            assertEquals("kinds: $p", "volume", kind(p))
            assertEquals("cmd: $p", "up", args(p)["cmd"])
        }
    }

    @Test
    fun volumeDown() {
        for (p in listOf(
            "quieter", "quiet", "queter", "volume down", "volum down",
            "turn it down", "turn the volume down", "decrease volume",
            "lower volume", "lower the volume", "down the volume",
            "make it quieter", "softer", "volume low", "turn the music down"
        )) {
            assertEquals("kinds: $p", "volume", kind(p))
            assertEquals("cmd: $p", "down", args(p)["cmd"])
        }
    }

    @Test
    fun volumeMute() {
        for (p in listOf(
            "mute", "mewt", "mutte", "mute the music", "mute the sound",
            "silence", "silent", "be quiet", "turn the sound off"
        )) {
            assertEquals("kinds: $p", "volume", kind(p))
            assertEquals("cmd: $p", "mute", args(p)["cmd"])
        }
        for (p in listOf(
            "unmute", "unmute the music", "turn the sound on",
            "speak up", "volume back"
        )) {
            assertEquals("kinds: $p", "volume", kind(p))
            assertEquals("cmd: $p", "unmute", args(p)["cmd"])
        }
    }

    @Test
    fun volumeSet() {
        assertEquals("70", args("set volume to 70")["level"])
        assertEquals("70", args("set the volume to 70 percent")["level"])
        assertEquals("30", args("volume 30")["level"])
        assertEquals("30", args("volume to 30%")["level"])
        assertEquals("100", args("volume max")["level"])
        assertEquals("100", args("max volume")["level"])
        assertEquals("100", args("turn the volume to full")["level"])
        assertEquals("50", args("half volume")["level"])
        assertTrue(args("set volume to 999")["level"].let { it == "100" })
        assertEquals("kinds", "volume", kind("set volume to 70"))
    }

    // ------------------------------------------------------------- clock / battery

    @Test
    fun timeAndDate() {
        for (p in listOf(
            "what time is it", "whats the time", "what is the time", "what time",
            "the time", "tell me the time", "current time", "whats the time now",
            "time please", "hey buds whats the time"
        )) assertEquals("time", kind(p))
        for (p in listOf(
            "whats the date", "what is the date", "what date is it",
            "what day is it", "todays date", "the date", "whats today"
        )) assertEquals("date", kind(p))
    }

    @Test
    fun battery() {
        for (p in listOf(
            "battery", "battery level", "battery percentage",
            "how much battery", "hows the battery", "how much battery do i have",
            "whats the battery", "charge level", "battery left", "battery status"
        )) assertEquals("battery", kind(p))
    }

    // ------------------------------------------------------------- identity / small talk

    @Test
    fun identity() {
        for (p in listOf(
            "who are you", "whats your name", "what is your name",
            "what are you", "your name", "are you buds"
        )) {
            assertEquals("say $p", "say", kind(p))
            assertTrue(say(p).contains("Buds"))
        }
        for (p in listOf(
            "how are you", "hows it going", "how are you doing", "whats up"
        )) assertEquals("say", kind(p))
    }

    @Test
    fun smallTalk() {
        for (p in listOf("hi", "hello", "hey", "good morning", "good night",
            "thanks", "thank you", "thank you buds", "ok", "cool", "good job")) {
            assertEquals("say $p", "say", kind(p))
        }
    }

    // ------------------------------------------------------------- torch

    @Test
    fun torch() {
        for (p in listOf(
            "turn on the flashlight", "flashlight on", "torch on", "light on",
            "turn on torch", "flash on", "turn on the light", "lights on"
        )) {
            assertEquals("kinds $p", "flash", kind(p))
            assertEquals("on", args(p)["state"])
        }
        for (p in listOf(
            "turn off the flashlight", "flashlight off", "torch off", "light off",
            "turn off torch", "flash off", "turn off the light", "lights off"
        )) {
            assertEquals("kinds $p", "flash", kind(p))
            assertEquals("off", args(p)["state"])
        }
    }

    // ------------------------------------------------------------- open / call / search

    @Test
    fun openSiteWorksWithoutResolver() {
        // Sites are offline - no app resolver needed.
        val m = FastPath.classify("open youtube") ?: error("no match")
        assertEquals("open", m.kind)
        assertEquals("open_url", m.act?.name)
        assertEquals("https://www.youtube.com", m.act?.args?.get("url"))
        assertEquals("Opening youtube.", m.say)
    }

    @Test
    fun openAppUsesResolver() {
        val m = FastPath.classify("open instagram") { "com.instagram.android" }
            ?: error("no match")
        assertEquals("open", m.kind)
        assertEquals("open_app", m.act?.name)
        assertEquals("com.instagram.android", m.act?.args?.get("pkg"))
    }

    @Test
    fun openUnknownGoesToBrain() {
        assertNull(kind("open the floodgates"))
        assertNull(kind("hey nova next song")) // "nova" is not this app's wake word
    }

    @Test
    fun call() {
        val m = FastPath.classify("call mum") ?: error("no match")
        assertEquals("call", m.kind)
        assertEquals("call", m.act?.name)
        assertEquals("mum", m.act?.args?.get("who"))
        for (p in listOf("dial mum", "call up mum", "ring mum", "phone mum")) {
            assertEquals("call", kind(p))
        }
    }

    @Test
    fun search() {
        val m = FastPath.classify("search youtube for cats") ?: error("no match")
        assertEquals("search", m.kind)
        assertEquals("cats", m.act?.args?.get("query"))
        assertEquals("youtube", m.act?.args?.get("site"))
        val g = FastPath.classify("google inspiration") ?: error("no match")
        assertEquals("google", g.act?.args?.get("site"))
        assertEquals("inspiration", g.act?.args?.get("query"))
    }

    @Test
    fun mathAndJunkGoesToBrain() {
        assertNull(kind("what is 2 plus 2"))
        assertNull(kind("set an alarm for 7 am"))
        assertNull(kind("message Riya let's meet at 5"))
        assertEquals("empty is just an acknowledgment", "say", kind(""))
    }
}