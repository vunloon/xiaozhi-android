package com.xiaozhi.client.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette

private data class ModeSpec(val mode: ListenMode, val label: String, val icon: ImageVector, val hint: String)

private val MODES: List<ModeSpec> = listOf(
    ModeSpec(ListenMode.manual, "Manual", Icons.Outlined.PanTool, "Hold to talk"),
    ModeSpec(ListenMode.auto, "Auto", Icons.Outlined.SmartToy, "Hands-free turns"),
    ModeSpec(ListenMode.realtime, "Realtime", Icons.Outlined.GraphicEq, "Full duplex, interrupt anytime"),
)

/** Segmented listening-mode switch with per-mode hints (web Controls parity). */
@Composable
fun ModeSwitch(
    mode: ListenMode,
    onChange: (ListenMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalXiaozhiPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.bg2.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            .border(1.dp, palette.line, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MODES.forEach { spec ->
            val selected = mode == spec.mode
            val bg by animateColorAsState(
                targetValue = if (selected) palette.accent else Color.Transparent,
                animationSpec = tween(180),
                label = "mode-bg",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (selected) 1f else 0.75f)
                    .background(bg, RoundedCornerShape(12.dp))
                    .clickable { onChange(spec.mode) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = null,
                    tint = if (selected) palette.bg else palette.dim,
                    modifier = Modifier.size(15.dp),
                )
                Box(Modifier.size(5.dp))
                Text(
                    text = spec.label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) palette.bg else palette.dim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One-line hint for the currently selected mode. */
@Composable
fun ModeHint(mode: ListenMode, modifier: Modifier = Modifier) {
    val palette = LocalXiaozhiPalette.current
    val hint = MODES.firstOrNull { it.mode == mode }?.hint ?: return
    Text(
        text = hint,
        fontSize = 11.sp,
        color = palette.dim,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}
