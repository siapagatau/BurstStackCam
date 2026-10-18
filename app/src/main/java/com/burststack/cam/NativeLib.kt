package com.burststack.cam

import java.nio.ByteBuffer

/** Receives progress (0..1) from the native stacking code. Called on the calling thread. */
fun interface ProgressListener {
    fun onProgress(fraction: Float)
}

/** JNI bridge to libburststack.so (alignment, stacking, tone mapping, rendering). */
object NativeLib {
    init {
        System.loadLibrary("burststack")
    }

    external fun nativeCreate(width: Int, height: Int, raw: Boolean, capacity: Int): Long
    external fun nativeRelease(handle: Long)
    external fun nativeFrameCount(handle: Long): Int

    external fun nativeAddYuv(
        handle: Long,
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, uRowStride: Int,
        v: ByteBuffer, vRowStride: Int,
        uvPixelStride: Int
    ): Boolean

    /** Loads ARGB rows [y0, y0+rows) (both even) of an existing photo into the context (for editing). */
    external fun nativeLoadArgb(handle: Long, pixels: IntArray, y0: Int, rows: Int): Int

    /** Analyzes a loaded photo (tone, scene, semantic regions). Call after all rows are loaded. */
    external fun nativeAnalyze(handle: Long): Int

    external fun nativeAddRaw(handle: Long, buffer: ByteBuffer, rowStrideBytes: Int): Boolean

    /** Returns index of the reference frame, or a negative error code. */
    external fun nativeStackYuv(handle: Long, align: Boolean, listener: ProgressListener): Int

    external fun nativeStackRaw(
        handle: Long, align: Boolean, blackLevel: Int, whiteLevel: Int, listener: ProgressListener
    ): Int

    /** Stacked RAW16 (little-endian, tightly packed) owned by the native context. */
    external fun nativeRawOutput(handle: Long): ByteBuffer?

    /** Renders rows [oy0, oy0+rows) as PNG scanlines (filter byte + 16-bit RGB big-endian). */
    external fun nativeRenderPng16(
        handle: Long, rotation: Int, tone: Boolean, sharpen: Boolean, look: Int,
        oy0: Int, rows: Int, out: ByteArray
    ): Int

    /** Renders rows [oy0, oy0+rows) as ARGB_8888 ints. */
    external fun nativeRenderArgb(
        handle: Long, rotation: Int, tone: Boolean, sharpen: Boolean, look: Int,
        oy0: Int, rows: Int, out: IntArray
    ): Int
}

/** Owns one native burst context. */
class StackSession(val handle: Long, val width: Int, val height: Int, val raw: Boolean) {
    @Volatile
    private var released = false

    @Synchronized
    fun release() {
        if (!released) {
            released = true
            NativeLib.nativeRelease(handle)
        }
    }

    companion object {
        fun create(width: Int, height: Int, raw: Boolean, capacity: Int): StackSession {
            val ew = width and 1.inv()
            val eh = height and 1.inv()
            val h = NativeLib.nativeCreate(ew, eh, raw, capacity)
            if (h == 0L) throw OutOfMemoryError("Gagal membuat konteks native")
            return StackSession(h, ew, eh, raw)
        }
    }
}
