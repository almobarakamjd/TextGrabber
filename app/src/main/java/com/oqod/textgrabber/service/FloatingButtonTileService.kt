package com.oqod.textgrabber.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.oqod.textgrabber.R

/**
 * بلاطة في الإعدادات السريعة (Quick Settings) لتشغيل/إيقاف الزر العائم
 * الخاص بـ TextGrabber مباشرة من أعلى الشاشة، دون الحاجة لفتح التطبيق.
 *
 * المستخدم يضيف البلاطة يدويا مرة واحدة عبر "تعديل" (Edit) في لوحة
 * الإعدادات السريعة، تماما كأي بلاطة أخرى في أندرويد.
 */
class FloatingButtonTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val newState = !MyAccessibilityService.isFloatingButtonEnabled(this)
        MyAccessibilityService.setFloatingButtonEnabled(this, newState)
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = MyAccessibilityService.isFloatingButtonEnabled(this)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        tile.updateTile()
    }
}
