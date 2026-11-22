package dev.dav.socksforward.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.checkbox.MaterialCheckBox
import dev.dav.socksforward.R
import dev.dav.socksforward.SocksForwardApp
import dev.dav.socksforward.core.model.AuthCredentials
import dev.dav.socksforward.core.model.ProxyConfig
import dev.dav.socksforward.data.SettingsRepository
import dev.dav.socksforward.databinding.ActivitySettingsBinding
import dev.dav.socksforward.proxy.ProxyPhase
import dev.dav.socksforward.util.NetworkAddresses
import dev.dav.socksforward.util.formatCount

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository

    /** One row per concrete address; the wildcard row is kept apart. */
    private data class BindRow(
        val address: String,
        val checkbox: MaterialCheckBox,
    )

    private val bindRows = mutableListOf<BindRow>()
    private var wildcardCheck: MaterialCheckBox? = null

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

        buildBindList(config.bindAddresses.toSet())
        binding.portInput.setText(formatCount(config.port))
        binding.maxConnectionsInput.setText(formatCount(config.maxConnections))
        binding.idleTimeoutInput.setText(formatCount(config.idleTimeoutMs / 60_000))

        val authEnabled = settings.isAuthEnabled()
        binding.authSwitch.isChecked = authEnabled
        binding.authFields.isVisible = authEnabled
        binding.usernameInput.setText(settings.username())
        binding.passwordInput.setText(settings.password())
    }

    /**
     * Rebuilds the checkbox list: loopback, every address of every active
     * interface, saved-but-currently-absent addresses (so a network change
     * does not silently drop the user's choice) and the wildcard row.
     */
    private fun buildBindList(selected: Set<String>) {
        binding.bindList.removeAllViews()
        bindRows.clear()
        wildcardCheck = null

        val available = NetworkAddresses.localIpv4Detailed()

        addBindRow(ProxyConfig.LOOPBACK_IPV4, getString(R.string.bind_loopback), selected)
        available
            .filterNot { it.address == ProxyConfig.LOOPBACK_IPV4 }
            .forEach { addBindRow(it.address, "${it.address} · ${it.interfaceName}", selected) }
        selected
            .filterNot {
                it == ProxyConfig.WILDCARD_IPV4 ||
                    it == ProxyConfig.LOOPBACK_IPV4 ||
                    available.any { local -> local.address == it }
            }
            .forEach { addBindRow(it, getString(R.string.bind_unavailable, it), selected) }

        val wildcard = addBindRow(
            ProxyConfig.WILDCARD_IPV4,
            getString(R.string.bind_all),
            selected,
            concrete = false,
        )
        wildcardCheck = wildcard.checkbox
        wildcard.checkbox.setOnCheckedChangeListener { _, checked ->
            // The wildcard socket covers every address, so specific rows make
            // no sense next to it (and would collide with it).
            bindRows.forEach { row ->
                row.checkbox.isEnabled = !checked
                if (checked) row.checkbox.isChecked = false
            }
        }
        if (wildcard.checkbox.isChecked) {
            // The listener only fires on change; mirror a migrated wildcard
            // selection into the initial row state.
            bindRows.forEach { it.checkbox.isEnabled = false }
        }
    }

    private fun addBindRow(
        address: String,
        label: String,
        selected: Set<String>,
        concrete: Boolean = true,
    ): BindRow {
        val checkbox = MaterialCheckBox(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            text = label
            isChecked = address in selected
        }
        binding.bindList.addView(checkbox)
        val row = BindRow(address, checkbox)
        if (concrete) bindRows += row
        return row
    }

    private fun selectedAddresses(): List<String> =
        if (wildcardCheck?.isChecked == true) {
            listOf(ProxyConfig.WILDCARD_IPV4)
        } else {
            bindRows.filter { it.checkbox.isChecked }.map { it.address }
        }

    private fun save() {
        binding.portLayout.error = null
        binding.maxConnectionsLayout.error = null
        binding.idleTimeoutLayout.error = null
        binding.usernameLayout.error = null
        binding.passwordLayout.error = null

        val bindAddresses = selectedAddresses()
        if (bindAddresses.isEmpty()) {
            Toast.makeText(this, R.string.error_bind_empty, Toast.LENGTH_LONG).show()
            return
        }

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

        val config = ProxyConfig(
            bindAddresses = bindAddresses,
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
