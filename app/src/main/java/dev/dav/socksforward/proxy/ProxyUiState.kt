package dev.dav.socksforward.proxy

import dev.dav.socksforward.core.model.ProxyConfig
import dev.dav.socksforward.core.model.ProxyStats

enum class ProxyPhase {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

data class ProxyUiState(
    val phase: ProxyPhase = ProxyPhase.STOPPED,
    val config: ProxyConfig = ProxyConfig(),
    val listenAddress: String? = null,
    val listenPort: Int? = null,
    val stats: ProxyStats? = null,
    val error: String? = null,
)
