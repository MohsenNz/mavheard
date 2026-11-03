package com.mavheart.h12.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mavheart.h12.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the [SerialHub] (and, if enabled, the [UdpProxy]) alive in the
 * background. Start it again to re-apply changed [ProxySettings].
 */
class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var udp: UdpProxy? = null
    private var appliedSettings: ProxySettings? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notifyJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        acquireLocks()
        SerialHub.instance.start(scope)
        applySettings(ProxySettingsStore(this).load())
        if (notifyJob == null) {
            notifyJob = scope.launch {
                SerialHub.instance.state
                    .map { Triple(it.connected, it.sourceDescription, it.udpPeers to (it.error ?: it.udpError)) }
                    .distinctUntilChanged()
                    .collect { updateNotification() }
            }
        }
        return START_STICKY
    }

    private fun applySettings(s: ProxySettings) {
        if (s == appliedSettings && (udp != null || !s.udpEnabled)) return
        udp?.stop(); udp = null
        appliedSettings = s
        if (s.udpEnabled && s.isValid) {
            val p = UdpProxy(SerialHub.instance, s)
            try { p.start(scope); udp = p } catch (e: java.io.IOException) {
                SerialHub.instance.setUdpStatus(0, e.message)
            }
        }
        updateNotification()
    }

    private fun updateNotification() {
        val hub = SerialHub.instance.state.value
        val s = appliedSettings
        val text = buildString {
            append(if (hub.connected) hub.sourceDescription ?: "connected" else "serial not connected")
            if (s?.udpEnabled == true) {
                append(" · UDP :${s.localPort} on all interfaces · ${hub.udpPeers} peer(s)")
            }
            (hub.error ?: hub.udpError)?.let { append(" · ").append(it.lineSequence().first()) }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Serial / UDP proxy", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("H12 serial proxy running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "h12:proxy").apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = applicationContext.getSystemService(Context.WIFI_SERVICE).let { it as? WifiManager }
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "h12:proxy")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        udp?.stop(); udp = null
        SerialHub.instance.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.mavheart.h12.STOP_PROXY"

        /** Starts the service, or re-applies settings if it is already running. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}
