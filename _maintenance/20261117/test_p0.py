# P0 回归测试：Origin 校验 + 配置脱敏（不依赖 pywinpty，不启动真实 shell）
import base64
import os
import socket
import sys
import threading
import time

sys.path.insert(0, r"D:\Terminal-Windows")
import server


class FakeSession:
    """只接收帧、不真的起 PTY：用于握手测试"""
    def __init__(self, conn):
        self.conn = conn
        self.fd = None

    def spawn(self, rows=24, cols=80):
        pass

    def write(self, data):
        pass

    def resize(self, rows, cols):
        pass

    def close(self):
        pass


def ws_handshake(port, origin=None):
    key = base64.b64encode(os.urandom(16)).decode()
    lines = [
        "GET / HTTP/1.1",
        "Host: 127.0.0.1:%d" % port,
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: %s" % key,
        "Sec-WebSocket-Version: 13",
    ]
    if origin is not None:
        lines.append("Origin: %s" % origin)
    raw = ("\r\n".join(lines) + "\r\n\r\n").encode()
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    s.sendall(raw)
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(4096)
        if not chunk:
            break
        resp += chunk
    status = resp.split(b"\r\n", 1)[0].decode("latin-1")
    s.close()
    return status


def main():
    # 强制走假会话，避免测试环境没有 pywinpty
    server.WinSession = FakeSession
    server.TOKEN = None

    srv = server.http.server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    srv.daemon_threads = True
    srv.remote_mode = False
    port = srv.server_address[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    time.sleep(0.2)

    results = []
    results.append(("伪造 Origin", ws_handshake(port, "http://evil.example"), 403))
    results.append(("同源 Origin", ws_handshake(port, "http://127.0.0.1:%d" % port), 101))
    results.append(("无 Origin（本机 CLI）", ws_handshake(port, None), 101))
    results.append(("Origin: null", ws_handshake(port, "null"), 403))

    ok = True
    for name, status, expect in results:
        good = (" %d " % expect) in (status + " ")
        ok = ok and good
        print("%-18s -> %-24s expect %s  %s" % (name, status, expect, "PASS" if good else "FAIL"))

    # 配置脱敏
    server._settings = {"theme": "dark", "ai": {"api_key": "sk-secret", "model": "m", "enabled": True}}
    pub = server.settings_public()
    good = ("api_key" not in pub["ai"]) and pub["ai"].get("has_key") is True and pub["theme"] == "dark"
    ok = ok and good
    print("settings_public        -> api_key 已剥离 / has_key=True  %s" % ("PASS" if good else "FAIL"))

    kept, dropped = server.settings_remote_filter({
        "theme": "light", "ai": {"api_key": "evil"}, "port": 9999, "remote": True
    })
    good = kept == {"theme": "light"} and set(dropped) == {"ai", "port", "remote"}
    ok = ok and good
    print("settings_remote_filter -> 丢弃 ai/port/remote  %s" % ("PASS" if good else "FAIL"))

    srv.shutdown()
    srv.server_close()
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
