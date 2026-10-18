package com.burststack.cam

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streaming 16-bit RGB PNG encoder. Scanlines (filter byte + data) are pushed row-block by
 * row-block, so the full image never has to exist in the Java heap.
 */
class PngWriter(target: OutputStream, private val width: Int, private val height: Int) {
    private val out = BufferedOutputStream(target, 1 shl 17)
    private val deflater = Deflater(3)
    private val crc = CRC32()
    private val buf = ByteArray(1 shl 16)

    fun begin() {
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, width)
        putInt(ihdr, 4, height)
        ihdr[8] = 16  // bit depth
        ihdr[9] = 2   // color type: RGB
        ihdr[10] = 0
        ihdr[11] = 0
        ihdr[12] = 0
        writeChunk("IHDR", ihdr, ihdr.size)
        writeChunk("sRGB", byteArrayOf(0), 1)
    }

    fun write(data: ByteArray, len: Int) {
        deflater.setInput(data, 0, len)
        while (!deflater.needsInput()) {
            val n = deflater.deflate(buf)
            if (n > 0) writeChunk("IDAT", buf, n)
        }
    }

    fun finish() {
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) writeChunk("IDAT", buf, n)
        }
        deflater.end()
        writeChunk("IEND", ByteArray(0), 0)
        out.flush()
    }

    private fun writeChunk(type: String, data: ByteArray, len: Int) {
        val head = ByteArray(4)
        putInt(head, 0, len)
        out.write(head)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        if (len > 0) out.write(data, 0, len)
        crc.reset()
        crc.update(t)
        if (len > 0) crc.update(data, 0, len)
        putInt(head, 0, crc.value.toInt())
        out.write(head)
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
