package com.burststack.cam

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

class PendingMedia(
    private val resolver: ContentResolver,
    val uri: Uri,
    val stream: OutputStream
) {
    fun commit() {
        stream.flush()
        stream.close()
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, v, null, null)
        }
    }

    fun abort() {
        try {
            stream.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            // ignore
        }
    }
}

object MediaSaver {
    private const val FOLDER = "BurstStackCam"

    /** Creates a MediaStore entry in Pictures/BurstStackCam and opens it for writing. */
    @Suppress("DEPRECATION")
    fun begin(context: Context, displayName: String, mime: String): PendingMedia {
        val resolver = context.contentResolver
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$FOLDER")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            if (!dir.exists()) dir.mkdirs()
            values.put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore menolak membuat berkas")
        val stream = try {
            resolver.openOutputStream(uri) ?: throw IOException("Tidak bisa membuka berkas keluaran")
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (e2: Exception) {
                // ignore
            }
            throw e
        }
        return PendingMedia(resolver, uri, stream)
    }
}
