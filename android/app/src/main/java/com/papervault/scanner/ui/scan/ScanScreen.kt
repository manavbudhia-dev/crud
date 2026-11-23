package com.papervault.scanner.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.hypot

import kotlin.math.min

/** Full scan flow: camera (live page tracking) → adjust edges / filter → more pages → PDF. */
@Composable
fun ScanScreen(onClose: () -> Unit, onCreated: (Long) -> Unit, vm: ScanViewModel = viewModel(factory = ScanViewModel.Factory)) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.doneFlow.collect(onCreated) }
    LaunchedEffect(Unit) { for (m in vm.messages) snackbar.showSnackbar(m) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val idx = editing
        AnimatedContent(
            targetState = idx != null && idx in pages.indices,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "scanStep",
        ) { adjusting ->
            if (adjusting && idx != null && idx in pages.indices) {
                BackHandler { vm.backToCamera() }
                AdjustView(vm, idx, pages[idx], pages.size)
            } else {
                BackHandler(onBack = onClose)
                CameraView(vm, pages.size, onClose)
            }
        }
        if (busy) Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }
}

// ------------------------------------------------------------------ camera

@Composable
private fun CameraView(vm: ScanViewModel, pageCount: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { vm.onImported(it) }
    val openGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var flash by remember { mutableStateOf(false) }
    var autoCapture by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    // Latest detection (upright, normalised) and the analysis frame aspect (w/h, upright).
    var detected by remember { mutableStateOf<Quad?>(null) }
    var frameAspect by remember { mutableFloatStateOf(3f / 4f) }
    var stableSince by remember { mutableLongStateOf(0L) }
    val flashAlpha = remember { Animatable(0f) }

    val ratio = remember { ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build() }
    val imageCapture = remember { ImageCapture.Builder().setResolutionSelector(ratio).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    LaunchedEffect(flash) { imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }

    val capture: () -> Unit = capture@{
        if (capturing || !granted) return@capture
        capturing = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { flashAlpha.snapTo(0.85f); flashAlpha.animateTo(0f, tween(350)) }
        val quadAtShutter = detected
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val bmp = image.toBitmap()
                image.close()
                val upright = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                capturing = false
                vm.onCaptured(upright, quadAtShutter, openEditor = !autoCapture)
            }
            override fun onError(exception: ImageCaptureException) { capturing = false }
        })
    }
    val currentCapture by rememberUpdatedState(capture)

    // Auto-capture: shoot when the page outline has been steady for 1.2 s.
    LaunchedEffect(autoCapture, detected) {
        if (autoCapture && detected != null && stableSince > 0 && System.currentTimeMillis() - stableSince > 1200 && !capturing) {
            stableSince = 0
            currentCapture()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (granted) {
                val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
                AndroidView({ previewView }, Modifier.fillMaxSize())
                DisposableEffect(lifecycle) {
                    analysis.setAnalyzer(analysisExecutor) { image ->
                        val rot = image.imageInfo.rotationDegrees
                        val aspect = if (rot % 180 == 0) image.width.toFloat() / image.height else image.height.toFloat() / image.width
                        val q = runCatching { DocDetectorAndroid.detect(image) }.getOrNull()
                        image.close()
                        ContextCompat.getMainExecutor(context).execute {
                            frameAspect = aspect
                            val prev = detected
                            val now = System.currentTimeMillis()
                            stableSince = when {
                                q == null -> 0
                                prev != null && DocDetector.distance(prev, q) < 0.02f -> if (stableSince == 0L) now else stableSince
                                else -> now
                            }
                            detected = q
                        }
                    }
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().setResolutionSelector(ratio).build().also { it.surfaceProvider = previewView.surfaceProvider }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                        }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose {
                        analysis.clearAnalyzer()
                        runCatching { future.get().unbindAll() }
                    }
                }
                DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }
            } else {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access is needed to scan", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = openGallery) { Text("Import from gallery", color = Color.White) }
                }
            }

            LiveQuadOverlay(detected, frameAspect, Modifier.fillMaxSize())

            // White flash on capture
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flashAlpha.value }.background(Color.White))

            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                val autoBg by animateColorAsState(if (autoCapture) Brand.Blue else Color(0x66000000), label = "autoBg")
                Text(
                    "AUTO", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(autoBg)
                        .clickable { autoCapture = !autoCapture; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                IconButton(onClick = { flash = !flash }) {
                    Crossfade(flash, label = "flash") { on ->
                        Icon(if (on) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", tint = if (on) Brand.Star else Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                IconButton(onClick = openGallery) { Icon(Icons.Outlined.Image, "Import from gallery", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }

            val hint = when {
                detected == null -> "Adjust edges for better scan"
                autoCapture -> "Hold steady — capturing automatically"
                else -> "Document detected"
            }
            AnimatedContent(
                hint, label = "hint",
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            ) { text ->
                Text(
                    text, color = Color.White, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (detected != null) Brand.Blue.copy(alpha = 0.85f) else Color(0x99000000))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Bottom controls
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF111111)).navigationBarsPadding().padding(horizontal = 36.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val badgeScale = remember { Animatable(1f) }
            LaunchedEffect(pageCount) { if (pageCount > 0) { badgeScale.snapTo(1.5f); badgeScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) } }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).clickable {
                if (pageCount > 0) vm.editing.value = pageCount - 1 else openGallery()
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PhotoLibrary, "Pages", tint = Color.White)
                if (pageCount > 0) Text(
                    "$pageCount", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).scale(badgeScale.value).clip(CircleShape).background(Brand.Blue).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Shutter(enabled = granted && !capturing, onClick = capture)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::retakeLast, enabled = pageCount > 0, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.Refresh, "Retake last page", tint = if (pageCount > 0) Color.White else Color.Gray, modifier = Modifier.size(32.dp))
            }
        }
        if (autoCapture && pageCount > 0) {
            Button(
                onClick = { vm.editing.value = 0 }, colors = ButtonDefaults.buttonColors(containerColor = Brand.Blue),
                modifier = Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(horizontal = 24.dp, vertical = 8.dp),
            ) { Text("Review $pageCount page${if (pageCount == 1) "" else "s"}") }
        }
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.86f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "shutter")
    val fill by animateColorAsState(if (enabled) Brand.Blue else Brand.BlueDark, label = "shutterFill")
    Box(
        Modifier.size(78.dp).scale(scale).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
            .background(fill).clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
    )
}

/**
 * Draws the tracked page outline. Detection arrives at camera frame rate (often jittery,
 * 15–30 fps); corners are eased towards it every display frame so the lines glide smoothly.
 */
@Composable
private fun LiveQuadOverlay(target: Quad?, frameAspect: Float, modifier: Modifier) {
    var shown by remember { mutableStateOf<Quad?>(null) }
    val alpha = remember { Animatable(0f) }
    val latestTarget by rememberUpdatedState(target)

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0f, 0.1f)
                last = t
                val tgt = latestTarget
                if (tgt != null) {
                    val cur = shown
                    // Critically-damped-style easing: ~90% of the way in ~120 ms.
                    val k = 1f - exp(-dt * 18f)
                    shown = if (cur == null) tgt else Quad(
                        lerp(cur.tl, tgt.tl, k), lerp(cur.tr, tgt.tr, k), lerp(cur.br, tgt.br, k), lerp(cur.bl, tgt.bl, k),
                    )
                }
            }
        }
    }
    LaunchedEffect(target != null) { alpha.animateTo(if (target != null) 1f else 0f, tween(if (target != null) 180 else 320)) }

    Canvas(modifier) {
        val a = alpha.value
        // Static corner brackets fade out as the live outline fades in.
        if (a < 1f) drawBrackets(1f - a)
        val q = shown ?: return@Canvas
        if (a <= 0f) return@Canvas
        // Map upright frame coords → PreviewView FILL_CENTER coords.
        val vw = size.width; val vh = size.height
        val iw: Float; val ih: Float
        if (vw / vh > frameAspect) { iw = vw; ih = vw / frameAspect } else { ih = vh; iw = vh * frameAspect }
        val ox = (vw - iw) / 2; val oy = (vh - ih) / 2
        val pts = q.points.map { (x, y) -> Offset(ox + x * iw, oy + y * ih) }
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
        drawPath(path, Brand.Blue.copy(alpha = 0.18f * a))
        drawPath(path, Brand.Blue.copy(alpha = a), style = Stroke(3.5.dp.toPx(), join = StrokeJoin.Round))
        pts.forEach {
            drawCircle(Color.White.copy(alpha = a), 7.dp.toPx(), it)
            drawCircle(Brand.Blue.copy(alpha = a), 4.5.dp.toPx(), it)
        }
    }
}

private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float) = (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)

private fun DrawScope.drawBrackets(alpha: Float) {
    val padX = 36.dp.toPx(); val padY = 110.dp.toPx()
    val l = padX; val t = padY; val r = size.width - padX; val b = size.height - padY
    val len = min(r - l, b - t) * 0.12f
    val sw = 5.dp.toPx()
    val c = Brand.Blue.copy(alpha = alpha)
    fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
        drawPath(Path().apply { moveTo(x, y + dy * len); lineTo(x, y); lineTo(x + dx * len, y) }, c, style = Stroke(sw, cap = StrokeCap.Round))
    }
    bracket(l, t, 1f, 1f); bracket(r, t, -1f, 1f); bracket(r, b, -1f, -1f); bracket(l, b, 1f, -1f)
}

// ------------------------------------------------------------------ adjust edges

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdjustView(vm: ScanViewModel, index: Int, page: ScanPage, total: Int) {
    val haptics = LocalHapticFeedback.current
    val original by produceState<Bitmap?>(null, page.raw) { value = withContext(Dispatchers.IO) { ScanProcessing.decode(page.raw, 1600) } }
    val preview by produceState<Bitmap?>(null, original, page.filter) {
        val o = original
        value = if (o == null) null else withContext(Dispatchers.Default) { ScanProcessing.filter(o, page.filter) }
    }

    // Corners live locally while dragging (no ViewModel round-trip per frame); committed on release.
    var quad by remember(index, page.raw) { mutableStateOf(page.quad) }
    LaunchedEffect(page.quad) { quad = page.quad }
    var dragging by remember { mutableIntStateOf(-1) }
    var touch by remember { mutableStateOf(Offset.Unspecified) }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::backToCamera) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to camera", tint = Color.White) }
            Text("Page ${index + 1} of $total", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.update(index) { it.copy(rotation = (it.rotation + 90) % 360) } }) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate", tint = Color.White)
            }
            IconButton(onClick = { vm.delete(index) }) { Icon(Icons.Default.Delete, "Delete page", tint = Color.White) }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val bmp = preview ?: original
            if (bmp == null) { CircularProgressIndicator(color = Color.White); return@BoxWithConstraints }
            val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / bmp.width, boxH / bmp.height)
            val imgW = bmp.width * scale; val imgH = bmp.height * scale
            val left = (boxW - imgW) / 2; val top = (boxH - imgH) / 2
            val geom by rememberUpdatedState(floatArrayOf(left, top, imgW, imgH))
            val currentQuad by rememberUpdatedState(quad)

            Box(
                Modifier.fillMaxSize().magnifier(
                    sourceCenter = { touch },
                    magnifierCenter = { if (touch.isSpecified()) touch - Offset(0f, 260f) else Offset.Unspecified },
                    zoom = 2.2f,
                ),
            ) {
                Crossfade(bmp, animationSpec = tween(160), label = "filterPreview") { b ->
                    Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Canvas(
                    Modifier.fillMaxSize()
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .pointerInput(index) {
                        // Stable key: the detector is never restarted mid-drag.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val (l, t, w, h) = geom.let { listOf(it[0], it[1], it[2], it[3]) }
                            val near = currentQuad.points.withIndex().minByOrNull { (_, p) -> hypot(l + p.first * w - down.position.x, t + p.second * h - down.position.y) }
                            val hit = near?.takeIf { (_, p) -> hypot(l + p.first * w - down.position.x, t + p.second * h - down.position.y) < 48.dp.toPx() }?.index ?: return@awaitEachGesture
                            down.consume()
                            dragging = hit
                            touch = down.position
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // Keep the finger offset so the corner doesn't jump under the finger.
                            val p0 = currentQuad.points[hit]
                            val grab = Offset(l + p0.first * w - down.position.x, t + p0.second * h - down.position.y)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChange() != Offset.Zero) {
                                    val pos = change.position + grab
                                    quad = currentQuad.with(hit, ((pos.x - l) / w).coerceIn(0f, 1f) to ((pos.y - t) / h).coerceIn(0f, 1f))
                                    touch = pos
                                    change.consume()
                                }
                            }
                            dragging = -1
                            touch = Offset.Unspecified
                            val committed = quad
                            vm.update(index) { it.copy(quad = committed) }
                        }
                    },
                ) {
                    val pts = quad.points.map { (x, y) -> Offset(left + x * imgW, top + y * imgH) }
                    val poly = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                    // Dim everything outside the page.
                    drawRect(Color(0x66000000))
                    drawPath(poly, Color.Black, blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
                    drawPath(poly, Brand.Blue, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
                    pts.forEachIndexed { i, p ->
                        val r = if (i == dragging) 15.dp.toPx() else 11.dp.toPx()
                        drawCircle(Color.White, r, p)
                        drawCircle(Brand.Blue, r - 4.dp.toPx(), p)
                    }
                }
            }
        }
        Text(
            "Drag the corners to the document edges", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanFilter.entries.forEach { f ->
                val sel = f == page.filter
                val bg by animateColorAsState(if (sel) Brand.Blue else Color(0xFF2A2A2A), label = "chip")
                Text(
                    f.label, color = if (sel) Color.White else Color(0xFFDDDDDD), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
                        .clickable { vm.update(index) { it.copy(filter = f) } }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            Text(
                "Full image", color = Color(0xFFDDDDDD), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2A))
                    .clickable { vm.update(index) { it.copy(quad = Quad.FULL) } }.padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::backToCamera, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) {
                Text("Add page", color = Color.White)
            }
            Button(
                onClick = { vm.update(index) { it.copy(quad = quad) }; vm.finish() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Blue),
            ) { Text("Save PDF ($total)") }
        }
    }
}

private fun Offset.isSpecified() = this != Offset.Unspecified && x.isFinite()

