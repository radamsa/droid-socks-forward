package dev.dav.socksforward.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import dev.dav.socksforward.SocksForwardApp
import dev.dav.socksforward.core.ProxyListener
import dev.dav.socksforward.core.Socks5ProxyServer
import dev.dav.socksforward.core.model.ProxyStats

/**
 * Foreground service that owns the listening socket. The proxy must survive
 * the activity being closed, so everything lives here and the activity only
 * observes [SocksForwardApp.proxyManager].
 */
class ProxyService : Service(), ProxyListener {

    private val app: SocksForwardApp
        get() = application as SocksForwardApp

    private lateinit var notifications: ProxyNotifications

    private var engine: Socks5ProxyServer? = null
    private var listenPort: Int = 0
    private var lastNotificationAt: Long = 0

    override fun onCreate() {
        super.onCreate()
        notifications = ProxyNotifications(this)
        notifications.ensureChannel()
        startAsForeground(notifications.buildStarting())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> startProxy()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine?.stop()
        engine = null
        notifications.cancel()
        super.onDestroy()
    }

    private fun startProxy() {
        if (engine != null) return

        val config = app.settings.load()
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            failStart(errors.first())
            return
        }

        val server = Socks5ProxyServer(config, this)
        server.start()
            .onSuccess { engine = server }
            .onFailure { failStart(it.message ?: "Не удалось запустить прокси") }
    }

    private fun failStart(message: String) {
        app.proxyManager.onStartFailed(message)
        stopSelf()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                ProxyNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(ProxyNotifications.NOTIFICATION_ID, notification)
        }
    }

    override fun onStarted(listenAddress: String, listenPort: Int) {
        this.listenPort = listenPort
        app.proxyManager.onStarted(listenAddress, listenPort)
        notifications.post(notifications.buildRunning(listenPort, ProxyStats()))
    }

    override fun onStats(stats: ProxyStats) {
        app.proxyManager.onStats(stats)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
            lastNotificationAt = now
            notifications.post(notifications.buildRunning(listenPort, stats))
        }
    }

    override fun onStopped(error: String?) {
        app.proxyManager.onStopped(error)
    }

    companion object {
        const val ACTION_START = "dev.dav.socksforward.action.START"
        const val ACTION_STOP = "dev.dav.socksforward.action.STOP"

        private const val NOTIFICATION_INTERVAL_MS = 1_000L

        fun startIntent(context: Context): Intent =
            Intent(context, ProxyService::class.java).setAction(ACTION_START)
    }
}
