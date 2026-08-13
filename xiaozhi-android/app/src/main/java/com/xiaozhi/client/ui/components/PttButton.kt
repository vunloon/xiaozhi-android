package com.xiaozhi.client.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Press-to-talk button. Hold to listen, release to send.
 *
 * The button is a press-and-hold gesture (not a click) so the user can hold
 * the device in one hand. On press-down we fire [onPress]; on release we fire
 * [onRelease]. Disabled state explicitly suppresses both.
 */
@Composable
fun PttButton(
    active: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // IMPORTANT: the pointerInput key must NOT include `active`. The state flip
    // from inactive→active on press-down would otherwise re-create the gesture
    // detector mid-press and the ACTION_UP would never be delivered to the
    // original coroutine — leaving capture running forever.
    val key = if (enabled) "enabled" else "disabled"
    val scale by animateFloatAsState(
        targetValue = if (active) 1.15f else 1.0f,
        animationSpec = tween(120),
        label = "ptt-scale",
    )
    val backgroundColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        active -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(scale)
            .background(backgroundColor, CircleShape)
            .semantics { role = Role.Button }
            .pointerInput(key) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) Icons.Outlined.Mic else Icons.Outlined.MicNone,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(32.dp),
        )
    }
}
