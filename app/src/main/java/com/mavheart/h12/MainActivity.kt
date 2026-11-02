package com.mavheart.h12

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val state by vm.state.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
                LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
                val view = LocalView.current
                DisposableEffect(state.running) {
                    view.keepScreenOn = state.running
                    onDispose { view.keepScreenOn = false }
                }
                HeartbeatScreen(state, onToggle = { if (state.running) vm.stop() else vm.start() })
            }
        }
    }
}

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Grey = Color(0xFF546E7A)

@Composable
fun HeartbeatScreen(s: UiState, onToggle: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).statusBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (label, color) = when (s.link) {
                LinkState.ARMED -> "ARMED" to Red
                LinkState.DISARMED -> "DISARMED" to Green
                LinkState.UNKNOWN -> (if (s.running) "UNKNOWN" else "STOPPED") to Grey
            }
            Box(
                Modifier.fillMaxWidth().height(160.dp)
                    .background(color, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (s.link == LinkState.UNKNOWN && s.running)
                        Text("no recent heartbeat", color = Color.White.copy(alpha = .8f))
                }
            }

            s.error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            Button(onClick = onToggle, Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (s.running) "Stop" else "Start", fontSize = 18.sp)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row2("Source", s.sourceDescription)
                Row2("System / component", if (s.sysid != null) "${s.sysid} / ${s.compid}" else null)
                Row2("MAVLink", s.mavlinkVersion?.let { "v$it" })
                Row2("base_mode", s.baseMode?.let { "0x%02X".format(it) })
                Row2("custom_mode", s.customMode?.toString())
                Row2("Raw bytes", s.rawBytes.toString())
                Row2("ArduPilot heartbeats", s.heartbeats.toString())
                Row2("First bytes", s.firstBytesHex)
            }
        }
    }
}

@Composable
private fun Row2(name: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value ?: "—", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f, fill = false))
    }
}
