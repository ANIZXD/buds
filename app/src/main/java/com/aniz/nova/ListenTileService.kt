package com.aniz.nova

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Pull down the shade and tap Nova - the always-available way in, even if a
 *  music player is holding onto the earbud button. */
class ListenTileService : TileService() {

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        if (VoiceService.running) {
            VoiceService.start(this, VoiceService.ACTION_LISTEN)
        } else {
            VoiceService.start(this, VoiceService.ACTION_START)
        }
        render()
    }

    private fun render() {
        val t = qsTile ?: return
        t.state = if (VoiceService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Nova"
        t.updateTile()
    }
}
