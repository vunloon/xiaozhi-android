package com.xiaozhi.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.state.ChatStateMachine
import com.xiaozhi.client.ui.components.PttButton
import com.xiaozhi.client.ui.components.ToolActivityDrawer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
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
    val drawerOpen by vm.toolDrawerOpen.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    ConnectionStatusBadge(connectionState)
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.IconButton(onClick = { vm.reconnect() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reconnect")
                    }
                    androidx.compose.material3.IconButton(onClick = { vm.toggleToolDrawer() }) {
                        Icon(Icons.Outlined.Build, contentDescription = stringResource(R.string.tool_drawer_title))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            Column(modifier = Modifier.fillMaxSize()) {
                if (!opusAvailable) OpusUnavailableBanner()

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    if (transcript.isEmpty()) {
                        item { EmptyConversation() }
                    } else {
                        items(transcript) { entry -> TranscriptBubble(entry) }
                    }
                }

                BottomBar(
                    chatState = chatState,
                    connectionState = connectionState,
                    onTalkDown = { vm.startListening() },
                    onTalkUp = { vm.stopListening() },
                    onSendText = { text -> vm.sendText(text) },
                )
            }

            AnimatedVisibility(
                visible = drawerOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                ToolActivityDrawer(
                    container = container,
                    onClose = { vm.toggleToolDrawer() },
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusBadge(state: WebSocketProtocol.ConnectionState) {
    val (text, color) = when (state) {
        WebSocketProtocol.ConnectionState.Connected -> "● " to MaterialTheme.colorScheme.primary
        WebSocketProtocol.ConnectionState.Connecting -> stringResource(R.string.chat_connecting) to MaterialTheme.colorScheme.secondary
        WebSocketProtocol.ConnectionState.Disconnected -> stringResource(R.string.chat_disconnected) to MaterialTheme.colorScheme.error
        is WebSocketProtocol.ConnectionState.Failed -> "✕ " to MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun OpusUnavailableBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "Opus native library failed to load — voice unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EmptyConversation() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.chat_ptt_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
            ),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(
                        if (isUser) R.string.chat_you else R.string.chat_assistant
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    chatState: ChatStateMachine.ChatState,
    connectionState: WebSocketProtocol.ConnectionState,
    onTalkDown: () -> Unit,
    onTalkUp: () -> Unit,
    onSendText: (String) -> Unit,
) {
    val listening = chatState == ChatStateMachine.ChatState.LISTENING
    val speaking = chatState == ChatStateMachine.ChatState.SPEAKING
    val connected = connectionState is WebSocketProtocol.ConnectionState.Connected

    var textInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StateLabel(listening = listening, speaking = speaking, connecting = !connected && connectionState is WebSocketProtocol.ConnectionState.Connecting)

            // Text input row — lets the user type instead of speaking.
            // Useful when the mic isn't working or for silent testing.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Type a message…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    singleLine = true,
                    enabled = connected,
                    shape = RoundedCornerShape(20.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendText(textInput)
                            textInput = ""
                            keyboardController?.hide()
                        }
                    },
                    enabled = connected && textInput.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "Send",
                        tint = if (connected && textInput.isNotBlank())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            PttButton(
                active = listening,
                enabled = connected,
                onPress = onTalkDown,
                onRelease = onTalkUp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_ptt_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.7f),
            )
        }
    }
}

@Composable
private fun StateLabel(listening: Boolean, speaking: Boolean, connecting: Boolean) {
    val text = when {
        listening -> stringResource(R.string.chat_listening)
        speaking -> stringResource(R.string.chat_speaking)
        connecting -> stringResource(R.string.chat_connecting)
        else -> ""
    }
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (listening || connecting) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
