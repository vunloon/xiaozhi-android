package com.xiaozhi.client.iot

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Registry for IoT "Things" — smart-home-style devices the server can
 * introspect and control. Plan 1 ships with no Things registered, but the
 * manager still exists so that:
 *
 *   - The protocol layer has somewhere to forward incoming `iot.commands`
 *     messages without NPE risk.
 *   - The shape of the registration API is in place for Plan 2 / follow-up
 *     work that adds device bindings.
 *
 * Mirrors the structure of `py-xiaozhi/src/iot/thing_manager.py`, simplified
 * to the bare minimum we need for an empty registry.
 */
class ThingManager {

    private val things: Map<String, Thing> = emptyMap()

    /** Handles an incoming `{type:"iot", commands:[...]}` message. */
    fun handleCommands(
        commands: List<JsonObject>,
        onStateChanged: (List<JsonElement>) -> Unit,
    ) {
        if (commands.isEmpty()) return
        // We have no Things registered — log the unhandled command for
        // diagnostic purposes (helps catch mis-routed device configs).
        commands.forEach { cmd ->
            val name = cmd[KEY_NAME]?.toString()?.trim('"') ?: "<unnamed>"
            val method = cmd[KEY_METHOD]?.toString()?.trim('"') ?: "<no-method>"
            Log.i(TAG, "ignoring IoT command (no Things registered): name=$name method=$method")
        }
        // No state changes → no callback.
    }

    companion object {
        private const val TAG = "ThingManager"
        private const val KEY_NAME = "name"
        private const val KEY_METHOD = "method"
    }
}

/**
 * Stub interface for future IoT device implementations (Lamp, Switch, …).
 * Plan 1 has no implementations — see Plan 2.
 */
interface Thing {
    val name: String
    val description: String
    fun descriptor(): JsonObject
    fun snapshotState(): JsonObject
}
