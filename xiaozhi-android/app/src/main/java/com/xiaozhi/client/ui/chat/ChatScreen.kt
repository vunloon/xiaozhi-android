package com.xiaozhi.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.state.ChatStateMachine
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

    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .imePadding()) {
        TopBar(
            connectionState = connectionState,
            onConnect = { vm.connect() },
            onDisconnect = { vm.reconnect() },
            onOpenSettings = onOpenSettings,
        )

        if (!opusAvailable) OpusUnavailableBanner()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val palette = LocalXiaozhiPalette.current
            palette.backgroundRes?.let { res ->
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(res),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (transcript.isEmpty()) {
                    item { EmptyConversation() }
                } else {
                    items(transcript) { entry -> TranscriptBubble(entry) }
                }
            }
        }

        val palette = LocalXiaozhiPalette.current
        MusicPlayerCard(
            accent = palette.accent,
            text = palette.text,
            dim = palette.dim,
        )

        BottomBar(
            chatState = chatState,
            connectionState = connectionState,
            mode = mode,
            sessionActive = sessionActive,
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

@Composable
private fun TopBar(
    connectionState: WebSocketProtocol.ConnectionState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.panel)
            .border(1.dp, palette.line, RoundedCornerShape(0.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontSize = 12.sp, color = palette.dim)
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { if (connected) onDisconnect() else onConnect() },
            modifier = Modifier.size(28.dp),
        ) {
            Text("⏻", fontSize = 15.sp, color = palette.dim)
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(28.dp),
        ) {
            Text("⚙", fontSize = 15.sp, color = palette.dim)
        }
    }
}

@Composable
private fun OpusUnavailableBanner() {
    val palette = LocalXiaozhiPalette.current
    Surface(
        color = palette.danger.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = "Opus native library failed to load — voice unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.danger,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EmptyConversation() {
    val palette = LocalXiaozhiPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Hold the mic button and talk, or type a message.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.dim,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Manual · push-to-talk  |  Auto · hands-free turns  |  Realtime · full duplex",
                fontSize = 12.sp,
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
        RoundedCornerShape(
            topStart = 14.dp, topEnd = 14.dp,
            bottomStart = 14.dp, bottomEnd = 4.dp,
        )
    } else {
        RoundedCornerShape(
            topStart = 14.dp, topEnd = 14.dp,
            bottomStart = 4.dp, bottomEnd = 14.dp,
        )
    }
    Surface(
        color = if (isUser) palette.userBubbleBg else palette.card,
        shape = shape,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isUser) palette.userBubbleLine else palette.line,
        ),
        modifier = Modifier.widthIn(max = 300.dp),
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = palette.text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BottomBar(
    chatState: ChatStateMachine.ChatState,
    connectionState: WebSocketProtocol.ConnectionState,
    mode: ListenMode,
    sessionActive: Boolean,
    onModeChange: (ListenMode) -> Unit,
    onTalkStart: () -> Unit,
    onTalkEnd: () -> Unit,
    onAbort: () -> Unit,
    onSendText: (String) -> Unit,
) {
    val palette = LocalXiaozhiPalette.current
    val connected = connectionState is WebSocketProtocol.ConnectionState.Connected
    val listening = chatState == ChatStateMachine.ChatState.LISTENING
    val speaking = chatState == ChatStateMachine.ChatState.SPEAKING
    val talkActive = listening || (speaking && sessionActive && mode == ListenMode.realtime)

    var textInput by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(palette.panel)
            .border(1.dp, palette.line, RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeSwitch(mode = mode, onChange = onModeChange)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (speaking) {
                IconButton(
                    onClick = onAbort,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(palette.danger.copy(alpha = 0.25f), CircleShape)
                            .border(2.dp, palette.danger, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⏹", fontSize = 14.sp, color = palette.danger)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            TalkButton(
                mode = mode,
                listening = talkActive,
                speaking = speaking,
                enabled = connected,
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
                    .height(44.dp),
                placeholder = {
                    Text("Type a message…", fontSize = 14.sp, color = palette.dim)
                },
                singleLine = true,
                enabled = connected,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.card,
                    unfocusedContainerColor = palette.card,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledContainerColor = palette.card.copy(alpha = 0.6f),
                    disabledBorderColor = Color.Transparent,
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text, fontSize = 14.sp),
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Button(
                onClick = {
                    val t = textInput.trim()
                    if (t.isNotEmpty()) {
                        onSendText(t)
                        textInput = ""
                        keyboardController?.hide()
                    }
                },
                modifier = Modifier
                    .height(44.dp)
                    .width(44.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.bg,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}