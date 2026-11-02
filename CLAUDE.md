# CLAUDE.md

Android app (Kotlin, Jetpack Compose, NDK) that shows ARMED / DISARMED / UNKNOWN from ArduPilot MAVLink HEARTBEATs on the Skydroid H12's `/dev/ttyHS1`. See README.md for usage.

## Commands
- Env: `source .env` first (sets `JAVA_HOME` and the proxy).
- Tests: `./gradlew :app:testDebugUnitTest`
- Build: `./gradlew :app:assembleDebug`
- Device-free run: `python3 tools/fake_fc.py` and `adb reverse tcp:5762 tcp:5762`, then start the app. It falls back to TCP.

## Rules
- **Read-only.** Never write to the serial port or send MAVLink commands. The port is opened `O_RDONLY`.
- `HeartbeatScanner.kt` and `serial.c` are ports of `h12_mavlink_heartbeat.py`. Keep their behaviour identical to the script: accepted frames, CRC extra 50, the 3 s UNKNOWN rule, and the termios settings.
- Source order in `HeartbeatMonitor`: direct `/dev/ttyHS1`, then `127.0.0.1:5762` (fed by `termux/h12_bridge.py`).
- `targetSdk` is 28 on purpose, to match Termux's permissions. Don't raise it.
- AGP 9 compiles Kotlin itself. Don't apply `org.jetbrains.kotlin.android`.
- Test vectors in `HeartbeatScannerTest` come from the script's `crc_x25`. Regenerate them from the script, don't hand-edit them.

## Open questions (need the real H12)
- Does the direct open work, or only the Termux bridge?
- `minSdk` (24) and ABI filters are guesses until `ro.build.version.release` and `ro.product.cpu.abi` are known.
