package com.mavheart.h12

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
                HeartbeatScreen(
                    state,
                    onToggle = { if (state.running) vm.stop() else vm.start() },
                    onArm = { arm, force -> vm.requestArm(arm, force) },
                    onMode = { vm.requestMode(it) },
                )
            }
        }
    }
}

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val Grey = Color(0xFF546E7A)

@Composable
fun HeartbeatScreen(
    s: UiState,
    onToggle: () -> Unit,
    onArm: (arm: Boolean, force: Boolean) -> Unit = { _, _ -> },
    onMode: (Int) -> Unit = {},
) {
    var dialog by remember { mutableStateOf<ArmDialog?>(null) }
    // Close the dialog if the command is no longer allowed (e.g. heartbeat lost) while it is open.
    LaunchedEffect(s.canCommand) { if (!s.canCommand) dialog = null }
    dialog?.let { d ->
        ArmConfirmDialog(d, onDismiss = { dialog = null }, onChoose = { d2 ->
            when (d2) {
                ArmDialog.Arm -> { dialog = null; onArm(true, false) }
                ArmDialog.Disarm -> { dialog = null; onArm(false, false) }
                ArmDialog.ForceDisarm -> { dialog = null; onArm(false, true) }
                ArmDialog.ConfirmForce -> dialog = ArmDialog.ForceDisarm
            }
        })
    }
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
                    .background(color, RoundedCornerShape(16.dp))
                    .clickable(enabled = s.canCommand) {
                        dialog = if (s.link == LinkState.ARMED) ArmDialog.Disarm else ArmDialog.Arm
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    if (s.link == LinkState.UNKNOWN && s.running)
                        Text("no recent heartbeat", color = Color.White.copy(alpha = .8f))
                    else if (s.canCommand)
                        Text("tap to ${if (s.link == LinkState.ARMED) "disarm" else "arm"}",
                            color = Color.White.copy(alpha = .8f))
                }
            }

            s.modeName?.let { name -> ModeSelector(name, s.customMode!!.toInt(), s.canCommand, onMode) }

            val status = s.commandResult?.let { commandText(it) } ?: s.commandBlockedReason
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

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

private enum class ArmDialog { Arm, Disarm, ConfirmForce, ForceDisarm }

private fun commandText(r: CommandResult) = when (r) {
    CommandResult.Sending -> "Sending command…"
    is CommandResult.Accepted -> r.text
    is CommandResult.Rejected -> "Command rejected: ${r.reason}"
    CommandResult.Timeout -> "No reply from the flight controller (timeout)"
    is CommandResult.Failed -> "Send failed: ${r.message}"
}

@Composable
private fun ArmConfirmDialog(d: ArmDialog, onDismiss: () -> Unit, onChoose: (ArmDialog) -> Unit) {
    when (d) {
        ArmDialog.Arm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Arm motors?") },
            text = { Text("The vehicle will arm. Make sure it is safe to do so.") },
            confirmButton = { TextButton(onClick = { onChoose(ArmDialog.Arm) }) { Text("Arm") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        ArmDialog.Disarm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Disarm?") },
            text = { Text("ArduPilot refuses a normal disarm while flying.") },
            confirmButton = { TextButton(onClick = { onChoose(ArmDialog.Disarm) }) { Text("Disarm") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChoose(ArmDialog.ConfirmForce) }) {
                        Text("Force disarm…", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
        ArmDialog.ConfirmForce, ArmDialog.ForceDisarm -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("FORCE disarm?", color = MaterialTheme.colorScheme.error) },
            text = { Text("This skips safety checks and kills the motors immediately, even in flight. The vehicle can fall.") },
            confirmButton = {
                TextButton(onClick = { onChoose(ArmDialog.ForceDisarm) }) {
                    Text("Force disarm", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** Current flight mode; tappable (when commands are allowed) to pick another one. Applies at once. */
@Composable
private fun ModeSelector(name: String, currentId: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) open = false }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Mode: $name" + if (enabled) "  ▾" else "", fontSize = 18.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CopterMode.selectableModes.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label + if (m.id == currentId) "  ✓" else "") },
                    onClick = { open = false; onPick(m.id) },
                )
            }
        }
    }
}
