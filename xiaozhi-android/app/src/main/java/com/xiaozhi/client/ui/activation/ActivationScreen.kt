package com.xiaozhi.client.ui.activation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.R
import com.xiaozhi.client.core.activation.ActivationManager
import com.xiaozhi.client.core.activation.ActivationState

@Composable
fun ActivationScreen(
    activationManager: ActivationManager,
    efuseStore: com.xiaozhi.client.core.identity.EfuseStore,
    onActivated: () -> Unit,
) {
    val state by activationManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Kick off activation once at first composition.
    LaunchedEffect(Unit) {
        if (activationManager.state.value is ActivationState.Idle) {
            activationManager.start()
        }
    }

    // Auto-advance when activation completes.
    LaunchedEffect(state) {
        if (state is ActivationState.Activated) onActivated()
    }

    ActivationContent(
        state = state,
        mac = remember { efuseStore.macAddress },
        serial = remember { efuseStore.serialNumber },
        onRetry = { activationManager.start() },
        onCopyCode = { code -> copyToClipboard(context, code) },
        onOpenSite = { url -> openInBrowser(context, url) },
    )
}

@Composable
private fun ActivationContent(
    state: ActivationState,
    mac: String,
    serial: String,
    onRetry: () -> Unit,
    onCopyCode: (String) -> Unit,
    onOpenSite: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader()
        Spacer(Modifier.height(24.dp))

        when (state) {
            ActivationState.Idle,
            is ActivationState.Connecting -> ConnectingView()
            is ActivationState.Pending -> PendingView(
                state = state,
                onCopyCode = onCopyCode,
                onOpenSite = onOpenSite,
                mac = mac,
                serial = serial,
            )
            ActivationState.Activated -> ActivatedView()
            is ActivationState.Failed -> FailedView(state.reason, onRetry)
        }
    }
}

@Composable
private fun BrandHeader() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Devices,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(40.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.activation_title),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun ConnectingView() {
    CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 3.dp,
        modifier = Modifier.size(40.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.activation_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PendingView(
    state: ActivationState.Pending,
    onCopyCode: (String) -> Unit,
    onOpenSite: (String) -> Unit,
    mac: String,
    serial: String,
) {
    Text(
        text = stringResource(R.string.activation_instructions),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))

    CodeCard(code = state.code, onCopy = { onCopyCode(state.code) })

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = { onOpenSite(state.authUrl) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        Icon(Icons.Outlined.OpenInNew, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.activation_open_site))
    }

    Spacer(Modifier.height(20.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = stringResource(R.string.activation_waiting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }

    Spacer(Modifier.height(36.dp))

    DeviceInfoBlock(mac = mac, serial = serial)

    state.message?.takeIf { it.isNotBlank() }?.let { msg ->
        Spacer(Modifier.height(16.dp))
        Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.7f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CodeCard(code: String, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.activation_code_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = code,
            style = MaterialTheme.typography.displayMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 6.sp,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.activation_copy_code))
        }
    }
}

@Composable
private fun DeviceInfoBlock(mac: String, serial: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InfoRow(label = "MAC", value = mac)
        InfoRow(label = "SN", value = serial)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActivatedView() {
    Icon(
        imageVector = Icons.Outlined.Devices,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.activation_success),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun FailedView(reason: String, onRetry: () -> Unit) {
    Text(
        text = stringResource(R.string.activation_failed, reason),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    OutlinedButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        Icon(Icons.Outlined.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.activation_retry))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clip = ClipData.newPlainText("xiaozhi activation code", text)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(clip)
}

private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
