package dev.dav.socksforward

import android.app.Application
import dev.dav.socksforward.data.SettingsRepository
import dev.dav.socksforward.proxy.ProxyManager

class SocksForwardApp : Application() {

    lateinit var settings: SettingsRepository
        private set

    lateinit var proxyManager: ProxyManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        proxyManager = ProxyManager(this, settings)
    }
}
