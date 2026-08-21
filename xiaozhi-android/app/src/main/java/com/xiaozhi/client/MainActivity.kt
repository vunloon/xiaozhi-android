package com.xiaozhi.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaozhi.client.core.settings.AppSettings
import com.xiaozhi.client.service.VoiceService
import com.xiaozhi.client.ui.nav.XiaozhiNavGraph
import com.xiaozhi.client.ui.theme.XiaozhiPalettes
import com.xiaozhi.client.ui.theme.XiaozhiTheme

class MainActivity : ComponentActivity() {

    private val requestMicLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result observed via re-check */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        ensureMicPermission()

        setContent {
            val container = (application as XiaozhiApp).container
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            XiaozhiTheme(palette = XiaozhiPalettes.getValue(settings.theme)) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    XiaozhiNavGraph(container = container)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The foreground service keeps mic capture + the WSS socket alive while
        // the user navigates away. We start it once the user has interacted.
        if (hasMicPermission()) {
            startVoiceService()
        }
    }

    private fun ensureMicPermission() {
        if (!hasMicPermission()) {
            requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
