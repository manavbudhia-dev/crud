package com.papervault.scanner.ui.qr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.papervault.scanner.ui.components.relativeTime
import com.papervault.scanner.ui.theme.Brand
import com.papervault.scanner.util.ShareUtils
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** QR & barcode scanner powered by ML Kit Barcode Scanning (on-device). */
@OptIn(ExperimentalGetImage::class)
@androidx.compose.runtime.Composable
fun QrScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val history = remember { QrHistory(context) }
    var result by remember { mutableStateOf<CodeResult?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    val scanner = remember { BarcodeScanning.getClient() }
    val paused = remember { AtomicBoolean(false) }
    val onFound by rememberUpdatedState<(CodeResult) -> Unit> { r ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        history.add(r)
        result = r
    }
    LaunchedEffect(result) { paused.set(result != null) }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    DisposableEffect(Unit) { onDispose { scanner.close() } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { InputImage.fromFilePath(context, uri) }.onSuccess { img ->
            scanner.process(img)
                .addOnSuccessListener { codes ->
                    codes.firstOrNull()?.let { onFound(QrParser.from(it)) }
                        ?: scope.launch { snackbar.showSnackbar("No QR code or barcode found in that image") }
                }
                .addOnFailureListener { scope.launch { snackbar.showSnackbar(it.message ?: "Couldn't read the image") } }
        }
    }

    BackHandler(enabled = result != null) { result = null }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(lifecycle) {
                val executor = Executors.newSingleThreadExecutor()
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || paused.get()) { proxy.close(); return@setAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val first = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                            if (first != null && paused.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(context).execute { onFound(QrParser.from(first)) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    runCatching {
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(context))
                onDispose {
                    analysis.clearAnalyzer()
                    runCatching { future.get().unbindAll() }
                    executor.shutdown()
                }
            }
            ScanFrame(Modifier.fillMaxSize(), active = result == null)
        } else {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.QrCode2, null, tint = Color.White, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("Camera access is needed to scan codes", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Scan QR / barcode", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { torch = !torch }) {
                Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, "Torch", tint = if (torch) Brand.Star else Color.White)
            }
            IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Outlined.Image, "Scan from photo", tint = Color.White)
            }
            IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, "History", tint = Color.White) }
        }
        Text(
            "Point the camera at a QR code or barcode", color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp)
                .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 8.dp),
        )

        AnimatedVisibility(
            visible = result != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            result?.let { r -> ResultCard(r, onDismiss = { result = null }, onCopied = { scope.launch { snackbar.showSnackbar("Copied") } }) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 60.dp))
    }

    if (showHistory) HistorySheet(history, onDismiss = { showHistory = false }) { showHistory = false; result = it }
}

/** Darkened surround, rounded window, and a sweeping scan line. */
@Composable
private fun ScanFrame(modifier: Modifier, active: Boolean) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "line",
    )
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f
        val tl = Offset((size.width - side) / 2, (size.height - side) / 2 - 40.dp.toPx())
        val r = CornerRadius(24.dp.toPx())
        drawRect(Color(0x99000000))
        drawRoundRect(Color.Black, tl, Size(side, side), r, blendMode = BlendMode.Clear)
        val len = side * 0.16f; val sw = 5.dp.toPx(); val c = Brand.Blue
        listOf(tl, Offset(tl.x + side, tl.y), Offset(tl.x + side, tl.y + side), Offset(tl.x, tl.y + side)).forEachIndexed { i, p ->
            val dx = if (i == 1 || i == 2) -1 else 1; val dy = if (i >= 2) -1 else 1
            drawLine(c, p, Offset(p.x + dx * len, p.y), sw, StrokeCap.Round)
            drawLine(c, p, Offset(p.x, p.y + dy * len), sw, StrokeCap.Round)
        }
        if (active) {
            val y = tl.y + 16.dp.toPx() + (side - 32.dp.toPx()) * sweep
            drawLine(Brand.Blue.copy(alpha = 0.9f), Offset(tl.x + 20.dp.toPx(), y), Offset(tl.x + side - 20.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun ResultCard(r: CodeResult, onDismiss: () -> Unit, onCopied: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${r.kind.label} · ${r.format}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(r.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (r.details.isNotEmpty()) r.details.filter { it.second.isNotBlank() }.forEach { (k, v) ->
            Row { Text("$k  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) }
        } else if (r.value != r.title) Text(r.value, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
        QrActions.primary(r)?.let { (label, intent) ->
            Button(onClick = { runCatching { context.startActivity(intent) } }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text(label) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                copy(context, if (r.kind == CodeKind.WIFI) r.value else r.raw); onCopied()
            }, Modifier.weight(1f), shape = RoundedCornerShape(50)) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)); Text(if (r.kind == CodeKind.WIFI) "  Copy password" else "  Copy")
            }
            OutlinedButton(onClick = { ShareUtils.shareText(context, r.raw) }, Modifier.weight(1f), shape = RoundedCornerShape(50)) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Text("  Share")
            }
        }
        TextButton(onClick = onDismiss, Modifier.align(Alignment.CenterHorizontally)) { Text("Scan again") }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(history: QrHistory, onDismiss: () -> Unit, onPick: (CodeResult) -> Unit) {
    var items by remember { mutableStateOf(history.load()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scan history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) TextButton(onClick = { history.clear(); items = emptyList() }) { Text("Clear") }
        }
        if (items.isEmpty()) Text("No scans yet", Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(items) { r ->
                Column(Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.kind.label} · ${relativeTime(r.time)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PaperVault", text))
}
