#!/usr/bin/env python3
"""Fake flight controller for device-free testing. Serves ArduPilot HEARTBEATs on TCP 127.0.0.1:5762.

  python3 tools/fake_fc.py            # toggles ARMED/DISARMED every 10 s, goes silent 5 s every 30 s
  adb reverse tcp:5762 tcp:5762       # so the emulator/phone app sees it as 127.0.0.1:5762
"""
import socket, struct, time

def crc_x25(data):
    crc = 0xFFFF
    for b in data:
        t = b ^ (crc & 0xFF); t ^= (t << 4) & 0xFF
        crc = ((crc >> 8) ^ (t << 8) ^ (t << 3) ^ (t >> 4)) & 0xFFFF
    return crc

def heartbeat(seq, armed):
    pl = struct.pack("<IBBBBB", 5, 2, 3, 0x81 if armed else 0x01, 4, 3)
    body = bytes([9, 0, 0, seq & 0xFF, 1, 1, 0, 0, 0]) + pl
    return b"\xfd" + body + struct.pack("<H", crc_x25(body + b"\x32"))

srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 5762)); srv.listen(1)
print("fake FC on 127.0.0.1:5762")
while True:
    c, _ = srv.accept(); print("client connected"); t0 = time.time(); seq = 0
    try:
        while True:
            e = time.time() - t0
            if e % 30 < 25:  # silent for the last 5 s of each 30 s
                c.sendall(b"\x00\x01" + heartbeat(seq, int(e // 10) % 2 == 1)); seq += 1
            time.sleep(1)
    except OSError:
        c.close()
