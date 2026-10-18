package com.burststack.cam

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- iOS palette
private val IosBlue = Color(0xFF0A84FF)
private val IosYellow = Color(0xFFFFD60A)
private val IosGreen = Color(0xFF34C759)
private val IosCell = Color(0xFF1C1C1E)
private val IosFill = Color(0xFF2C2C2E)
private val IosSeparator = Color(0xFF38383A)
private val IosSecondary = Color(0xFF8E8E93)

@Composable
fun BurstTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = IosBlue, background = Color.Black, surface = Color.Black),
        content = content
    )
}

@Composable
fun AppRoot(vm: MainViewModel, onRequestPermissions: () -> Unit) {
    val granted by vm.permissionsGranted.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        if (!granted) PermissionScreen(onRequestPermissions) else CameraScreen(vm)
    }
}

/** iOS-style "liquid glass": translucent tint, soft top-lit sheen and a thin bright edge. */
fun Modifier.glass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color(0x4D000000))
    .background(Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x0DFFFFFF))))
    .border(0.8.dp, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x1AFFFFFF))), shape)

// ---------------------------------------------------------------- Permission

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(IosBlue), contentAlignment = Alignment.Center) {
            CameraGlyph(Modifier.size(44.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Akses Kamera", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(10.dp))
        Text(
            "Izinkan kamera supaya aplikasi bisa memotret. Foto otomatis dirapikan cahaya dan warnanya, " +
                "lalu disimpan ke galeri.",
            color = IosSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp
        )
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(IosBlue).clickable(onClick = onRequest),
            contentAlignment = Alignment.Center
        ) { Text("Lanjutkan", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// ---------------------------------------------------------------- Camera

@Composable
fun CameraScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val zoom by vm.zoom.collectAsState()
    val ev by vm.ev.collectAsState()
    val caps = vm.camera.caps
    val haptic = LocalHapticFeedback.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.autoEdit(uri)
    }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    var evAccum by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var lastToast by remember { mutableStateOf("") }

    LaunchedEffect(ui.message) {
        val m = ui.message
        if (m != null) {
            lastToast = m
            toast = m
            vm.clearMessage()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2600)
            toast = null
        }
    }
    LaunchedEffect(focusTick) {
        if (focusPoint != null) {
            delay(2500)
            focusPoint = null
        }
    }
    LaunchedEffect(flash) {
        if (flash) {
            delay(120)
            flash = false
        }
    }
    val flashAlpha by animateFloatAsState(if (flash) 0.9f else 0f, tween(if (flash) 30 else 160), label = "flash")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (caps == null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Kamera belakang tidak tersedia atau tidak kompatibel dengan Camera2.",
                    color = Color.White, textAlign = TextAlign.Center
                )
            }
        } else {
            val pw = caps.previewSize.width.toFloat()
            val ph = caps.previewSize.height.toFloat()
            val ratio = if (caps.sensorOrientation % 180 == 90) ph / pw else pw / ph

            // Viewfinder
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(22.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                    vm.camera.setPreviewTexture(st)
                                    vm.camera.start()
                                }

                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    vm.camera.stop()
                                    vm.camera.setPreviewTexture(null)
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                            val existing = surfaceTexture
                            if (isAvailable && existing != null) {
                                vm.camera.setPreviewTexture(existing)
                                vm.camera.start()
                            }
                        }
                    },
                    modifier = Modifier.matchParentSize()
                )
                // Gesture layer: tap = focus, pinch = zoom, drag up/down after a tap = exposure.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures { off ->
                                focusPoint = off
                                focusTick++
                                evAccum = 0f
                                vm.tapToFocus(off.x / size.width, off.y / size.height)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, z, _ ->
                                if (z != 1f) {
                                    vm.setZoom(vm.zoom.value * z)
                                } else if (focusPoint != null && caps.aeMax > caps.aeMin) {
                                    evAccum += -pan.y / 10.dp.toPx()
                                    val steps = evAccum.toInt()
                                    if (steps != 0) {
                                        evAccum -= steps
                                        vm.setEv((vm.ev.value + steps).coerceIn(caps.aeMin, caps.aeMax))
                                    }
                                    focusTick++
                                }
                            }
                        }
                )
                val fp = focusPoint
                if (fp != null) FocusBox(fp, ev, caps.aeMin, caps.aeMax)
                if (caps.maxZoom > 1.05f) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
                        ZoomPills(zoom, caps.maxZoom) { vm.setZoom(it) }
                    }
                }
                Box(Modifier.matchParentSize().alpha(flashAlpha).background(Color.Black))
            }

            // Glass control dock
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .height(112.dp)
                    .glass(RoundedCornerShape(40.dp))
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Box(
                        Modifier.size(54.dp).glass(CircleShape).clickable(enabled = !ui.busy) { pickPhoto.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) { PhotoGlyph(Modifier.size(26.dp)) }
                }
                ShutterButton(enabled = !ui.busy) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    flash = true
                    vm.shutter()
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(54.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                        Text("AUTO", color = IosYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Toast (glass pill)
        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp),
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { -it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 2 }
        ) {
            Box(Modifier.glass(RoundedCornerShape(22.dp)).padding(horizontal = 20.dp, vertical = 11.dp)) {
                Text(toast ?: lastToast, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Processing HUD (glass card)
        if (ui.busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0x80000000)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.glass(RoundedCornerShape(26.dp)).padding(horizontal = 34.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (ui.progress < 0f) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
                    } else {
                        CircularProgressIndicator(
                            progress = ui.progress, color = Color.White, trackColor = Color(0x33FFFFFF),
                            strokeWidth = 3.dp, modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(ui.status, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (ui.progress >= 0f) {
                        Spacer(Modifier.height(4.dp))
                        Text("${(ui.progress * 100).roundToInt()}%", color = Color(0xCCFFFFFF), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusBox(p: Offset, ev: Int, evMin: Int, evMax: Int) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val boxPx = with(d) { 72.dp.toPx() }
    val barH = 64.dp
    Box(
        Modifier
            .offset { IntOffset((p.x - boxPx / 2).roundToInt(), (p.y - boxPx / 2).roundToInt()) }
            .size(72.dp)
            .border(1.5.dp, IosYellow)
    )
    // Sun slider to the right of the box
    val frac = if (evMax > evMin) (ev - evMin).toFloat() / (evMax - evMin) else 0.5f
    Box(
        Modifier
            .offset { IntOffset((p.x + boxPx / 2 + with(d) { 14.dp.toPx() }).roundToInt(), (p.y - with(d) { barH.toPx() } / 2).roundToInt()) }
            .size(width = 24.dp, height = barH)
    ) {
        Box(Modifier.align(Alignment.TopCenter).width(1.5.dp).height(barH).background(IosYellow))
        val sunY = (1f - frac) * (barH.value - 22f)
        Canvas(Modifier.align(Alignment.TopCenter).offset(y = sunY.dp).size(22.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.width / 2, center = c)
            drawCircle(IosYellow, radius = size.width * 0.17f, center = c, style = Stroke(1.6.dp.toPx()))
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                val r0 = size.width * 0.28f
                val r1 = size.width * 0.40f
                drawLine(
                    IosYellow,
                    Offset(c.x + (r0 * Math.cos(a)).toFloat(), c.y + (r0 * Math.sin(a)).toFloat()),
                    Offset(c.x + (r1 * Math.cos(a)).toFloat(), c.y + (r1 * Math.sin(a)).toFloat()),
                    strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun ZoomPills(zoom: Float, maxZoom: Float, onSelect: (Float) -> Unit) {
    val presets = listOf(1f, 2f, 3f, 5f).filter { it <= maxZoom + 0.01f }
    val selected = presets.firstOrNull { abs(zoom - it) < 0.05f }
    Row(
        Modifier.glass(CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (p in presets) {
            val sel = p == selected
            val label = if (p == p.toInt().toFloat()) p.toInt().toString() else String.format("%.1f", p)
            Box(
                Modifier.size(if (sel) 38.dp else 32.dp).clip(CircleShape)
                    .background(if (sel) Color(0x99000000) else Color.Transparent)
                    .clickable { onSelect(p) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (sel) "$label×" else label,
                    color = if (sel) IosYellow else Color.White,
                    fontSize = if (sel) 13.sp else 12.sp, fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (selected == null) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Text(String.format("%.1f×", zoom), color = IosYellow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.86f else 1f, tween(90), label = "shutter")
    Box(
        Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .scale(s)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color(0xFF8E8E93))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
    )
}

// ---------------------------------------------------------------- Glyphs (drawn, no icon pack needed)

@Composable
private fun PhotoGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val sw = 2.dp.toPx()
        drawRoundRect(Color.White, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(5.dp.toPx()), style = Stroke(sw))
        drawCircle(Color.White, radius = size.width * 0.075f, center = Offset(size.width * 0.68f, size.height * 0.34f))
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.82f)
            lineTo(size.width * 0.38f, size.height * 0.5f)
            lineTo(size.width * 0.55f, size.height * 0.7f)
            lineTo(size.width * 0.68f, size.height * 0.58f)
            lineTo(size.width * 0.88f, size.height * 0.82f)
        }
        drawPath(path, Color.White, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun CameraGlyph(modifier: Modifier) {
    Canvas(modifier) {
        val sw = 2.6.dp.toPx()
        drawRoundRect(Color.White, topLeft = Offset(sw / 2, size.height * 0.2f), size = Size(size.width - sw, size.height * 0.68f),
            cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(sw))
        drawCircle(Color.White, radius = size.width * 0.19f, center = Offset(size.width / 2, size.height * 0.54f), style = Stroke(sw))
        drawLine(Color.White, Offset(size.width * 0.34f, size.height * 0.15f), Offset(size.width * 0.66f, size.height * 0.15f),
            strokeWidth = sw, cap = StrokeCap.Round)
    }
}
