# CLAUDE.md

Android app (Kotlin, Jetpack Compose, NDK) that shows ARMED / DISARMED / UNKNOWN from ArduPilot MAVLink HEARTBEATs on the Skydroid H12's `/dev/ttyHS1`. See README.md for usage.

## Commands
- Env: `source .env` first (sets `JAVA_HOME` and the proxy).
- Tests: `./gradlew :app:testDebugUnitTest`
- Build: `./gradlew :app:assembleDebug`
- Device-free run: `python3 tools/fake_fc.py` and `adb reverse tcp:5762 tcp:5762`, then start the app. It falls back to TCP.

## Rules
- **Write only arm/disarm and set-mode.** The only things ever sent are the user-initiated `MAV_CMD_COMPONENT_ARM_DISARM` (confirm dialog) and `MAV_CMD_DO_SET_MODE` (ArduCopter mode picker, applies on tap by the user's choice) COMMAND_LONGs (`ArmCommand.kt`, built with mavlink-kotlin). Modes are Copter-only (gated on the heartbeat MAV_TYPE) and limited to `CopterMode.selectable`. Never add other commands or any unprompted write. The port opens `O_RDWR`, falling back to `O_RDONLY` if that is denied. TCP is writable too (SITL accepts commands), but `termux/h12_bridge.py` and `tools/fake_fc.py` are receive-only and drop them, so on those the command just times out. Force-disarm (param2 21196) needs its own second confirmation.
- `HeartbeatScanner.kt` and `serial.c` are ports of `h12_mavlink_heartbeat.py`. Keep their behaviour identical to the script: accepted frames, CRC extra 50, the 3 s UNKNOWN rule, and the termios settings.
- Source order in `HeartbeatMonitor`: direct `/dev/ttyHS1`, then `127.0.0.1:5762` (fed by `termux/h12_bridge.py`).
- `targetSdk` is 28 on purpose, to match Termux's permissions. Don't raise it.
- AGP 9 compiles Kotlin itself. Don't apply `org.jetbrains.kotlin.android`.
- Test vectors in `HeartbeatScannerTest` come from the script's `crc_x25`. Regenerate them from the script, don't hand-edit them.

## Open questions (need the real H12)
- Does the direct open work, or only the Termux bridge?
- `minSdk` (24) and ABI filters are guesses until `ro.build.version.release` and `ro.product.cpu.abi` are known.
