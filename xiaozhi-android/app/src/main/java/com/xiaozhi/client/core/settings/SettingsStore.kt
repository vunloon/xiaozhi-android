package com.xiaozhi.client.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.client.ui.theme.ThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xiaozhi_settings")

/**
 * Persistent app settings. Backed by DataStore.
 *
 * The voice-session WebSocket URL/token are NOT stored here — they are
 * fetched automatically from the OTA endpoint at connect time
 * (see [com.xiaozhi.client.core.activation.ActivationManager]).
 */
data class AppSettings(
    val theme: ThemeId = ThemeId.DARK,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            theme = ThemeId.fromWire(prefs[KEY_THEME] ?: ThemeId.DARK.wire),
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.first())
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = next.theme.wire
        }
    }

    /** Read the current value once. */
    suspend fun first(): AppSettings = settings.first()

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme")
    }
}
