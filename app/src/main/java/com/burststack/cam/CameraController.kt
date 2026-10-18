package com.burststack.cam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the selected back camera can do (all read from CameraCharacteristics). */
data class CameraCaps(
    val cameraId: String,
    val sensorOrientation: Int,
    val activeArray: Rect,
    val maxZoom: Float,
    val aeMin: Int,
    val aeMax: Int,
    val aeStep: Float,
    val minFocusDistance: Float,
    val afRegions: Int,
    val aeRegions: Int,
    val afContinuous: Boolean,
    val manualSensor: Boolean,
    val aeLockAvailable: Boolean,
    val awbLockAvailable: Boolean,
    val minimalNr: Boolean,
    val yuvSize: Size,
    val previewSize: Size,
    val rawSupported: Boolean,
    val rawSize: Size?,
    val blackLevel: Int,
    val whiteLevel: Int
)

private fun Size.area(): Long = width.toLong() * height.toLong()

fun probeCaps(context: Context): CameraCaps? {
    val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    for (id in mgr.cameraIdList) {
        val ch = try {
            mgr.getCameraCharacteristics(id)
        } catch (e: Exception) {
            continue
        }
        if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
        val capList = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (!capList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
        val yuvAll = map.getOutputSizes(ImageFormat.YUV_420_888) ?: continue
        if (yuvAll.isEmpty()) continue
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: continue
        val aspect = active.width().toFloat() / active.height().toFloat()

        // YUV capture size: largest <= ~8.5 MP (≈ 3264x2448, ukuran file kecil) with the sensor's aspect ratio (bounds memory use).
        val sameAspect = yuvAll.filter { abs(it.width.toFloat() / it.height.toFloat() - aspect) < 0.03f }
        val pool = if (sameAspect.isNotEmpty()) sameAspect else yuvAll.toList()
        val yuv = pool.filter { it.area() <= 8_500_000L }.maxByOrNull { it.area() }
            ?: pool.minByOrNull { it.area() }!!

        // Preview size: same aspect as the capture, <= 1920 px.
        val prevAll = map.getOutputSizes(SurfaceTexture::class.java) ?: continue
        if (prevAll.isEmpty()) continue
        val ya = yuv.width.toFloat() / yuv.height.toFloat()
        val preview = prevAll
            .filter { abs(it.width.toFloat() / it.height.toFloat() - ya) < 0.03f && max(it.width, it.height) <= 1920 }
            .maxByOrNull { it.area() }
            ?: prevAll.filter { max(it.width, it.height) <= 1920 }.maxByOrNull { it.area() }
            ?: prevAll.minByOrNull { it.area() }!!

        val aeRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)

        // RAW support: capability + RAW16 output + a Bayer CFA + even dimensions.
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
        val rs = rawSizes?.maxByOrNull { it.area() }
        val rawOk = capList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
            rs != null && rs.width % 2 == 0 && rs.height % 2 == 0 && cfa != null && cfa in 0..3
        val bl = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        var black = 0
        if (bl != null) {
            var sum = 0
            for (yy in 0..1) for (xx in 0..1) sum += bl.getOffsetForIndex(xx, yy)
            black = sum / 4
        }
        val white = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023

        return CameraCaps(
            cameraId = id,
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            activeArray = active,
            maxZoom = min(ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f, 8f),
            aeMin = aeRange?.lower ?: 0,
            aeMax = aeRange?.upper ?: 0,
            aeStep = aeStep,
            minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            afRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
            aeRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
            afContinuous = afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            manualSensor = capList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            aeLockAvailable = ch.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false,
            awbLockAvailable = ch.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false,
            minimalNr = nrModes.contains(CameraCharacteristics.NOISE_REDUCTION_MODE_MINIMAL),
            yuvSize = yuv,
            previewSize = preview,
            rawSupported = rawOk,
            rawSize = if (rawOk) rs else null,
            blackLevel = black,
            whiteLevel = if (white > black) white else 1023
        )
    }
    return null
}

class RawMeta(
    val characteristics: CameraCharacteristics,
    val result: TotalCaptureResult,
    val blackLevel: Int,
    val whiteLevel: Int
)

/**
 * Camera2 controller: preview, tap-to-focus, zoom, exposure compensation and the locked-exposure
 * burst. All camera state lives on one handler thread; public methods just post to it.
 */
class CameraController(
    private val appContext: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCameraError(message: String)
        fun onBurstProgress(captured: Int, total: Int)
        fun onBurstReady(result: BurstResult?, error: String?)
    }

    class BurstResult(
        val session: StackSession,
        val frames: Int,
        val rotation: Int,
        val rawMeta: RawMeta?
    )

    private class BurstState(
        val total: Int,
        val raw: Boolean,
        val session: StackSession,
        val rotation: Int
    ) {
        val lock = Any()
        var received = 0
        var lost = 0
        var firstResult: TotalCaptureResult? = null
        var finished = false
    }

    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val caps: CameraCaps? = try {
        probeCaps(appContext)
    } catch (e: Exception) {
        null
    }

    private val thread = HandlerThread("CameraThread").also { it.start() }
    private val handler = Handler(thread.looper)
    private val imageThread = HandlerThread("ImageThread").also { it.start() }
    private val imageHandler = Handler(imageThread.looper)

    @Volatile
    private var texture: SurfaceTexture? = null

    // --- state below is touched only on `handler`'s thread (except where @Volatile)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var opening = false
    private var wantRaw = false
    private var readerIsRaw = false
    private var zoom = 1f
    private var evIndex = 0
    private var afRect: MeteringRectangle? = null

    @Volatile
    private var lastResult: TotalCaptureResult? = null

    @Volatile
    private var burst: BurstState? = null

    private val afCancel = Runnable { sendAfCancel() }
    private val burstTimeout = Runnable {
        val b = burst
        if (b != null) {
            synchronized(b.lock) { b.finished = true }
            finishBurst(b)
        }
    }

    // ------------------------------------------------------------------ public API

    fun setPreviewTexture(st: SurfaceTexture?) {
        texture = st
    }

    fun start() {
        handler.post { openIfNeeded() }
    }

    fun stop() {
        handler.post { closeAll() }
    }

    fun shutdown() {
        handler.post {
            closeAll()
            thread.quitSafely()
            imageThread.quitSafely()
        }
    }

    fun setCaptureMode(raw: Boolean) {
        handler.post {
            val r = raw && (caps?.rawSupported == true)
            if (r != wantRaw) {
                wantRaw = r
                if (device != null && burst == null) createSession()
            }
        }
    }

    fun setZoom(z: Float) {
        handler.post {
            val c = caps ?: return@post
            zoom = z.coerceIn(1f, c.maxZoom)
            updatePreview()
        }
    }

    fun setExposureIndex(i: Int) {
        handler.post {
            val c = caps ?: return@post
            evIndex = i.coerceIn(c.aeMin, c.aeMax)
            updatePreview()
        }
    }

    /** nx, ny: normalized tap position inside the (portrait, upright) preview. */
    fun tapToFocus(nx: Float, ny: Float) {
        handler.post { doTapFocus(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f)) }
    }

    fun captureBurst(count: Int, raw: Boolean, rotation: Int) {
        handler.post { doBurst(count, raw, rotation) }
    }

    // ------------------------------------------------------------------ open / close

    @SuppressLint("MissingPermission")
    private fun openIfNeeded() {
        val c = caps
        if (c == null) {
            callbacks.onCameraError("Kamera belakang tidak ditemukan atau tidak kompatibel.")
            return
        }
        if (texture == null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (device != null) {
            if (session == null) createSession()
            return
        }
        if (opening) return
        opening = true
        try {
            manager.openCamera(c.cameraId, stateCallback, handler)
        } catch (e: Exception) {
            opening = false
            callbacks.onCameraError("Gagal membuka kamera: ${e.message}")
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            device = camera
            if (texture == null) {
                closeAll()
                return
            }
            createSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            camera.close()
            if (device === camera) device = null
            closeAll()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            camera.close()
            if (device === camera) device = null
            closeAll()
            callbacks.onCameraError("Kamera error (kode $error)")
        }
    }

    private fun closeSessionOnly() {
        handler.removeCallbacks(afCancel)
        try {
            session?.close()
        } catch (e: Exception) {
            // ignore
        }
        session = null
        try {
            reader?.close()
        } catch (e: Exception) {
            // ignore
        }
        reader = null
        try {
            previewSurface?.release()
        } catch (e: Exception) {
            // ignore
        }
        previewSurface = null
    }

    private fun closeAll() {
        handler.removeCallbacks(burstTimeout)
        val b = burst
        burst = null
        if (b != null) {
            synchronized(b.lock) { b.finished = true }
            b.session.release()
            callbacks.onBurstReady(null, "Pengambilan frame dibatalkan.")
        }
        closeSessionOnly()
        try {
            device?.close()
        } catch (e: Exception) {
            // ignore
        }
        device = null
        opening = false
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val dev = device ?: return
        val st = texture ?: return
        val c = caps ?: return
        closeSessionOnly()
        try {
            st.setDefaultBufferSize(c.previewSize.width, c.previewSize.height)
            val pSurface = Surface(st)
            previewSurface = pSurface
            val raw = wantRaw && c.rawSupported && c.rawSize != null
            val rd: ImageReader = if (raw) {
                val rsz = c.rawSize!!
                ImageReader.newInstance(rsz.width, rsz.height, ImageFormat.RAW_SENSOR, 3)
            } else {
                ImageReader.newInstance(c.yuvSize.width, c.yuvSize.height, ImageFormat.YUV_420_888, 4)
            }
            reader = rd
            readerIsRaw = raw
            rd.setOnImageAvailableListener({ r -> onImages(r) }, imageHandler)
            dev.createCaptureSession(
                listOf(pSurface, rd.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (device == null || reader !== rd) {
                            s.close()
                            return
                        }
                        session = s
                        startPreview()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        callbacks.onCameraError("Konfigurasi sesi kamera gagal.")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            callbacks.onCameraError("Gagal membuat sesi kamera: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ preview

    private fun cropFor(z: Float): Rect {
        val c = caps ?: return Rect()
        val a = c.activeArray
        val zz = z.coerceIn(1f, c.maxZoom)
        val cw = ((a.width() / zz).toInt() and 1.inv()).coerceAtLeast(2)
        val chh = ((a.height() / zz).toInt() and 1.inv()).coerceAtLeast(2)
        val left = a.left + (a.width() - cw) / 2
        val top = a.top + (a.height() - chh) / 2
        return Rect(left, top, left + cw, top + chh)
    }

    private fun applyPreviewParams(b: CaptureRequest.Builder) {
        val c = caps ?: return
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (c.afContinuous) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (c.aeMax > c.aeMin) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(zoom))
        val r = afRect
        if (r != null) {
            if (c.afRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(r))
            if (c.aeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(r))
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
        ) {
            lastResult = result
        }
    }

    private fun startPreview() = updatePreview()

    private fun updatePreview() {
        val dev = device ?: return
        val sess = session ?: return
        val surf = previewSurface ?: return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            applyPreviewParams(b)
            sess.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: CameraAccessException) {
            callbacks.onCameraError("Preview gagal: ${e.message}")
        } catch (e: IllegalStateException) {
            // session closed while updating; ignore
        }
    }

    private fun doTapFocus(nx: Float, ny: Float) {
        val c = caps ?: return
        val dev = device ?: return
        val sess = session ?: return
        val surf = previewSurface ?: return
        // View (upright portrait) -> normalized sensor coordinates.
        val (sx, sy) = when (c.sensorOrientation) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }
        val a = c.activeArray
        val crop = cropFor(zoom)
        val px = crop.left + sx * crop.width()
        val py = crop.top + sy * crop.height()
        val half = max(8, (min(a.width(), a.height()) * 0.06f).toInt())
        val left = (px - half).toInt().coerceIn(a.left, max(a.left, a.right - 2 * half - 1))
        val top = (py - half).toInt().coerceIn(a.top, max(a.top, a.bottom - 2 * half - 1))
        afRect = MeteringRectangle(left, top, 2 * half, 2 * half, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        updatePreview()
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            applyPreviewParams(b)
            if (c.minFocusDistance > 0f) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            sess.capture(b.build(), null, handler)
            handler.removeCallbacks(afCancel)
            handler.postDelayed(afCancel, 4000)
        } catch (e: Exception) {
            // focus trigger is best-effort
        }
    }

    private fun sendAfCancel() {
        val c = caps ?: return
        val dev = device ?: return
        val sess = session ?: return
        val surf = previewSurface ?: return
        if (burst != null || c.minFocusDistance <= 0f) return
        try {
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            applyPreviewParams(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            sess.capture(b.build(), null, handler)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ------------------------------------------------------------------ burst

    /** Locks exposure/ISO (manual if supported, else AE/AWB lock) and focus so frames match. */
    private fun lockForBurst(b: CaptureRequest.Builder) {
        val c = caps ?: return
        val last = lastResult
        val exp = last?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = last?.get(CaptureResult.SENSOR_SENSITIVITY)
        if (c.manualSensor && exp != null && iso != null) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            if (c.aeLockAvailable) b.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (c.awbLockAvailable) b.set(CaptureRequest.CONTROL_AWB_LOCK, true)

        if (c.minFocusDistance > 0f) {
            val fd = last?.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (fd != null) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd.coerceIn(0f, c.minFocusDistance))
            } else {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            }
        }
        if (c.minimalNr) {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
        }
        b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(zoom))
    }

    private fun doBurst(count: Int, raw: Boolean, rotation: Int) {
        val c = caps
        val dev = device
        val sess = session
        val rd = reader
        if (c == null || dev == null || sess == null || rd == null || burst != null) {
            callbacks.onBurstReady(null, "Kamera belum siap.")
            return
        }
        if (raw != readerIsRaw) {
            wantRaw = raw && c.rawSupported
            createSession()
            callbacks.onBurstReady(null, "Mode kamera sedang disiapkan, coba lagi sebentar.")
            return
        }
        val w: Int
        val h: Int
        if (raw) {
            val rsz = c.rawSize ?: run {
                callbacks.onBurstReady(null, "RAW tidak didukung.")
                return
            }
            w = rsz.width
            h = rsz.height
        } else {
            w = c.yuvSize.width
            h = c.yuvSize.height
        }
        val stack = try {
            StackSession.create(w, h, raw, count)
        } catch (t: Throwable) {
            callbacks.onBurstReady(null, "Tidak bisa menyiapkan memori: ${t.message}")
            return
        }
        val b = BurstState(count, raw, stack, rotation)
        burst = b
        try {
            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(rd.surface)
            lockForBurst(builder)
            val requests = List(count) { builder.build() }
            sess.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    synchronized(b.lock) {
                        if (b.firstResult == null) b.firstResult = result
                    }
                    checkDone(b)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure
                ) {
                    if (!failure.wasImageCaptured()) {
                        synchronized(b.lock) { b.lost++ }
                        checkDone(b)
                    }
                }
            }, handler)
            handler.postDelayed(burstTimeout, 25_000)
        } catch (e: Exception) {
            burst = null
            stack.release()
            callbacks.onBurstReady(null, "Burst gagal: ${e.message}")
        }
    }

    // Runs on the image thread: copy each image into native memory, then close it immediately.
    private fun onImages(r: ImageReader) {
        while (true) {
            val img: Image = try {
                r.acquireNextImage()
            } catch (e: Exception) {
                null
            } ?: break
            try {
                val b = burst
                if (b != null && r === reader) {
                    val ok = try {
                        if (b.raw) addRaw(b, img) else addYuv(b, img)
                    } catch (e: Exception) {
                        false
                    }
                    var got = 0
                    synchronized(b.lock) {
                        if (b.finished) return@synchronized
                        if (ok) b.received++ else b.lost++
                        got = b.received
                    }
                    callbacks.onBurstProgress(got, b.total)
                    checkDone(b)
                }
            } finally {
                img.close()
            }
        }
    }

    private fun addYuv(b: BurstState, img: Image): Boolean {
        val p = img.planes
        if (p.size < 3) return false
        return NativeLib.nativeAddYuv(
            b.session.handle,
            p[0].buffer, p[0].rowStride,
            p[1].buffer, p[1].rowStride,
            p[2].buffer, p[2].rowStride,
            p[1].pixelStride
        )
    }

    private fun addRaw(b: BurstState, img: Image): Boolean {
        val p = img.planes
        if (p.isEmpty()) return false
        return NativeLib.nativeAddRaw(b.session.handle, p[0].buffer, p[0].rowStride)
    }

    private fun checkDone(b: BurstState) {
        var done = false
        synchronized(b.lock) {
            if (b.finished) return
            val allIn = b.received + b.lost >= b.total
            val metaOk = !b.raw || b.firstResult != null || b.received == 0
            if (allIn && metaOk) {
                b.finished = true
                done = true
            }
        }
        if (done) handler.post { finishBurst(b) }
    }

    private fun finishBurst(b: BurstState) {
        if (burst !== b) return
        burst = null
        handler.removeCallbacks(burstTimeout)
        val got = synchronized(b.lock) { b.received }
        if (got < 2) {
            b.session.release()
            callbacks.onBurstReady(null, "Hanya $got frame yang berhasil diambil. Coba lagi.")
            return
        }
        var meta: RawMeta? = null
        if (b.raw) {
            val res = synchronized(b.lock) { b.firstResult }
            val c = caps
            if (res == null || c == null) {
                b.session.release()
                callbacks.onBurstReady(null, "Metadata RAW tidak diterima dari kamera.")
                return
            }
            meta = RawMeta(manager.getCameraCharacteristics(c.cameraId), res, c.blackLevel, c.whiteLevel)
        }
        callbacks.onBurstReady(BurstResult(b.session, got, b.rotation, meta), null)
    }
}
