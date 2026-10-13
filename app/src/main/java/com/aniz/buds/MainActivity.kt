package com.aniz.buds

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aniz.buds.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val perms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.etKey.setText(prefs.apiKey)
        b.etModel.setText(prefs.model)
        b.swSpeak.isChecked = prefs.speakReplies
        b.swFast.isChecked = prefs.fastPath
        b.swChime.isChecked = prefs.chime
        b.swWake.isChecked = prefs.wakeWord

        b.btnSave.setOnClickListener {
            save()
            toast("Saved")
        }
        b.btnTest.setOnClickListener {
            save()
            test()
        }
        b.btnToggle.setOnClickListener { toggleHandsfree() }
        b.btnListen.setOnClickListener {
            VoiceService.start(this, VoiceService.ACTION_LISTEN)
            toast("Listening")
        }
        b.btnPerms.setOnClickListener { askPerms() }
        b.btnOverlay.setOnClickListener { askOverlay() }

        val transcript = BudsLog.all().takeLast(200).joinToString("\n")
        b.tvLog.text = transcript.ifBlank { getString(R.string.log_empty) }
        BudsLog.listener = { line ->
            runOnUiThread {
                val empty = b.tvLog.text.isEmpty() || b.tvLog.text.toString() == getString(R.string.log_empty)
                b.tvLog.text = if (empty) line else b.tvLog.text.toString() + "\n" + line
                b.logScroll.post { b.logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        BudsLog.listener = null
        scope.cancel()
        super.onDestroy()
    }

    private fun save() {
        prefs.apiKey = b.etKey.text.toString().trim()
        val m = b.etModel.text.toString().trim()
        prefs.model = if (m.isBlank()) Prefs.DEFAULT_MODEL else m
        prefs.speakReplies = b.swSpeak.isChecked
        prefs.fastPath = b.swFast.isChecked
        prefs.chime = b.swChime.isChecked
        prefs.wakeWord = b.swWake.isChecked
    }

    private fun refresh() {
        val on = VoiceService.running
        b.tvState.text = getString(if (on) R.string.state_on else R.string.state_off)
        b.btnToggle.text = getString(if (on) R.string.btn_stop else R.string.btn_start)

        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, if (on) R.color.ok else R.color.bad))
        }
        b.dotState.background = dot

        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        val phone = granted(Manifest.permission.CALL_PHONE)
        val camera = granted(Manifest.permission.CAMERA)
        val overlay = Settings.canDrawOverlays(this)

        val rows = listOf(
            mic to getString(R.string.perm_mic),
            notif to getString(R.string.perm_notif),
            contacts to getString(R.string.perm_contacts),
            phone to getString(R.string.perm_phone),
            camera to getString(R.string.perm_camera),
            overlay to "Open apps from the background (Display over other apps)"
        )
        b.tvPerms.text = Html.fromHtml(
            rows.joinToString("<br>") { (ok, label) -> mark(ok) + "&nbsp;&nbsp;" + label },
            Html.FROM_HTML_MODE_LEGACY
        )
    }

    /** A green tick or a red cross, as inline HTML so it can sit in one TextView. */
    private fun mark(ok: Boolean): String {
        val c = ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad)
        val hex = String.format("#%06X", 0xFFFFFF and c)
        return "<font color=\"$hex\"><b>" + (if (ok) "✓" else "✗") + "</b></font>"
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        list.add(Manifest.permission.READ_CONTACTS)
        list.add(Manifest.permission.CALL_PHONE)
        list.add(Manifest.permission.CAMERA)
        perms.launch(list.toTypedArray())
    }

    private fun askOverlay() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (t: Throwable) {
            toast("Settings > Apps > Buds > Display over other apps")
        }
    }

    private fun toggleHandsfree() {
        if (VoiceService.running) {
            VoiceService.start(this, VoiceService.ACTION_STOP)
        } else {
            if (!granted(Manifest.permission.RECORD_AUDIO)) {
                askPerms()
                toast("Microphone permission is required")
                return
            }
            VoiceService.start(this, VoiceService.ACTION_START)
        }
        b.root.postDelayed({ refresh() }, 400)
    }

    private fun test() {
        if (prefs.apiKey.isBlank()) {
            toast("Paste a Groq API key first")
            return
        }
        b.tvTest.text = "asking…"
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                try {
                    Brain.think(
                        prefs.apiKey, prefs.model, Brain.simplePrompt(),
                        emptyList(), "Hello, who are you?"
                    )
                } catch (t: Throwable) {
                    Decision("ERROR " + (t.message ?: t.javaClass.simpleName))
                }
            }
            b.tvTest.text = "say:  ${d.say}\naction:  ${d.act?.name ?: "none"} ${d.act?.args ?: ""}"
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
