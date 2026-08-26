package com.xiaozhi.client

import android.app.Application
import android.content.Context
import com.xiaozhi.client.audio.OpusCodec
import com.xiaozhi.client.core.activation.ActivationManager
import com.xiaozhi.client.core.activation.OtaClient
import com.xiaozhi.client.core.identity.EfuseStore
import com.xiaozhi.client.core.protocol.SessionStore
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.core.settings.SettingsStore
import com.xiaozhi.client.mcp.McpDispatcher
import com.xiaozhi.client.mcp.ToolRegistry
import com.xiaozhi.client.state.ChatStateMachine
import com.xiaozhi.client.tools.KlseTools
import com.xiaozhi.client.tools.MusicTools
import com.xiaozhi.client.tools.NewsTools
import com.xiaozhi.client.tools.WatchlistTools
import com.xiaozhi.client.iot.ThingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Application class. Acts as a manual DI container (no Hilt — keeps the build
 * fast and avoids kapt complexity).
 *
 * All singletons live here for the lifetime of the process. UI/view-models reach
 * them through [container] (from a CompositionLocal) or via AndroidService
 * onAttachedToApplication when run from a service.
 */
class XiaozhiApp : Application() {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this, appScope)
        OpusCodec.init()
    }

    companion object {
        @Volatile private var instance: XiaozhiApp? = null

        fun get(context: Context): XiaozhiApp =
            instance ?: (context.applicationContext as XiaozhiApp)
    }
}

/**
 * Holds process-wide singletons. Built once at process start.
 */
class AppContainer(
    private val context: Context,
    val appScope: CoroutineScope,
) {
    private val sharedHttpClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }
        builder.build()
    }

    val efuseStore: EfuseStore by lazy { EfuseStore(context) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val otaClient: OtaClient by lazy { OtaClient(context, sharedHttpClient) }
    val activationManager: ActivationManager by lazy {
        ActivationManager(otaClient, efuseStore, appScope)
    }
    val sessionStore: SessionStore by lazy { SessionStore() }
    val thingManager: ThingManager by lazy { ThingManager() }

    /** In-app MCP tools (news/klse/watchlist/music), shared by both channels. */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry().apply {
            registerAll(*NewsTools().tools().toTypedArray())
            registerAll(*KlseTools().tools().toTypedArray())
            registerAll(*WatchlistTools(context).tools().toTypedArray())
            registerAll(*MusicTools().tools().toTypedArray())
        }
    }
    val mcpDispatcher: McpDispatcher by lazy { McpDispatcher(toolRegistry) }

    val protocol: WebSocketProtocol by lazy {
        WebSocketProtocol(
            context = context,
            efuseStore = efuseStore,
            sessionStore = sessionStore,
            thingManager = thingManager,
            httpClient = sharedHttpClient,
            mcpDispatcher = mcpDispatcher,
        )
    }
    val chatStateMachine: ChatStateMachine by lazy {
        ChatStateMachine(protocol = protocol, scope = appScope)
    }
}
