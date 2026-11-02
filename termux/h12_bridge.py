#!/usr/bin/env python3
"""Termux fallback: forward /dev/ttyHS1 (read-only) to 127.0.0.1:5762 for the H12 Heartbeat app.

Run in Termux (stdlib only):  python3 h12_bridge.py
The app connects to the TCP port when it cannot open the serial port itself.
Reuses the termios setup of h12_mavlink_heartbeat.py. Never writes to the serial port.
"""
import os, select, socket, termios

PORT = "/dev/ttyHS1"
TCP_PORT = 5762

def main():
    fd = os.open(PORT, os.O_RDONLY | os.O_NONBLOCK | os.O_NOCTTY)
    original = termios.tcgetattr(fd)
    s = termios.tcgetattr(fd)
    s[0] = 0
    s[1] = 0
    s[2] = ((s[2] & ~(termios.CSIZE | termios.PARENB | termios.CSTOPB |
                      getattr(termios, "CRTSCTS", 0))) | termios.CS8 | termios.CREAD | termios.CLOCAL)
    s[3] = 0
    s[4] = s[5] = termios.B921600
    termios.tcsetattr(fd, termios.TCSANOW, s)

    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", TCP_PORT))
    srv.listen(1)
    print(f"[BRIDGE] {PORT} -> 127.0.0.1:{TCP_PORT}. Ctrl+C to stop.", flush=True)
    try:
        while True:
            conn, _ = srv.accept()
            print("[BRIDGE] app connected", flush=True)
            try:
                while True:
                    if select.select([fd], [], [], 0.5)[0]:
                        try:
                            data = os.read(fd, 4096)
                        except BlockingIOError:
                            continue
                        if data:
                            conn.sendall(data)
            except OSError as exc:
                print(f"[BRIDGE] client gone: {exc}", flush=True)
            finally:
                conn.close()
    except KeyboardInterrupt:
        pass
    finally:
        termios.tcsetattr(fd, termios.TCSANOW, original)
        os.close(fd)

if __name__ == "__main__":
    main()
