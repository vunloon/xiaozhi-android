package com.xiaozhi.client

import android.app.Application
import android.content.Context
import com.xiaozhi.client.audio.OpusCodec
import com.xiaozhi.client.core.activation.ActivationManager
import com.xiaozhi.client.core.activation.OtaClient
import com.xiaozhi.client.core.identity.EfuseStore
import com.xiaozhi.client.core.protocol.SessionStore
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.state.ChatStateMachine
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
    val otaClient: OtaClient by lazy { OtaClient(context, sharedHttpClient) }
    val activationManager: ActivationManager by lazy {
        ActivationManager(otaClient, efuseStore, appScope)
    }
    val sessionStore: SessionStore by lazy { SessionStore() }
    val thingManager: ThingManager by lazy { ThingManager() }
    val protocol: WebSocketProtocol by lazy {
        WebSocketProtocol(
            context = context,
            efuseStore = efuseStore,
            sessionStore = sessionStore,
            thingManager = thingManager,
            httpClient = sharedHttpClient,
        )
    }
    val chatStateMachine: ChatStateMachine by lazy {
        ChatStateMachine(protocol = protocol, scope = appScope)
    }
}
