package com.mavheart.h12

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mavheart.h12.proxy.HubState
import com.mavheart.h12.proxy.SerialHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The serial port is owned by [SerialHub] (kept alive by `ProxyService`). This ViewModel only runs
 * the heartbeat monitor while the hub is running and the screen is visible.
 */
class MainViewModel : ViewModel() {
    private val hub = SerialHub.instance
    private val monitor = HeartbeatMonitor(viewModelScope)
    private val foreground = MutableStateFlow(false)

    /** Monitor state, with `running` and `error` taken from the hub (the service) as well. */
    val state: StateFlow<UiState> = combine(monitor.state, hub.state) { m, h ->
        m.copy(running = h.running, error = m.error ?: h.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    val hubState: StateFlow<HubState> = hub.state

    init {
        viewModelScope.launch {
            combine(hub.state.map { it.running }, foreground) { running, fg -> running && fg }
                .distinctUntilChanged()
                .collect { if (it) monitor.start() else monitor.stop() }
        }
    }

    /** Sends arm/disarm (user already confirmed). Returns false if not currently allowed. */
    fun requestArm(arm: Boolean, force: Boolean = false) = monitor.requestArm(arm, force)

    /** Switches the Copter's flight mode (applies at once; no confirm). False if not allowed. */
    fun requestMode(modeId: Int) = monitor.requestMode(modeId)

    /** The screen left the foreground: stop the monitor only. The service keeps the port open. */
    fun onBackground() { foreground.value = false }
    fun onForeground() { foreground.value = true }

    override fun onCleared() = monitor.stop()
}
