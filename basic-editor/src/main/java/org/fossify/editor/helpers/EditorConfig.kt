package org.fossify.editor.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig

/**
 * Persists the editor's last-used settings. Extends commons' [BaseConfig],
 * so it shares the same SharedPreferences file as the host app's commons config.
 */
class EditorConfig(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = EditorConfig(context)
    }

    var ultraHdrRendering: Boolean
        get() = prefs.getBoolean(ULTRA_HDR_RENDERING, true)
        set(value) = prefs.edit().putBoolean(ULTRA_HDR_RENDERING, value).apply()

    var lastEditorCropAspectRatio: Int
        get() = prefs.getInt(LAST_EDITOR_CROP_ASPECT_RATIO, ASPECT_RATIO_FREE)
        set(value) = prefs.edit().putInt(LAST_EDITOR_CROP_ASPECT_RATIO, value).apply()

    var lastEditorCropOtherAspectRatioX: Float
        get() = prefs.getFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X, 2f)
        set(value) = prefs.edit().putFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X, value).apply()

    var lastEditorCropOtherAspectRatioY: Float
        get() = prefs.getFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y, 1f)
        set(value) = prefs.edit().putFloat(LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y, value).apply()

    var lastEditorDrawColor: Int
        get() = prefs.getInt(LAST_EDITOR_DRAW_COLOR, primaryColor)
        set(value) = prefs.edit().putInt(LAST_EDITOR_DRAW_COLOR, value).apply()

    var lastEditorBrushSize: Int
        get() = prefs.getInt(LAST_EDITOR_BRUSH_SIZE, 50)
        set(value) = prefs.edit().putInt(LAST_EDITOR_BRUSH_SIZE, value).apply()
}
