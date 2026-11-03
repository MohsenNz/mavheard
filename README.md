# H12 Heartbeat

Android app for the Skydroid H12 that shows whether an ArduPilot flight controller is **ARMED**, **DISARMED** or **UNKNOWN**. It reads MAVLink HEARTBEATs from the H12's internal UART (`/dev/ttyHS1`, 921600 8N1). It can also arm and disarm: tap the status box and confirm. That is the only thing it ever writes to the port, over the direct serial link or the TCP port. The Termux bridge and `tools/fake_fc.py` drop it, so there the command shows a timeout. An ArduPilot simulator on `127.0.0.1:5762` (SITL) accepts it.

It is the Android version of `h12_mavlink_heartbeat.py`, which does the same in Termux.

## How it gets the data
1. **Direct:** the app opens `/dev/ttyHS1` itself through a small native (NDK) library.
2. **Fallback:** if the open fails, it connects to `127.0.0.1:5762`. Run `termux/h12_bridge.py` in Termux to feed that port.

The Source row in the app shows which one is in use. Any error text appears in a banner.

The status is UNKNOWN when no valid ArduPilot heartbeat has arrived for 3 seconds.

## Build
Needs JDK 21 and the Android SDK, with NDK 29 and CMake 3.22.1.

```sh
source .env                              # JAVA_HOME / proxy, if you use it
./gradlew :app:testDebugUnitTest         # unit tests
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Testing without the H12
```sh
python3 tools/fake_fc.py                 # fake flight controller on 127.0.0.1:5762
adb reverse tcp:5762 tcp:5762            # phone or emulator reaches the PC
```
Open the app and tap Start. It falls back to TCP and the status switches between DISARMED, ARMED and UNKNOWN.

## Status
Tested on the Pixel 8 and an API 29 emulator with the fake flight controller. **Not yet tested on the H12**, so it is unknown whether the direct open works. If it fails, use the Termux bridge and check the node's permissions with `adb shell ls -lZ /dev/ttyHS1`.

`minSdk` (24) and the ABI filters in `app/build.gradle.kts` should be set from `adb shell getprop ro.build.version.release` and `ro.product.cpu.abi` on the H12.

## Layout
| Path | Purpose |
|---|---|
| `app/src/main/cpp/serial.c` | serial open (read-write, read-only fallback), read, write and close (JNI) |
| `app/src/main/java/com/mavheart/h12/` | `HeartbeatScanner`, `HeartbeatMonitor`, `ByteSource` implementations, Compose UI |
| `app/src/test/` | JVM unit tests |
| `termux/h12_bridge.py` | Termux fallback that forwards the serial port to TCP |
| `tools/fake_fc.py` | fake flight controller for device-free testing |
| `h12_mavlink_heartbeat.py` | original Termux script |
