package com.xiaozhi.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette

private val MODES: List<Pair<ListenMode, String>> = listOf(
    ListenMode.manual to "Manual",
    ListenMode.auto to "Auto",
    ListenMode.realtime to "Realtime",
)

/** Three-pill listening-mode switch, mirroring the web's Controls mode tabs. */
@Composable
fun ModeSwitch(
    mode: ListenMode,
    onChange: (ListenMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalXiaozhiPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MODES.forEach { (m, label) ->
            val selected = mode == m
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) palette.bg else palette.dim,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) palette.accent else palette.card,
                        shape = RoundedCornerShape(50),
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) palette.accent else palette.line,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onChange(m) }
                    .padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}