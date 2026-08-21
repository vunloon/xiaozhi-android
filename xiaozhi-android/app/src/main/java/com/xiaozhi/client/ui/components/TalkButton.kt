package com.xiaozhi.client.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
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
 *  - AUTO / REALTIME: tap to start a hands-free session, tap again to stop.
 *
 * All inputs are read through [rememberUpdatedState] inside a keyless
 * `pointerInput` — the gesture detector must NEVER be restarted by
 * recomposition, otherwise a mode switch mid-session leaves a stale detector
 * (e.g. manual press-hold semantics while in auto mode, which made a tap
 * start+instantly stop the mic — "pressing record does nothing").
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
    micLevel: Float = 0f,
) {
    val palette = LocalXiaozhiPalette.current

    val currentMode by rememberUpdatedState(mode)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTalkStart by rememberUpdatedState(onTalkStart)
    val currentOnTalkEnd by rememberUpdatedState(onTalkEnd)
    val currentListening by rememberUpdatedState(listening)
    val currentSpeaking by rememberUpdatedState(speaking)

    val active = listening || speaking
    val pulse = rememberInfiniteTransition(label = "talk-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (speaking) 550 else 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "talk-pulse-alpha",
    )
    val levelScale by animateFloatAsState(
        targetValue = 1f + (micLevel.coerceIn(0f, 1f) * 0.22f),
        animationSpec = tween(90),
        label = "talk-level-scale",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (listening) 1.05f else 1f,
        animationSpec = tween(120),
        label = "talk-press-scale",
    )

    val ringColor = when {
        !enabled -> palette.line
        listening -> palette.accent
        speaking -> palette.accent2
        else -> palette.line
    }
    val bgColor = when {
        !enabled -> palette.card.copy(alpha = 0.55f)
        listening -> palette.accent.copy(alpha = 0.16f)
        speaking -> palette.accent2.copy(alpha = 0.16f)
        else -> palette.card
    }
    val label = when {
        !enabled -> "Unavailable"
        mode == ListenMode.manual -> if (listening) "Release to send" else "Hold to talk"
        active -> "Stop"
        else -> "Tap to talk"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            // Pulsing halo while a session is live.
            if (active && enabled) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(levelScale)
                        .alpha(pulseAlpha)
                        .background(ringColor.copy(alpha = 0.18f), CircleShape),
                )
            }
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .scale(pressScale)
                    .graphicsLayer { alpha = 0.99f }
                    .background(bgColor, CircleShape)
                    .border(2.dp, ringColor, CircleShape)
                    .semantics { role = Role.Button }
                    .pointerInput(Unit) {
                        // One detector for the app's lifetime. The mode branch
                        // lives INSIDE the callbacks so every event reads the
                        // latest mode/callbacks via the State objects above.
                        detectTapGestures(
                            onPress = {
                                if (currentEnabled && currentMode == ListenMode.manual) {
                                    currentOnTalkStart()
                                    tryAwaitRelease()
                                    currentOnTalkEnd()
                                } else {
                                    tryAwaitRelease()
                                }
                            },
                            onTap = {
                                if (!currentEnabled || currentMode == ListenMode.manual) return@detectTapGestures
                                if (currentListening || currentSpeaking) currentOnTalkEnd() else currentOnTalkStart()
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        !enabled -> Icons.Outlined.MicOff
                        listening -> Icons.Outlined.Mic
                        speaking -> Icons.Outlined.Stop
                        else -> Icons.Outlined.Mic
                    },
                    contentDescription = null,
                    tint = if (!enabled) palette.dim else if (active) ringColor else palette.text,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = palette.dim,
            textAlign = TextAlign.Center,
        )
    }
}
