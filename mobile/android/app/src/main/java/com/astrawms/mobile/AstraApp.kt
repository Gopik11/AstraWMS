package com.astrawms.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.astrawms.mobile.auth.AuthManager
import com.astrawms.mobile.core.api.AstraApi
import com.astrawms.mobile.core.offline.CommandQueue
import com.astrawms.mobile.rfid.ReaderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the screens share: settings, sign-in, the API, the offline queue and the RFID reader. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SettingsStore(app)
    val auth = AuthManager(app, settings)
    val api = AstraApi(baseUrl = { settings.current.gatewayUrl }, token = { auth.accessToken() })
    val queue = CommandQueue(
        store = PrefsStore(app),
        post = { c -> api.send("POST", c.path, c.body, c.idempotencyKey) },
        flagConflict = { path, detail -> api.syncConflict(path, detail) },
    )
    val readers = ReaderManager(app, settings, scope)
}

class AstraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // ADR-0023: waiting RF commands go out when the network is back, and every 30 s while some wait.
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                container.scope.launch { container.queue.sync() }
            }
        })
        container.scope.launch {
            while (true) {
                delay(30_000)
                if (container.queue.pending.isNotEmpty()) container.queue.sync()
            }
        }
    }
}
