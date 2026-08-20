package com.xiaozhi.client.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

/** Web-style emotion orb: emoji face inside a ring that scales with mic level. */
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
    val scale by animateFloatAsState(
        targetValue = 1f + kotlin.math.min(level * 6f, 0.25f),
        animationSpec = tween(120),
        label = "orb-scale",
    )
    val ringColor = when {
        connecting -> palette.warn
        state == ChatStateMachine.ChatState.LISTENING -> palette.accent
        state == ChatStateMachine.ChatState.SPEAKING -> palette.accent2
        else -> palette.line
    }
    val label = if (connecting) "Connecting" else STATE_LABEL[state] ?: "Idle"

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .border(2.dp, ringColor, CircleShape),
            )
            Text(text = emoji, fontSize = 52.sp)
        }
        Text(
            text = label,
            fontSize = 11.sp,
            color = palette.dim,
            textAlign = TextAlign.Center,
        )
    }
}