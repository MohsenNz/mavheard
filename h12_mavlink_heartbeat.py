#!/usr/bin/env python3
"""Read ArduPilot MAVLink HEARTBEAT from Skydroid H12 /dev/ttyHS1.

Termux / Python standard library only. Read-only: no MAVLink commands are sent.
"""
import os
import select
import termios
import time

PORT = "/dev/ttyHS1"
BAUD = 921600  # H12 Android internal UART; NOT the FC <-> MultiLink baud.
HEARTBEAT_CRC_EXTRA = 50
ARMED_FLAG = 0x80
ARDUPILOT_AUTOPILOT_ID = 3


def crc_x25(data):
    crc = 0xFFFF
    for byte in data:
        tmp = byte ^ (crc & 0xFF)
        tmp ^= (tmp << 4) & 0xFF
        crc = ((crc >> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >> 4)) & 0xFFFF
    return crc


class HeartbeatScanner:
    """Scan even if MAVLink packets are preceded by extra framing bytes."""
    def __init__(self):
        self.buffer = bytearray()

    def feed(self, chunk):
        self.buffer.extend(chunk)
        found = []
        i = 0
        while i < len(self.buffer):
            magic = self.buffer[i]
            if magic not in (0xFD, 0xFE):
                i += 1
                continue
            v2 = magic == 0xFD
            header_size = 10 if v2 else 6
            if len(self.buffer) - i < header_size:
                break  # Partial header: wait for more bytes.

            length = self.buffer[i + 1]
            msgid = (self.buffer[i + 7] | (self.buffer[i + 8] << 8)
                     | (self.buffer[i + 9] << 16)) if v2 else self.buffer[i + 5]
            if msgid != 0 or length != 9:  # Only standard HEARTBEAT frames.
                i += 1
                continue
            if v2 and self.buffer[i + 2] & ~0x01:
                i += 1  # Unknown incompatible MAVLink2 flags.
                continue

            signed = v2 and bool(self.buffer[i + 2] & 0x01)
            frame_size = header_size + length + 2 + (13 if signed else 0)
            if len(self.buffer) - i < frame_size:
                break  # Partial frame.

            frame = self.buffer[i:i + frame_size]
            crc_offset = header_size + length
            received_crc = int.from_bytes(frame[crc_offset:crc_offset + 2], "little")
            computed_crc = crc_x25(frame[1:crc_offset] + bytes([HEARTBEAT_CRC_EXTRA]))
            if received_crc != computed_crc:
                i += 1
                continue

            payload = frame[header_size:header_size + length]
            found.append({
                "version": 2 if v2 else 1,
                "sysid": frame[5] if v2 else frame[3],
                "compid": frame[6] if v2 else frame[4],
                "autopilot": payload[5],
                "armed": bool(payload[6] & ARMED_FLAG),
                "base_mode": payload[6],
                "custom_mode": int.from_bytes(payload[:4], "little"),
                "system_status": payload[7],
            })
            i += frame_size

        del self.buffer[:i]
        return found


def main():
    fd = None
    original = None
    scanner = HeartbeatScanner()
    received_bytes = 0
    hb_count = 0
    last_hb = None
    last_armed = None
    last_print = 0.0
    last_status = time.monotonic()
    try:
        fd = os.open(PORT, os.O_RDONLY | os.O_NONBLOCK | os.O_NOCTTY)
        original = termios.tcgetattr(fd)
        settings = termios.tcgetattr(fd)
        settings[0] = 0
        settings[1] = 0
        settings[2] = ((settings[2] & ~(termios.CSIZE | termios.PARENB |
                          termios.CSTOPB | getattr(termios, "CRTSCTS", 0))) |
                       termios.CS8 | termios.CREAD | termios.CLOCAL)
        settings[3] = 0
        settings[4] = termios.B921600
        settings[5] = termios.B921600
        termios.tcsetattr(fd, termios.TCSANOW, settings)
        print(f"[OPEN] {PORT}, {BAUD} baud, read-only", flush=True)
        print("Listening for ArduPilot HEARTBEAT. Ctrl+C to stop.\n", flush=True)

        while True:
            ready, _, _ = select.select([fd], [], [], 0.5)
            now = time.monotonic()
            if ready:
                try:
                    data = os.read(fd, 4096)
                except BlockingIOError:
                    data = b""
                if data:
                    if received_bytes == 0:
                        print(f"[RAW] First bytes: {data[:32].hex(' ')}", flush=True)
                    received_bytes += len(data)
                    for hb in scanner.feed(data):
                        if hb["autopilot"] != ARDUPILOT_AUTOPILOT_ID:
                            continue  # Ignore non-ArduPilot heartbeat sources.
                        hb_count += 1
                        last_hb = now
                        state = "ARMED" if hb["armed"] else "DISARMED"
                        if hb["armed"] != last_armed or now - last_print >= 3:
                            print(f"[HEARTBEAT #{hb_count}] {state} | "
                                  f"sys={hb['sysid']} comp={hb['compid']} "
                                  f"MAVLink{hb['version']} "
                                  f"base_mode=0x{hb['base_mode']:02X} "
                                  f"custom_mode={hb['custom_mode']}", flush=True)
                            last_print = now
                        last_armed = hb["armed"]

            if now - last_status >= 5:
                fresh = last_hb is not None and now - last_hb < 3
                state = ("ARMED" if last_armed else "DISARMED") if fresh else "UNKNOWN (no recent heartbeat)"
                print(f"[STATUS] {state} | raw_bytes={received_bytes} "
                      f"valid_ArduPilot_heartbeats={hb_count}", flush=True)
                last_status = now

    except KeyboardInterrupt:
        print("\nStopped.")
    except (OSError, AttributeError) as exc:
        print(f"[ERROR] {type(exc).__name__}: {exc}")
    finally:
        if fd is not None:
            if original is not None:
                try:
                    termios.tcsetattr(fd, termios.TCSANOW, original)
                except OSError:
                    pass
            os.close(fd)


if __name__ == "__main__":
    main()
