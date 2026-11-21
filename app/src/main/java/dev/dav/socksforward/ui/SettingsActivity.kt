package dev.dav.socksforward.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import dev.dav.socksforward.R
import dev.dav.socksforward.SocksForwardApp
import dev.dav.socksforward.core.model.AuthCredentials
import dev.dav.socksforward.core.model.ProxyConfig
import dev.dav.socksforward.data.SettingsRepository
import dev.dav.socksforward.databinding.ActivitySettingsBinding
import dev.dav.socksforward.proxy.ProxyPhase
import dev.dav.socksforward.util.formatCount

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        settings = (application as SocksForwardApp).settings

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.authSwitch.setOnCheckedChangeListener { _, checked ->
            binding.authFields.isVisible = checked
        }
        binding.saveButton.setOnClickListener { save() }

        load()
    }

    private fun load() {
        val config = settings.load()

        binding.bindGroup.check(
            if (config.isWildcard) R.id.bind_all else R.id.bind_local,
        )
        binding.portInput.setText(formatCount(config.port))
        binding.maxConnectionsInput.setText(formatCount(config.maxConnections))
        binding.idleTimeoutInput.setText(formatCount(config.idleTimeoutMs / 60_000))

        val authEnabled = settings.isAuthEnabled()
        binding.authSwitch.isChecked = authEnabled
        binding.authFields.isVisible = authEnabled
        binding.usernameInput.setText(settings.username())
        binding.passwordInput.setText(settings.password())
    }

    private fun save() {
        binding.portLayout.error = null
        binding.maxConnectionsLayout.error = null
        binding.idleTimeoutLayout.error = null
        binding.usernameLayout.error = null
        binding.passwordLayout.error = null

        val port = binding.portInput.text?.toString()?.trim()?.toIntOrNull()
        if (port == null || port !in ProxyConfig.MIN_PORT..ProxyConfig.MAX_PORT) {
            binding.portLayout.error = getString(R.string.error_port)
            return
        }

        val maxConnections = binding.maxConnectionsInput.text?.toString()?.trim()?.toIntOrNull()
        if (maxConnections == null || maxConnections !in 1..ProxyConfig.MAX_CONNECTIONS_LIMIT) {
            binding.maxConnectionsLayout.error =
                getString(R.string.error_max_connections, ProxyConfig.MAX_CONNECTIONS_LIMIT)
            return
        }

        val idleMinutes = binding.idleTimeoutInput.text?.toString()?.trim()?.toIntOrNull()
        if (idleMinutes == null || idleMinutes !in 1..1440) {
            binding.idleTimeoutLayout.error = getString(R.string.error_idle_timeout)
            return
        }

        val authEnabled = binding.authSwitch.isChecked
        val username = binding.usernameInput.text?.toString().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        var auth: AuthCredentials? = null
        if (authEnabled) {
            auth = runCatching { AuthCredentials(username, password) }.getOrNull()
            if (auth == null) {
                binding.usernameLayout.error = getString(R.string.error_credentials)
                binding.passwordLayout.error = getString(R.string.error_credentials)
                return
            }
        }

        val bindAddress = if (binding.bindGroup.checkedRadioButtonId == R.id.bind_all) {
            ProxyConfig.WILDCARD_IPV4
        } else {
            ProxyConfig.LOOPBACK_IPV4
        }

        val config = ProxyConfig(
            bindAddress = bindAddress,
            port = port,
            auth = auth,
            maxConnections = maxConnections,
            idleTimeoutMs = idleMinutes * 60_000,
        )
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            Toast.makeText(this, errors.first(), Toast.LENGTH_LONG).show()
            return
        }

        settings.save(config, authEnabled, username, password)

        val running = (application as SocksForwardApp).proxyManager.state.value.phase
        val message = if (running == ProxyPhase.RUNNING || running == ProxyPhase.STARTING) {
            R.string.saved_restart
        } else {
            R.string.saved
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
