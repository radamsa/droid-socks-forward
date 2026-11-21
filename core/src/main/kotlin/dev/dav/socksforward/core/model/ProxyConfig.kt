package dev.dav.socksforward.core.model

/**
 * Everything needed to run the proxy. Immutable snapshot: changing settings
 * requires a restart, which keeps the listening socket and its options
 * consistent for the whole lifetime of the server.
 */
data class ProxyConfig(
    val bindAddress: String = DEFAULT_BIND_ADDRESS,
    val port: Int = DEFAULT_PORT,
    val auth: AuthCredentials? = null,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    val handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val idleTimeoutMs: Int = DEFAULT_IDLE_TIMEOUT_MS,
) {
    val isWildcard: Boolean
        get() = bindAddress == WILDCARD_IPV4 || bindAddress == WILDCARD_IPV6

    /** Human readable, non-fatal validation problems. Empty when the config is usable. */
    fun validationErrors(): List<String> = buildList {
        if (port !in MIN_PORT..MAX_PORT) {
            add("Порт должен быть от $MIN_PORT до $MAX_PORT")
        }
        if (!isValidListenAddress(bindAddress)) {
            add("Некорректный IP-адрес для прослушивания")
        }
        if (maxConnections !in 1..MAX_CONNECTIONS_LIMIT) {
            add("Лимит соединений: от 1 до $MAX_CONNECTIONS_LIMIT")
        }
        if (handshakeTimeoutMs < 1_000) add("Таймаут рукопожатия слишком мал")
        if (connectTimeoutMs < 1_000) add("Таймаут подключения слишком мал")
        if (idleTimeoutMs < 30_000) add("Таймаут простоя должен быть не меньше 30 секунд")
        if (auth != null) {
            if (auth.username.isEmpty() || auth.username.length > AuthCredentials.MAX_FIELD_LENGTH) {
                add("Логин должен быть от 1 до ${AuthCredentials.MAX_FIELD_LENGTH} символов")
            }
            if (auth.password.isEmpty() || auth.password.length > AuthCredentials.MAX_FIELD_LENGTH) {
                add("Пароль должен быть от 1 до ${AuthCredentials.MAX_FIELD_LENGTH} символов")
            }
        }
    }

    companion object {
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val DEFAULT_PORT = 1080
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val DEFAULT_MAX_CONNECTIONS = 32
        const val MAX_CONNECTIONS_LIMIT = 256
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60_000

        const val WILDCARD_IPV4 = "0.0.0.0"
        const val WILDCARD_IPV6 = "::"
        const val LOOPBACK_IPV4 = "127.0.0.1"

        fun isValidListenAddress(value: String): Boolean {
            if (value == WILDCARD_IPV4 || value == WILDCARD_IPV6) return true
            if (value.contains(':')) return isValidIpv6(value)
            return isValidIpv4(value)
        }

        private fun isValidIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                    part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        private fun isValidIpv6(value: String): Boolean {
            if (value.isEmpty() || value.contains(":::")) return false
            val parts = value.split("::")
            if (parts.size > 2) return false
            val compressed = parts.size == 2
            val groups = parts.flatMap { if (it.isEmpty()) emptyList() else it.split(':') }
            if (groups.any { it.isEmpty() }) return false

            var total = 0
            for ((index, group) in groups.withIndex()) {
                if (group.contains('.')) {
                    // Embedded IPv4 must be the last group and counts as two groups.
                    if (index != groups.lastIndex || !isValidIpv4(group)) return false
                    total += 2
                } else {
                    if (group.length !in 1..4) return false
                    if (!group.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
                    total += 1
                }
            }
            return if (compressed) total < 8 else total == 8
        }
    }
}
