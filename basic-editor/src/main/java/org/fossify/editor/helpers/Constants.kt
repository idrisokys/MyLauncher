package org.fossify.editor.helpers

import org.fossify.commons.helpers.PERMISSION_READ_MEDIA_IMAGES
import org.fossify.commons.helpers.PERMISSION_WRITE_STORAGE
import org.fossify.commons.helpers.isTiramisuPlus

// shared preferences keys
const val ULTRA_HDR_RENDERING = "ultra_hdr_rendering"
const val LAST_EDITOR_CROP_ASPECT_RATIO = "last_editor_crop_aspect_ratio"
const val LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X = "last_editor_crop_other_aspect_ratio_x_2"
const val LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y = "last_editor_crop_other_aspect_ratio_y_2"
const val LAST_EDITOR_DRAW_COLOR = "last_editor_draw_color"
const val LAST_EDITOR_BRUSH_SIZE = "last_editor_brush_size"

// crop aspect ratios
const val ASPECT_RATIO_FREE = 0
const val ASPECT_RATIO_ONE_ONE = 1
const val ASPECT_RATIO_FOUR_THREE = 2
const val ASPECT_RATIO_SIXTEEN_NINE = 3
const val ASPECT_RATIO_OTHER = 4

// cache sub-folder used when sharing the edited bitmap (must match your FileProvider paths)
const val TEMP_FOLDER_NAME = "images"

// intent extras understood by EditActivity
const val EXTRA_CROP = "crop"
const val EXTRA_ASPECT_X = "aspectX"
const val EXTRA_ASPECT_Y = "aspectY"

fun getPermissionToRequest() = if (isTiramisuPlus()) PERMISSION_READ_MEDIA_IMAGES else PERMISSION_WRITE_STORAGE
