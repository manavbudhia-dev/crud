package com.papervault.scanner.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.papervault.scanner.ui.theme.extra

enum class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    DOCUMENTS("documents", "Documents", Icons.Outlined.Folder, Icons.Filled.Folder),
    STARRED("starred", "Starred", Icons.Outlined.StarOutline, Icons.Filled.Star),
    TOOLS("tools", "Tools", Icons.Outlined.Build, Icons.Filled.Build),
}

/** White rounded bottom bar with blue selected item and underline, as in the design. */
@Composable
fun PaperBottomBar(current: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    Row(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, shape, ambientColor = Color(0x221E3A8A), spotColor = Color(0x221E3A8A))
            .clip(shape)
            .background(MaterialTheme.extra.card)
            .navigationBarsPadding()
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.extra.inkSoft
            Column(
                Modifier
                    .weight(1f)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(if (selected) tab.selectedIcon else tab.icon, null, tint = color, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(3.dp))
                Text(tab.label, color = color, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}
