package dev.dav.socksforward.proxy

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.dav.socksforward.core.model.ProxyStats
import dev.dav.socksforward.data.SettingsRepository
import dev.dav.socksforward.service.ProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process wide source of truth for the UI. The service writes into it, the
 * activity observes it. Survives activity recreation, dies with the process.
 */
class ProxyManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow(ProxyUiState(config = settings.load()))
    val state: StateFlow<ProxyUiState> = _state.asStateFlow()

    fun refreshConfig() {
        _state.update { current ->
            if (current.phase == ProxyPhase.STOPPED || current.phase == ProxyPhase.ERROR) {
                current.copy(config = settings.load())
            } else {
                current
            }
        }
    }

    fun start() {
        val current = _state.value
        if (current.phase == ProxyPhase.STARTING || current.phase == ProxyPhase.RUNNING) return

        val config = settings.load()
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) {
            _state.update { it.copy(phase = ProxyPhase.ERROR, error = errors.first()) }
            return
        }

        _state.update {
            it.copy(
                phase = ProxyPhase.STARTING,
                config = config,
                error = null,
                stats = null,
                listenAddresses = emptyList(),
                listenPort = null,
            )
        }
        try {
            ContextCompat.startForegroundService(
                context,
                ProxyService.startIntent(context),
            )
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    phase = ProxyPhase.ERROR,
                    error = error.message ?: "Не удалось запустить сервис",
                )
            }
        }
    }

    fun stop() {
        val stopped = context.stopService(Intent(context, ProxyService::class.java))
        if (stopped) {
            // onDestroy reports the final state as well, but a service that is
            // still starting would otherwise keep the UI in STARTING forever.
            _state.update {
                it.copy(
                    phase = ProxyPhase.STOPPED,
                    stats = null,
                    listenAddresses = emptyList(),
                    listenPort = null,
                    error = null,
                )
            }
        }
    }

    fun onStarted(listenAddresses: List<String>, listenPort: Int) {
        _state.update {
            it.copy(
                phase = ProxyPhase.RUNNING,
                listenAddresses = listenAddresses,
                listenPort = listenPort,
                error = null,
            )
        }
    }

    fun onStats(stats: ProxyStats) {
        _state.update {
            it.copy(
                stats = stats,
                phase = if (it.phase == ProxyPhase.STARTING) ProxyPhase.RUNNING else it.phase,
            )
        }
    }

    fun onStopped(error: String?) {
        _state.update {
            it.copy(
                phase = if (error == null) ProxyPhase.STOPPED else ProxyPhase.ERROR,
                stats = null,
                listenAddresses = emptyList(),
                listenPort = null,
                error = error,
            )
        }
    }

    fun onStartFailed(message: String) {
        _state.update { it.copy(phase = ProxyPhase.ERROR, error = message, stats = null) }
    }
}
