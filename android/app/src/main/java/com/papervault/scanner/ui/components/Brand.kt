package com.papervault.scanner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papervault.scanner.data.Document
import com.papervault.scanner.data.DocumentSource
import com.papervault.scanner.ui.theme.Brand
import com.papervault.scanner.ui.theme.extra

// ---------------------------------------------------------------- logo & header

/** The blue rounded "P" mark. */
@Composable
fun BrandLogo(size: Dp = 34.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(topStart = size * 0.32f, topEnd = size * 0.32f, bottomEnd = size * 0.32f, bottomStart = size * 0.1f))
            .background(Brush.linearGradient(listOf(Color(0xFF5E93FF), Brand.Blue))),
        contentAlignment = Alignment.Center,
    ) {
        Text("P", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.62f).sp)
    }
}

@Composable
fun Avatar(initial: String, size: Dp = 40.dp, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFF6E9BFF), Color(0xFF3F74F8))))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(initial.ifBlank { "P" }.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.45f).sp)
    }
}

/** "P PaperVault ........ (search) (M)" header used on the tab screens. */
@Composable
fun AppHeader(initial: String, onSearch: () -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BrandLogo()
        Spacer(Modifier.width(10.dp))
        Text("PaperVault", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.extra.ink, modifier = Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search", Modifier.size(26.dp), tint = MaterialTheme.extra.ink) }
        Spacer(Modifier.width(4.dp))
        Avatar(initial, onClick = onProfile)
    }
}

// ---------------------------------------------------------------- cards & tiles

/** White rounded card with the soft shadow used throughout the design. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .shadow(10.dp, shape, ambientColor = Color(0x1A1E3A8A), spotColor = Color(0x1A1E3A8A))
            .clip(shape)
            .background(MaterialTheme.extra.card)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        content = content,
    )
}

/** Rounded square with a coloured icon, as in the Tools list. */
@Composable
fun ToolTile(icon: ImageVector, fg: Color, bg: Color, size: Dp = 46.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.52f))
    }
}

enum class FileKind { PDF, DOC, IMAGE }

fun Document.fileKind(): FileKind = when (source) {
    DocumentSource.PHOTOS -> FileKind.IMAGE
    DocumentSource.CONVERT -> FileKind.DOC
    else -> FileKind.PDF
}

/** File-type thumbnail tile: red PDF sheet, blue text sheet, or picture. */
@Composable
fun FileTypeTile(kind: FileKind, size: Dp = 64.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(if (kind == FileKind.PDF) MaterialTheme.extra.card else MaterialTheme.extra.iconSoft),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.62f)) {
            when (kind) {
                FileKind.PDF -> drawPdfSheet()
                FileKind.DOC -> drawDocSheet()
                FileKind.IMAGE -> drawPicture()
            }
        }
        if (kind == FileKind.PDF) {
            Text("PDF", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.19f).sp, modifier = Modifier.padding(top = size * 0.1f))
        }
    }
}

private fun DrawScope.foldedSheet(color: Color) {
    val w = size.width; val h = size.height; val fold = w * 0.3f
    val p = Path().apply {
        moveTo(w * 0.1f, 0f); lineTo(w * 0.9f - fold, 0f); lineTo(w * 0.9f, fold)
        lineTo(w * 0.9f, h); lineTo(w * 0.1f, h); close()
    }
    drawPath(p, color)
    drawPath(Path().apply { moveTo(w * 0.9f - fold, 0f); lineTo(w * 0.9f - fold, fold); lineTo(w * 0.9f, fold); close() }, Color.White.copy(alpha = 0.45f))
}

private fun DrawScope.drawPdfSheet() = foldedSheet(Brand.Red)

private fun DrawScope.drawDocSheet() {
    val w = size.width; val h = size.height
    drawRoundRect(Color(0xFFB9D3FF), Offset(w * 0.12f, 0f), Size(w * 0.76f, h), CornerRadius(w * 0.08f))
    val stroke = h * 0.075f
    listOf(0.28f to 0.7f, 0.46f to 0.55f, 0.64f to 0.65f).forEach { (y, len) ->
        drawLine(Brand.Blue, Offset(w * 0.26f, h * y), Offset(w * (0.26f + len * 0.66f), h * y), stroke, StrokeCap.Round)
    }
}

private fun DrawScope.drawPicture() {
    val w = size.width; val h = size.height
    drawRoundRect(Color(0xFFBCD6FF), Offset(0f, h * 0.1f), Size(w, h * 0.8f), CornerRadius(w * 0.1f))
    drawCircle(Color.White, w * 0.09f, Offset(w * 0.72f, h * 0.32f))
    drawPath(Path().apply { moveTo(0f, h * 0.9f); lineTo(w * 0.36f, h * 0.42f); lineTo(w * 0.62f, h * 0.76f); lineTo(w * 0.76f, h * 0.58f); lineTo(w, h * 0.9f); close() }, Color(0xFF2557D6))
}

// ---------------------------------------------------------------- backgrounds & illustration

/** Soft blue blobs behind the hero. */
@Composable
fun BlobBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val blob = MaterialTheme.extra.blob
    Box(modifier.background(MaterialTheme.extra.background)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawPath(Path().apply {
                moveTo(w * 0.78f, 0f)
                cubicTo(w * 0.70f, w * 0.20f, w * 0.98f, w * 0.30f, w * 0.86f, w * 0.52f)
                cubicTo(w * 0.80f, w * 0.64f, w * 0.92f, w * 0.75f, w, w * 0.78f)
                lineTo(w, 0f); close()
            }, blob.copy(alpha = 0.75f))
            drawCircle(blob.copy(alpha = 0.55f), w * 0.22f, Offset(w * 0.02f, w * 0.92f))
        }
        content()
    }
}

/** Document-stack illustration for the home hero. */
@Composable
fun HeroIllustration(modifier: Modifier = Modifier) {
    val blob = MaterialTheme.extra.blob
    val card = MaterialTheme.extra.card
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2
        // soft floor ellipse + background blobs
        drawOval(blob, Offset(cx - w * 0.34f, h * 0.80f), Size(w * 0.68f, h * 0.14f))
        drawCircle(blob.copy(alpha = 0.7f), h * 0.22f, Offset(cx - w * 0.30f, h * 0.62f))
        drawCircle(blob.copy(alpha = 0.6f), h * 0.18f, Offset(cx + w * 0.30f, h * 0.66f))
        // folder back
        val folder = Path().apply {
            moveTo(cx - w * 0.30f, h * 0.38f); lineTo(cx - w * 0.12f, h * 0.38f); lineTo(cx - w * 0.08f, h * 0.44f)
            lineTo(cx + w * 0.30f, h * 0.44f); lineTo(cx + w * 0.28f, h * 0.86f); lineTo(cx - w * 0.28f, h * 0.86f); close()
        }
        drawPath(folder, Color(0xFFB9CFFB))
        // document
        val dl = cx - w * 0.17f; val dt = h * 0.12f; val dw = w * 0.34f; val dh = h * 0.70f; val fold = dw * 0.26f
        val doc = Path().apply {
            moveTo(dl + 12f, dt); lineTo(dl + dw - fold, dt); lineTo(dl + dw, dt + fold)
            lineTo(dl + dw, dt + dh - 12f); quadraticTo(dl + dw, dt + dh, dl + dw - 12f, dt + dh)
            lineTo(dl + 12f, dt + dh); quadraticTo(dl, dt + dh, dl, dt + dh - 12f)
            lineTo(dl, dt + 12f); quadraticTo(dl, dt, dl + 12f, dt); close()
        }
        drawPath(doc, card)
        drawPath(doc, Brand.Blue, style = Stroke(w * 0.012f, join = StrokeJoin.Round))
        drawPath(Path().apply { moveTo(dl + dw - fold, dt); lineTo(dl + dw - fold, dt + fold); lineTo(dl + dw, dt + fold); close() }, Brand.Blue)
        listOf(0.36f to 0.7f, 0.50f to 0.7f, 0.64f to 0.5f).forEach { (y, len) ->
            drawLine(Brand.Blue, Offset(dl + dw * 0.18f, dt + dh * y), Offset(dl + dw * (0.18f + len * 0.9f), dt + dh * y), w * 0.014f, StrokeCap.Round)
        }
        // folder front lip
        drawPath(Path().apply {
            moveTo(cx - w * 0.32f, h * 0.58f); lineTo(cx - w * 0.24f, h * 0.58f)
            lineTo(cx - w * 0.22f, h * 0.86f); lineTo(cx - w * 0.28f, h * 0.86f); close()
        }, Color(0xFFD5E2FD))
        // corner brackets (scan frame)
        val b = w * 0.05f
        val sw = w * 0.012f
        drawLine(Brand.Blue, Offset(cx - w * 0.33f, h * 0.30f), Offset(cx - w * 0.33f, h * 0.30f + b), sw, StrokeCap.Round)
        drawLine(Brand.Blue, Offset(cx - w * 0.33f, h * 0.30f), Offset(cx - w * 0.33f + b, h * 0.30f), sw, StrokeCap.Round)
        // sparkle + dash
        val sx = cx + w * 0.30f; val sy = h * 0.14f; val r = w * 0.045f
        drawPath(Path().apply {
            moveTo(sx, sy - r); quadraticTo(sx, sy, sx + r, sy); quadraticTo(sx, sy, sx, sy + r)
            quadraticTo(sx, sy, sx - r, sy); quadraticTo(sx, sy, sx, sy - r); close()
        }, Brand.Blue)
        drawLine(Brand.Blue, Offset(sx + r * 0.2f, sy + r * 2f), Offset(sx + r * 1.4f, sy + r * 2.5f), sw, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- misc

@Composable
fun PageBadge(text: String, modifier: Modifier = Modifier) {
    Surface(color = Brand.Badge.copy(alpha = 0.9f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.extra.ink)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.inkSoft)
        }
    }
}

/** "2h ago", "3d ago", "just now". */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - millis).coerceAtLeast(0) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 86_400 * 30 -> "${s / 86_400}d ago"
        s < 86_400 * 365 -> "${s / (86_400 * 30)}mo ago"
        else -> "${s / (86_400 * 365)}y ago"
    }
}

/** Gold crown used on the Premium card. */
@Composable
fun CrownIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val crown = Path().apply {
            moveTo(w * 0.1f, h * 0.35f); lineTo(w * 0.32f, h * 0.55f); lineTo(w * 0.5f, h * 0.18f)
            lineTo(w * 0.68f, h * 0.55f); lineTo(w * 0.9f, h * 0.35f); lineTo(w * 0.8f, h * 0.78f); lineTo(w * 0.2f, h * 0.78f); close()
        }
        drawPath(crown, Brush.verticalGradient(listOf(Color(0xFFFFC53D), Color(0xFFFF9A1F))))
        drawRoundRect(Color(0xFFFF9A1F), Offset(w * 0.2f, h * 0.82f), Size(w * 0.6f, h * 0.1f), CornerRadius(h * 0.05f))
        listOf(0.1f to 0.35f, 0.5f to 0.18f, 0.9f to 0.35f).forEach { (x, y) -> drawCircle(Color(0xFFFFC53D), w * 0.07f, Offset(w * x, h * y)) }
    }
}
