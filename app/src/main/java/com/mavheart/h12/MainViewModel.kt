package com.mavheart.h12

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

class MainViewModel : ViewModel() {
    private val monitor = HeartbeatMonitor(viewModelScope)
    val state = monitor.state
    private var resumeOnForeground = false

    fun start() { resumeOnForeground = true; monitor.start() }
    /** Sends arm/disarm (user already confirmed). Returns false if not currently allowed. */
    fun requestArm(arm: Boolean, force: Boolean = false) = monitor.requestArm(arm, force)

    /** Switches the Copter's flight mode (applies at once; no confirm). False if not allowed. */
    fun requestMode(modeId: Int) = monitor.requestMode(modeId)

    fun stop() { resumeOnForeground = false; monitor.stop() }

    /** App left the foreground: release the port, but remember to resume. */
    fun onBackground() { if (monitor.state.value.running) { monitor.stop(); resumeOnForeground = true } }
    fun onForeground() { if (resumeOnForeground) monitor.start() }

    override fun onCleared() = monitor.stop()
}
