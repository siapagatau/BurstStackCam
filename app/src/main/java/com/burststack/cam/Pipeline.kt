package com.burststack.cam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.hardware.camera2.DngCreator
import android.net.Uri
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Runs the native stacking pipeline and writes the result to the gallery. */
object Pipeline {
    class Saved(val uri: Uri, val name: String)

    fun run(
        context: Context,
        burst: CameraController.BurstResult,
        s: AppSettings,
        onProgress: (Float, String) -> Unit
    ): Saved {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return if (burst.session.raw) runRaw(context, burst, s, stamp, onProgress)
        else runYuv(context, burst, s, stamp, onProgress)
    }

    private fun phase(f: Float): String = when {
        f < 0.1f -> "Memilih frame tertajam…"
        f < 0.6f -> "Meratakan frame…"
        else -> "Menggabungkan frame…"
    }

    // ------------------------------------------------------------------ YUV

    private fun runYuv(
        context: Context, burst: CameraController.BurstResult, s: AppSettings,
        stamp: String, onProgress: (Float, String) -> Unit
    ): Saved {
        val ses = burst.session
        val listener = ProgressListener { f -> onProgress(f * 0.65f, phase(f)) }
        val ref = NativeLib.nativeStackYuv(ses.handle, s.align, listener)
        if (ref < 0) throw IllegalStateException("Stacking gagal (kode $ref)")

        val rot = burst.rotation
        val swap = rot == 90 || rot == 270
        val ow = if (swap) ses.height else ses.width
        val oh = if (swap) ses.width else ses.height
        return if (s.format == OutputFormat.JPEG) writeJpeg(context, ses, rot, ow, oh, s, stamp, onProgress)
        else writePng(context, ses, rot, ow, oh, s, stamp, onProgress)
    }

    private fun writePng(
        context: Context, ses: StackSession, rot: Int, ow: Int, oh: Int,
        s: AppSettings, stamp: String, onProgress: (Float, String) -> Unit
    ): Saved {
        val name = "BurstStack_$stamp.png"
        val pending = MediaSaver.begin(context, name, "image/png")
        try {
            val png = PngWriter(pending.stream, ow, oh)
            png.begin()
            val strip = 32
            val buf = ByteArray((1 + ow * 6) * strip)
            var y = 0
            while (y < oh) {
                val rows = min(strip, oh - y)
                val n = NativeLib.nativeRenderPng16(ses.handle, rot, s.toneMap, s.sharpen, s.look, y, rows, buf)
                if (n <= 0) throw IllegalStateException("Render gagal (kode $n)")
                png.write(buf, n)
                y += rows
                onProgress(0.65f + 0.35f * y / oh, "Menyimpan PNG 16-bit…")
            }
            png.finish()
            pending.commit()
            return Saved(pending.uri, name)
        } catch (t: Throwable) {
            pending.abort()
            throw t
        }
    }

    private fun writeJpeg(
        context: Context, ses: StackSession, rot: Int, ow: Int, oh: Int,
        s: AppSettings, stamp: String, onProgress: (Float, String) -> Unit
    ): Saved {
        val name = "BurstStack_$stamp.jpg"
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val pending = MediaSaver.begin(context, name, "image/jpeg")
        try {
            val strip = 64
            val px = IntArray(ow * strip)
            var y = 0
            while (y < oh) {
                val rows = min(strip, oh - y)
                val n = NativeLib.nativeRenderArgb(ses.handle, rot, s.toneMap, s.sharpen, s.look, y, rows, px)
                if (n <= 0) throw IllegalStateException("Render gagal (kode $n)")
                bmp.setPixels(px, 0, ow, 0, y, ow, rows)
                y += rows
                onProgress(0.65f + 0.25f * y / oh, "Render JPEG…")
            }
            onProgress(0.92f, "Mengompres JPEG…")
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, pending.stream)) {
                throw IllegalStateException("Kompresi JPEG gagal")
            }
            pending.commit()
            return Saved(pending.uri, name)
        } catch (t: Throwable) {
            pending.abort()
            throw t
        } finally {
            bmp.recycle()
        }
    }

    // ------------------------------------------------------------------ Auto edit of an existing photo

    private const val MAX_EDIT_PIXELS = 16_000_000L

    /** Fully automatic iPhone-style edit of any photo from the gallery. No sliders: the scene decides everything. */
    fun editPhoto(context: Context, src: Uri, s0: AppSettings, onProgress: (Float, String) -> Unit): Saved {
        val s = s0.copy(look = 0, toneMap = true, sharpen = true,
            format = if (s0.format == OutputFormat.RAW_DNG) OutputFormat.JPEG else s0.format)
        val cr = context.contentResolver
        onProgress(0.02f, "Membaca foto…")
        val orient = cr.openInputStream(src)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val rot = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Foto tidak bisa dibaca")
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_EDIT_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        onProgress(0.05f, "Mendekode foto…")
        val bmp = cr.openInputStream(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Foto tidak bisa didekode")
        val ses = StackSession.create(bmp.width, bmp.height, false, 1)
        try {
            val w = ses.width
            val h = ses.height
            val strip = 64
            val px = IntArray(w * strip)
            var y = 0
            while (y < h) {
                val rows = min(strip, h - y)
                bmp.getPixels(px, 0, w, 0, y, w, rows)
                val n = NativeLib.nativeLoadArgb(ses.handle, px, y, rows)
                if (n <= 0) throw IllegalStateException("Muat foto gagal (kode $n)")
                y += rows
                onProgress(0.05f + 0.25f * y / h, "Memuat foto…")
            }
            bmp.recycle()
            onProgress(0.32f, "Menyeimbangkan cahaya & warna…")
            val a = NativeLib.nativeAnalyze(ses.handle)
            if (a < 0) throw IllegalStateException("Analisis gagal (kode $a)")
            val swap = rot == 90 || rot == 270
            val ow = if (swap) h else w
            val oh = if (swap) w else h
            val stamp = "EDIT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return if (s.format == OutputFormat.JPEG) writeJpeg(context, ses, rot, ow, oh, s, stamp, onProgress)
            else writePng(context, ses, rot, ow, oh, s, stamp, onProgress)
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
            ses.release()
        }
    }

    // ------------------------------------------------------------------ RAW / DNG

    private fun runRaw(
        context: Context, burst: CameraController.BurstResult, s: AppSettings,
        stamp: String, onProgress: (Float, String) -> Unit
    ): Saved {
        val meta = burst.rawMeta ?: throw IllegalStateException("Metadata RAW tidak tersedia")
        val ses = burst.session
        val listener = ProgressListener { f -> onProgress(f * 0.85f, phase(f)) }
        val ref = NativeLib.nativeStackRaw(ses.handle, s.align, meta.blackLevel, meta.whiteLevel, listener)
        if (ref < 0) throw IllegalStateException("Stacking RAW gagal (kode $ref)")
        val data = NativeLib.nativeRawOutput(ses.handle)
            ?: throw IllegalStateException("Hasil RAW kosong")

        onProgress(0.88f, "Menulis DNG…")
        val name = "BurstStack_$stamp.dng"
        val pending = MediaSaver.begin(context, name, "image/x-adobe-dng")
        val dng = DngCreator(meta.characteristics, meta.result)
        try {
            dng.setOrientation(
                when (burst.rotation) {
                    90 -> 6   // ExifInterface.ORIENTATION_ROTATE_90
                    180 -> 3  // ORIENTATION_ROTATE_180
                    270 -> 8  // ORIENTATION_ROTATE_270
                    else -> 1 // ORIENTATION_NORMAL
                }
            )
            dng.writeByteBuffer(pending.stream, Size(ses.width, ses.height), data, 0)
            pending.commit()
            onProgress(1f, "Selesai")
            return Saved(pending.uri, name)
        } catch (t: Throwable) {
            pending.abort()
            throw t
        } finally {
            dng.close()
        }
    }
}
