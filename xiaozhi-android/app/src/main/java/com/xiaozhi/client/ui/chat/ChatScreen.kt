package com.xiaozhi.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.state.ChatStateMachine
import com.xiaozhi.client.ui.components.PttButton
import com.xiaozhi.client.ui.components.ToolActivityDrawer
import com.xiaozhi.client.ui.theme.CloudWhite
import com.xiaozhi.client.ui.theme.DeepBlueText
import com.xiaozhi.client.ui.theme.HeartAccentBlue
import com.xiaozhi.client.ui.theme.ReceivedBubble
import com.xiaozhi.client.ui.theme.SentBubble
import com.xiaozhi.client.ui.theme.TopBarBlue
import java.time.LocalTime
import java.time.format.DateTimeFormatter

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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = Color(0xFFFFE0B2),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("🐻", fontSize = 22.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = DeepBlueText,
                                fontFamily = FontFamily.Default,
                            )
                            Text(
                                text = statusText(connectionState),
                                fontSize = 11.sp,
                                color = statusColor(connectionState),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reconnect() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reconnect", tint = DeepBlueText)
                    }
                    IconButton(onClick = { vm.toggleToolDrawer() }) {
                        Icon(Icons.Outlined.Build, contentDescription = stringResource(R.string.tool_drawer_title), tint = DeepBlueText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TopBarBlue,
                ),
            )
        },
        containerColor = PastelBlueBg,
    ) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {

            Column(modifier = Modifier.fillMaxSize()) {
                if (!opusAvailable) OpusUnavailableBanner()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    BackgroundImage()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
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

private val PastelBlueBg = Color(0xFFE8F1F5)

@Composable
private fun BackgroundImage() {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.chat_background),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun statusText(state: WebSocketProtocol.ConnectionState): String {
    val ctx = LocalContext.current
    return when (state) {
        WebSocketProtocol.ConnectionState.Connected -> "● ${ctx.getString(R.string.chat_online)}"
        WebSocketProtocol.ConnectionState.Connecting -> "● ${ctx.getString(R.string.chat_connecting)}"
        WebSocketProtocol.ConnectionState.Disconnected -> "● ${ctx.getString(R.string.chat_disconnected)}"
        is WebSocketProtocol.ConnectionState.Failed -> "✕ ${ctx.getString(R.string.chat_disconnected)}"
    }
}

@Composable
private fun statusColor(state: WebSocketProtocol.ConnectionState): Color = when (state) {
    WebSocketProtocol.ConnectionState.Connected -> Color(0xFF4CAF50)
    WebSocketProtocol.ConnectionState.Connecting -> Color(0xFF8FAAD8)
    WebSocketProtocol.ConnectionState.Disconnected -> Color(0xFFB0BEC5)
    is WebSocketProtocol.ConnectionState.Failed -> MaterialTheme.colorScheme.error
}

@Composable
private fun OpusUnavailableBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(12.dp),
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
            Surface(
                color = PastelBlueBubble,
                shape = CircleShape,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = null,
                        tint = HeartAccentBlue,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "🐻 Say hi to Xiaozhi!",
                style = MaterialTheme.typography.bodyMedium,
                color = DeepBlueText,
                fontFamily = FontFamily.Default,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_ptt_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun getCuteBubbleShape(isFromMe: Boolean) = if (isFromMe) {
    RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 6.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp,
    )
} else {
    RoundedCornerShape(
        topStart = 6.dp,
        topEnd = 20.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp,
    )
}

private val bubbleTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun TranscriptBubble(entry: ChatStateMachine.TranscriptEntry) {
    val isUser = entry is ChatStateMachine.TranscriptEntry.User
    val bubbleColor = if (isUser) SentBubble else ReceivedBubble
    val time = remember(entry) { LocalTime.now().format(bubbleTimeFormat) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Surface(
                    color = bubbleColor,
                    shape = getCuteBubbleShape(isFromMe = true),
                    shadowElevation = 1.dp,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = entry.text,
                        color = DeepBlueText,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                Text(
                    text = time,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Avatar("🐰")
        } else {
            Avatar("🐻")
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Surface(
                    color = bubbleColor,
                    shape = getCuteBubbleShape(isFromMe = false),
                    shadowElevation = 1.dp,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = entry.text,
                        color = DeepBlueText,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Default,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                Text(
                    text = time,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                )
            }
        }
    }
}

private val PastelBlueBubble = Color(0xFFD0E8F2)

@Composable
private fun Avatar(emoji: String) {
    Surface(
        color = Color(0xFFFFE0B2),
        shape = CircleShape,
        modifier = Modifier.size(32.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 16.sp)
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

    var textInput by remember { mutableStateOf("") }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Surface(
        color = TopBarBlue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StateLabel(listening = listening, speaking = speaking, connecting = !connected && connectionState is WebSocketProtocol.ConnectionState.Connecting)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Type a cute message… 💙",
                            fontSize = 14.sp,
                            color = Color.Gray,
                        )
                    },
                    singleLine = true,
                    enabled = connected,
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CloudWhite,
                        unfocusedContainerColor = CloudWhite,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledContainerColor = CloudWhite.copy(alpha = 0.6f),
                        disabledBorderColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = DeepBlueText),
                )
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendText(textInput)
                            textInput = ""
                            keyboardController?.hide()
                        }
                    },
                    containerColor = HeartAccentBlue,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Favorite,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp),
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
