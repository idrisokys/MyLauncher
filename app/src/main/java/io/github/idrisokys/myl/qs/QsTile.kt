package io.github.idrisokys.myl.qs

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.idrisokys.myl.L

/** Hızlı Ayarlar kutucuğu: dokun = alıcıyı aç/kapat. İzin eksikse izin ekranını açar. */
class QsTile : TileService() {

    override fun onStartListening() { render() }

    override fun onClick() {
        super.onClick()
        if (!ReceiverService.ready(this)) { askPermissions(); return }
        if (ReceiverService.running) ReceiverService.stop(this) else ReceiverService.start(this)
        // servis durumu birkaç ms içinde değişir; kutucuğu hemen güncelle
        qsTile?.apply { state = if (ReceiverService.running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; updateTile() }
    }

    private fun render() {
        qsTile?.apply {
            state = if (ReceiverService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = L("Hızlı Paylaş", "QuickShare")
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    private fun askPermissions() {
        val i = Intent(this, QsPermActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(i)
    }
}
