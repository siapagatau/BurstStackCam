package com.burststack.cam

import android.os.Bundle
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var orientationListener: OrientationEventListener? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.refreshPermissions()
            vm.camera.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                vm.deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }
        setContent {
            BurstTheme {
                AppRoot(vm = vm, onRequestPermissions = { permissionLauncher.launch(vm.requiredPermissions()) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
        orientationListener?.enable()
        vm.camera.start()
    }

    override fun onPause() {
        orientationListener?.disable()
        vm.camera.stop()
        super.onPause()
    }
}
