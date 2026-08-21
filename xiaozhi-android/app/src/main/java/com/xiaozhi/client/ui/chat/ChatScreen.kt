package com.xiaozhi.client.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.state.ChatStateMachine
import com.xiaozhi.client.ui.components.ModeHint
import com.xiaozhi.client.ui.components.ModeSwitch
import com.xiaozhi.client.ui.components.MusicPlayerCard
import com.xiaozhi.client.ui.components.TalkButton
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette

@Composable
fun ChatScreen(
    container: AppContainer,
    onOpenSettings: () -> Unit,
) {
    val vm: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(container) as T
            }
        },
    )

    val chatState by vm.chatState.collectAsStateWithLifecycle()
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val transcript by vm.transcript.collectAsStateWithLifecycle()
    val opusAvailable by vm.opusAvailable.collectAsStateWithLifecycle()
    val mode by vm.listeningMode.collectAsStateWithLifecycle()
    val sessionActive by vm.sessionActive.collectAsStateWithLifecycle()
    val emotion by vm.emotion.collectAsStateWithLifecycle()
    val micLevel by vm.micLevel.collectAsStateWithLifecycle()

    val palette = LocalXiaozhiPalette.current
    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(palette.bg, palette.bg2, palette.bg)),
            ),
    ) {
        // Optional theme backdrop image with a readability scrim.
        palette.backgroundRes?.let { res ->
            Image(
                painter = painterResource(res),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.bg.copy(alpha = 0.55f), palette.bg.copy(alpha = 0.25f), palette.bg.copy(alpha = 0.6f)),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            TopBar(
                connectionState = connectionState,
                onPower = { vm.toggleConnection() },
                onOpenSettings = onOpenSettings,
            )

            if (!opusAvailable) OpusUnavailableBanner()

            // Hero: emotion orb + live state.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                StatusOrb(
                    state = chatState,
                    connecting = connectionState is WebSocketProtocol.ConnectionState.Connecting,
                    emotion = emotion,
                    level = if (chatState == ChatStateMachine.ChatState.LISTENING) micLevel else 0f,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    if (transcript.isEmpty()) {
                        item { EmptyConversation(mode = mode) }
                    } else {
                        items(transcript) { entry -> TranscriptBubble(entry) }
                    }
                }
            }

            MusicPlayerCard(
                accent = palette.accent,
                text = palette.text,
                dim = palette.dim,
            )

            ControlsPanel(
                chatState = chatState,
                mode = mode,
                sessionActive = sessionActive,
                micLevel = micLevel,
                opusAvailable = opusAvailable,
                onModeChange = { vm.setMode(it) },
                onTalkStart = { vm.startListening() },
                onTalkEnd = {
                    if (mode == ListenMode.manual) vm.stopListening() else vm.endConversation()
                },
                onAbort = { vm.abort() },
                onSendText = { text -> vm.sendText(text) },
            )
        }
    }
}

@Composable
private fun TopBar(
    connectionState: WebSocketProtocol.ConnectionState,
    onPower: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalXiaozhiPalette.current
    val connected = connectionState is WebSocketProtocol.ConnectionState.Connected
    val connecting = connectionState is WebSocketProtocol.ConnectionState.Connecting
    val dotColor = when {
        connected -> palette.ok
        connecting -> palette.warn
        else -> Color(0xFF64748B)
    }
    val label = when {
        connected -> stringResource(R.string.chat_online)
        connecting -> stringResource(R.string.chat_connecting)
        else -> stringResource(R.string.chat_disconnected)
    }
    val pulse = rememberInfiniteTransition(label = "conn-pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "conn-dot-alpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Connection status pill.
        Row(
            modifier = Modifier
                .background(palette.panel, RoundedCornerShape(50))
                .border(1.dp, palette.line, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (connecting) dotAlpha else 1f)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, fontSize = 12.sp, color = palette.dim)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = palette.text,
        )
        Spacer(Modifier.weight(1f))
        CircleIconButton(
            icon = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = if (connected) palette.ok else palette.dim, modifier = Modifier.size(18.dp)) },
            onClick = onPower,
        )
        Spacer(Modifier.width(8.dp))
        CircleIconButton(
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = palette.dim, modifier = Modifier.size(18.dp)) },
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val palette = LocalXiaozhiPalette.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = palette.panel,
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun OpusUnavailableBanner() {
    val palette = LocalXiaozhiPalette.current
    Surface(
        color = palette.danger.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.danger.copy(alpha = 0.35f)),
    ) {
        Text(
            text = "Opus native library failed to load — voice unavailable, text chat still works.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.danger,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyConversation(mode: ListenMode) {
    val palette = LocalXiaozhiPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Start a conversation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (mode) {
                    ListenMode.manual -> "Hold the mic button and talk, or type below."
                    ListenMode.auto -> "Tap the mic for a hands-free chat, or type below."
                    ListenMode.realtime -> "Tap the mic — it listens continuously and you can interrupt anytime."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.dim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TranscriptBubble(entry: ChatStateMachine.TranscriptEntry) {
    val isUser = entry is ChatStateMachine.TranscriptEntry.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        ChatBubble(text = entry.text, isUser = isUser)
    }
}

@Composable
private fun ChatBubble(text: String, isUser: Boolean) {
    val palette = LocalXiaozhiPalette.current
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }
    Surface(
        color = if (isUser) palette.userBubbleBg else palette.card,
        shape = shape,
        border = BorderStroke(1.dp, if (isUser) palette.userBubbleLine else palette.line),
        modifier = Modifier.widthIn(max = 310.dp),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = palette.text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun ControlsPanel(
    chatState: ChatStateMachine.ChatState,
    mode: ListenMode,
    sessionActive: Boolean,
    micLevel: Float,
    opusAvailable: Boolean,
    onModeChange: (ListenMode) -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    onAbort: () -> Unit,
    onSendText: (String) -> Unit,
) {
    val palette = LocalXiaozhiPalette.current
    val listening = chatState == ChatStateMachine.ChatState.LISTENING
    val speaking = chatState == ChatStateMachine.ChatState.SPEAKING
    val talkActive = listening || (speaking && sessionActive && mode == ListenMode.realtime)

    var textInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val sendBg by animateColorAsState(
        targetValue = if (textInput.isNotBlank()) palette.accent else palette.card,
        animationSpec = tween(150),
        label = "send-bg",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.panel, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .border(1.dp, palette.line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeSwitch(mode = mode, onChange = onModeChange)
        ModeHint(mode = mode, modifier = Modifier.fillMaxWidth())

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (speaking) {
                Surface(
                    onClick = onAbort,
                    shape = CircleShape,
                    color = palette.danger.copy(alpha = 0.14f),
                    border = BorderStroke(1.5.dp, palette.danger),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Stop,
                            contentDescription = "Interrupt",
                            tint = palette.danger,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
            }
            TalkButton(
                mode = mode,
                listening = talkActive,
                speaking = speaking,
                enabled = opusAvailable,
                micLevel = if (listening) micLevel else 0f,
                onTalkStart = onTalkStart,
                onTalkEnd = onTalkEnd,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                placeholder = {
                    Text("Type a message…", fontSize = 14.sp, color = palette.dim)
                },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.card,
                    unfocusedContainerColor = palette.card.copy(alpha = 0.7f),
                    focusedBorderColor = palette.accent.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                    disabledContainerColor = palette.card.copy(alpha = 0.6f),
                    disabledBorderColor = Color.Transparent,
                    cursorColor = palette.accent,
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text, fontSize = 14.sp),
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                onClick = {
                    val t = textInput.trim()
                    if (t.isNotEmpty()) {
                        onSendText(t)
                        textInput = ""
                        keyboardController?.hide()
                    }
                },
                shape = CircleShape,
                color = sendBg,
                border = BorderStroke(1.dp, if (textInput.isNotBlank()) palette.accent else palette.line),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Send,
                        contentDescription = "Send",
                        tint = if (textInput.isNotBlank()) palette.bg else palette.dim,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}
