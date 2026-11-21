package dev.dav.socksforward.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.dav.socksforward.SocksForwardApp
import dev.dav.socksforward.proxy.ProxyUiState
import kotlinx.coroutines.flow.StateFlow

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = (application as SocksForwardApp).proxyManager

    val state: StateFlow<ProxyUiState> = manager.state

    fun start() = manager.start()

    fun stop() = manager.stop()

    fun refreshConfig() = manager.refreshConfig()
}
