package com.xiaozhi.client.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.client.ui.theme.ThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xiaozhi_settings")

/**
 * Persistent app settings, mirroring the browser client's `Settings`
 * (`xiaozhi-web/src/core/types.ts`). Backed by DataStore.
 *
 * Any blank URL falls back to the protocol defaults at the point of use
 * (e.g. [com.xiaozhi.client.core.protocol.WebSocketProtocol.DEFAULT_URL]).
 */
data class AppSettings(
    val otaUrl: String = SettingsStore.DEFAULT_OTA_URL,
    val wsUrl: String = "",          // blank => protocol default (api.tenclass.net)
    val token: String = SettingsStore.DEFAULT_TOKEN,
    val visionBase: String = "",     // blank => vision tool disabled
    val mcpWssUrl: String = SettingsStore.DEFAULT_MCP_WSS_URL,
    val mcpWssEnabled: Boolean = false,
    val theme: ThemeId = ThemeId.DARK,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            otaUrl = prefs[KEY_OTA_URL] ?: DEFAULT_OTA_URL,
            wsUrl = prefs[KEY_WS_URL].orEmpty(),
            token = prefs[KEY_TOKEN] ?: DEFAULT_TOKEN,
            visionBase = prefs[KEY_VISION_BASE].orEmpty(),
            mcpWssUrl = prefs[KEY_MCP_WSS_URL] ?: DEFAULT_MCP_WSS_URL,
            mcpWssEnabled = prefs[KEY_MCP_WSS_ENABLED] ?: false,
            theme = ThemeId.fromWire(prefs[KEY_THEME] ?: ThemeId.DARK.wire),
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.first())
        context.dataStore.edit { prefs ->
            prefs[KEY_OTA_URL] = next.otaUrl
            prefs[KEY_WS_URL] = next.wsUrl
            prefs[KEY_TOKEN] = next.token
            prefs[KEY_VISION_BASE] = next.visionBase
            prefs[KEY_MCP_WSS_URL] = next.mcpWssUrl
            prefs[KEY_MCP_WSS_ENABLED] = next.mcpWssEnabled
            prefs[KEY_THEME] = next.theme.wire
        }
    }

    /** Read the current value once. */
    suspend fun first(): AppSettings = settings.first()

    companion object {
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        const val DEFAULT_TOKEN = "test-token"
        const val DEFAULT_MCP_WSS_URL = "wss://api.xiaozhi.me/mcp/?token="

        private val KEY_OTA_URL = stringPreferencesKey("ota_url")
        private val KEY_WS_URL = stringPreferencesKey("ws_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_VISION_BASE = stringPreferencesKey("vision_base")
        private val KEY_MCP_WSS_URL = stringPreferencesKey("mcp_wss_url")
        private val KEY_MCP_WSS_ENABLED = booleanPreferencesKey("mcp_wss_enabled")
        private val KEY_THEME = stringPreferencesKey("theme")
    }
}