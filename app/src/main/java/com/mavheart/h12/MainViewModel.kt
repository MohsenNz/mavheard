package com.mavheart.h12

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

class MainViewModel : ViewModel() {
    private val monitor = HeartbeatMonitor(viewModelScope)
    val state = monitor.state
    private var resumeOnForeground = false

    fun start() { resumeOnForeground = true; monitor.start() }
    fun stop() { resumeOnForeground = false; monitor.stop() }

    /** App left the foreground: release the port, but remember to resume. */
    fun onBackground() { if (monitor.state.value.running) { monitor.stop(); resumeOnForeground = true } }
    fun onForeground() { if (resumeOnForeground) monitor.start() }

    override fun onCleared() = monitor.stop()
}
