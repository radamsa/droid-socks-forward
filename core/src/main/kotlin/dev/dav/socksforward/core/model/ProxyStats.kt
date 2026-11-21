package dev.dav.socksforward.core.model

enum class ConnectionState {
    CONNECTING,
    ACTIVE,
    FAILED,
}

/** Snapshot of a single proxied connection, safe to hand to the UI. */
data class ConnectionInfo(
    val id: Long,
    val clientAddress: String,
    val target: String,
    val state: ConnectionState,
    val startedAt: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val error: String?,
)

/**
 * Cumulative counters plus the list of live connections.
 *
 * [bytesUp] is client -> target, [bytesDown] is target -> client.
 * Counters are reset every time the server starts.
 */
data class ProxyStats(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val totalConnections: Long = 0,
    val failedConnections: Long = 0,
    val startedAt: Long = 0,
    val connections: List<ConnectionInfo> = emptyList(),
) {
    val activeConnections: Int get() = connections.size
}
