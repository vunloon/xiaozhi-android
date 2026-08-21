package com.xiaozhi.client.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import com.xiaozhi.client.core.settings.AppSettings
import com.xiaozhi.client.core.settings.SettingsStore
import com.xiaozhi.client.ui.theme.LocalXiaozhiPalette
import com.xiaozhi.client.ui.theme.ThemeId
import com.xiaozhi.client.ui.theme.XiaozhiPalettes
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    onClose: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SettingsViewModel(container) as T
            }
        },
    )

    LaunchedEffect(Unit) { vm.loadDraft() }

    val palette = LocalXiaozhiPalette.current
    val draft by vm.draft.collectAsStateWithLifecycle(initialValue = null)
    val mcpWssState by vm.mcpWssState.collectAsStateWithLifecycle(initialValue = McpWssUiState.Off)
    val identity by vm.identity.collectAsStateWithLifecycle()
    val toolActivity by vm.toolActivity.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf("") }

    val d = draft ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = palette.text,
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                color = palette.text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Field(
                label = stringResource(R.string.settings_ota_url),
                placeholder = SettingsStore.DEFAULT_OTA_URL,
                value = d.otaUrl,
                palette = palette,
                onValue = { v -> vm.setField { it.copy(otaUrl = v) } },
            )
            Field(
                label = stringResource(R.string.settings_ws_url),
                placeholder = "wss://your-proxy.example.com/ws/xiaozhi/v1/",
                value = d.wsUrl,
                palette = palette,
                onValue = { v -> vm.setField { it.copy(wsUrl = v) } },
            )
            Field(
                label = stringResource(R.string.settings_token),
                placeholder = SettingsStore.DEFAULT_TOKEN,
                value = d.token,
                palette = palette,
                onValue = { v -> vm.setField { it.copy(token = v) } },
            )
            Field(
                label = stringResource(R.string.settings_vision_base),
                placeholder = "/vision",
                value = d.visionBase,
                palette = palette,
                onValue = { v -> vm.setField { it.copy(visionBase = v) } },
            )
            Field(
                label = stringResource(R.string.settings_mcp_wss_url),
                placeholder = SettingsStore.DEFAULT_MCP_WSS_URL,
                value = d.mcpWssUrl,
                palette = palette,
                onValue = { v -> vm.setField { it.copy(mcpWssUrl = v) } },
            )

            SectionLabel(stringResource(R.string.settings_mcp_wss_connect), palette.dim)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val (dotColor, stateText) = when (mcpWssState) {
                    McpWssUiState.Off -> palette.dim to stringResource(R.string.settings_mcp_wss_off)
                    McpWssUiState.Connecting -> palette.warn to stringResource(R.string.settings_mcp_wss_connecting)
                    McpWssUiState.Connected -> palette.ok to stringResource(R.string.settings_mcp_wss_connected)
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(stateText, color = palette.text, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { vm.toggleMcpWss() }) {
                    Text(
                        text = stringResource(
                            if (mcpWssState is McpWssUiState.Off) {
                                R.string.settings_mcp_wss_connect
                            } else {
                                R.string.settings_mcp_wss_disconnect
                            }
                        ),
                        color = palette.accent,
                    )
                }
            }

            SectionLabel(stringResource(R.string.settings_theme), palette.dim)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThemeId.ALL.forEach { id ->
                    val swatch = XiaozhiPalettes.getValue(id)
                    ThemeSwatch(
                        id = id,
                        color = swatch.bg,
                        selected = d.theme == id,
                        accent = palette.accent,
                        line = palette.line,
                        labelColor = swatch.text,
                        onClick = { vm.setTheme(id) },
                    )
                }
            }

            SectionLabel(stringResource(R.string.settings_identity), palette.dim)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.card)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IdentityLine("MAC", identity.mac, palette)
                IdentityLine("Serial", identity.serial, palette)
                IdentityLine("Client", identity.clientId.take(13) + "…", palette)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", color = palette.dim, fontSize = 13.sp)
                    Text(
                        text = stringResource(
                            if (identity.activated) {
                                R.string.settings_identity_status_activated
                            } else {
                                R.string.settings_identity_status_not
                            }
                        ),
                        color = if (identity.activated) palette.ok else palette.warn,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(vm.copyIdentity()))
                        copied = true
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            copied = false
                        }
                    }) {
                        Text(
                            stringResource(
                                if (copied) R.string.settings_identity_copied
                                else R.string.settings_copy_identity
                            ),
                            color = palette.accent,
                        )
                    }
                    OutlinedButton(onClick = { vm.resetIdentity() }) {
                        Text(stringResource(R.string.settings_reset_identity), color = palette.danger)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.settings_import_identity),
                    color = palette.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text("{…}", color = palette.dim, fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    colors = fieldColors(palette),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = palette.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                )
                TextButton(onClick = {
                    if (importText.isNotBlank()) {
                        val ok = vm.importIdentity(importText)
                        importMsg = if (ok) "Identity imported. Reconnect to use it." else "Import failed"
                        if (ok) importText = ""
                    }
                }, enabled = importText.isNotBlank()) {
                    Text(stringResource(R.string.settings_import_identity), color = palette.accent)
                }
                if (importMsg.isNotEmpty()) {
                    Text(importMsg, color = palette.dim, fontSize = 12.sp)
                }
            }

            SectionLabel(stringResource(R.string.settings_tool_activity), palette.dim)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.card)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (toolActivity.isEmpty()) {
                    Text(stringResource(R.string.settings_tool_empty), color = palette.dim, fontSize = 13.sp)
                } else {
                    toolActivity.takeLast(8).forEach { item ->
                        Text(
                            methodLabel(item),
                            color = palette.text,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            if (message != null) {
                Text(message!!, color = palette.dim, fontSize = 12.sp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_cancel), color = palette.text)
                }
                Button(
                    onClick = {
                        vm.save(d)
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_save), color = palette.bg)
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    placeholder: String,
    value: String,
    palette: com.xiaozhi.client.ui.theme.XiaozhiPalette,
    onValue: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = palette.dim, fontSize = 13.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            placeholder = { Text(placeholder, color = palette.dim, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = fieldColors(palette),
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.text, fontSize = 14.sp),
        )
    }
}

@Composable
private fun fieldColors(palette: com.xiaozhi.client.ui.theme.XiaozhiPalette) =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.accent,
        unfocusedBorderColor = palette.line,
        focusedTextColor = palette.text,
        unfocusedTextColor = palette.text,
        cursorColor = palette.accent,
        focusedPlaceholderColor = palette.dim,
        unfocusedPlaceholderColor = palette.dim,
    )

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun IdentityLine(label: String, value: String, palette: com.xiaozhi.client.ui.theme.XiaozhiPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = palette.dim, fontSize = 13.sp)
        Text(value, color = palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ThemeSwatch(
    id: ThemeId,
    color: Color,
    labelColor: Color,
    selected: Boolean,
    accent: Color,
    line: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color)
                .border(width = if (selected) 3.dp else 1.dp, color = if (selected) accent else line, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(id.label, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
        Text(id.label, color = if (selected) accent else line, fontSize = 11.sp)
    }
}

private fun methodLabel(json: String): String {
    val marker = "\"method\":"
    val i = json.indexOf(marker)
    if (i < 0) return json
    val start = i + marker.length
    val end = json.indexOf('"', start + 1)
    if (end < 0) return json
    return json.substring(start + 1, end)
}