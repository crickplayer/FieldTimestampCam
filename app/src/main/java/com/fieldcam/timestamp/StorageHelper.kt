package com.fieldcam.timestamp

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Picks the MediaStore collection: internal storage, or a removable SD card when enabled. */
object StorageHelper {

    /** Name of a removable (SD card) volume, or null if none is mounted / OS too old. */
    fun sdCardVolume(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaStore.getExternalVolumeNames(context).firstOrNull {
            it != MediaStore.VOLUME_EXTERNAL_PRIMARY && it != MediaStore.VOLUME_EXTERNAL
        }
    }

    fun imagesCollection(context: Context, preferSd: Boolean): Uri {
        val vol = if (preferSd) sdCardVolume(context) else null
        return if (vol != null) MediaStore.Images.Media.getContentUri(vol)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    fun videoCollection(context: Context, preferSd: Boolean): Uri {
        val vol = if (preferSd) sdCardVolume(context) else null
        return if (vol != null) MediaStore.Video.Media.getContentUri(vol)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
}
