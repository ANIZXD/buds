package com.aniz.nova

import android.Manifest
import android.provider.AlarmClock
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Everything that leaves the app: opening things, alarms, calls, messages, torch. */
object Actions {

    private var torchId: String? = null
    private var torchOn = false

    /** Runs the action; returns an extra line to speak, or null. */
    fun run(ctx: Context, a: Act): String? {
        return try {
            when (a.name) {
                "open_app" -> openApp(ctx, a)
                "open_url" -> { openUrl(ctx, a.args["url"]); null }
                "search" -> { openUrl(ctx, Sites.searchUrl(a.args["site"] ?: "google", a.args["query"].orEmpty())); null }
                "set_alarm" -> setAlarm(ctx, a.args)
                "set_timer" -> setTimer(ctx, a.args)
                "call" -> call(ctx, a.args)
                "send_sms" -> sendSms(ctx, a.args)
                "flashlight" -> torch(ctx, a.args["state"] == "on")
                else -> null
            }
        } catch (t: Throwable) {
            "That didn't work - ${t.javaClass.simpleName}."
        }
    }

    // ---------------------------------------------------------------- open

    private fun openApp(ctx: Context, a: Act): String? {
        var pkg = a.args["pkg"]
        if (pkg.isNullOrBlank()) pkg = Apps.find(ctx, a.args["app"].orEmpty())
        if (pkg.isNullOrBlank()) return "I don't see that app on the phone."
        if (!Apps.launch(ctx, pkg)) return "I couldn't open that one."
        return null
    }

    private fun openUrl(ctx: Context, url: String?) {
        if (url.isNullOrBlank()) return
        val fixed = if (url.startsWith("http")) url else "https://$url"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(fixed))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    // ---------------------------------------------------------------- time

    private fun setAlarm(ctx: Context, args: Map<String, String>): String {
        val hour = args["hour"]?.trim()?.toIntOrNull()
            ?: return "I didn't catch the time."
        val minute = args["minute"]?.trim()?.toIntOrNull() ?: 0
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args["label"]?.takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.startActivity(i)
        return "Alarm set for %02d:%02d.".format(hour, minute)
    }

    private fun setTimer(ctx: Context, args: Map<String, String>): String {
        val secs = args["seconds"]?.trim()?.toIntOrNull() ?: return "How long?"
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args["label"]?.takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.startActivity(i)
        val m = secs / 60
        return if (m >= 1) "Timer set for $m minutes." else "Timer set for $secs seconds."
    }

    // ---------------------------------------------------------------- comms

    private fun call(ctx: Context, args: Map<String, String>): String {
        val who = args["who"].orEmpty().trim()
        val num = args["number"]?.takeIf { it.isNotBlank() } ?: Contacts.number(ctx, who)
        if (num.isNullOrBlank()) {
            val d = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(d)
            return "I don't have a number for $who - I opened the dialler."
        }
        val ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val i = Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return if (ok) "Calling $who." else "Dialling $who - tap call."
    }

    private fun sendSms(ctx: Context, args: Map<String, String>): String {
        val who = args["who"].orEmpty().trim()
        if (who.isEmpty()) return "Who should I message?"
        val body = args["text"].orEmpty()
        val num = args["number"]?.takeIf { it.isNotBlank() } ?: Contacts.number(ctx, who)
            ?: return "I don't have a number for $who."
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return "Message ready for $who - tap send."
    }

    // ---------------------------------------------------------------- torch

    private fun torch(ctx: Context, on: Boolean): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(ctx, "Nova needs camera permission for the flashlight", Toast.LENGTH_LONG).show()
            return "I need camera permission for the flashlight."
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = torchId ?: cm.cameraIdList.firstOrNull { cid ->
            cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) return "This phone has no flashlight."
        torchId = id
        cm.setTorchMode(id, on)
        torchOn = on
        return null
    }

    fun torchIsOn(): Boolean = torchOn
}

/** Index of every launchable app, so "open instagram" needs no network. */
object Apps {

    private var cache: Map<String, String>? = null
    private var cachedAt = 0L

    fun labels(ctx: Context): List<String> = index(ctx).keys.sorted()

    fun index(ctx: Context): Map<String, String> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cachedAt < 5 * 60_000) return it }

        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = if (Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else
            pm.queryIntentActivities(intent, 0)

        val map = LinkedHashMap<String, String>()
        for (ri in list) {
            val label = ri.loadLabel(pm).toString().lowercase().trim()
            val pkg = ri.activityInfo.packageName
            if (label.isNotEmpty() && pkg != "com.aniz.nova") map.putIfAbsent(label, pkg)
        }
        cache = map
        cachedAt = now
        return map
    }

    fun find(ctx: Context, query: String): String? {
        val idx = index(ctx)
        val q = query.lowercase().trim().removePrefix("the ").trim()
        if (q.isEmpty()) return null

        idx[q]?.let { return it }
        val squashed = q.replace(" ", "")
        idx.entries.firstOrNull { it.key.replace(" ", "") == squashed }?.let { return it.value }
        idx.entries.firstOrNull { it.key.startsWith(q) }?.let { return it.value }
        if (q.length >= 3) {
            idx.entries.firstOrNull { it.key.contains(q) || it.value.contains(q) }?.let { return it.value }
        }
        return null
    }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}

/** Name -> phone number, used by "call mum". */
object Contacts {

    fun number(ctx: Context, name: String): String? {
        if (name.isBlank()) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val sel = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        var fallback: String? = null
        ctx.contentResolver
            .query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, sel, arrayOf("%$name%"), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val num = c.getString(1) ?: continue
                    val display = c.getString(0) ?: ""
                    if (display.equals(name, true)) return num
                    if (fallback == null) fallback = num
                }
            }
        return fallback
    }
}
