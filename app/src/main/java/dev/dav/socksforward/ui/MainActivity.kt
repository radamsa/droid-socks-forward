package dev.dav.socksforward.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dev.dav.socksforward.R
import dev.dav.socksforward.core.model.ProxyConfig
import dev.dav.socksforward.databinding.ActivityMainBinding
import dev.dav.socksforward.proxy.ProxyPhase
import dev.dav.socksforward.proxy.ProxyUiState
import dev.dav.socksforward.util.NetworkAddresses
import dev.dav.socksforward.util.VpnStatus
import dev.dav.socksforward.util.formatBytes
import dev.dav.socksforward.util.formatCount
import dev.dav.socksforward.util.formatDuration
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ProxyViewModel by viewModels()
    private val connectionsAdapter = ConnectionsAdapter()

    private var pendingStart = false
    private var vpnCallbackRegistered = false

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { updateVpnHint() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { updateVpnHint() }
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        if (pendingStart) {
            pendingStart = false
            viewModel.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setSupportActionBar(binding.toolbar)

        binding.connectionsList.layoutManager = LinearLayoutManager(this)
        binding.connectionsList.adapter = connectionsAdapter
        binding.toggleButton.setOnClickListener { toggleProxy() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConfig()
        updateVpnHint()
    }

    override fun onStart() {
        super.onStart()
        registerVpnCallback()
        updateVpnHint()
    }

    override fun onStop() {
        unregisterVpnCallback()
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleProxy() {
        val phase = viewModel.state.value.phase
        if (phase == ProxyPhase.RUNNING || phase == ProxyPhase.STARTING) {
            viewModel.stop()
            return
        }
        if (needsNotificationPermission()) {
            pendingStart = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start()
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun registerVpnCallback() {
        if (vpnCallbackRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching { manager.registerNetworkCallback(request, vpnCallback) }
            .onSuccess { vpnCallbackRegistered = true }
    }

    private fun unregisterVpnCallback() {
        if (!vpnCallbackRegistered) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(vpnCallback) }
        vpnCallbackRegistered = false
    }

    private fun updateVpnHint() {
        if (!::binding.isInitialized) return
        val active = VpnStatus.isVpnActive(this)
        binding.vpnText.text = getString(if (active) R.string.vpn_active else R.string.vpn_inactive)
        binding.vpnText.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.status_running else R.color.status_idle),
        )
    }

    private fun render(state: ProxyUiState) {
        val running = state.phase == ProxyPhase.RUNNING
        val starting = state.phase == ProxyPhase.STARTING

        binding.statusText.text = when (state.phase) {
            ProxyPhase.STOPPED -> getString(R.string.status_stopped)
            ProxyPhase.STARTING -> getString(R.string.status_starting)
            ProxyPhase.RUNNING -> {
                val uptime = state.stats?.startedAt
                    ?.takeIf { it > 0 }
                    ?.let { System.currentTimeMillis() - it }
                if (uptime != null) {
                    getString(R.string.status_running_uptime, formatDuration(uptime))
                } else {
                    getString(R.string.status_running)
                }
            }

            ProxyPhase.ERROR -> getString(R.string.status_error)
        }
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                this,
                when (state.phase) {
                    ProxyPhase.RUNNING -> R.color.status_running
                    ProxyPhase.ERROR -> R.color.status_error
                    ProxyPhase.STARTING -> R.color.status_connecting
                    ProxyPhase.STOPPED -> R.color.status_idle
                },
            ),
        )

        binding.errorText.isVisible = state.phase == ProxyPhase.ERROR && !state.error.isNullOrBlank()
        binding.errorText.text = state.error.orEmpty()

        val port = state.listenPort
        binding.addressText.text = when {
            !running || port == null -> "—"

            // Wildcard: 0.0.0.0 is not a connectable address, so list every
            // address the device actually answers on.
            state.config.isWildcard -> NetworkAddresses.localIpv4()
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "$it:$port" }
                ?: "${ProxyConfig.WILDCARD_IPV4}:$port"

            // Concrete checkboxes: one line per bound address (loopback
            // included). Addresses that could not be bound are absent.
            else -> state.listenAddresses.ifEmpty { state.config.bindAddresses }
                .joinToString("\n") { "$it:$port" }
        }
        binding.vpnText.isVisible = running

        val stats = state.stats
        binding.downValue.text = formatBytes(stats?.bytesDown ?: 0)
        binding.upValue.text = formatBytes(stats?.bytesUp ?: 0)
        binding.activeValue.text = formatCount(stats?.activeConnections ?: 0)
        binding.totalValue.text = formatCount(stats?.totalConnections?.toInt() ?: 0)

        val connections = stats?.connections.orEmpty()
        connectionsAdapter.submitList(connections)
        binding.connectionsCount.text = formatCount(connections.size)
        binding.emptyView.isVisible = connections.isEmpty()

        binding.warningOpen.isVisible = state.config.isWildcard && state.config.auth == null

        binding.toggleButton.text = if (running || starting) {
            getString(R.string.stop)
        } else {
            getString(R.string.start)
        }
    }
}
