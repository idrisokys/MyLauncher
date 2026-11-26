package github.idrisokys.myl.ocr

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Hızlı Ayarlar kutucuğu "Metin seç": herhangi bir uygulamanın üstünden tek dokunuşla ekranı okutur. */
class GrabTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, TextGrabActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
