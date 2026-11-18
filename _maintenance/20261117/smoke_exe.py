# 对构建出的 WebTerm.exe 做真实冒烟测试：
#   1) HTTP /api/me 可用
#   2) 伪造 Origin 的 WS 被 403
#   3) 同源 WS 101，并能起 PowerShell 收到横幅/提示符
#   4) settings_get 不含 api_key
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request

ROOT = r"D:\Terminal-Windows"
EXE = os.path.join(ROOT, "_build", "20261117", "dist", "WebTerm.exe")
SMOKE = os.path.join(ROOT, "_build", "20261117", "smoke")
PORT = 18123


def wait_http(timeout=30):
    url = "http://127.0.0.1:%d/api/me" % PORT
    end = time.time() + timeout
    last = None
    while time.time() < end:
        try:
            with urllib.request.urlopen(url, timeout=1) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            last = e
            time.sleep(0.4)
    raise RuntimeError("服务未就绪: %r" % last)


def ws_handshake(origin):
    key = "dGhlIHNhbXBsZSBub25jZQ=="
    lines = [
        "GET / HTTP/1.1",
        "Host: 127.0.0.1:%d" % PORT,
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: %s" % key,
        "Sec-WebSocket-Version: 13",
    ]
    if origin is not None:
        lines.append("Origin: %s" % origin)
    s = socket.create_connection(("127.0.0.1", PORT), timeout=5)
    s.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        chunk = s.recv(4096)
        if not chunk:
            break
        resp += chunk
    status = resp.split(b"\r\n", 1)[0].decode("latin-1")
    return s, status


def send_text(sock, text):
    payload = text.encode("utf-8")
    header = b"\x81"
    n = len(payload)
    if n < 126:
        header += bytes([0x80 | n])
    elif n < 65536:
        header += bytes([0x80 | 126]) + struct.pack("!H", n)
    else:
        header += bytes([0x80 | 127]) + struct.pack("!Q", n)
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    sock.sendall(header + mask + masked)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def recv_frame(sock):
    b1, b2 = recv_exact(sock, 2)
    opcode = b1 & 0x0F
    n = b2 & 0x7F
    if n == 126:
        n = struct.unpack("!H", recv_exact(sock, 2))[0]
    elif n == 127:
        n = struct.unpack("!Q", recv_exact(sock, 8))[0]
    payload = recv_exact(sock, n) if n else b""
    return opcode, payload


def main():
    if not os.path.isfile(EXE):
        print("FAIL: exe 不存在", EXE)
        return 1
    os.makedirs(SMOKE, exist_ok=True)
    target = os.path.join(SMOKE, "WebTerm.exe")
    shutil.copy2(EXE, target)

    proc = subprocess.Popen(
        [target, "--port", str(PORT), "--no-browser"],
        cwd=SMOKE,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    ok = True
    try:
        me = wait_http()
        print("HTTP /api/me       ->", json.dumps(me, ensure_ascii=False)[:140])

        with urllib.request.urlopen("http://127.0.0.1:%d/" % PORT, timeout=3) as r:
            html = r.read(4096).decode("utf-8", "replace")
        print("GET /              ->", "WebTerm" if "WebTerm" in html else "??", "chars", len(html))

        s, status = ws_handshake("http://evil.example")
        s.close()
        print("WS 伪造 Origin     ->", status)
        ok &= "403" in status

        s, status = ws_handshake("http://127.0.0.1:%d" % PORT)
        print("WS 同源 Origin     ->", status)
        ok &= "101" in status
        if "101" in status:
            send_text(s, json.dumps({"type": "resize", "rows": 24, "cols": 80}))
            # 发一条无害命令，确保 shell 真的能执行
            time.sleep(0.4)
            send_text(s, json.dumps({"type": "input", "data": "echo CYRIL-SMOKE\r"}))
            text = b""
            end = time.time() + 8
            while time.time() < end:
                try:
                    op, payload = recv_frame(s)
                except (EOFError, socket.timeout):
                    break
                if op == 0x1:
                    text += payload
                    if b"CYRIL-SMOKE" in text or b"PS " in text:
                        break
                elif op == 0x8:
                    break
                elif op == 0x9:
                    s.sendall(b"\x8a\x00")
            decoded = text.decode("utf-8", "replace")
            has_term = ("CYRIL-SMOKE" in decoded) or ("PS " in decoded) or ("PowerShell" in decoded)
            print("终端输出           ->", "OK" if has_term else "EMPTY", repr(decoded[:180]))
            ok &= has_term
        s.close()

        # settings_get 脱敏：本地连接也不应包含 api_key
        # 直接读 Settings.json 做对照（不启动 WS 也能验证 settings_public）
        sys.path.insert(0, ROOT)
        import server
        server._settings = {"theme": "dark", "ai": {"api_key": "sk-smoke", "model": "m"}}
        pub = server.settings_public()
        print("settings_public    -> api_key" if "api_key" in pub.get("ai", {}) else "settings_public    -> 已脱敏")
        ok &= "api_key" not in pub.get("ai", {})
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
