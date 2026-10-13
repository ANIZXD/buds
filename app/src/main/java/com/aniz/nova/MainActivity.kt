package com.aniz.nova

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aniz.nova.databinding.ActivityMainBinding
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

        b.tvLog.text = NovaLog.all().joinToString("\n")
        NovaLog.listener = { line -> runOnUiThread { b.tvLog.append("\n$line") } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        NovaLog.listener = null
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
    }

    private fun refresh() {
        val on = VoiceService.running
        b.tvState.text = getString(if (on) R.string.state_on else R.string.state_off)
        b.btnToggle.text = getString(if (on) R.string.btn_stop else R.string.btn_start)

        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        val phone = granted(Manifest.permission.CALL_PHONE)
        val camera = granted(Manifest.permission.CAMERA)
        val overlay = Settings.canDrawOverlays(this)

        b.tvPerms.text = buildString {
            appendLine(tick(mic) + "  " + getString(R.string.perm_mic))
            appendLine(tick(notif) + "  " + getString(R.string.perm_notif))
            appendLine(tick(contacts) + "  " + getString(R.string.perm_contacts))
            appendLine(tick(phone) + "  " + getString(R.string.perm_phone))
            appendLine(tick(camera) + "  " + getString(R.string.perm_camera))
            append(tick(overlay) + "  Open apps from the background (Display over other apps)")
        }
    }

    private fun tick(ok: Boolean) = if (ok) "✓" else "✗"

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
            toast("Settings > Apps > Nova > Display over other apps")
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
