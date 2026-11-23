package com.papervault.scanner.ui.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Saved signatures live as transparent PNGs in app-private storage. */
object SignatureStore {
    fun dir(context: android.content.Context) = File(context.filesDir, "signatures").apply { mkdirs() }
    fun list(context: android.content.Context): List<File> =
        dir(context).listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun save(context: android.content.Context, strokes: List<List<Offset>>, canvas: IntSize): File? {
        val pts = strokes.flatten()
        if (pts.isEmpty()) return null
        val pad = 12f
        val minX = max(0f, pts.minOf { it.x } - pad)
        val minY = max(0f, pts.minOf { it.y } - pad)
        val maxX = min(canvas.width.toFloat(), pts.maxOf { it.x } + pad)
        val maxY = min(canvas.height.toFloat(), pts.maxOf { it.y } + pad)
        val w = (maxX - minX).toInt().coerceAtLeast(1)
        val h = (maxY - minY).toInt().coerceAtLeast(1)
        val scale = 2f
        val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(10, 30, 90)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3.5f * scale
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        strokes.forEach { s ->
            val p = android.graphics.Path()
            s.forEachIndexed { i, o ->
                val x = (o.x - minX) * scale
                val y = (o.y - minY) * scale
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            c.drawPath(p, paint)
        }
        val f = File(dir(context), "${UUID.randomUUID()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }
}

/** Choose a saved signature or draw a new one. */
@Composable
fun SignatureDialog(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SignatureStore.list(context)) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signature") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (saved.isNotEmpty()) {
                    Text("Saved", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(saved, key = { it.path }) { f ->
                            val bmp = remember(f) { BitmapFactory.decodeFile(f.path) }
                            Box(
                                Modifier.size(110.dp, 56.dp).background(Color.White).border(1.dp, Color.LightGray)
                                    .clickable { onPick(f) },
                                contentAlignment = Alignment.Center,
                            ) {
                                bmp?.let { Image(it.asImageBitmap(), "Signature", Modifier.padding(4.dp)) }
                                Icon(
                                    Icons.Default.Close, "Delete signature",
                                    Modifier.align(Alignment.TopEnd).size(18.dp).clickable {
                                        f.delete(); saved = SignatureStore.list(context)
                                    },
                                )
                            }
                        }
                    }
                }
                Text("Draw a new signature", style = MaterialTheme.typography.labelLarge)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(Color.White)
                        .border(1.dp, Color.LightGray)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { ch, _ -> current = current + ch.position },
                                onDragEnd = { strokes.add(current); current = emptyList() },
                            )
                        },
                ) {
                    (strokes + listOf(current)).filter { it.isNotEmpty() }.forEach { s ->
                        val p = Path().apply { s.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                        drawPath(p, Color(0xFF0A1E5A), style = Stroke(3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Row {
                    TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { SignatureStore.save(context, strokes.toList(), size)?.let(onPick) },
                enabled = strokes.isNotEmpty(),
            ) { Text("Use new signature") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
