package com.mavheart.h12.proxy

import android.content.Context

const val DEFAULT_UDP_PORT = 14555

data class ProxySettings(
    val udpEnabled: Boolean = false,
    /** Local UDP port, bound on all interfaces (0.0.0.0). */
    val localPort: Int = DEFAULT_UDP_PORT,
    /** Optional "host:port" of a peer to send to (client mode). Blank = none. */
    val remote: String = "",
    /** Whether datagrams from UDP peers are written to the vehicle. Off = receive-only proxy. */
    val allowClientsToSend: Boolean = true,
) {
    val isValid: Boolean get() = localPort in 1..65535 && (remote.isBlank() || parseHostPort(remote) != null)
}

/** "host:port" -> (host, port), or null if malformed. */
fun parseHostPort(s: String): Pair<String, Int>? {
    val t = s.trim()
    val idx = t.lastIndexOf(':')
    if (idx <= 0 || idx == t.length - 1) return null
    val host = t.substring(0, idx).trim()
    val port = t.substring(idx + 1).trim().toIntOrNull() ?: return null
    if (host.isEmpty() || port !in 1..65535) return null
    return host to port
}

class ProxySettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("proxy", Context.MODE_PRIVATE)

    fun load() = ProxySettings(
        udpEnabled = prefs.getBoolean("udpEnabled", false),
        localPort = prefs.getInt("localPort", DEFAULT_UDP_PORT),
        remote = prefs.getString("remote", "") ?: "",
        allowClientsToSend = prefs.getBoolean("allowClientsToSend", true),
    )

    fun save(s: ProxySettings) {
        prefs.edit()
            .putBoolean("udpEnabled", s.udpEnabled).putInt("localPort", s.localPort)
            .putString("remote", s.remote).putBoolean("allowClientsToSend", s.allowClientsToSend)
            .apply()
    }
}
