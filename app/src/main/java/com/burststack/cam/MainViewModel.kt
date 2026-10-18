package com.burststack.cam

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val busy: Boolean = false,
    /** 0..1, or negative for indeterminate. */
    val progress: Float = -1f,
    val status: String = "",
    val message: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app), CameraController.Callbacks {

    private val repo = SettingsRepository(app)
    val camera = CameraController(app, this)
    val rawSupported: Boolean = camera.caps?.rawSupported == true

    private val _settings = MutableStateFlow(repo.load().sanitized(rawSupported))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    private val _ev = MutableStateFlow(0)
    val ev: StateFlow<Int> = _ev.asStateFlow()

    private val _permissions = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissions.asStateFlow()

    /** Device orientation in degrees (0/90/180/270), fed by the activity's OrientationEventListener. */
    @Volatile
    var deviceOrientation: Int = 0

    @Volatile
    private var activeSettings: AppSettings = _settings.value

    init {
        camera.setCaptureMode(_settings.value.format == OutputFormat.RAW_DNG)
        refreshPermissions()
    }

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT <= 28) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _permissions.value = requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val n = transform(_settings.value).sanitized(rawSupported)
        _settings.value = n
        repo.save(n)
        camera.setCaptureMode(n.format == OutputFormat.RAW_DNG)
    }

    fun setZoom(z: Float) {
        val max = camera.caps?.maxZoom ?: 1f
        val v = z.coerceIn(1f, max)
        _zoom.value = v
        camera.setZoom(v)
    }

    fun setEv(index: Int) {
        _ev.value = index
        camera.setExposureIndex(index)
    }

    fun tapToFocus(nx: Float, ny: Float) = camera.tapToFocus(nx, ny)

    fun clearMessage() {
        _ui.update { it.copy(message = null) }
    }

    fun shutter() {
        if (_ui.value.busy) return
        val c = camera.caps ?: return
        val s = _settings.value
        val raw = s.format == OutputFormat.RAW_DNG && c.rawSupported
        val rotation = (c.sensorOrientation + deviceOrientation) % 360
        activeSettings = s
        _ui.value = UiState(busy = true, progress = -1f, status = "Mengambil foto…")
        camera.captureBurst(s.frameCount, raw, rotation)
    }

    // ---------------------------------------------------------------- CameraController.Callbacks

    override fun onCameraError(message: String) {
        _ui.update { it.copy(message = message) }
    }

    override fun onBurstProgress(captured: Int, total: Int) {
        _ui.update { it.copy(status = "Mengambil foto…") }
    }

    override fun onBurstReady(result: CameraController.BurstResult?, error: String?) {
        if (result == null) {
            _ui.value = UiState(busy = false, message = error ?: "Pengambilan gagal.")
            return
        }
        val s = activeSettings
        val appCtx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                _ui.value = UiState(busy = true, progress = 0f, status = "Memproses…")
                val saved = Pipeline.run(appCtx, result, s) { p, text ->
                    _ui.value = UiState(busy = true, progress = p.coerceIn(0f, 1f), status = text)
                }
                _ui.value = UiState(busy = false, message = "Foto tersimpan")
            } catch (t: Throwable) {
                _ui.value = UiState(busy = false, message = "Gagal memproses: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                result.session.release()
            }
        }
    }

    /** One-tap, fully automatic edit of a photo picked from the gallery. */
    fun autoEdit(uri: android.net.Uri) {
        if (_ui.value.busy) return
        val s = _settings.value
        val appCtx = getApplication<Application>()
        _ui.value = UiState(busy = true, progress = 0f, status = "Memperbaiki foto…")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val saved = Pipeline.editPhoto(appCtx, uri, s) { p, text ->
                    _ui.value = UiState(busy = true, progress = p.coerceIn(0f, 1f), status = text)
                }
                _ui.value = UiState(busy = false, message = "Foto tersimpan")
            } catch (t: OutOfMemoryError) {
                _ui.value = UiState(busy = false, message = "Memori tidak cukup untuk foto ini.")
            } catch (t: Throwable) {
                _ui.value = UiState(busy = false, message = "Gagal mengedit: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun onCleared() {
        camera.shutdown()
        super.onCleared()
    }
}
