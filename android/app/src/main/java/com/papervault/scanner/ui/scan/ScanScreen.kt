package com.papervault.scanner.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papervault.scanner.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

/** Full scan flow: camera → adjust edges / filter → more pages → PDF. */
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
        if (idx != null && idx in pages.indices) {
            BackHandler { vm.backToCamera() }
            AdjustView(vm, idx, pages[idx], pages.size)
        } else {
            BackHandler(onBack = onClose)
            CameraView(vm, pages.size, onClose)
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
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { vm.onImported(it) }
    val openGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    var flash by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    LaunchedEffect(flash) { imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 0.dp))) {
            if (granted) {
                val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
                AndroidView({ previewView }, Modifier.fillMaxSize())
                DisposableEffect(lifecycle) {
                    val future = ProcessCameraProvider.getInstance(context)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        }
                    }, ContextCompat.getMainExecutor(context))
                    onDispose { runCatching { future.get().unbindAll() } }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Camera access is needed to scan", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = openGallery) { Text("Import from gallery", color = Color.White) }
                }
            }
            CornerFrame(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 110.dp))
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { flash = !flash }) {
                    Icon(if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = openGallery) { Icon(Icons.Outlined.Image, "Import from gallery", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }
            Text(
                "Adjust edges for better scan", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).clip(RoundedCornerShape(50))
                    .background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Bottom controls
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF111111)).navigationBarsPadding().padding(horizontal = 36.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).clickable {
                if (pageCount > 0) vm.editing.value = pageCount - 1 else openGallery()
            }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PhotoLibrary, "Pages", tint = Color.White)
                if (pageCount > 0) Text(
                    "$pageCount", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).background(Brand.Blue).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // Shutter
            Box(
                Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                    .background(if (capturing) Brand.BlueDark else Brand.Blue)
                    .clickable(enabled = granted && !capturing) {
                        capturing = true
                        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val rot = image.imageInfo.rotationDegrees
                                val bmp = image.toBitmap()
                                image.close()
                                val upright = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                                capturing = false
                                vm.onCaptured(upright)
                            }
                            override fun onError(exception: ImageCaptureException) { capturing = false }
                        })
                    },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::retakeLast, enabled = pageCount > 0, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Default.Refresh, "Retake last page", tint = if (pageCount > 0) Color.White else Color.Gray, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** Blue rounded corner brackets of the scan frame. */
@Composable
private fun CornerFrame(modifier: Modifier) {
    Canvas(modifier) {
        val len = size.minDimension * 0.12f
        val sw = 5.dp.toPx()
        val c = Brand.Blue
        fun bracket(x: Float, y: Float, dx: Float, dy: Float) {
            val p = Path().apply { moveTo(x, y + dy * len); lineTo(x, y); lineTo(x + dx * len, y) }
            drawPath(p, c, style = Stroke(sw, cap = StrokeCap.Round))
        }
        bracket(0f, 0f, 1f, 1f); bracket(size.width, 0f, -1f, 1f)
        bracket(size.width, size.height, -1f, -1f); bracket(0f, size.height, 1f, -1f)
    }
}

// ------------------------------------------------------------------ adjust edges

@Composable
private fun AdjustView(vm: ScanViewModel, index: Int, page: ScanPage, total: Int) {
    val original by produceState<Bitmap?>(null, page.raw) { value = withContext(Dispatchers.IO) { ScanProcessing.decode(page.raw, 1600) } }
    val preview by produceState<Bitmap?>(null, original, page.filter) {
        val o = original
        value = if (o == null) null else withContext(Dispatchers.Default) { ScanProcessing.filter(o, page.filter) }
    }

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
            val bmp = preview
            if (bmp == null) { CircularProgressIndicator(color = Color.White); return@BoxWithConstraints }
            val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / bmp.width, boxH / bmp.height)
            val imgW = bmp.width * scale; val imgH = bmp.height * scale
            val left = (boxW - imgW) / 2; val top = (boxH - imgH) / 2
            var dragging by remember { mutableStateOf(-1) }
            Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(
                Modifier.fillMaxSize().pointerInput(page.quad, imgW, imgH) {
                    detectDragGestures(
                        onDragStart = { o ->
                            dragging = page.quad.points.withIndex().minByOrNull { (_, p) ->
                                hypot(left + p.first * imgW - o.x, top + p.second * imgH - o.y)
                            }?.takeIf { (_, p) -> hypot(left + p.first * imgW - o.x, top + p.second * imgH - o.y) < 120f }?.index ?: -1
                        },
                        onDrag = { ch, _ ->
                            if (dragging >= 0) {
                                ch.consume()
                                val nx = ((ch.position.x - left) / imgW).coerceIn(0f, 1f)
                                val ny = ((ch.position.y - top) / imgH).coerceIn(0f, 1f)
                                vm.update(index) { it.copy(quad = it.quad.with(dragging, nx to ny)) }
                            }
                        },
                        onDragEnd = { dragging = -1 },
                    )
                },
            ) {
                val pts = page.quad.points.map { (x, y) -> Offset(left + x * imgW, top + y * imgH) }
                val poly = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(poly, Brand.Blue.copy(alpha = 0.12f))
                drawPath(poly, Brand.Blue, style = Stroke(3.dp.toPx()))
                pts.forEachIndexed { i, p ->
                    drawCircle(Color.White, if (i == dragging) 16.dp.toPx() else 12.dp.toPx(), p)
                    drawCircle(Brand.Blue, if (i == dragging) 12.dp.toPx() else 8.dp.toPx(), p)
                }
            }
        }
        Text("Drag the corners to the document edges", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterHorizontally))

        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanFilter.entries.forEach { f ->
                val sel = f == page.filter
                Text(
                    f.label, color = if (sel) Color.White else Color(0xFFDDDDDD), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Brand.Blue else Color(0xFF2A2A2A))
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
                onClick = vm::finish, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Blue),
            ) { Text("Save PDF ($total)") }
        }
    }
}

