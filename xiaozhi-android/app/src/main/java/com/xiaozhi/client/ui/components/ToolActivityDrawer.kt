package com.xiaozhi.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.R
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Sliding drawer that shows MCP tool-call activity forwarded by the cloud from
 * your remote xiaozhi-wss MCP server.
 *
 * Plan 1 has no in-app tools — the events shown here are read-only traces
 * of what your *remote* MCP server is doing in response to voice prompts.
 * The drawer is for transparency/debugging.
 *
 * Each event is rendered as a single JSON payload line. The list is in
 * arrival order, newest at the bottom, capped at 50 entries.
 */
@Composable
fun ToolActivityDrawer(
    container: AppContainer,
    onClose: () -> Unit,
) {
    val events = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()

    // Subscribe to MCP events for the lifetime of the drawer.
    androidx.compose.runtime.LaunchedEffect(container) {
        container.protocol.mcpEvents.collect { payload ->
            events.add(payload.toString())
            if (events.size > MAX_EVENTS) events.removeAt(0)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.tool_drawer_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close drawer")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.tool_drawer_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(events) { ev -> EventRow(ev) }
            }
        }
    }
}

@Composable
private fun EventRow(payload: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
    ) {
        Text(
            text = payload,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val MAX_EVENTS = 50
