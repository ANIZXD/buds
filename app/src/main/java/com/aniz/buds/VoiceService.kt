package com.aniz.buds

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.support.v4.media.session.MediaSessionCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * The whole assistant. It holds a foreground service (so the mic and the earbud
 * button keep working with the screen off and the phone in a pocket), a media
 * session (so an earbud tap = talk to me), the speech recogniser, the speech
 * synthesiser, and the loop that ties them to the cloud brain.
 */
class VoiceService : Service() {

    companion object {
        const val ACTION_START = "com.aniz.buds.START"
        const val ACTION_STOP = "com.aniz.buds.STOP_SERVICE"
        const val ACTION_TOGGLE = "com.aniz.buds.TOGGLE"
        const val ACTION_LISTEN = "com.aniz.buds.LISTEN"

        private const val CHANNEL = "buds_main"
        private const val NOTIF_ID = 41

        /** How long after a command you can keep talking without tapping again. */
        private const val FOLLOW_UP_MS = 20_000L

        @Volatile
        var running = false
            private set

        private val STOP_WORDS = setOf(
            "stop", "cancel", "never mind", "nevermind", "that's all", "thats all",
            "stop listening", "shut up", "goodbye", "bye", "bye bye", "quit", "exit"
        )

        fun start(ctx: Context, action: String) {
            val i = Intent(ctx, VoiceService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var session: MediaSessionCompat? = null
    private var tone: ToneGenerator? = null

    private var listening = false
    private var wantListening = false
    private var speaking = false
    private var followUpUntil = 0L
    private var suppressMediaUntil = 0L
    private var lastTap = 0L
    private var lastState = ""

    private val turns = ArrayList<Brain.Turn>()

    // ------------------------------------------------------------------ setup

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        loadTurns()
        initTts()
        initSession()
        handler.postDelayed(watchdog, 1000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> {
                goForeground(getString(R.string.notif_idle))
                startListening()
            }
            ACTION_TOGGLE, Intent.ACTION_MEDIA_BUTTON -> {
                goForeground(getString(R.string.notif_idle))
                toggle()
            }
            else -> {
                goForeground(getString(R.string.notif_idle))
                startListening()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        try { session?.release() } catch (_: Throwable) {}
        session = null
        try { tone?.release() } catch (_: Throwable) {}
        tone = null
        super.onDestroy()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (wantListening && !listening && !speaking && System.currentTimeMillis() > followUpUntil) goIdle()
            handler.postDelayed(this, 1000)
        }
    }

    // -------------------------------------------------------------- earbud tap

    @Suppress("DEPRECATION")
    private fun initSession() {
        val s = MediaSessionCompat(this, "Buds")
        s.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        s.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = onTap()
            override fun onPause() = onTap()
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val e = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                val code = e?.keyCode ?: return super.onMediaButtonEvent(mediaButtonIntent)
                if (e.action != KeyEvent.ACTION_DOWN) return true
                return when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        onTap()
                        true
                    }
                    else -> false
                }
            }
        }, handler)
        s.isActive = true
        session = s
    }

    private fun onTap() {
        val now = System.currentTimeMillis()
        if (now < suppressMediaUntil) return
        if (now - lastTap < 350) return
        lastTap = now
        buzz()
        goForeground(getString(R.string.notif_idle))
        toggle()
    }

    private fun toggle() {
        when {
            speaking -> { stopSpeaking(); startListening() }
            listening -> stopListening()
            else -> startListening()
        }
    }

    // ------------------------------------------------------------- listening

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (t: Throwable) {
            null
        }
        recognizer?.setRecognitionListener(sttListener)
    }

    private fun sttIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)

    private fun startListening(extendWindow: Boolean = true) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak("I need microphone permission first.")
            return
        }
        if (extendWindow) followUpUntil = System.currentTimeMillis() + FOLLOW_UP_MS
        wantListening = true
        if (speaking) stopSpeaking()
        if (listening) return

        ensureRecognizer()
        val r = recognizer ?: run {
            setState("No speech recogniser on this phone")
            return
        }
        try {
            r.startListening(sttIntent())
            listening = true
            setState("Listening…")
            if (prefs.chime) beep()
        } catch (t: Throwable) {
            listening = false
            setState("Microphone busy - tap again")
        }
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.stopListening() } catch (_: Throwable) {}
    }

    private fun goIdle() {
        wantListening = false
        stopListening()
        setState(getString(R.string.notif_idle))
    }

    private fun restartSoon(delay: Long) {
        handler.postDelayed({
            if (wantListening && !listening && !speaking) startListening(false)
        }, delay)
    }

    private val sttListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = setState("Listening…")
        override fun onBeginningOfSpeech() = setState("Hearing you…")
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            listening = false
            setState("Thinking…")
        }

        override fun onError(error: Int) {
            listening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (wantListening && System.currentTimeMillis() < followUpUntil) restartSoon(250)
                    else goIdle()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon(600)
                SpeechRecognizer.ERROR_CLIENT -> { /* harmless, usually a cancelled session */ }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    speak("I need microphone permission.")
                    goIdle()
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    speak("I can't reach the speech service.")
                    goIdle()
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    try { recognizer?.destroy() } catch (_: Throwable) {}
                    recognizer = null
                    restartSoon(400)
                }
                else -> {
                    if (wantListening && System.currentTimeMillis() < followUpUntil) restartSoon(400) else goIdle()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
            if (said.isNullOrEmpty()) {
                if (wantListening && System.currentTimeMillis() < followUpUntil) restartSoon(250) else goIdle()
                return
            }
            handleUtterance(said)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.lastOrNull()
            if (!p.isNullOrBlank()) setState("… $p")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ------------------------------------------------------------- deciding

    private fun handleUtterance(raw: String) {
        BudsLog.add("You: $raw")
        val text = FastPath.stripWake(raw).trim()
        if (text.isBlank()) {
            speak("Yeah?")
            return
        }

        val low = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        if (low in STOP_WORDS) {
            speak("Okay.")
            wantListening = false
            return
        }

        if (prefs.fastPath) {
            val local = FastPath.decide(text, this)
            if (local != null) {
                followUpUntil = System.currentTimeMillis() + FOLLOW_UP_MS
                perform(local)
                return
            }
        }
        askBrain(text)
    }

    private fun askBrain(text: String) {
        val key = prefs.apiKey.trim()
        if (key.isEmpty()) {
            speak("I need a Groq API key. Open the Buds app and paste one in.")
            return
        }
        setState("Thinking…")
        followUpUntil = System.currentTimeMillis() + FOLLOW_UP_MS
        val model = prefs.model
        val history = turns.toList()

        scope.launch {
            val device = withContext(Dispatchers.IO) { deviceInfo() }
            val decision = withContext(Dispatchers.IO) {
                try {
                    Brain.think(key, model, Brain.devicePrompt(device), history, text)
                } catch (t: Throwable) {
                    Decision("I couldn't reach the brain - check your connection.")
                }
            }
            turns.add(Brain.Turn("user", text))
            if (decision.say.isNotBlank()) turns.add(Brain.Turn("assistant", decision.say))
            trimAndSave()
            perform(decision)
        }
    }

    private fun perform(d: Decision) {
        var say = d.say.trim()
        try {
            d.act?.let { a ->
                val note = runAct(a)
                if (!note.isNullOrBlank()) say = if (say.isBlank()) note else "$say $note"
            }
        } catch (t: Throwable) {
            if (say.isBlank()) say = "That didn't work."
        }
        speak(say)
    }

    private fun runAct(a: Act): String? = when (a.name) {
        "media" -> { mediaKey(a.args["cmd"] ?: "toggle"); null }
        "volume" -> volume(a.args)
        else -> Actions.run(this, a)
    }

    // ------------------------------------------------------------- playback

    private fun mediaKey(cmd: String) {
        val code = when (cmd) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        // Step out of the way so the key lands on the music app, not on ourselves.
        suppressMediaUntil = System.currentTimeMillis() + 900
        try { session?.isActive = false } catch (_: Throwable) {}
        try {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Throwable) {}
        handler.postDelayed({ try { session?.isActive = true } catch (_: Throwable) {} }, 800)
    }

    private fun volume(args: Map<String, String>): String? = when (args["cmd"]) {
        "up" -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            null
        }
        "down" -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            null
        }
        "mute" -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            null
        }
        "unmute" -> {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            null
        }
        "set" -> {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pct = args["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max * pct / 100, AudioManager.FLAG_SHOW_UI)
            null
        }
        else -> null
    }

    // ------------------------------------------------------------- speaking

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            try {
                tts?.setLanguage(Locale.US)
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        onSpeechDone()
                    }
                    @Deprecated("superseded by the int overload")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                        onSpeechDone()
                    }
                })
            } catch (_: Throwable) {}
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) {
            onSpeechDone()
            return
        }
        BudsLog.add("Buds: $text")
        if (!prefs.speakReplies || !ttsReady) {
            onSpeechDone()
            return
        }
        if (listening) stopListening()
        val t = tts
        if (t == null) {
            onSpeechDone()
            return
        }
        speaking = true
        setState("Speaking…")
        val ok = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.currentTimeMillis())
        if (ok == TextToSpeech.ERROR) {
            speaking = false
            onSpeechDone()
        }
    }

    private fun stopSpeaking() {
        try { tts?.stop() } catch (_: Throwable) {}
        speaking = false
    }

    private fun onSpeechDone() {
        if (wantListening && System.currentTimeMillis() < followUpUntil) {
            restartSoon(220)
        } else {
            goIdle()
        }
    }

    // ------------------------------------------------------------- plumbing

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            try {
                startForeground(NOTIF_ID, n)
            } catch (t2: Throwable) {
                // The system refused a background start; fall back to a plain notification.
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, n)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun setState(text: String) {
        if (text == lastState) return
        lastState = text
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }

    private fun piFlags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun buildNotification(text: String): Notification {
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).setAction(ACTION_TOGGLE), piFlags()
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP), piFlags()
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), piFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle("Buds")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(0, getString(R.string.notif_action_listen), toggle).build())
            .addAction(NotificationCompat.Action.Builder(0, getString(R.string.notif_action_stop), stop).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = getString(R.string.notif_channel_desc)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        } catch (_: Throwable) {}
    }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(35, 60))
            } else {
                @Suppress("DEPRECATION") v.vibrate(35)
            }
        } catch (_: Throwable) {}
    }

    private fun deviceInfo(): Brain.Device {
        val now = Calendar.getInstance().time
        val time = DateFormat.getTimeFormat(this).format(now)
        val date = DateFormat.getDateFormat(this).format(now)
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        return Brain.Device(time, date, pct, Apps.labels(this))
    }

    private fun trimAndSave() {
        while (turns.size > 12) turns.removeAt(0)
        try {
            val arr = JSONArray()
            for (t in turns) arr.put(JSONObject().put("role", t.role).put("content", t.content))
            prefs.history = arr.toString()
        } catch (_: Throwable) {}
    }

    private fun loadTurns() {
        try {
            val raw = prefs.history
            if (raw.isBlank()) return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                turns.add(Brain.Turn(o.getString("role"), o.getString("content")))
            }
        } catch (_: Throwable) {}
    }

    private fun shutdown() {
        wantListening = false
        stopListening()
        stopSpeaking()
        lastState = ""
        try { stopForeground(true) } catch (_: Throwable) {}
        stopSelf()
    }
}
