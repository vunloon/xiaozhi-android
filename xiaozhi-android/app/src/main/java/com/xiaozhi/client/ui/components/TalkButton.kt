package com.xiaozhi.client.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette

/**
 * The round talk button. Behaves per mode like the web client:
 *  - MANUAL: press-and-hold to talk, release to send.
 *  - AUTO / REALTIME: tap to start a hands-free turn, tap again to stop.
 */
@Composable
fun TalkButton(
    mode: ListenMode,
    listening: Boolean,
    speaking: Boolean,
    enabled: Boolean,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalXiaozhiPalette.current
    // The pointerInput key must NOT include `active` — flipping it mid-press
    // would re-create the gesture detector and swallow the release.
    val key = if (enabled) "enabled" else "disabled"
    val scale by animateFloatAsState(
        targetValue = if (listening) 1.08f else 1.0f,
        animationSpec = tween(120),
        label = "talk-scale",
    )
    val borderColor = when {
        !enabled -> palette.line
        listening || speaking -> palette.accent
        else -> palette.line
    }
    val bgColor = when {
        !enabled -> palette.card
        listening -> palette.accent.copy(alpha = 0.18f)
        else -> palette.card
    }
    val label = when {
        !enabled -> "Unavailable"
        mode == ListenMode.manual -> if (listening) "Release to send" else "Hold to talk"
        listening || speaking -> "Stop"
        else -> "Start"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .background(bgColor, CircleShape)
                .border(2.dp, borderColor, CircleShape)
                .semantics { role = Role.Button }
                .pointerInput(key) {
                    if (!enabled) return@pointerInput
                    if (mode == ListenMode.manual) {
                        detectTapGestures(
                            onPress = {
                                onTalkStart()
                                tryAwaitRelease()
                                onTalkEnd()
                            },
                        )
                    } else {
                        detectTapGestures(onTap = {
                            if (listening || speaking) onTalkEnd() else onTalkStart()
                        })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (listening) Icons.Outlined.Mic else Icons.Outlined.MicNone,
                contentDescription = null,
                tint = if (listening) palette.accent else palette.text,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            color = palette.dim,
            textAlign = TextAlign.Center,
        )
    }
}