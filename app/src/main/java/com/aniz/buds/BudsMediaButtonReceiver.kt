package com.aniz.buds

import android.content.Context
import android.content.Intent
import androidx.media.session.MediaButtonReceiver

/**
 * Claims the earbud's media button so a tap starts Buds instead of handing the
 * key to the assistant (Gemini).
 *
 * While the service is alive the tap is forwarded to its live MediaSession
 * (whose callback turns it into "listen now"). If the service is not running
 * we spin it up and start listening.
 */
class BudsMediaButtonReceiver : MediaButtonReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val session = VoiceService.sessionForButtons
        if (session != null && VoiceService.running) {
            // Delivers the KeyEvent into the session callback → onTap() → listen.
            MediaButtonReceiver.handleIntent(session, intent)
        } else {
            try {
                VoiceService.start(context, VoiceService.ACTION_TOGGLE)
            } catch (_: Throwable) {
                // A background-started foreground service can be refused on
                // recent Androids; the live-session path above is the main one.
            }
        }
    }
}