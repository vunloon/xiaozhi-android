package com.xiaozhi.client.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaozhi.client.state.ChatStateMachine
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette

// Same emotion->emoji map as xiaozhi-web/src/ui/StatusOrb.tsx.
private val EMOTION_EMOJI: Map<String, String> = mapOf(
    "neutral" to "🙂",
    "happy" to "😊",
    "laughing" to "😄",
    "funny" to "😆",
    "sad" to "😢",
    "angry" to "😠",
    "crying" to "😭",
    "loving" to "😍",
    "embarrassed" to "😳",
    "surprised" to "😲",
    "shocked" to "😱",
    "thinking" to "🤔",
    "winking" to "😉",
    "cool" to "😎",
    "relaxed" to "😌",
    "delicious" to "🤤",
    "kissy" to "😘",
    "confident" to "😏",
    "sleepy" to "🥱",
    "silly" to "😜",
    "bewildered" to "😵",
    "smug" to "😒",
)

private val STATE_LABEL: Map<ChatStateMachine.ChatState, String> = mapOf(
    ChatStateMachine.ChatState.IDLE to "Idle",
    ChatStateMachine.ChatState.LISTENING to "Listening",
    ChatStateMachine.ChatState.SPEAKING to "Speaking",
)

/**
 * Web-style emotion orb: emoji face inside rings that breathe with the state
 * (pulse while listening/speaking) and scale with the live mic level.
 */
@Composable
fun StatusOrb(
    state: ChatStateMachine.ChatState,
    connecting: Boolean,
    emotion: String,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val palette = LocalXiaozhiPalette.current
    val emoji = EMOTION_EMOJI[emotion] ?: EMOTION_EMOJI["neutral"]!!
    val ringColor = when {
        connecting -> palette.warn
        state == ChatStateMachine.ChatState.LISTENING -> palette.accent
        state == ChatStateMachine.ChatState.SPEAKING -> palette.accent2
        else -> palette.line
    }
    val breathing = connecting || state != ChatStateMachine.ChatState.IDLE
    val label = if (connecting) "Connecting" else STATE_LABEL[state] ?: "Idle"

    val pulse = rememberInfiniteTransition(label = "orb-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    state == ChatStateMachine.ChatState.SPEAKING -> 600
                    breathing -> 1100
                    else -> 1100
                },
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orb-pulse-alpha",
    )
    val levelScale by animateFloatAsState(
        targetValue = 1f + (level.coerceIn(0f, 1f) * 0.2f),
        animationSpec = tween(100),
        label = "orb-level-scale",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            // Outer glow halo.
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .alpha(if (breathing) pulseAlpha else 0.12f)
                    .background(ringColor.copy(alpha = 0.16f), CircleShape),
            )
            // Level-reactive soft ring.
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .scale(levelScale)
                    .border(1.5.dp, ringColor.copy(alpha = 0.35f), CircleShape),
            )
            // Main ring.
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer { scaleX = levelScale; scaleY = levelScale }
                    .border(3.dp, ringColor, CircleShape)
                    .background(palette.card.copy(alpha = 0.35f), CircleShape),
            )
            Text(
                text = emoji,
                fontSize = 50.sp,
                modifier = Modifier.graphicsLayer {
                    val s = 1f + (level.coerceIn(0f, 1f) * 0.08f)
                    scaleX = s; scaleY = s
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = palette.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(palette.panel, RoundedCornerShape(50))
                .border(1.dp, palette.line, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 3.dp),
        )
    }
}
