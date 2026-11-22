package dev.dav.socksforward.data

import android.content.Context
import androidx.core.content.edit
import dev.dav.socksforward.core.model.AuthCredentials
import dev.dav.socksforward.core.model.ProxyConfig

/**
 * Small SharedPreferences wrapper. Values are validated on the way in and on
 * the way out, so a corrupted preference file can never break startup.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ProxyConfig {
        val bindAddresses = loadBindAddresses()

        val port = prefs.getInt(KEY_PORT, ProxyConfig.DEFAULT_PORT)
            .takeIf { it in ProxyConfig.MIN_PORT..ProxyConfig.MAX_PORT }
            ?: ProxyConfig.DEFAULT_PORT

        val auth = if (isAuthEnabled()) {
            runCatching { AuthCredentials(username(), password()) }.getOrNull()
        } else {
            null
        }

        val maxConnections = prefs.getInt(KEY_MAX_CONNECTIONS, ProxyConfig.DEFAULT_MAX_CONNECTIONS)
            .coerceIn(1, ProxyConfig.MAX_CONNECTIONS_LIMIT)

        val idleMinutes = prefs.getInt(KEY_IDLE_MINUTES, DEFAULT_IDLE_MINUTES)
            .coerceIn(MIN_IDLE_MINUTES, MAX_IDLE_MINUTES)

        return ProxyConfig(
            bindAddresses = bindAddresses,
            port = port,
            auth = auth,
            maxConnections = maxConnections,
            idleTimeoutMs = idleMinutes * 60_000,
        )
    }

    /**
     * Checked addresses from the checkbox list. Falls back to the pre-checkbox
     * single-address preference, so an update keeps the user's choice.
     */
    private fun loadBindAddresses(): List<String> {
        val stored = prefs.getStringSet(KEY_BIND_ADDRESSES, null)
            ?: prefs.getString(KEY_BIND_ADDRESS, null)
                ?.takeIf(ProxyConfig::isValidListenAddress)
                ?.let { setOf(it) }
            ?: emptySet()
        return stored
            .filter(ProxyConfig::isValidListenAddress)
            .distinct()
            .ifEmpty { listOf(ProxyConfig.DEFAULT_BIND_ADDRESS) }
    }

    fun save(config: ProxyConfig, authEnabled: Boolean, username: String, password: String) {
        prefs.edit {
            putStringSet(KEY_BIND_ADDRESSES, config.bindAddresses.toSet())
            remove(KEY_BIND_ADDRESS)
            putInt(KEY_PORT, config.port)
            putBoolean(KEY_AUTH_ENABLED, authEnabled)
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password)
            putInt(KEY_MAX_CONNECTIONS, config.maxConnections)
            putInt(KEY_IDLE_MINUTES, config.idleTimeoutMs / 60_000)
        }
    }

    fun isAuthEnabled(): Boolean = prefs.getBoolean(KEY_AUTH_ENABLED, false)

    fun username(): String = prefs.getString(KEY_USERNAME, "").orEmpty()

    fun password(): String = prefs.getString(KEY_PASSWORD, "").orEmpty()

    companion object {
        private const val PREFS_NAME = "socks_forward_settings"
        private const val KEY_BIND_ADDRESS = "bind_address"
        private const val KEY_BIND_ADDRESSES = "bind_addresses"
        private const val KEY_PORT = "port"
        private const val KEY_AUTH_ENABLED = "auth_enabled"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_MAX_CONNECTIONS = "max_connections"
        private const val KEY_IDLE_MINUTES = "idle_minutes"

        private const val DEFAULT_IDLE_MINUTES = 5
        private const val MIN_IDLE_MINUTES = 1
        private const val MAX_IDLE_MINUTES = 1440
    }
}
