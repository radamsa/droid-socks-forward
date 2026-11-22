package dev.dav.socksforward.core

import dev.dav.socksforward.core.model.ProxyStats

/** Callbacks are invoked from background threads; implementations must be thread safe. */
interface ProxyListener {
    fun onStarted(listenAddresses: List<String>, listenPort: Int) = Unit

    fun onStats(stats: ProxyStats) = Unit

    /** Called once when the server stops. [error] is null for a normal stop. */
    fun onStopped(error: String?) = Unit
}
