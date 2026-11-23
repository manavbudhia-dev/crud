package com.papervault.scanner.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

@Composable
fun PageNavigator(page: Int, count: Int, onChange: (Int) -> Unit, trailing: String = "") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(page - 1) }, enabled = page > 0) { Icon(Icons.Default.ChevronLeft, "Previous page") }
        Text("Page ${page + 1} of $count$trailing")
        IconButton(onClick = { onChange(page + 1) }, enabled = page < count - 1) { Icon(Icons.Default.ChevronRight, "Next page") }
    }
}

/** Normalise a pixel position inside a box of [size] to 0..1. */
fun norm(x: Float, y: Float, size: IntSize): Pair<Float, Float> =
    (x / size.width).coerceIn(0f, 1f) to (y / size.height).coerceIn(0f, 1f)
