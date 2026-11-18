#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WebTerm for Windows 后端 —— 纯 Python 标准库版（零第三方依赖）

- 不使用 websockets 库：手写 RFC6455 WebSocket 协议（http.server + socket）
- 同一端口：GET / 返回终端页面(web/index.html)，带 Upgrade 头的请求升级为 WebSocket
- Windows 会话：启动 PowerShell 通过管道交互（无 PTY，纯标准库 subprocess）
  * 支持常见 PowerShell 命令与管理任务；全屏 TUI 程序（python REPL / vim）不可用
- 资源路径支持 PyInstaller 打包（sys._MEIPASS）

运行（Windows）：
    python server.py                     # 仅本机访问：http://127.0.0.1:8765/
    python server.py --remote            # 开启远程：局域网内手机/其他设备可访问
    python server.py --port 9000         # 自定义端口（默认 8765）
    python server.py --remote --port 9000
    # 端口 / 局域网开关也可在网页【设置】里改（写入 Settings.json，重启生效）

打包 exe（Windows 上）：
    pip install pyinstaller
    pyinstaller -F -n WebTerm --add-data "web;web" server.py
"""
import base64
import collections
import hashlib
import hmac
import http.server
import json
import os
import re
import secrets
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

if os.name != "nt":
    import fcntl
    import pty
    import termios

HOST = os.environ.get("WEBTERM_HOST", "127.0.0.1")
PORT = int(os.environ.get("WEBTERM_PORT", "8765"))
SHELL = os.environ.get("WEBTERM_SHELL", os.environ.get("SHELL", "/bin/bash"))
TOKEN = os.environ.get("WEBTERM_TOKEN", "") or None

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

# 资源根目录：PyInstaller 打包后使用 _MEIPASS，源码运行时使用脚本所在目录
if getattr(sys, "frozen", False):
    _BASE = sys._MEIPASS
else:
    _BASE = os.path.dirname(os.path.abspath(__file__))
INDEX_PATH = os.path.join(_BASE, "web", "index.html")


def _lan_ip():
    """检测本机局域网 IP（UDP connect 只拿路由出口，不实际发包）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def _decode_console_bytes(raw):
    """解码 Windows 控制台程序的输出（netsh / ipconfig 等）。

    不能直接用 subprocess(text=True)：中文 Windows 上 Python 会按本地代码页（cp936/gbk）
    严格解码，而 netsh 的提示串里常混入非 GBK 字节（如 0xae，常见于 OEM 代码页），
    于是读线程抛 UnicodeDecodeError 并打印大段 traceback（功能不受影响但很难看）。
    这里按 UTF-8 → GBK → cp936 依次尝试，最后用 replace 兜底，永不抛异常。
    """
    if not raw:
        return ""
    if isinstance(raw, str):
        return raw
    for enc in ("utf-8", "gbk", "cp936"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("gbk", "replace")


def _try_firewall_rule(port):
    """--remote 时放行 Windows 防火墙入站（修复：源码能访问、打包 EXE 后局域网访问失败）。

    根因：源码跑的是 python.exe（防火墙可能已放行过）；
    打包成 WebTerm.exe 后是全新程序，Windows 防火墙默认拦截其入站连接，
    即使监听 0.0.0.0 局域网设备也无法访问。
    此处尝试自动添加放行规则（需要管理员权限）；失败则打印手动放行指引。
    """
    if os.name != "nt":
        return
    rule = "WebTerm Remote %d" % port
    exe = sys.executable if getattr(sys, "frozen", False) else None
    print("  Firewall: 正在尝试为 %s 放行防火墙入站..." % (exe or ("TCP 端口 %d" % port)))
    try:
        if exe:
            # EXE 场景：按程序放行（仅放行本 exe，最安全）
            r = subprocess.run(
                ["netsh", "advfirewall", "firewall", "add", "rule",
                 "name=%s" % rule, "dir=in", "action=allow",
                 "program=%s" % exe, "enable=yes", "profile=any"],
                capture_output=True, timeout=20,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        else:
            # 源码场景：按端口放行
            r = subprocess.run(
                ["netsh", "advfirewall", "firewall", "add", "rule",
                 "name=%s" % rule, "dir=in", "action=allow",
                 "localport=%d" % port, "protocol=TCP", "enable=yes", "profile=any"],
                capture_output=True, timeout=20,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        out = (_decode_console_bytes(r.stdout) + _decode_console_bytes(r.stderr)).strip()
        if r.returncode == 0:
            print("  Firewall: 已自动放行（%s）" % rule)
        else:
            # 非管理员权限时 netsh 拒绝，打印手动指引
            print("  Firewall: 自动放行未生效（需管理员权限）%s" % ("：" + out[-120:] if out else ""))
            print("  Firewall: 请以管理员身份运行一次，或在 控制面板→Windows Defender 防火墙→高级设置→入站规则 手动放行：")
            if exe:
                print("    · 新建规则 → 程序 → 指向 %s → 允许连接（专用+公用）" % exe)
            else:
                print("    · 新建规则 → 端口 → TCP %d → 允许连接（专用+公用）" % port)
            print("    或管理员 PowerShell 执行：")
            if exe:
                print("      netsh advfirewall firewall add rule name=\"%s\" dir=in action=allow program=\"%s\" profile=any" % (rule, exe))
            else:
                print("      netsh advfirewall firewall add rule name=\"%s\" dir=in action=allow localport=%d protocol=TCP profile=any" % (rule, port))
    except Exception as exc:
        print("  Firewall: 自动放行调用失败（%r）——请手动添加入站规则后重试" % exc)
    # 无论是否放行，都给出访问检查提示
    print("  Firewall: 若设备仍无法访问，请确认：①同一局域网/同一网段 ②路由器未开 AP 隔离 ③本机未开 VPN 虚拟网卡抢占流量")


# ---------------- 前端页面 ----------------
# 注意：不做启动时快照。每次请求实时读取 index.html，
# 覆盖 web/index.html 后无需重启 server，刷新即可看到最新页面。
def _load_index():
    try:
        with open(INDEX_PATH, "rb") as f:
            return f.read()
    except OSError:
        return b"<html><body><h1>web/index.html not found</h1></body></html>"


# ---------------- 终端内建指令（音乐 / AI）----------------
# 为什么绕一圈用 PowerShell 命令、而不是前端拦按键：
#   前端拦 Enter 就必须自己「擦掉用户已经敲进去的那行、再重画提示符」，
#   但 PTY 那条线的光标在哪，前端根本不知道，擦行/重画极易跟 PowerShell 抢光标
#   （表现为错位、多出空行、提示符被吃掉）。改成下面这条路就绕开了全部时序问题：
#   PowerShell 里预置 Cyril-Start / Cyril-Stop / Cyril-Ai / Cyril-Music 四个命令（外加
#   play / stop / ai / music 短别名），命令只打印一行标记
#   「__CYRIL__:名字 参数」；前端（index.html 的 feedTerm）截获这行、执行真正的动作，
#   再把这一行原地改写成结果。于是「回显 / 换行 / 提示符」全由 PowerShell 自己产生，
#   ↑ 调历史、Tab 补全、Ctrl+C 也照常可用，用户看到的形态和普通命令完全一致。
#
# 关于命名：主命令统一带 Cyril- 前缀（Cyril-Start / Cyril-Stop / Cyril-Ai / Cyril-Music），
#   这样**绝不占用任何现有命令名**，`start` 仍然是 Start-Process，不受影响。
#   另外给几个短别名 play / stop / ai / music —— 这四个名字在 PowerShell 里本来就没有
#   同名别名或内置命令（`stop` 不是 Stop-Process 的别名，`spps` 才是），所以安全。
#   `start` 故意**不做别名**：它是 Start-Process 的 ReadOnly + AllScope 别名，
#   删不掉也改不了（不带 -Option AllScope 的 Set-Alias 会抛「无法从别名 start 中删除
#   AllScope 选项」），硬覆盖就得破坏 Start-Process，不值当。
#   要播放就写 Cyril-Start 或 play。
TERM_CMDS_PS = (
    "function global:Cyril-Start{'__CYRIL__:start ' + ($args -join ' ')};"
    "function global:Cyril-Stop{'__CYRIL__:stop ' + ($args -join ' ')};"
    "function global:Cyril-Continue{'__CYRIL__:continue ' + ($args -join ' ')};"
    "function global:Cyril-Ai{'__CYRIL__:ai ' + ($args -join ' ')};"
    "function global:Cyril-Music{'__CYRIL__:music ' + ($args -join ' ')};"
    "function global:Cyril-Help{'__CYRIL__:help ' + ($args -join ' ')};"
    "function global:Cyril-Img{'__CYRIL__:img ' + ($args -join ' ')};"
    "function global:Cyril-Theme{'__CYRIL__:theme ' + ($args -join ' ')};"
    "function global:Cyril-Bg{'__CYRIL__:bg ' + ($args -join ' ')};"
    "function global:Cyril-Tree{'__CYRIL__:tree ' + ($args -join ' ')};"
    "function global:Cyril-Import{'__CYRIL__:import ' + ($args -join ' ')};"
    "function global:Cyril-Settings{'__CYRIL__:settings ' + ($args -join ' ')};"
    "function global:Cyril-Appearance{'__CYRIL__:appearance ' + ($args -join ' ')};"
    "function global:Cyril-Comp{'__CYRIL__:comp ' + ($args -join ' ')};"
    "function global:Cyril-Users{'__CYRIL__:users ' + ($args -join ' ')};"
    "function global:Cyril-Trans{'__CYRIL__:trans ' + ($args -join ' ')};"
    "function global:Cyril-About{'__CYRIL__:about ' + ($args -join ' ')};"
    "Set-Alias -Name play -Value Cyril-Start -Force;"
    "Set-Alias -Name stop -Value Cyril-Stop -Force;"
    # continue / help 分别是 PS 关键字和 Get-Help 的别名，不能占用，所以短名用 cont / chelp
    "Set-Alias -Name cont -Value Cyril-Continue -Force;"
    "Set-Alias -Name resume -Value Cyril-Continue -Force;"
    "Set-Alias -Name ai -Value Cyril-Ai -Force;"
    "Set-Alias -Name music -Value Cyril-Music -Force;"
    "Set-Alias -Name chelp -Value Cyril-Help -Force;"
    "Set-Alias -Name img -Value Cyril-Img -Force;"
    "Set-Alias -Name theme -Value Cyril-Theme -Force;"
    "Set-Alias -Name bg -Value Cyril-Bg -Force;"
    "Set-Alias -Name ctree -Value Cyril-Tree -Force;"
    "Set-Alias -Name cimport -Value Cyril-Import -Force;"
    "Set-Alias -Name settings -Value Cyril-Settings -Force;"
    "Set-Alias -Name appearance -Value Cyril-Appearance -Force;"
    # 不用 comp 当别名：System32 里有 comp.exe（文件比较），别名会把它遮住；plugin / 插件 无冲突
    "Set-Alias -Name plugin -Value Cyril-Comp -Force;"
    "Set-Alias -Name users -Value Cyril-Users -Force;"
    "Set-Alias -Name trans -Value Cyril-Trans -Force;"
    "Set-Alias -Name about -Value Cyril-About -Force"
)

# 必须走 -EncodedCommand，**不能用 -Command "脚本"**：
#   pywinpty 的 spawn 在 Windows 上不按 Windows 参数规则处理引号，`-Command "…"` 的外层
#   引号会被原样保留，于是 PowerShell 把整个内容当成一个字符串字面量求值再输出 ——
#   表现为屏幕上把脚本原文打了出来、函数一个都没定义；更糟的是里面的 `$args` 在传参
#   阶段就被吃掉了（实测脚本里的 ($args -join ' ') 变成 ( -join ' ')）。
#   -EncodedCommand 收 UTF-16LE 的 Base64，纯字母数字，没有引号/`$`/花括号，
#   传参绝不会被曲解，也不回显脚本内容。
# 注意：实际启动命令由 build_ps_cmd(rows) 现拼（多了横幅那一段），这里不再预编码。


# ---------------- 文件目录树（只读列目录） ----------------
# 供前端右侧文件树用：每次只列一层（不递归），展开子目录时前端再发一次请求。
# 为什么要分「一层」：对 C:\Windows\System32 这种目录做递归会卡死整个 WS 线程；
# 一层扫描是 O(条目数)，几万条也只是毫秒级。
# 每个条目都带上绝对路径（p），前端不做路径拼接 —— Windows 的分隔符与大小写
# 归一化容易出错（C:\ / C:/ 、末尾反斜杠），交给 os.scandir 的 e.path 最稳。
_FS_MAX_ENTRIES = 1500


def _fs_drives():
    """Windows 盘符列表（文件树切换盘符用）。非 Windows 返回空列表。
    用 GetLogicalDrives 位掩码一次取全，避免对光驱/空读卡器逐个 os.path.exists
    卡住；返回形如 ["C:\\", "D:\\"] 的根路径。"""
    if os.name != "nt":
        return []
    try:
        import ctypes
        mask = ctypes.windll.kernel32.GetLogicalDrives()
        return [chr(65 + i) + ":\\" for i in range(26) if mask & (1 << i)]
    except Exception:
        return []


def _fs_list(path):
    """列一层目录。返回 (ok, real_path, parent, entries, error, truncated)"""
    p = (path or "").strip().strip('"').strip("'")
    if not p:
        p = os.getcwd()                      # 空路径 = 服务器自己的工作目录
    try:
        p = os.path.abspath(os.path.expanduser(p))
    except Exception:
        return False, path or "", "", [], "路径无效", False
    if not os.path.isdir(p):
        parent = os.path.dirname(p) or ""
        return False, p, parent, [], "不是有效目录（%s）" % p, False
    parent = os.path.dirname(p)
    if parent == p:                          # 盘符根：没有上一级
        parent = ""
    entries = []
    try:
        with os.scandir(p) as it:
            for e in it:
                try:
                    is_dir = e.is_dir()
                except OSError:
                    continue
                if is_dir:
                    size = 0
                else:
                    try:
                        size = e.stat().st_size
                    except OSError:
                        size = -1
                entries.append({"n": e.name, "p": e.path, "d": is_dir, "s": size})
    except PermissionError:
        return False, p, parent, [], "没有访问权限", False
    except OSError as ex:
        return False, p, parent, [], "读取失败：%s" % ex, False
    # 目录在前，然后按名字不区分大小写排序（资源管理器的手感）
    entries.sort(key=lambda x: (0 if x["d"] else 1, x["n"].lower()))
    truncated = False
    if len(entries) > _FS_MAX_ENTRIES:
        entries = entries[:_FS_MAX_ENTRIES]
        truncated = True
    return True, p, parent, entries, "", truncated


# ---------------- 终端图片预览（Cyril-img） ----------------
# 前端拿不到磁盘路径（浏览器沙箱），所以由后端读文件、base64 回传，前端用 data URL 显示。
# 为什么不做「把图片嵌进终端输出流」：终端内容由 PTY 决定，PowerShell 不知道图片占了几行，
# ConPTY 每次重绘都按自己的行列来，图片立刻被后续输出压掉。
_IMG_EXT = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
            ".gif": "image/gif", ".webp": "image/webp", ".bmp": "image/bmp"}
_IMG_MAX = 8 * 1024 * 1024


def _img_size(data, mime):
    """从文件头读宽高（只为标题栏显示；读不出来就 0,0，不影响看图）"""
    try:
        if mime == "image/png" and data[:8] == b"\x89PNG\r\n\x1a\n":
            w, h = struct.unpack(">II", data[16:24])
            return w, h
        if mime == "image/gif" and data[:3] == b"GIF":
            w, h = struct.unpack("<HH", data[6:10])
            return w, h
        if mime == "image/bmp" and data[:2] == b"BM":
            w, h = struct.unpack("<ii", data[18:26])
            return abs(w), abs(h)
        if mime == "image/webp" and data[:4] == b"RIFF":
            if data[12:16] == b"VP8 ":
                w, h = struct.unpack("<HH", data[26:30])
                return w & 0x3FFF, h & 0x3FFF
            if data[12:16] == b"VP8L":
                b = data[21:25]
                if len(b) == 4:
                    v = b[0] | (b[1] << 8) | (b[2] << 16) | (b[3] << 24)
                    return (v & 0x3FFF) + 1, ((v >> 14) & 0x3FFF) + 1
            if data[12:16] == b"VP8X":
                w = 1 + (data[24] | (data[25] << 8) | (data[26] << 16))
                h = 1 + (data[27] | (data[28] << 8) | (data[29] << 16))
                return w, h
        if mime == "image/jpeg":
            i = 2
            n = len(data)
            while i + 9 < n:
                if data[i] != 0xFF:
                    i += 1
                    continue
                marker = data[i + 1]
                if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                              0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                    h, w = struct.unpack(">HH", data[i + 5:i + 9])
                    return w, h
                seglen = struct.unpack(">H", data[i + 2:i + 4])[0]
                i += 2 + seglen
    except (struct.error, IndexError):
        pass
    return 0, 0


def _img_read(path):
    """读一张图。返回 dict：ok / mime / data(base64) / size / name / w / h / error"""
    p = (path or "").strip().strip('"').strip("'")
    if not p:
        return {"ok": False, "error": "没给路径"}
    try:
        p = os.path.abspath(os.path.expanduser(p))
    except Exception:
        return {"ok": False, "error": "路径无效"}
    ext = os.path.splitext(p)[1].lower()
    mime = _IMG_EXT.get(ext)
    if not mime:
        return {"ok": False, "error": "不支持的格式（支持 png/jpg/gif/webp/bmp）"}
    if not os.path.isfile(p):
        return {"ok": False, "error": "文件不存在"}
    try:
        size = os.path.getsize(p)
        if size > _IMG_MAX:
            return {"ok": False, "error": "图片太大（上限 %d MB）" % (_IMG_MAX // (1024 * 1024))}
        with open(p, "rb") as f:
            raw = f.read()
    except OSError as ex:
        return {"ok": False, "error": "读取失败：%s" % ex}
    w, h = _img_size(raw, mime)
    return {"ok": True, "mime": mime, "data": base64.b64encode(raw).decode("ascii"),
            "size": size, "name": os.path.basename(p), "w": w, "h": h, "error": ""}


# ---------------- 启动横幅与提示符 ----------------
_FRAME = "─" * 48
if os.name == "nt":
    LOGIN_BANNER = (
        "\r\n"
        # 字模：C Y R I L（ANSI Shadow）。注意 R 的第 5/6 行必须是「██║  ██║ / ╚═╝  ╚═╝」，
        # 历史上这里误写成了 B 的收口（██████╔╝ / ╚═════╝），右腿丢失后整词被读成 CYBIL。
        "\x1b[1;36m ██████╗ ██╗   ██╗██████╗ ██╗██╗     \x1b[0m\r\n"
        "\x1b[1;35m██╔════╝ ╚██╗ ██╔╝██╔══██╗██║██║     \x1b[0m\r\n"
        "\x1b[1;34m██║       ╚████╔╝ ██████╔╝██║██║     \x1b[0m\r\n"
        "\x1b[1;33m██║        ╚██╔╝  ██╔══██╗██║██║     \x1b[0m\r\n"
        "\x1b[1;32m╚██████╗    ██║   ██║  ██║██║███████╗\x1b[0m\r\n"
        "\x1b[1;31m ╚═════╝    ╚═╝   ╚═╝  ╚═╝╚═╝╚══════╝\x1b[0m\r\n"
        "\x1b[0m"
        "\x1b[1;33m┌" + _FRAME + "┐\x1b[0m\r\n"
        "\x1b[1;33m│  \x1b[1;36mCyril\x1b[0m  \x1b[1;33m- Web Terminal for Windows (PowerShell)  \x1b[0m\x1b[1;33m│\x1b[0m\r\n"
        "\x1b[1;33m└" + _FRAME + "┘\x1b[0m\r\n"
        "\x1b[0m"
        "\x1b[1;32mHint:\x1b[0m try commands like  \x1b[1;34mGet-Process\x1b[0m,  \x1b[1;34mipconfig\x1b[0m,  \x1b[1;34mdir\x1b[0m,  \x1b[1;34mping 8.8.8.8\x1b[0m\r\n"
        "\x1b[1;33mNote:\x1b[0m 完整 PowerShell 会话（ConPTY）：支持提示符 / Tab 补全 / 中文\r\n"
        "\x1b[1;33mMusic:\x1b[0m \x1b[1;34mplay\x1b[0m 播放   \x1b[1;34mcontinue\x1b[0m 继续   "
        "\x1b[1;34mstop\x1b[0m 停止   \x1b[1;34mstop -3s\x1b[0m 3 秒后停   \x1b[1;34mai <问题>\x1b[0m 问 AI\r\n"
        "\x1b[1;33mOther:\x1b[0m \x1b[1;34mCyril-help\x1b[0m 命令一览   \x1b[1;34mimg <图片>\x1b[0m 看图   "
        "\x1b[1;34mtheme\x1b[0m 换主题   \x1b[1;34mtree\x1b[0m 文件树\r\n"
        "\x1b[1;33mPanel:\x1b[0m \x1b[1;34msettings\x1b[0m 设置   \x1b[1;34mcomp\x1b[0m 插件   "
        "\x1b[1;34mtrans\x1b[0m 译文开关   \x1b[1;34mappearance\x1b[0m 外观\r\n"
        "\x1b[0m"
    )
    # 矮窗口（< 32 行）用这个：整块字模 25 行，窗口一矮就会把缓冲区顶到滚动，
    # 滚动 + ConPTY 重绘会留下半截字符、光标跑到左上角。这里压到 4 行。
    LOGIN_BANNER_COMPACT = (
        "\r\n"
        "\x1b[1;36m Cyril\x1b[0m \x1b[1;33m·\x1b[0m Web Terminal for Windows (PowerShell)\r\n"
        "\x1b[1;33mMusic:\x1b[0m \x1b[1;34mplay\x1b[0m 播放 \x1b[1;34mcontinue\x1b[0m 继续 "
        "\x1b[1;34mstop\x1b[0m 停止 \x1b[1;34mstop -3s\x1b[0m 延时 \x1b[1;34mai <问题>\x1b[0m 问 AI\r\n"
        "\x1b[1;33mOther:\x1b[0m \x1b[1;34mCyril-help\x1b[0m 命令一览 \x1b[1;34mimg <图片>\x1b[0m 看图 "
        "\x1b[1;34mtheme\x1b[0m 换主题 \x1b[1;34mtree\x1b[0m 文件树\r\n"
        "\x1b[1;33mPanel:\x1b[0m \x1b[1;34msettings\x1b[0m 设置 \x1b[1;34mcomp\x1b[0m 插件 "
        "\x1b[1;34mtrans\x1b[0m 译文开关 \x1b[1;34mappearance\x1b[0m 外观\r\n"
        "\x1b[0m"
    )
else:
    LOGIN_BANNER = (
        "\r\n"
        "\x1b[1;31m ███ █   █ █████ █████ █    \x1b[0m\r\n"
        "\x1b[1;32m█   █ █   █ █   █   █   █    \x1b[0m\r\n"
        "\x1b[1;33m█      █ █  █████   █   █    \x1b[0m\r\n"
        "\x1b[1;34m█       █   █ █     █   █    \x1b[0m\r\n"
        "\x1b[1;35m█   █   █   █   █   █   █    \x1b[0m\r\n"
        "\x1b[1;31m ███    █   █   █ █████ █████\x1b[0m\r\n"
        "\x1b[0m"
        "\x1b[1;33m┌" + _FRAME + "┐\x1b[0m\r\n"
        "\x1b[1;33m│  \x1b[1;36mCyril\x1b[0m  \x1b[1;33m- Web Terminal for Kali Linux (Debian)  \x1b[0m\x1b[1;33m│\x1b[0m\r\n"
        "\x1b[1;33m└" + _FRAME + "┘\x1b[0m\r\n"
        "\x1b[0m"
        "\x1b[1;32mHint:\x1b[0m try commands like  \x1b[1;34mifconfig\x1b[0m,  \x1b[1;34msudo apt update\x1b[0m,  \x1b[1;34mnmap --version\x1b[0m\r\n"
        "\x1b[0m"
    )
    LOGIN_BANNER_COMPACT = (
        "\r\n"
        "\x1b[1;36m Cyril\x1b[0m \x1b[1;33m·\x1b[0m Web Terminal for Kali Linux (Debian)\r\n"
        "\x1b[1;33mMusic:\x1b[0m \x1b[1;34mplay\x1b[0m 播放 \x1b[1;34mcontinue\x1b[0m 继续 "
        "\x1b[1;34mstop\x1b[0m 停止 \x1b[1;34mstop -3s\x1b[0m 延时\r\n"
        "\x1b[1;33mOther:\x1b[0m \x1b[1;34mCyril-help\x1b[0m 命令一览 \x1b[1;34mimg <图片>\x1b[0m 看图 "
        "\x1b[1;34mtheme\x1b[0m 换主题 \x1b[1;34mtree\x1b[0m 文件树\r\n"
        "\x1b[0m"
    )

KALI_PS1 = (
    "\\[\\e[1;31m\\]\\u\\[\\e[0m\\]@"
    "\\[\\e[1;32m\\]\\h\\[\\e[0m\\]:"
    "\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ "
)


def build_ps_cmd(rows=24):
    """拼出启动 PowerShell 时用的 -EncodedCommand：Cyril- 指令 + 启动横幅。

    ★ 横幅为什么必须由 PowerShell 自己写 stdout，而不能由服务端直接发 WebSocket：

    ConPTY 内部维护一份自己的 VT 屏幕。服务端绕过 PTY 往终端塞横幅时，ConPTY 的屏幕
    里没有这些行 —— 它始终认为光标在「自己的第 1 行」。之后 PSReadLine 重绘输入框，
    发出的 `ESC[1;1H` 会在 ConPTY 里被翻译成「屏幕顶行」，于是用户敲的字被画到字模
    上面（截图里压在 CYRIL 上的那个 `ls` 就是这么来的）。

    这不是 ConPTY 的 bug，而是「客户端注入了 PTY 不知道的行」这个经典坑，xterm.js
    社区把它叫 **Coordinate Lying**：前端与后端行数不一致，任何绝对定位（CUP）都错。
    微软 terminal 仓库也有同源 issue（#20009 / #18725 一类的 cursor desync）。
    干净解只有一个 —— 让 PTY 知情，也就是横幅走 shell 的 stdout。

    横幅内容用 base64 传，避开单引号 / 反引号 / `$` 的转义问题（纯字母数字最安全）。
    """
    banner = LOGIN_BANNER if rows >= 32 else LOGIN_BANNER_COMPACT
    b64 = base64.b64encode(banner.encode("utf-8")).decode("ascii")
    script = (
        TERM_CMDS_PS
        + ";$__cyrilBanner=[Text.Encoding]::UTF8.GetString("
        + "[Convert]::FromBase64String('" + b64 + "'));"
        # [Console]::Write 走 stdout，ConPTY 会把它当成普通的 shell 输出解析，
        # 屏幕/光标与客户端完全同步；中文实测也原样通过（不必动 OutputEncoding）。
        + "[Console]::Write($__cyrilBanner);"
        + "Remove-Variable __cyrilBanner -ErrorAction SilentlyContinue"
    )
    # 整段脚本自己也要编码：命令行里只留纯字母数字，杜绝引号/$/花括号被曲解。
    return base64.b64encode(script.encode("utf-16-le")).decode("ascii")

# ---------------- WebSocket 帧编解码（RFC6455） ----------------
def encode_text_frame(text):
    """服务端 -> 客户端：text 帧（无掩码）"""
    data = text.encode("utf-8")
    header = bytearray([0x81])  # FIN + opcode=text
    n = len(data)
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126)
        header += struct.pack(">H", n)
    else:
        header.append(127)
        header += struct.pack(">Q", n)
    return bytes(header) + data


class FrameParser:
    """流式解析客户端 -> 服务端帧（处理半包/粘包），客户端帧必须带掩码。"""

    def __init__(self, on_message, on_close):
        self.buf = bytearray()
        self.on_message = on_message
        self.on_close = on_close

    def feed(self, data):
        self.buf.extend(data)
        while True:
            consumed = self._try_parse()
            if consumed is None:
                break
            del self.buf[:consumed]

    def _try_parse(self):
        b = self.buf
        if len(b) < 2:
            return None
        b0, b1 = b[0], b[1]
        opcode = b0 & 0x0F
        masked = bool(b1 & 0x80)
        length = b1 & 0x7F
        idx = 2
        if length == 126:
            if len(b) < idx + 2:
                return None
            length = struct.unpack(">H", b[idx:idx + 2])[0]
            idx += 2
        elif length == 127:
            if len(b) < idx + 8:
                return None
            length = struct.unpack(">Q", b[idx:idx + 8])[0]
            idx += 8
        mask = b[idx:idx + 4] if masked else None
        if masked:
            idx += 4
        if len(b) < idx + length:
            return None
        payload = bytes(b[idx:idx + length])
        if mask:
            payload = bytes(c ^ mask[i % 4] for i, c in enumerate(payload))
        if opcode == 0x8:      # close
            self.on_close()
        elif opcode in (0x1, 0x2):  # text / binary
            self.on_message(payload)
        # ping / pong / 其他：协议层忽略（浏览器无需 ping 保活）
        # pong/其他：忽略
        return idx + length


def compute_accept(key):
    return base64.b64encode(hashlib.sha1((key + WS_GUID).encode()).digest()).decode()


# ---------------- 关于光标位置查询（DSR / CPR） ----------------
# 这里以前有一个 CursorTracker + 服务端应答 ESC[6n 的逻辑，已经删掉了。原因有两层：
#   1) xterm.js 自己就会应答 DSR，用的是浏览器里真实的光标位置，比服务端猜的准；
#   2) 服务端再答一遍，PSReadLine 会收到两份应答，晚到的那份常与用户的下一帧输入
#      粘在一起被当成序列尾部吞掉 —— 表现是「第一次输入的首字符偶尔消失」，
#      同时把输入回显到错误的位置（截图上的「光标错位」）。
# 结论：CPR 交给前端，服务端不插手。


# ---------------- 会话（Linux: PTY / Windows: ConPTY via pywinpty） ----------------
if os.name == "nt":
    class WinSession:
        """Windows 会话：ConPTY 伪终端（pywinpty），真正的 PowerShell 交互。

        依赖：pip install pywinpty（ConPTY 是 Windows 10 1809+ 内置能力，
        网上开源 Web 终端（如 HiMCP powershell-terminal）的标准做法）。
        """

        def __init__(self, conn):
            self.conn = conn
            self.proc = None
            self.reader = None
            self.fd = None          # 标记 Windows：主循环只等 socket
            # 横幅由定时器线程 / 消息线程一起发，sendall 必须串行，否则两帧会交错
            self._send_lock = threading.Lock()
            # 用户输入全部走消息线程，但仍串行化写入：pywinpty 的 write 不是原子操作，
            # 两次写入交错会让 ConPTY 的输入解析器吃掉字符（曾经的 `img` → `mg`）。
            self._write_lock = threading.Lock()

        def spawn(self, rows=24, cols=80):
            try:
                from winpty import PtyProcess
            except ImportError:
                raise RuntimeError("缺少 pywinpty：请先执行 pip install pywinpty")
            rows = max(1, min(int(rows or 24), 500))
            cols = max(1, min(int(cols or 80), 1000))
            # 启动真正的 PowerShell（含提示符/颜色/TUI），ConPTY 挂接。
            # -NoExit -EncodedCommand：先注入终端指令与启动横幅（见 build_ps_cmd），
            # 再留在交互模式 —— 横幅是从 shell 的 stdout 出来的，ConPTY 完全知情，
            # 所以它的光标模型和你眼前的屏幕一致，输入框不会画错行。
            # dimensions 必须是浏览器真实尺寸：起完再 setwinsize 会让 ConPTY 重排，
            # 已经画好的横幅会被拆行/并字，留下半截字符。
            self.proc = PtyProcess.spawn(
                "powershell.exe -NoLogo -NoProfile -NoExit -EncodedCommand " + build_ps_cmd(rows),
                dimensions=(rows, cols),
            )
            self.reader = threading.Thread(target=self._pump, daemon=True)
            self.reader.start()

        def _pump(self):
            """读线程：ConPTY 输出 -> WebSocket（Windows 上无 selectable fd）"""
            while True:
                try:
                    data = self.proc.read(4096)
                except Exception:
                    break
                if not data:
                    break
                if isinstance(data, bytes):
                    data = data.decode("utf-8", "replace")
                self.send_text(data)
            # 会话进程退出（EOF/关闭）后断开客户端
            try:
                self.conn.close()
            except Exception:
                pass

        def set_size(self, rows, cols):
            """起 shell 之前单独设尺寸：spawn 时已经按真实尺寸创建，这里留空对齐接口。"""
            return

        def write(self, data: str):
            try:
                with self._write_lock:
                    self.proc.write(data)
            except Exception:
                pass

        def resize(self, rows, cols):
            try:
                rows = max(1, min(rows, 500))
                cols = max(1, min(cols, 1000))
                self.proc.setwinsize(rows, cols)
            except Exception:
                pass

        def send_text(self, text):
            with self._send_lock:
                try:
                    self.conn.sendall(encode_text_frame(text))
                except OSError:
                    pass

        def close(self):
            if self.proc:
                try:
                    if self.proc.isalive():
                        self.proc.terminate(force=True)
                except Exception:
                    pass
                self.proc = None

else:
    class PtySession:
        def __init__(self, conn):
            self.conn = conn
            self.fd = None
            self.pid = None
            # 横幅与 shell 输出都会经 send_text 下发，sendall 必须串行
            self._send_lock = threading.Lock()

        def spawn(self, rows=24, cols=80):
            pid, fd = pty.fork()
            if pid == 0:
                os.environ["TERM"] = "xterm-256color"
                os.environ["PS1"] = KALI_PS1
                base = os.path.basename(SHELL)
                os.execv(SHELL, ["-" + base])
                os._exit(1)
            self.pid = pid
            self.fd = fd
            # 新 pty 的初始窗口尺寸是 0×0，必须抢在 shell 查询之前设好，
            # 否则 bash 会以为终端没有宽度（行首提示符叠成一团）。
            self.resize(rows, cols)

        def set_size(self, rows, cols):
            """Linux 的 CPR 由浏览器（xterm）自己应答，这里没有追踪器，留空对齐接口。"""
            return

        def write(self, data: str):
            try:
                os.write(self.fd, data.encode("utf-8", "replace"))
            except OSError:
                pass

        def resize(self, rows, cols):
            try:
                rows = max(1, min(rows, 500))
                cols = max(1, min(cols, 1000))
                fcntl.ioctl(self.fd, termios.TIOCSWINSZ,
                            struct.pack("HHHH", rows, cols, 0, 0))
            except Exception:
                pass

        def send_text(self, text):
            with self._send_lock:
                try:
                    self.conn.sendall(encode_text_frame(text))
                except OSError:
                    pass

        def close(self):
            if self.pid:
                try:
                    os.kill(self.pid, signal.SIGHUP)
                except Exception:
                    pass
            if self.fd is not None:
                try:
                    os.close(self.fd)
                except Exception:
                    pass
                self.fd = None


# ---------------- AI：内置简化版 llama.cpp（llama-server 子进程） ----------------
# 运行时文件位于 exe 同目录 llama/（llama-server.exe + 全套 DLL）；
# GGUF 模型放 llama/models/（用户自行下载，不随 exe 打包）。
LLAMA_PORT = 18081


def _llama_dirs():
    """运行时文件（llama-server.exe）随包解压到 _MEIPASS/llama；
    模型（GGUF）放 exe 同目录 llama/models/，便于用户自行下载放置。"""
    if getattr(sys, "frozen", False):
        bin_dir = os.path.join(sys._MEIPASS, "llama")
        model_dir = os.path.join(
            os.path.dirname(os.path.abspath(sys.executable)), "llama", "models")
    else:
        bin_dir = os.path.join(_BASE, "llama")
        model_dir = os.path.join(_BASE, "llama", "models")
    return bin_dir, model_dir


LLAMA_BIN_DIR, LLAMA_MODELS_DIR = _llama_dirs()
LLAMA_SERVER = os.path.join(LLAMA_BIN_DIR, "llama-server.exe")

_llama_proc = None
_llama_model = None
_llama_err = None
_llama_started = False


def find_model():
    """在 llama/models 下找 MiniCPM/heretic 模型，否则取第一个 .gguf"""
    if not os.path.isdir(LLAMA_MODELS_DIR):
        return None
    cands = [f for f in os.listdir(LLAMA_MODELS_DIR)
             if f.lower().endswith(".gguf") and os.path.isfile(
                 os.path.join(LLAMA_MODELS_DIR, f))]
    if not cands:
        return None

    def _score(n):
        nl = n.lower()
        s = 0
        if "minicpm" in nl:
            s += 4
        if "heretic" in nl:
            s += 3
        if "q4_k_m" in nl:
            s += 2
        if "q8_0" in nl:
            s += 1
        return s

    cands.sort(key=_score, reverse=True)
    return os.path.join(LLAMA_MODELS_DIR, cands[0])


def start_llama():
    """启动 llama-server 子进程（无模型/无程序时记录错误，不阻塞主服务）"""
    global _llama_proc, _llama_model, _llama_err, _llama_started
    if _llama_started:
        return
    _llama_started = True
    if not os.path.isfile(LLAMA_SERVER):
        _llama_err = "缺少 llama/llama-server.exe（运行时未随包分发）"
        return
    model = find_model()
    if not model:
        _llama_err = "未找到 GGUF 模型：请把模型放入 exe 同目录 llama/models/"
        return
    _llama_model = model
    # 线程数：物理核心数级别收益最大，超过物理核（超线程）对生成速度基本无益甚至抖动。
    # 16 逻辑核机器取 8；小机器至少给 4，避免 1B 模型跑不满。
    n_threads = min(8, max(4, (os.cpu_count() or 8) // 2))
    cmd = [LLAMA_SERVER, "-m", model, "-c", "4096",
           "--host", "127.0.0.1", "--port", str(LLAMA_PORT),
           "--n-gpu-layers", "0", "-t", str(n_threads), "--no-webui"]
    try:
        _llama_proc = subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        sys.stderr.write("[webterm] llama-server started: %s\n" % model)
    except Exception as exc:
        _llama_err = "llama-server 启动失败: %r" % exc


def llama_ready():
    return (_llama_proc is not None and _llama_proc.poll() is None)


# ---------------- 统一配置：Settings.json ----------------
# 以前配置是散的：主题/背景/树宽躺在浏览器 localStorage，AI 配置单独一个
# ai-config.json。换浏览器、换机器、重装 exe 就全丢。现在统一收进
# exe（或源码）同目录的 Settings.json：
#   · 启动时读一次 → _settings；
#   · 前端任何配置改动都发 settings_set，后端合并后整体落盘；
#   · 前端连上后发 settings_get，拿回整包再套 UI。
# 注意：背景图片/视频的二进制**不进** JSON（几十 MB 转 base64 会拖慢启动），
# 仍放浏览器 IndexedDB，Settings.json 只记 bg_kind 这种「当前用什么」的元信息。
# ---------------- 组件 / 插件 ----------------
# 两类来源：
#   ① 内置（audio 电平 / sys 占用）：本体写在 web/index.html 里，后端只报个名字给列表
#   ② 插件：一个文件夹一个组件，含 manifest.json + index.js（可选 index.css），
#      目录优先用 exe 同目录的 components/（用户可写、可新建可删除），
#      其次用随包分发的 web/components/（示例组件，只读，删不掉）。
# 前端按 manifest 里的 script/style 去 /comp/<id>/<file> 取文件，脚本里调
# Cyrill.component(id, { mount(root, api), unmount() }) 注册自己。
def _app_base():
    """可写数据目录：打包后是 exe 所在目录，源码运行时是脚本所在目录"""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return _BASE


COMPONENTS_PATH = os.path.join(_app_base(), "components")
_COMPONENTS_SHIPPED = os.path.join(_BASE, "web", "components")

# id 直接用文件夹名，限定字符集：它同时是 URL 里的一段，不能有空格 / 点 / 斜杠
_COMP_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")

# 组件目录里允许被 HTTP 取走的文件类型（其余一律 404，免得组件目录变成下载盘）
_COMP_MIME = {
    "js": "application/javascript; charset=utf-8",
    "mjs": "application/javascript; charset=utf-8",
    "css": "text/css; charset=utf-8",
    "json": "application/json; charset=utf-8",
    "svg": "image/svg+xml",
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "gif": "image/gif",
    "webp": "image/webp",
    "woff2": "font/woff2",
    "ttf": "font/ttf",
    "txt": "text/plain; charset=utf-8",
    "html": "text/html; charset=utf-8",
    "htm": "text/html; charset=utf-8",
    "md": "text/markdown; charset=utf-8",
    "woff": "font/woff",
    "otf": "font/otf",
    "mp3": "audio/mpeg",
    "flac": "audio/flac",
}

_BUILTIN_COMPONENTS = [
    {"id": "audio", "name": "实时电平", "desc": "左右声道峰值 + 过载灯",
     "builtin": True, "editable": False},
    {"id": "sys", "name": "系统占用", "desc": "CPU / 内存 / 显卡（有 N 卡才显示显卡）",
     "builtin": True, "editable": False},
]


def _comp_dirs():
    """[用户目录, 自带示例目录]；同一 id 用户目录优先"""
    return [COMPONENTS_PATH, _COMPONENTS_SHIPPED]


def _comp_scan():
    """扫描所有组件文件夹。

    id 一律取文件夹名（manifest 里若写了不同的 id 只当展示，不参与寻址），
    否则 /comp/<id>/ 会和目录对不上，脚本直接 404。
    """
    items = [dict(c) for c in _BUILTIN_COMPONENTS]
    seen = set(c["id"] for c in items)
    for base in _comp_dirs():
        try:
            names = sorted(os.listdir(base))
        except OSError:
            continue
        for name in names:
            if name in seen or not _COMP_ID_RE.match(name):
                continue
            d = os.path.join(base, name)
            if not os.path.isdir(d):
                continue
            try:
                with open(os.path.join(d, "manifest.json"), "r", encoding="utf-8") as f:
                    man = json.load(f)
            except (OSError, ValueError):
                continue                    # 没 manifest / JSON 坏了：跳过，不报错
            if not isinstance(man, dict):
                continue
            try:
                w = int(man.get("width") or 0)
                h = int(man.get("height") or 0)
            except (TypeError, ValueError):
                w = h = 0
            try:
                pw = int(man.get("panel_width") or 900)
                ph = int(man.get("panel_height") or 620)
            except (TypeError, ValueError):
                pw, ph = 900, 620
            items.append({
                "id": name,
                "name": str(man.get("name") or name)[:40],
                "desc": str(man.get("desc") or "")[:200],
                "author": str(man.get("author") or "")[:40],
                "version": str(man.get("version") or "")[:16],
                "script": str(man.get("script") or "index.js"),
                "style": str(man.get("style") or ""),
                "width": w,
                "height": h,
                # kind=panel：不开左栏，改成打开一个独立窗口（iframe 载 entry）
                "kind": "panel" if str(man.get("kind") or "") == "panel" else "rail",
                "entry": str(man.get("entry") or "index.html"),
                "panel_width": pw,
                "panel_height": ph,
                "icon": str(man.get("icon") or "")[:4],
                "builtin": False,
                "editable": base == COMPONENTS_PATH,
            })
            seen.add(name)
    return items


def _comp_dir_of(cid):
    """组件实际所在目录（用户目录优先），找不到回 None"""
    if not _COMP_ID_RE.match(cid or ""):
        return None
    for base in _comp_dirs():
        d = os.path.join(base, cid)
        if os.path.isdir(d):
            return d
    return None


def _comp_asset(cid, rel):
    """把 /comp/<id>/<rel> 映射到磁盘文件；越界或非白名单后缀一律 None"""
    d = _comp_dir_of(cid)
    if not d:
        return None
    rel = (rel or "").replace("\\", "/").lstrip("/")
    if not rel or ".." in rel.split("/"):
        return None
    p = os.path.normpath(os.path.join(d, rel))
    if not (p == d or p.startswith(d + os.sep)):
        return None
    base = os.path.basename(p)
    ext = base.rsplit(".", 1)[-1].lower() if "." in base else ""
    if ext not in _COMP_MIME:
        return None
    return p if os.path.isfile(p) else None


# ==================== 插件自带后端（components/<id>/bridge.py） ====================
# 约定不变：插件目录里放后端文件，导出 PREFIX（如 "/cs/"）以及
# handle_get(handler, path) / handle_post(handler, path)，宿主把该前缀的请求
# 原样转进去。没有后端文件的组件完全不受影响。
#
# 后端文件名不强制 .py：优先 manifest.json 的 "backend"，否则按
# bridge.py → bridge.pyw → bridge.pyc → bridge（无后缀）→ 同 stem 其它后缀 查找；
# 导入时显式指定 SourceFileLoader，后缀完全不参与判定。
#
# 加载策略（v2，插件写法与 v1 完全一致，不需要改任何插件代码）：
#   - 懒加载：首个走到插件路由的请求才扫描，不拖慢启动
#   - 可重扫：登记表按 bridge.py 的（绝对路径, mtime, 大小）签名比对，
#     文件新增 / 修改 / 删除都在下一次请求前自动生效，改后端不必再重启
#   - 失败可重试：导入失败的原因记进登记表，文件一变就重试，不会永久缓存
#   - 删除即卸载：组件被删时立刻摘掉它的前缀路由，并调用可选的 unload()
#   - 线程安全：扫描与重载都在 _PLUGIN_LOCK 内，HTTP 本身是并发的
#   - 前缀冲突：同一 PREFIX 被多个插件声明时按目录优先级先到先得，后者记 dup_prefix
# 观测：GET /api/plugins 看清单，POST /api/plugins/reload 强制全部重载。
_PLUGIN_LOCK = threading.Lock()
_PLUGIN_REG = None       # 登记表：见 _plugin_refresh() 里 rec 的字段
_PLUGIN_SIG = None       # 上一次扫描的文件签名
_PLUGIN_CHECKED = 0.0    # 上一次扫描的 monotonic 时间
_PLUGIN_IDLE = 0.5       # 两次 stat 的最小间隔（秒）：轮询接口不会把磁盘打满


# 后端文件的默认名，按优先级排队；真正的判据是「能当 Python 源码执行」，不是后缀
_PLUGIN_BACKEND_NAMES = ("bridge.py", "bridge.pyw", "bridge.pyc", "bridge")
_PLUGIN_BACKEND_RANK = {".py": 0, ".pyw": 1, ".pyc": 2}


def _plugin_backend_of(d):
    """组件目录里的后端文件，没有则 None。

    后缀不参与判定，三条路任选：
      1. manifest.json 写 "backend": "server"（相对组件目录，可以是任意文件名）
      2. 约定名 bridge.py / bridge.pyw / bridge.pyc / bridge（无后缀）
      3. 同 stem 的其它后缀，如 bridge.txt / bridge.python（按 .py 优先排序）
    """
    name = ""
    try:
        with open(os.path.join(d, "manifest.json"), "r", encoding="utf-8") as f:
            man = json.load(f)
        if isinstance(man, dict):
            name = str(man.get("backend") or "").strip()
    except (OSError, ValueError):
        name = ""
    if name:
        rel = name.replace("\\", "/").lstrip("/")
        if rel and ".." not in rel.split("/"):
            p = os.path.normpath(os.path.join(d, rel))
            if p.startswith(d + os.sep) and os.path.isfile(p):
                return p
    for n in _PLUGIN_BACKEND_NAMES:
        p = os.path.join(d, n)
        if os.path.isfile(p):
            return p
    try:
        names = os.listdir(d)
    except OSError:
        return None
    other = []
    for n in names:
        stem, dot, ext = n.rpartition(".")
        if dot and stem == "bridge":
            # 排序用小写 + 原样回用真名，大小写敏感的文件系统上不会找错文件
            other.append((_PLUGIN_BACKEND_RANK.get(ext.lower(), 9), n.lower(), n))
    if other:
        other.sort()
        p = os.path.join(d, other[0][2])
        if os.path.isfile(p):
            return p
    return None


def _plugin_candidates():
    """候选插件：[{id, dir, path}]，用户目录优先、同名 id 只认一个。

    这里刻意不校验目录名（v1 就没校验），免得好好的插件因为目录名不合规突然失效。
    """
    out, seen = [], set()
    for base in _comp_dirs():
        try:
            names = sorted(os.listdir(base))
        except OSError:
            continue
        for name in names:
            if name in seen:
                continue
            d = os.path.join(base, name)
            if not os.path.isdir(d):
                continue
            path = _plugin_backend_of(d)
            if not path:
                continue
            seen.add(name)
            out.append({"id": name, "dir": d, "path": path})
    return out


def _plugin_sig_of(path):
    """(绝对路径, 修改时间, 大小)：用来判断后端文件是否变过"""
    try:
        st = os.stat(path)
    except OSError:
        return None
    return (os.path.abspath(path), int(st.st_mtime_ns), int(st.st_size))


def _plugin_import(rec):
    """导入一个插件后端文件，返回 (模块 or None, 状态, 原因)。

    状态三态：ok 参与路由 / skip 没声明契约（不报错、不参与）/ error 导入失败。
    显式指定 loader，所以文件名与后缀都不影响加载。
    """
    import importlib.machinery
    import importlib.util
    modname = rec["modname"]
    # 重载前先摘掉旧对象，避免模块级缓存 / dataclass 拿到上一次的定义
    for k in [k for k in list(sys.modules) if k == modname or k.startswith(modname + ".")]:
        sys.modules.pop(k, None)
    path = rec["path"]
    try:
        if path.lower().endswith(".pyc"):
            loader = importlib.machinery.SourcelessFileLoader(modname, path)
        else:
            # 没有 .py 也照样当源码执行：后缀不参与判断
            loader = importlib.machinery.SourceFileLoader(modname, path)
        spec = importlib.util.spec_from_file_location(modname, path, loader=loader)
        if spec is None:
            return None, "error", "无法为 %s 构造模块规格" % os.path.basename(path)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[modname] = mod          # dataclass / pickle 需要它在 sys.modules
        spec.loader.exec_module(mod)
    except SystemExit as exc:
        # 插件顶层写 sys.exit() 不能把宿主进程带走（v1 会真的退出）
        sys.modules.pop(modname, None)
        return None, "error", "插件顶层调用了 sys.exit(%r)，已拦截" % (exc.code,)
    except Exception as exc:
        sys.modules.pop(modname, None)
        return None, "error", "%s: %s" % (type(exc).__name__, exc)
    prefix = str(getattr(mod, "PREFIX", "") or "")
    if not prefix:
        # v1 也是静默跳过：顶层代码跑了，但因为没声明契约而不参与路由
        sys.modules.pop(modname, None)
        return None, "skip", "未声明 PREFIX"
    if not prefix.startswith("/"):
        sys.modules.pop(modname, None)
        return None, "error", "PREFIX 必须以 / 开头（当前是 %r）" % prefix
    if not (hasattr(mod, "handle_get") or hasattr(mod, "handle_post")):
        sys.modules.pop(modname, None)
        return None, "skip", "未实现 handle_get / handle_post"
    return mod, "ok", ""


def _plugin_drop(rec, why=""):
    """卸载一个已登记的插件：先给 unload() 钩子收尾，再从 sys.modules 摘掉"""
    mod = rec.get("mod")
    if mod is None:
        return
    fn = getattr(mod, "unload", None)
    if callable(fn):
        try:
            fn()
        except SystemExit as exc:
            sys.stderr.write("[webterm] 插件卸载钩子试图退出宿主 %s：%r（已拦截）\n"
                             % (rec.get("id"), exc.code))
        except Exception as exc:
            sys.stderr.write("[webterm] 插件卸载钩子出错 %s：%s\n" % (rec.get("id"), exc))
    modname = rec.get("modname") or getattr(mod, "__name__", "")
    if modname:
        for k in [k for k in list(sys.modules)
                  if k == modname or k.startswith(modname + ".")]:
            sys.modules.pop(k, None)
    rec["mod"] = None
    if why:
        sys.stderr.write("[webterm] 插件后端已卸载 %s（%s）\n" % (rec.get("id"), why))


def _plugin_refresh(force=False):
    """按需扫描：签名没变就复用已加载模块，变了只重载变动的那个。

    返回登记表（list[dict]），每项字段：
      id / dir / path / modname / sig / prefix / mod / state / error /
      dup_prefix / version / loaded_at
    """
    global _PLUGIN_REG, _PLUGIN_SIG, _PLUGIN_CHECKED
    now = time.monotonic()
    if not force and _PLUGIN_REG is not None and (now - _PLUGIN_CHECKED) < _PLUGIN_IDLE:
        return _PLUGIN_REG
    with _PLUGIN_LOCK:
        now = time.monotonic()
        if not force and _PLUGIN_REG is not None and (now - _PLUGIN_CHECKED) < _PLUGIN_IDLE:
            return _PLUGIN_REG
        _PLUGIN_CHECKED = now
        cands = _plugin_candidates()
        sig = tuple((c["id"], _plugin_sig_of(c["path"])) for c in cands)
        if not force and _PLUGIN_REG is not None and sig == _PLUGIN_SIG:
            return _PLUGIN_REG
        _PLUGIN_SIG = sig
        old = {} if force else dict((r["id"], r) for r in (_PLUGIN_REG or []))
        if force:
            for r in (_PLUGIN_REG or []):
                _plugin_drop(r)
        reg, by_id, prefix_owner = [], {}, {}
        for c in cands:
            fsig = _plugin_sig_of(c["path"])
            prev = old.get(c["id"])
            if prev and prev.get("mod") is not None and prev.get("sig") == fsig:
                rec = prev                      # 文件没动过：白捡已加载的模块
            else:
                if prev and prev.get("mod") is not None:
                    _plugin_drop(prev, "后端文件已变化，重载")
                rec = {"id": c["id"], "dir": c["dir"], "path": c["path"],
                       "backend": os.path.basename(c["path"]),
                       "modname": "webterm_plugin_" + c["id"], "sig": fsig,
                       "prefix": "", "mod": None, "state": "error", "error": "",
                       "dup_prefix": "", "version": "", "loaded_at": 0.0}
                mod, state, err = _plugin_import(rec)
                rec["state"], rec["error"] = state, err
                if mod is None:
                    if state == "error" and (prev is None or prev.get("error") != err):
                        sys.stderr.write("[webterm] 插件后端加载失败 %s：%s\n"
                                         % (c["id"], err))
                else:
                    rec["mod"] = mod
                    rec["prefix"] = str(getattr(mod, "PREFIX", "") or "")
                    rec["version"] = str(getattr(mod, "PLUGIN_VERSION", "") or "")
                    rec["loaded_at"] = time.time()
                    sys.stderr.write("[webterm] 插件后端已加载：%s\n" % c["id"])
            if rec.get("mod") is not None:
                p = rec.get("prefix") or ""
                if p in prefix_owner:
                    rec["dup_prefix"] = prefix_owner[p]
                    sys.stderr.write("[webterm] 插件前缀冲突：%s 与 %s 都声明了 %s，"
                                     "按目录优先级先到先得\n"
                                     % (rec["id"], prefix_owner[p], p))
                else:
                    prefix_owner[p] = rec["id"]
            by_id[c["id"]] = rec
            reg.append(rec)
        # 目录已被删掉的插件：立即卸载，别留下一个还在响应的幽灵后端
        for pid, prev in old.items():
            if pid not in by_id and prev.get("mod") is not None:
                _plugin_drop(prev, "组件目录已移除")
        _PLUGIN_REG = reg
        return reg


def _plugin_mods():
    """当前可参与路由的模块（v1 的返回契约，保留给外部调用方；路由内部用登记表）"""
    return [r["mod"] for r in _plugin_refresh() if r.get("mod") is not None]


def _plugin_unload(cid):
    """按 id 卸载（组件被删时调用），返回是否真的卸了一个"""
    global _PLUGIN_REG, _PLUGIN_SIG
    with _PLUGIN_LOCK:
        kept, hit = [], None
        for r in (_PLUGIN_REG or []):
            if r["id"] == cid:
                hit = r
            else:
                kept.append(r)
        if hit is None:
            return False
        _plugin_drop(hit, "组件已删除")
        _PLUGIN_REG = kept
        _PLUGIN_SIG = None          # 让下一次请求重新算签名
        return True


def _plugin_reload_all():
    """强制全部重载（不看签名），返回成功参与的插件数"""
    global _PLUGIN_REG, _PLUGIN_SIG
    with _PLUGIN_LOCK:
        for r in (_PLUGIN_REG or []):
            _plugin_drop(r)
        _PLUGIN_REG = []
        _PLUGIN_SIG = None
    return len([r for r in _plugin_refresh(force=True) if r.get("mod") is not None])


def _plugin_status(local=True):
    """给 /api/plugins 用的清单；非本机不暴露磁盘路径"""
    items = []
    for r in _plugin_refresh():
        sig = r.get("sig") or ()
        items.append({
            "id": r["id"],
            "backend": r.get("backend") or os.path.basename(r.get("path") or ""),
            "prefix": r.get("prefix") or "",
            "state": r.get("state") or ("ok" if r.get("mod") else "error"),
            "error": r.get("error") or "",
            "dup_prefix": r.get("dup_prefix") or "",
            "version": r.get("version") or "",
            "size": sig[2] if len(sig) > 2 else 0,
            "loaded_at": (time.strftime("%H:%M:%S", time.localtime(r["loaded_at"]))
                          if r.get("loaded_at") else ""),
            "file": r.get("path") or "" if local else "",
        })
    return items


def _plugin_route(method, handler, path):
    """命中插件前缀就交给插件处理，返回 True 表示已响应"""
    for rec in _plugin_refresh():
        mod = rec.get("mod")
        if mod is None:
            continue
        prefix = rec.get("prefix") or ""
        if not prefix or not path.startswith(prefix):
            continue
        fn = getattr(mod, "handle_get" if method == "GET" else "handle_post", None)
        if fn is None:
            continue
        if not callable(fn):
            try:
                handler._send_json({"ok": False, "error":
                                    "插件的 handle_%s 不可调用" % method.lower()}, 500)
            except Exception:
                pass
            return True
        try:
            fn(handler, path)
        except SystemExit as exc:
            # 处理函数里 sys.exit() 只结束本次请求，不能结束宿主
            sys.stderr.write("[webterm] 插件试图退出宿主 %s：%r（已拦截）\n" % (prefix, exc.code))
            try:
                handler._send_json({"ok": False, "error": "插件调用了 sys.exit，已拦截"}, 500)
            except Exception:
                pass
        except Exception as exc:
            sys.stderr.write("[webterm] 插件处理出错 %s：%s\n" % (prefix, exc))
            try:
                handler._send_json({"ok": False, "error": "插件内部错误：%s" % exc}, 500)
            except Exception:
                pass
        return True
    return False


# ==================== 音乐插件后端（/lx/*） ====================
# 数据目录不能放在 components/ 下：components/<id>/ 是「组件」的寻址空间，
# 同名目录没有 manifest.json 会让 /comp/lxmusic/* 全部 404。这里单开一个。
LX_DATA_DIR = os.path.join(_app_base(), "lxmusic-data")
LX_SOURCES_DIR = os.path.join(LX_DATA_DIR, "sources")
LX_CONFIG_PATH = os.path.join(LX_DATA_DIR, "config.json")

LX_UA = ("Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 "
         "(KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36")
# 下载/取图用较新的 UA（部分 CDN 会拒旧版）
LX_DL_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

_LX_DEFAULT_CFG = {
    "download_dir": "",           # 空 = 用户音乐目录/CyrilMusic
    "name_format": "{name} - {singer}",
    "save_lrc": True,             # 另存 .lrc 文件
    "embed_lyric": True,          # 内嵌歌词
    "embed_cover": True,          # 内嵌封面
    "lrc_format": "utf8",         # utf8 / gbk
    "quality": "320k",
    "sources_auto_load": True,    # 启动时自动装载最后一个音源
    "last_source": "",
}

_LX_SKIP_HEADERS = {"host", "content-length", "connection", "accept-encoding",
                    "transfer-encoding", "proxy-connection"}


def _lx_cfg():
    cfg = dict(_LX_DEFAULT_CFG)
    try:
        with open(LX_CONFIG_PATH, "r", encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict):
            cfg.update({k: v for k, v in d.items() if k in _LX_DEFAULT_CFG})
    except (OSError, ValueError):
        pass
    return cfg


def _lx_cfg_save(patch):
    cfg = _lx_cfg()
    if isinstance(patch, dict):
        cfg.update({k: v for k, v in patch.items() if k in _LX_DEFAULT_CFG})
    try:
        os.makedirs(LX_DATA_DIR, exist_ok=True)
        tmp = LX_CONFIG_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        os.replace(tmp, LX_CONFIG_PATH)
    except OSError as e:
        return None, "写入配置失败：%s" % e
    return cfg, None


def _lx_music_dir():
    """默认下载目录：系统音乐目录/CyrilMusic"""
    cfg = _lx_cfg()
    d = (cfg.get("download_dir") or "").strip()
    if d:
        return os.path.expanduser(d)
    home = os.path.expanduser("~")
    for name in ("Music", "音乐"):
        p = os.path.join(home, name)
        if os.path.isdir(p):
            return os.path.join(p, "CyrilMusic")
    return os.path.join(home, "CyrilMusic")


def _lx_ensure_dirs():
    try:
        os.makedirs(LX_SOURCES_DIR, exist_ok=True)
    except OSError:
        pass
    # 上次被强制关掉时会留下 _dl_*.part，一小时前的清掉
    try:
        d = _lx_music_dir()
        now = time.time()
        for fn in os.listdir(d):
            if fn.startswith("_dl_") and fn.endswith(".part"):
                p = os.path.join(d, fn)
                try:
                    if now - os.path.getmtime(p) > 3600:
                        os.remove(p)
                except OSError:
                    pass
    except OSError:
        pass


# 音源文件名：不再用白名单正则卡死（中文名常带空格/括号/加号，会被误杀），
# 改成「只拦真正危险的字符」——路径分隔符、Windows 非法字符、控制字符。
_SRC_BAD_CHARS = re.compile(r'[\\/:*?"<>|\x00-\x1f]')
_SRC_RESERVED = {
    "con", "prn", "aux", "nul",
    "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
    "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
}


def _lx_ok_name(fn):
    """已落盘文件能否被读取/列出/删除（宽松：只要是个安全的 .js 文件名）"""
    fn = str(fn or "")
    if not fn or len(fn) > 120 or fn in (".", ".."):
        return False
    if not fn.lower().endswith(".js"):
        return False
    if _SRC_BAD_CHARS.search(fn):
        return False
    if fn != fn.strip() or fn.startswith("."):
        return False
    return True


def _lx_safe_name(filename):
    """把用户给的名字变成能落盘的名字：保留中文/空格/括号，只清危险字符"""
    fn = os.path.basename(str(filename or "").replace("\\", "/").split("/")[-1])
    fn = _SRC_BAD_CHARS.sub("_", fn).strip().strip(".")
    fn = re.sub(r"\s+", " ", fn)
    if fn.lower().endswith(".js"):
        fn = fn[:-3]
    fn = fn.strip()[:100] or "source"
    if fn.lower() in _SRC_RESERVED:
        fn = "_" + fn
    return fn + ".js"


def _lx_sources():
    """列出已导入音源：{name, file, size, mtime, meta}"""
    out = []
    try:
        names = sorted(os.listdir(LX_SOURCES_DIR))
    except OSError:
        return out
    for fn in names:
        if not _lx_ok_name(fn):
            continue
        p = os.path.join(LX_SOURCES_DIR, fn)
        if not os.path.isfile(p):
            continue
        try:
            st = os.stat(p)
            with open(p, "r", encoding="utf-8", errors="replace") as f:
                head = f.read(1200)
        except OSError:
            continue
        meta = {}
        m = re.search(r"@name\s+(.+)", head)
        if m:
            meta["name"] = m.group(1).strip()[:40]
        m = re.search(r"@version\s+(.+)", head)
        if m:
            meta["version"] = m.group(1).strip()[:20]
        m = re.search(r"@author\s+(.+)", head)
        if m:
            meta["author"] = m.group(1).strip()[:30]
        out.append({
            "file": fn,
            "name": meta.get("name") or fn.rsplit(".", 1)[0],
            "version": meta.get("version", ""),
            "author": meta.get("author", ""),
            "size": st.st_size,
            "mtime": int(st.st_mtime),
        })
    return out


def _lx_save_source(filename, code):
    """导入音源：写进 sources/<name>.js
    文件名保留原样（允许中文、空格、括号），只清掉路径分隔符等危险字符；
    重名且内容不同时自动加 (2)(3)，内容相同则视为重复导入直接覆盖。"""
    code = str(code or "")
    if len(code) < 40:
        return None, "音源内容太短，可能不是有效脚本"
    fn = _lx_safe_name(filename)
    if not _lx_ok_name(fn):
        return None, "文件名不合法"
    try:
        os.makedirs(LX_SOURCES_DIR, exist_ok=True)
        p = os.path.join(LX_SOURCES_DIR, fn)
        if os.path.isfile(p):
            same = False
            try:
                with open(p, "r", encoding="utf-8", errors="replace") as f:
                    same = f.read() == code
            except OSError:
                same = False
            if not same:
                stem = fn[:-3]
                n = 2
                while os.path.isfile(os.path.join(LX_SOURCES_DIR, "%s (%d).js" % (stem, n))):
                    n += 1
                fn = "%s (%d).js" % (stem, n)
                p = os.path.join(LX_SOURCES_DIR, fn)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(code)
    except OSError as e:
        return None, "写入失败：%s" % e
    return fn, None


def _lx_read_source(fn):
    fn = os.path.basename(str(fn or ""))
    if not _lx_ok_name(fn):
        return None
    p = os.path.join(LX_SOURCES_DIR, fn)
    try:
        with open(p, "r", encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def _lx_del_source(fn):
    fn = os.path.basename(str(fn or ""))
    if not _lx_ok_name(fn):
        return False, "文件名不合法"
    p = os.path.join(LX_SOURCES_DIR, fn)
    if not os.path.isfile(p):
        return False, "音源不存在"
    try:
        os.remove(p)
    except OSError as e:
        return False, str(e)
    return True, None


def _lx_http(url, method="GET", headers=None, body=None, body_enc="utf8", timeout=15):
    """服务端转发 HTTP（浏览器同源限制 / 防盗链都绕开）"""
    if not re.match(r"^https?://", str(url or ""), re.I):
        return {"ok": False, "error": "只允许 http/https 地址"}
    hdrs = {}
    for k, v in (headers or {}).items():
        if str(k).lower() in _LX_SKIP_HEADERS:
            continue
        hdrs[str(k)] = str(v)
    hdrs.setdefault("User-Agent", LX_UA)
    hdrs["Accept-Encoding"] = "identity"
    data = None
    if body:
        try:
            data = base64.b64decode(body) if body_enc == "base64" else str(body).encode("utf-8")
        except Exception:
            data = str(body).encode("utf-8")
    req = urllib.request.Request(str(url), data=data, headers=hdrs,
                                 method=str(method or "GET").upper())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            status = getattr(r, "status", 200)
            rh = {k: v for k, v in r.headers.items()}
    except urllib.error.HTTPError as e:
        try:
            raw = e.read() or b""
        except Exception:
            raw = b""
        status = e.code
        rh = dict(e.headers or {})
    except Exception as e:                                  # 网络错误 / 超时
        return {"ok": False, "error": str(e), "code": "EREQ"}
    out = {"ok": True, "status": status, "headers": rh}
    try:
        out["body"] = raw.decode("utf-8")
    except UnicodeDecodeError:
        out["bodyB64"] = base64.b64encode(raw).decode()
    return out


def _lx_safe_name(s):
    s = re.sub(r'[\\/:*?"<>|\r\n\t]', "", str(s or "")).strip()
    s = re.sub(r"\s+", " ", s)
    return s[:120] or "未命名"


def _lx_format_name(fmt, info):
    name = _lx_safe_name(info.get("name"))
    singer = _lx_safe_name(info.get("singer") or "未知")
    album = _lx_safe_name(info.get("album") or "")
    try:
        s = str(fmt or "{name} - {singer}").format(name=name, singer=singer, album=album)
    except (KeyError, IndexError, ValueError):
        s = "%s - %s" % (name, singer)
    return _lx_safe_name(s)


def _lx_dl_bytes(url, limit=8 * 1024 * 1024, timeout=20):
    if not url:
        return None
    try:
        req = urllib.request.Request(str(url), headers={"User-Agent": LX_DL_UA,
                                                        "Accept-Encoding": "identity"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read(limit + 1)
        if len(data) > limit:
            return None
        return data
    except Exception:
        return None


def _lx_write_tags(path, ext, info, cover, lyric):
    """写音频标签：标题/歌手/专辑 + 可选内嵌歌词与封面。
    对象是 mp3(ID3) / flac / m4a / ogg，失败不影响文件本身。"""
    ext = (ext or "").lower()
    title = info.get("name") or ""
    artist = info.get("singer") or ""
    album = info.get("album") or ""
    try:
        if ext == "mp3":
            from mutagen.id3 import (ID3, TIT2, TPE1, TALB, USLT, APIC,
                                     ID3NoHeaderError)
            try:
                tags = ID3(path)
            except ID3NoHeaderError:
                tags = ID3()
            if title:
                tags.setall("TIT2", [TIT2(encoding=3, text=[title])])
            if artist:
                tags.setall("TPE1", [TPE1(encoding=3, text=[artist])])
            if album:
                tags.setall("TALB", [TALB(encoding=3, text=[album])])
            if lyric:
                tags.setall("USLT::zho", [USLT(encoding=1, lang="zho", desc="", text=lyric)])
            if cover:
                mime = "image/png" if cover[:4] == b"\x89PNG" else "image/jpeg"
                # 先清干净：多张 APIC 会让部分播放器挑错图或不显示
                for _k in [k for k in tags.keys() if k.startswith("APIC")]:
                    del tags[_k]
                tags.add(APIC(encoding=0, mime=mime, type=3, desc="", data=cover))
            # Windows 媒体播放器只认 ID3v2.3 + 空 desc 的 APIC；v2.4 一律不显示封面
            try:
                tags.update_to_v23()
            except Exception:
                pass
            tags.save(path, v2_version=3)
        elif ext == "flac":
            from mutagen.flac import FLAC, Picture
            f = FLAC(path)
            if title:
                f["TITLE"] = title
            if artist:
                f["ARTIST"] = artist
            if album:
                f["ALBUM"] = album
            if lyric:
                f["LYRICS"] = lyric
            if cover:
                f.clear_pictures()
                pic = Picture()
                pic.type = 3
                pic.mime = "image/png" if cover[:4] == b"\x89PNG" else "image/jpeg"
                pic.desc = ""
                pic.data = cover
                f.add_picture(pic)
            f.save()
        elif ext in ("m4a", "mp4", "aac"):
            from mutagen.mp4 import MP4, MP4Cover
            m = MP4(path)
            if m.tags is None:
                m.add_tags()
            if title:
                m.tags["\xa9nam"] = [title]
            if artist:
                m.tags["\xa9ART"] = [artist]
            if album:
                m.tags["\xa9alb"] = [album]
            if lyric:
                m.tags["\xa9lyr"] = [lyric]
            if cover:
                fmt = MP4Cover.FORMAT_PNG if cover[:4] == b"\x89PNG" else MP4Cover.FORMAT_JPEG
                m.tags["covr"] = [MP4Cover(cover, imageformat=fmt)]
            m.save()
    except Exception as e:                                   # 标签写失败不该毁掉下载
        sys.stderr.write("[lx] tag failed: %s\n" % e)
        return False
    return True


def _lx_write_lrc(path, text, fmt):
    """独立歌词文件：UTF-8/GBK + BOM，与原版一致"""
    try:
        if str(fmt).lower() == "gbk":
            data = b"\xef\xbb\xbf" + text.encode("gbk", errors="replace")
            if data[:3] != b"\xef\xbb\xbf":
                data = text.encode("gbk", errors="replace")
        else:
            data = b"\xef\xbb\xbf" + text.encode("utf-8")
        with open(path, "wb") as f:
            f.write(data)
        return True
    except OSError:
        return False


def _lx_sniff_audio(path):
    """按文件头判断真实格式：音源经常「说要 flac 给的却是 mp3」，不能信请求参数"""
    try:
        with open(path, "rb") as f:
            h = f.read(16)
    except OSError:
        return ""
    if len(h) < 8:
        return ""
    if h[:3] == b"ID3" or h[:2] in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf2", b"\xff\xfa"):
        return "mp3"
    if h[:4] == b"fLaC":
        return "flac"
    if h[:4] == b"OggS":
        return "ogg"
    if h[4:8] == b"ftyp":
        return "m4a"
    if h[:4] == b"RIFF":
        return "wav"
    if h[:4] == b"MAC ":
        return "ape"
    return ""


# ---- 下载进度：前端轮询 /lx/progress?tid=xxx ----
_LX_DL_JOBS = {}
_LX_DL_LOCK = threading.Lock()


def _lx_job_set(tid, **kw):
    if not tid:
        return
    with _LX_DL_LOCK:
        j = _LX_DL_JOBS.get(tid)
        if j is None:
            j = {"phase": "prep", "done": 0, "total": 0, "pct": 0}
            _LX_DL_JOBS[tid] = j
        j.update(kw)
        j["ts"] = time.time()
        if len(_LX_DL_JOBS) > 32:                     # 顺手清陈旧任务
            now = time.time()
            for k in [k for k, v in _LX_DL_JOBS.items()
                      if k != tid and now - v.get("ts", 0) > 600]:
                del _LX_DL_JOBS[k]


def _lx_job_get(tid):
    with _LX_DL_LOCK:
        j = _LX_DL_JOBS.get(tid)
        return dict(j) if j else None


def _lx_download(payload):
    """下载一首：音频落盘 + 写标签 + 可选独立 lrc"""
    tid = str(payload.get("tid") or "").strip()[:40]
    if tid:
        _lx_job_set(tid, phase="prep", done=0, total=0, pct=0)
    url = payload.get("url")
    if not url:
        _lx_job_set(tid, phase="error", error="缺少下载地址")
        return {"ok": False, "error": "缺少下载地址"}
    cfg = _lx_cfg()
    info = {
        "name": payload.get("name") or "未命名",
        "singer": payload.get("singer") or "",
        "album": payload.get("album") or "",
    }
    want_ext = (payload.get("ext") or "mp3").lower().lstrip(".")
    if want_ext not in ("mp3", "flac", "m4a", "wav", "ogg", "aac", "ape"):
        want_ext = "mp3"
    out_dir = _lx_music_dir()
    try:
        os.makedirs(out_dir, exist_ok=True)
    except OSError as e:
        return {"ok": False, "error": "下载目录不可写：%s" % e}

    # 先下到临时文件，看清真实格式再决定文件名
    tmp = os.path.join(out_dir, "_dl_%d.part" % os.getpid())
    try:
        req = urllib.request.Request(str(url), headers={
            "User-Agent": LX_DL_UA, "Accept-Encoding": "identity",
            "Referer": payload.get("referer") or "",
        })
        deadline = time.time() + 600          # 音源限速时别无限挂着
        with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
            total = 0
            try:
                total_bytes = int(r.headers.get("Content-Length") or 0)
            except (TypeError, ValueError):
                total_bytes = 0
            _lx_job_set(tid, phase="fetch", done=0, total=total_bytes,
                        pct=0, bytes=total_bytes)
            last = 0.0
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                total += len(chunk)
                if total > 512 * 1024 * 1024:
                    raise IOError("文件超过 512MB")
                if time.time() > deadline:
                    raise IOError("下载超时（超过 10 分钟）")
                f.write(chunk)
                now = time.time()
                if now - last > 0.1:          # 节流，别把锁刷爆
                    last = now
                    _lx_job_set(tid, phase="fetch", done=total,
                                total=total_bytes,
                                pct=int(total * 100 / total_bytes) if total_bytes else 0)
        if total < 1024:
            raise IOError("下载内容过小（%d 字节），可能被拒绝" % total)
        _lx_job_set(tid, phase="fetch", done=total, total=total_bytes or total, pct=100)
    except Exception as e:
        try:
            os.remove(tmp)
        except OSError:
            pass
        _lx_job_set(tid, phase="error", error="下载失败：%s" % e)
        return {"ok": False, "error": "下载失败：%s" % e}

    ext = _lx_sniff_audio(tmp) or want_ext
    _lx_job_set(tid, phase="tag", done=total, total=total, pct=100)

    base = _lx_format_name(cfg.get("name_format"), info)
    path = os.path.join(out_dir, "%s.%s" % (base, ext))
    n = 1
    while os.path.exists(path):
        path = os.path.join(out_dir, "%s (%d).%s" % (base, n, ext))
        n += 1

    cover = None
    if cfg.get("embed_cover") and payload.get("cover"):
        cover = _lx_dl_bytes(payload["cover"], limit=6 * 1024 * 1024)
    lyric = payload.get("lyric") or ""
    tagged = _lx_write_tags(tmp, ext, info,
                            cover, lyric if cfg.get("embed_lyric") else "")

    try:
        os.replace(tmp, path)
    except OSError as e:
        try:
            os.remove(tmp)
        except OSError:
            pass
        _lx_job_set(tid, phase="error", error="保存失败：%s" % e)
        return {"ok": False, "error": "保存失败：%s" % e}

    lrc_path = ""
    if cfg.get("save_lrc") and lyric:
        lrc_path = os.path.splitext(path)[0] + ".lrc"
        if not _lx_write_lrc(lrc_path, lyric, cfg.get("lrc_format")):
            lrc_path = ""

    size = os.path.getsize(path)
    _lx_job_set(tid, phase="done", pct=100, path=path, size=size,
                name=os.path.basename(path))
    return {"ok": True, "path": path, "lrc": lrc_path, "tagged": tagged,
            "ext": ext, "want_ext": want_ext,
            "size": size, "dir": out_dir}


def _lx_open_dir(path):
    p = path or _lx_music_dir()
    try:
        os.makedirs(p, exist_ok=True)
        if os.name == "nt":
            os.startfile(p)                                  # noqa: 只在 Windows
        else:
            subprocess.Popen(["xdg-open", p], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


_COMP_JS_TEMPLATE = """/* 组件入口。文件名/目录名就是组件 id，注册时用同一个字符串。
   想改名字、介绍，改同目录的 manifest.json。 */
Cyril.component('__ID__', {
  mount: function (root, api) {
    // root：组件自己的容器（左栏里的一个小方块）
    // api：{ id, send(patch), open(panel), every(fn, ms), clear() }
    root.innerHTML = '<div class="cyc-card"><b>__NAME__</b>'
      + '<span class="cyc-time">--:--:--</span></div>';
    var el = root.querySelector('.cyc-time');
    api.every(function () {
      var d = new Date();
      var p = function (n) { return (n < 10 ? '0' : '') + n; };
      el.textContent = p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    }, 1000);
    // api.clear() 会在卸载时自动清掉 every 开的定时器
  },
  unmount: function () {}
});
"""

_COMP_CSS_TEMPLATE = """/* 组件样式。根节点是组件容器，宽度由 manifest.json 的 width 决定（默认 44）。 */
.cyc-card {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  font-size: 9px; line-height: 1.3; color: var(--cy-label);
}
.cyc-card b { font-weight: normal; color: var(--cy-dim); }
.cyc-time { font-size: 11px; color: var(--cy-text); font-variant-numeric: tabular-nums; }
"""


def _comp_create(cid, name, desc):
    """在用户组件目录里新建一个可用的模板组件（时钟）。返回 (ok, error)"""
    cid = (cid or "").strip().lower()
    if not _COMP_ID_RE.match(cid):
        return False, "组件 id 只能用小写字母、数字、下划线、短横线，且以字母或数字开头（≤32 位）"
    d = os.path.join(COMPONENTS_PATH, cid)
    if os.path.exists(d):
        return False, "已经有同名组件了"
    name = (name or cid).strip()[:40] or cid
    try:
        os.makedirs(d, exist_ok=True)
        man = {"id": cid, "name": name, "desc": (desc or "").strip()[:200],
               "version": "1.0", "author": "", "script": "index.js", "style": "index.css"}
        with open(os.path.join(d, "manifest.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(man, f, ensure_ascii=False, indent=2)
        with open(os.path.join(d, "index.js"), "w", encoding="utf-8", newline="\n") as f:
            f.write(_COMP_JS_TEMPLATE.replace("__ID__", cid).replace("__NAME__", name))
        with open(os.path.join(d, "index.css"), "w", encoding="utf-8", newline="\n") as f:
            f.write(_COMP_CSS_TEMPLATE)
    except OSError as exc:
        return False, "写入失败：%s" % exc
    return True, ""


def _comp_delete(cid):
    """只删用户目录里的组件；自带示例 / 内置组件一律拒绝"""
    if not _COMP_ID_RE.match(cid or ""):
        return False, "组件 id 不合法"
    d = os.path.join(COMPONENTS_PATH, cid)
    if not os.path.isdir(d):
        if os.path.isdir(os.path.join(_COMPONENTS_SHIPPED, cid or "")):
            return False, "这是随程序自带的示例组件，不能删除（可以关掉）"
        return False, "组件不存在"
    try:
        shutil.rmtree(d)
    except OSError as exc:
        return False, "删除失败：%s" % exc
    # 目录没了，但已 import 的模块还在内存里：不摘掉的话它的前缀路由会继续响应，
    # 静态文件却已经 404（看起来像半失效）。这里同步卸载。
    try:
        _plugin_unload(cid)
    except Exception as exc:
        sys.stderr.write("[webterm] 插件卸载失败 %s：%s\n" % (cid, exc))
    return True, ""


def _settings_path():
    base = (os.path.dirname(os.path.abspath(sys.executable))
            if getattr(sys, "frozen", False) else _BASE)
    return os.path.join(base, "Settings.json")


SETTINGS_PATH = _settings_path()

# 老版本单独的 AI 配置文件：只用于首次迁移，之后以 Settings.json 为准
def _ai_config_path():
    base = (os.path.dirname(os.path.abspath(sys.executable))
            if getattr(sys, "frozen", False) else _BASE)
    return os.path.join(base, "ai-config.json")


AI_CONFIG_PATH = _ai_config_path()

_settings = {}
_settings_lock = threading.Lock()
# 配置文件是否真的存在过。刚装/刚清空时它没有：这时 get 要回空对象，
# 否则前端会拿「默认值」把用户当前的操作覆盖掉（例如把刚打开的树关掉）。
_settings_exist = False


def _settings_default():
    return {
        "version": 1,
        # 启动页：super（银河超级终端）| terminal（直接进终端）
        "start_page": "super",
        # 超级终端四个节点的位置（百分比）：{music:[x,y], file:[...], ...}
        # 拖动即写这里；localStorage 只是连上后端前的缓存
        "super_nodes": {},
        "theme": "dark",          # dark | light
        "bg_level": 0,            # 背景明暗 -100..100
        "bg_kind": "",            # '' | custom | video（图片/视频本体在 IndexedDB）
        "bg_source": "builtin",   # builtin | sonic | lumiere
        "tree_width": 280,
        "tree_open": False,
        "panels": {},             # 浮层位置：{appearance:{x,y}, lyrics:{...}, ...}
        "ai": {},                 # AI 配置（原 ai-config.json 的内容）
        # 可选组件开关（左侧实时电平 / 系统占用）；默认都关，用户自己去【组件】里点开
        "components": {"audio": False, "sys": False},
        # 混响 + 环绕参数（EQ 面板下半部分）
        "fx": {"wet": 0, "damp": 45, "room": 35, "gain": 100, "pre": 20, "sur": 0},
        # ===== 【设置】面板 =====
        # 服务：端口 / 是否允许局域网访问。这俩只能在进程 bind 之前读，所以
        # load_settings() 提前到 main() 开头；命令行 --port / --remote 优先。
        # 改完必须重启程序（Web 版 exe / Electron 壳都会重读同一份 Settings.json）。
        "port": PORT,
        "remote": False,
        # 顶栏联动：默认关，鼠标停在顶栏只显示顶栏自己；开启后组件栏 /
        # 文件目录 / 音乐控制栏才一起显出来（避免点右上角窗口按钮时全部弹出）
        "link_bars": False,
        # 自动隐藏鼠标光标：hide 开关 + 静止多少秒后隐藏
        "cursor": {"hide": False, "delay": 3},
        # 终端字体：等宽度量字体优先（可变字体如 Cascadia 会让 xterm 度量错位）
        "terminal": {
            "font_family": 'Consolas, "Microsoft YaHei", "Courier New", monospace',
            "font_size": 15,
            "line_height": 1.0,
            # 终端空闲隐藏：一段时间不操作就把终端文字淡出；false 即完全不启用
            "idle_hide": True,
            "idle_delay": 60,
        },
    }


def load_settings():
    """启动时读配置；不存在/损坏都退回默认值，绝不因为配置坏了起不来"""
    global _settings
    data = {}
    try:
        with open(SETTINGS_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    if not isinstance(data, dict):
        data = {}
    global _settings_exist
    merged = _settings_default()
    for k, v in data.items():
        merged[k] = v          # 未知键也保留：新版本写的字段不被老版本吃掉
    _settings = merged
    _settings_exist = bool(data)

    # 一次性迁移：老版本把 AI 配置写在 ai-config.json，Settings.json 里还没有 ai 段
    if not _settings.get("ai"):
        try:
            with open(AI_CONFIG_PATH, "r", encoding="utf-8") as f:
                old = json.load(f)
        except (OSError, ValueError):
            old = None
        if isinstance(old, dict):
            ai = {k: old[k] for k in
                  ("enabled", "base_url", "api_key", "model", "temperature")
                  if k in old}
            if ai:
                _settings["ai"] = ai
                # 迁移完立刻落盘：以后就以 Settings.json 为准，ai-config.json 只是旧账本
                save_settings()
    return _settings


def save_settings():
    with _settings_lock:
        try:
            global _settings_exist
            with open(SETTINGS_PATH, "w", encoding="utf-8", newline="\n") as f:
                json.dump(_settings, f, ensure_ascii=False, indent=2)
            _settings_exist = True
            return True
        except OSError:
            return False


def settings_patch(patch):
    """合并写入：顶层键直接覆盖；panels / ai 这类 dict 按子键合并
    （只拖了一个浮层，不能把其它浮层的位置冲掉）"""
    if not isinstance(patch, dict):
        return False
    for k, v in patch.items():
        if isinstance(v, dict) and isinstance(_settings.get(k), dict):
            _settings[k].update(v)
        else:
            _settings[k] = v
    return save_settings()


def settings_public():
    """给前端下发的配置副本：绝不把 ai.api_key 发到浏览器（只给 has_key）。
    AI 面板本来就用 ai_config_get 回读，不需要明文 key。"""
    if not isinstance(_settings, dict):
        return {}
    data = json.loads(json.dumps(_settings, ensure_ascii=False))
    ai = data.get("ai")
    if isinstance(ai, dict):
        ai["has_key"] = bool((ai.get("api_key") or "").strip())
        ai.pop("api_key", None)
    return data


REMOTE_BLOCKED_SETTINGS = ("ai", "port", "remote")


def settings_remote_filter(patch):
    """远程连接只能改外观类配置；AI 配置 / 监听端口 / 远程开关一律丢弃。
    返回 (过滤后的 patch, 被丢弃的键)。"""
    dropped = []
    if not isinstance(patch, dict):
        return {}, dropped
    kept = dict(patch)
    for k in REMOTE_BLOCKED_SETTINGS:
        if k in kept:
            kept.pop(k)
            dropped.append(k)
    return kept, dropped


# ---------------- AI：自定义 API（OpenAI 兼容） ----------------
# 本地 llama-server 之外的第二种后端：可填任意 OpenAI 兼容服务
# （DeepSeek / 通义 / Moonshot / OpenAI / 自建 one-api 等）。
# 配置存在 Settings.json 的 ai 段里（明文，注意不要外传）。
_ai_cfg = {
    "enabled": False,
    "base_url": "",
    "api_key": "",
    "model": "",
    "temperature": 0.7,
    # 输出上限（token）。0 = 不主动限制：自定义 API 省略该字段（用服务端默认），
    # 本地路径用 2048 兜底。思考模型（Qwen3 / DeepSeek-R1 等）会先输出几百上千
    # token 的 <think> 推理段，写死小值会把正文整段掐掉（实测 300 全被思考吃光，
    # 气泡里一个字都没有），所以这里默认放开、由 persona 约束回答长度。
    "max_tokens": 0,
}


def load_ai_config():
    """从 Settings.json 的 ai 段套回运行期配置（老 ai-config.json 已由 load_settings 迁移）"""
    ai = _settings.get("ai")
    if not isinstance(ai, dict):
        return
    for k in list(_ai_cfg.keys()):
        if k in ai:
            _ai_cfg[k] = ai[k]


def save_ai_config():
    """AI 配置写回 Settings.json（不再单独维护 ai-config.json）"""
    _settings["ai"] = dict(_ai_cfg)
    return save_settings()


def _normalize_endpoint(url):
    """把用户填的地址补全成 .../v1/chat/completions（容忍只填到域名或 /v1）"""
    u = (url or "").strip()
    if not u:
        return ""
    u = u.rstrip("/")
    if u.endswith("/chat/completions"):
        return u
    if u.endswith("/v1"):
        return u + "/chat/completions"
    return u + "/v1/chat/completions"


def ai_custom_active():
    return bool(_ai_cfg.get("enabled") and _normalize_endpoint(_ai_cfg.get("base_url")))


def ai_ready():
    """任一路径可用即视为就绪：自定义 API 优先，否则看本地 llama"""
    return ai_custom_active() or llama_ready()


def ai_model_name():
    if ai_custom_active():
        return (_ai_cfg.get("model") or "").strip() or "(未填模型名)"
    return os.path.basename(_llama_model) if _llama_model else None



# ---------------- AI：人设与互动 ----------------
# 女朋友人设 + 终端助手能力（system prompt 注入；"正在听的歌"动态拼接）。
# 注意长度：这段是每轮请求的公共前缀，CPU 上的小模型对 token 很敏感——
# 2026-11-14 精简版 928 字 → 约 470 字，prefill 时间约减半；措辞只保留
# 会改变模型行为的约束（称呼、句数、命令格式、风险提醒），客套描写全删。
AI_SYSTEM_PROMPT = (
    "你是他的女朋友兼终端助手，叫他“宝宝”，语气亲昵俏皮爱撒娇，像恋人聊天，"
    "不要官方、不要说教。他在 Windows PowerShell 终端里工作。规则：中文回答，"
    "一般 2~4 句，不写长教程；他问怎么做就给可直接粘贴的 PowerShell 命令（用 ``` 包裹），"
    "删除/格式化等危险操作先提醒一句风险；他贴报错就帮忙解读并鼓励他。"
)

# 主动聊天的触发配置
PROACTIVE_MIN_GAP = 120      # 两次主动说话最短间隔（秒），防切歌刷屏
PROACTIVE_INTERVAL = 600     # 定时主动聊天的周期（秒，10 分钟；可在设置面板 1~10 分钟调节）

_now_playing = None          # {title, artist, lyrics} 前端上报的当前歌曲
_active_conn = None          # 当前活跃 WebSocket 连接（主动推送用）
_ai_busy = False             # 是否有正在进行的 AI 流（防前后端并发流）
_proactive_lock = threading.Lock()
_proactive_ts = 0.0          # 上次主动说话时间
_proactive_enabled = True    # AI 主动聊天总开关（设置面板可关闭）
_proactive_interval = PROACTIVE_INTERVAL   # 定时主动间隔（秒，设置面板 1~10 分钟）
_terminal_history = collections.deque(maxlen=20)   # 最近完成的终端命令（AI 感知）
_library = []                # 前端上报的音乐库 [{title, artist, hasLrc}]，供 AI 点歌
_input_buf = ""              # 正在拼装的命令缓冲（input 帧逐字符/逐段到达）

# 音乐播放器控制指令：AI 通过 ```player 代码块输出 JSON 动作，前端解析执行。
# 放在独立常量里，方便单独调措辞而不动主 persona。
# 措辞经过实测调优：本地 1B 小模型只有在这种「先说格式 + 给示例 + 禁止反问」的写法下，
# 才会稳定吐 player 块。2026-11-14 精简（570→约 380 字）时踩过一次回归：把 next 的
# 示例压成内联 JSON 后，模型跟着输出**不带 ```player 围栏**的裸 JSON，前端解析不到。
# 结论：至少要保留一个非 play 动作的完整代码块示例，其余动作才能压成一行清单。
PLAYER_PROMPT = (
    "\n\n【播放器控制】只要他的意思涉及播放音乐，必须先输出一个 ```player 代码块再说话，"
    "不许反问、不许只嘴上答应。块内 JSON 一次一个动作：\n"
    "```player\n{\"action\":\"play\",\"query\":\"歌名或歌手\"}\n```\n"
    "```player\n{\"action\":\"next\"}\n```\n"
    "动作：play(query=歌名 或 index=序号) / pause / resume / next / prev / stop / "
    "volume(level=0~100) / seek(seconds) / loop(mode=off|all|one) / shuffle(on) / queue / lyrics。\n"
    "示例：“安静点”→ {\"action\":\"volume\",\"level\":30}。\n"
    "他问的是系统/命令等与音乐无关的事时，不要输出 player 块。\n"
    "一次回复最多一个 player 块，JSON 必须合法；点歌先在下面的曲库里找，没有就说没有，不编歌名。"
)




def _clean_cmdline(s):
    """清洗终端输入：去掉 ANSI/方向键/控制序列，处理退格，只保留可打印字符"""
    s = re.sub(r"\x1b\][^\x07\x1b]*(\x07|\x1b\\)", "", s)   # OSC 序列（标题等）
    s = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", s)          # CSI 序列（方向键/颜色）
    s = re.sub(r"\x1b[()][0-9A-B]", "", s)                   # 字符集切换
    out = []
    for ch in s:
        if ch in ("\x7f", "\x08"):
            if out:
                out.pop()
        elif ord(ch) >= 32 and ch != "\x7f":
            out.append(ch)
    return "".join(out).strip()


# 终端内置指令（音乐 / AI）不算「用户在电脑上干活」，不能进 AI 的感知上下文。
# 实测：把 `Cyril-Stop -0.1s` 记进历史后，下一轮它被模型当成了「用户想听的歌」，
# 直接回「♪ 曲库里没找到《Cyril-Stop -0.1s》」，非常出戏。
# 注意不拦 start —— 它没被本功能占用，`start notepad` 是正常的 shell 操作。
_CYRIL_CMD_RE = re.compile(
    r"^(?:cyril-(?:start|stop|ai|music)|play|stop|ai|music)(?:\s|$)", re.I)


def _is_cyril_cmd(text):
    return bool(_CYRIL_CMD_RE.match((text or "").strip()))


def _feed_terminal_input(data):
    """累积终端输入，回车时把完整命令记入 AI 感知历史，并触发主动点评（节流）"""
    global _input_buf
    if not data:
        return
    _input_buf += data
    if "\r" not in _input_buf and "\n" not in _input_buf:
        return
    lines = re.split(r"[\r\n]+", _input_buf)
    _input_buf = lines.pop() if lines else ""   # 最后一段可能未完成，留回缓冲
    for ln in lines:
        cmd = _clean_cmdline(ln)
        if not cmd:
            continue
        if _is_cyril_cmd(cmd):
            continue        # 内置指令：不喂模型，也不触发主动点评
        _terminal_history.append({"text": cmd, "ts": time.time()})
        _maybe_proactive("cmd")


def _build_system_prompt():
    """人设 prompt + 当前播放 / 音乐库 / 终端操作上下文（动态拼接）

    注意两件事：
      · 长度：本地小模型上下文只有 4096，动态段（歌词/曲库/命令）每轮都变，
        变了就吃 prefill。2026-11-14 起收紧：歌词 12→6 行、曲库 30→20 首、
        命令 5→3 条，宁可少给也不能把上下文撑爆（撑爆会让模型答非所问）。
      · 顺序：静态段在前、动态段在后——llama-server 会复用公共前缀的 KV 缓存
        （配合请求体 cache_prompt:true），静态段每轮免重算，顺序别调换。
    """
    extra = ""
    if _now_playing and _now_playing.get("title"):
        extra += ("\n他正在听：《%s》%s。可以自然聊几句这首歌。"
                  % (_now_playing["title"],
                     " - " + _now_playing["artist"] if _now_playing.get("artist") else ""))
        lyr = _now_playing.get("lyrics") or []
        if lyr:
            snippet = "\n".join("  " + str(x)[:34] for x in lyr[:6])
            extra += "\n歌词片段：\n%s\n" % snippet
    if _library:
        names = "、".join(
            t.get("title", "") + ("(%s)" % t["artist"] if t.get("artist") else "")
            for t in _library[:20] if t.get("title"))
        extra += ("\n音乐库共 %d 首（列前 20）：%s。他想听什么时先在这里找。" % (len(_library), names))
    if _terminal_history:
        cmds = "、".join("“%s”" % c["text"][:40]
                         for c in list(_terminal_history)[-3:])
        extra += ("\n他刚在终端输入过：%s。结合他正在做的事关心一句，不要写成教程。" % cmds)
    return AI_SYSTEM_PROMPT + PLAYER_PROMPT + extra


def _safe_send(conn, text):
    """向连接发 text 帧，断开时静默失败（主动推送容错）"""
    try:
        conn.sendall(encode_text_frame(text))
    except Exception:
        pass


def _maybe_proactive(reason):
    """触发主动聊天（节流）：hello=新连接欢迎 / song=切歌点评 / cmd=命令点评 / timer=定时问候"""
    global _proactive_ts
    if not _proactive_enabled:      # 设置面板关闭主动聊天后全部不触发
        return
    if not ai_ready() or _ai_busy:
        return
    now = time.time()
    with _proactive_lock:
        if now - _proactive_ts < PROACTIVE_MIN_GAP:
            return
        _proactive_ts = now
    threading.Thread(target=_proactive_worker, args=(reason,), daemon=True).start()


def _proactive_worker(reason):
    """后台线程：AI 主动说一句话（流式推到前端 AI 面板，不写终端）"""
    conn = _active_conn
    if conn is None:
        return
    if reason == "hello":
        guide = "用户刚刚打开终端页面。主动打个招呼，简短撒个娇，问问今天在做什么、要不要一起听歌。"
    elif reason == "song":
        if _now_playing and _now_playing.get("title"):
            guide = ("用户切歌了，正在听《%s》%s。就这首歌自然地聊一两句。"
                     % (_now_playing["title"],
                        " - " + _now_playing["artist"] if _now_playing.get("artist") else ""))
        else:
            guide = "用户停止了播放。随口问一句他接下来想听什么。"
    elif reason == "cmd":
        if _terminal_history:
            last = _terminal_history[-1]["text"]
            guide = ("用户刚在终端输入了命令：%s。就这条命令自然地聊一两句："
                     "关心他在做什么、给一句简短建议或撒个娇；不要复述整条命令，不要写教程。"
                     % last)
        else:
            return
    else:
        guide = "主动找他说一两句话，自然一点，可以问问他在忙什么，或者聊聊他正在听的歌（如果有）。"
    messages = [
        {"role": "system", "content": _build_system_prompt()},
        {"role": "user",
         "content": "（系统提示：%s 请主动说出这句话，不要复述括号里的提示。）" % guide},
    ]
    try:
        conn.sendall(encode_text_frame(json.dumps({"type": "ai_proactive"})))
    except Exception:
        return
    try:
        # 必须包装成 ai_chunk JSON 协议帧；直接发裸文本会被前端当终端输出 term.write 打进终端
        ai_chat_stream(messages, lambda t: _safe_send(
            conn, json.dumps({"type": "ai_chunk", "text": t})))
    finally:
        _safe_send(conn, json.dumps({"type": "ai_done"}))


def _proactive_loop():
    """后台守护线程：每 60s 检查一次：到周期就主动找用户说话；
    若从未主动过（如模型加载太慢错过 hello），就绪后补一次问候"""
    global _proactive_ts
    while True:
        time.sleep(60)
        if (_proactive_enabled and _active_conn is not None
                and ai_ready() and not _ai_busy):
            with _proactive_lock:
                reason = None
                if _proactive_ts == 0:
                    _proactive_ts = time.time()
                    reason = "hello"      # 补发首条主动问候
                elif time.time() - _proactive_ts >= _proactive_interval:
                    _proactive_ts = time.time()
                    reason = "timer"
                if reason:
                    threading.Thread(target=_proactive_worker,
                                     args=(reason,), daemon=True).start()


class _ThinkFilter(object):
    """流式过滤 <think>...</think> 推理段（Qwen3 / DeepSeek-R1 等思考模型）。

    SSE 的 delta 很小，标签可能被切在任意两个分块之间（如 "</th" + "ink>"），
    所以用跨块缓冲 + 部分前缀匹配：正文态扣住可能是 "<think>" 前缀的尾部，
    思考态丢弃内容、只扣住可能是 "</think>" 前缀的尾部。
    """

    OPEN = "<think>"
    CLOSE = "</think>"

    def __init__(self):
        self.state = 0        # 0 = 正文，1 = 思考中
        self.buf = ""
        self._strip_nl = False  # 刚出思考段时吃掉紧随的换行/空白

    @staticmethod
    def _hold_len(buf, tag):
        """buf 尾部能构成 tag 前缀的最大长度（0 = 没有可疑前缀）"""
        for k in range(min(len(tag) - 1, len(buf)), 0, -1):
            if tag.startswith(buf[-k:]):
                return k
        return 0

    def feed(self, text):
        """喂入一个 delta，返回本次应展示的正文（可能为空）"""
        out = []
        self.buf += text
        while True:
            if self.state == 0:
                i = self.buf.find(self.OPEN)
                if i >= 0:
                    out.append(self.buf[:i])
                    self.buf = self.buf[i + len(self.OPEN):]
                    self.state = 1
                    self._strip_nl = True
                    continue
                hold = self._hold_len(self.buf, self.OPEN)
                keep = self.buf[len(self.buf) - hold:] if hold else ""
                emit = self.buf[:len(self.buf) - hold] if hold else self.buf
                if self._strip_nl:
                    stripped = emit.lstrip()
                    # 只有真吐出了可见正文才消费标志；空段/挂起前缀继续留着吃换行
                    if stripped:
                        self._strip_nl = False
                    emit = stripped
                self.buf = keep
                if emit:
                    out.append(emit)
                return "".join(out)
            else:
                j = self.buf.find(self.CLOSE)
                if j >= 0:
                    self.buf = self.buf[j + len(self.CLOSE):]
                    self.state = 0
                    self._strip_nl = True
                    continue
                hold = self._hold_len(self.buf, self.CLOSE)
                self.buf = self.buf[len(self.buf) - hold:] if hold else ""
                return "".join(out)

    def flush(self):
        """流结束调用：残留缓冲按当前态处理（正文态要吐出来，思考态直接丢）"""
        rest = self.buf
        self.buf = ""
        if self.state == 1:
            return ""
        if self._strip_nl:
            rest = rest.lstrip()
            self._strip_nl = False
        return rest


def ai_chat_stream(messages, send):
    """流式对话：自定义 API 优先，其次本地 llama-server。

    两者都走 OpenAI 兼容的 /v1/chat/completions + SSE，解析逻辑共用一份。
    """
    temp = 0.7
    if ai_custom_active():
        url = _normalize_endpoint(_ai_cfg.get("base_url"))
        headers = {"Content-Type": "application/json"}
        key = (_ai_cfg.get("api_key") or "").strip()
        if key:
            headers["Authorization"] = "Bearer " + key
        model = (_ai_cfg.get("model") or "").strip() or "gpt-4o-mini"
        try:
            temp = float(_ai_cfg.get("temperature") or 0.7)
        except (TypeError, ValueError):
            temp = 0.7
    else:
        url = "http://127.0.0.1:%d/v1/chat/completions" % LLAMA_PORT
        headers = {"Content-Type": "application/json"}
        model = os.path.basename(_llama_model) if _llama_model else "local"

    try:
        mt = int(_ai_cfg.get("max_tokens") or 0)
    except (TypeError, ValueError):
        mt = 0
    if mt <= 0 and not ai_custom_active():
        mt = 2048  # 本地路径兜底：思考模型要留足推理余量，同时掐住 1B 的失控复读

    body = json.dumps({
        "model": model,
        "messages": messages,
        "stream": True,
        "temperature": temp,
        # 输出上限：思考模型（Qwen3 / R1）的 <think> 推理段可能上千 token，
        # 写死小值会把正文整段掐掉（实测 300 全被思考吃光）。0 = 不主动限制：
        # 自定义 API 省略字段用服务端默认；本地路径给 2048 兜底（掐住失控复读）。
        # **只对正文计数的服务端（部分云 API）思考段另计，故不要在这里设小值。**
        **({"max_tokens": mt} if mt > 0 else {}),
        # cache_prompt 是 llama-server 专用字段（OpenAI 官方 API 不认未知参数，会 400），
        # 所以只在本地路径带：复用公共前缀 KV，静态人设段每轮免重算 prefill。
        **({} if ai_custom_active() else {"cache_prompt": True}),
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers)
    tf = _ThinkFilter()
    shown_any = False
    finish = None
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            for line in resp:
                line = line.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    break
                try:
                    obj = json.loads(payload)
                except Exception:
                    continue
                # 部分服务在流里回错误对象（如 {"error":{...}}），原样告知用户
                if isinstance(obj, dict) and obj.get("error") and not obj.get("choices"):
                    err = obj["error"]
                    msg = err.get("message") if isinstance(err, dict) else str(err)
                    send("\r\n[AI 接口报错] %s\r\n" % (msg or err))
                    shown_any = True
                    continue
                ch0 = (obj.get("choices") or [{}])[0]
                delta = ch0.get("delta") or {}
                if ch0.get("finish_reason"):
                    finish = ch0.get("finish_reason")
                text = delta.get("content") or ""
                if not text:
                    continue  # delta.reasoning_content（思考段单独字段）也一并跳过
                shown = tf.feed(text)
                if shown:
                    send(shown)
                    shown_any = True
        tail = tf.flush()
        if tail:
            send(tail)
            shown_any = True
        # 全程没有任何可见输出：多半是思考段把输出预算吃光（finish=length）或服务忙，
        # 给一句提示，别让气泡永远停在占位符上
        if not shown_any:
            if finish == "length":
                send("\r\n[模型把输出额度全用在了思考上，正文被截断；"
                     "可在 AI 面板把「输出上限」调大，或换非思考模型]\r\n")
            else:
                send("\r\n[模型没有返回内容（服务可能正忙），请重试]\r\n")
    except Exception as exc:
        hint = ""
        if ai_custom_active():
            hint = "（自定义 API：%s）" % url
        send("\r\n[AI 请求失败: %r]%s\r\n" % (exc, hint))


# ---------------- HTTP + WebSocket 处理 ----------------
# ---------------- 登录 / 用户 / 会话 ----------------
# 规则（前端与之约定）：
#   · 回环地址（127.0.0.1 / ::1）免登录，直接是管理员 —— 本机本来就是机器所有者的；
#   · 局域网（--remote）来的连接必须登录，**没有注册入口**，账号只能由管理员建；
#   · 管理员可以增删用户、改任意用户的用户名与密码；
#   · 口令只存 PBKDF2-HMAC-SHA256 派生值（20 万轮 + 16 字节随机盐），不存明文。
USERS_PATH = os.path.join(os.path.dirname(SETTINGS_PATH), "users.json")
_PBKDF2_ROUNDS = 200_000
_SESSION_TTL = 7 * 24 * 3600          # 7 天
_COOKIE_NAME = "cyril_sid"
_MAX_FAILS = 6                        # 同一 IP 连续失败次数
_FAIL_LOCK_SEC = 600                  # 超过就锁 10 分钟

_sessions = {}                        # token -> {name, admin, ip, exp}
_sessions_lock = threading.Lock()
_login_fails = {}                     # ip -> [失败次数, 解禁时间戳]
_fail_lock = threading.Lock()
_users_cache = {"mtime": 0.0, "data": None}
_users_lock = threading.Lock()


def _hash_password(password, salt=None, rounds=_PBKDF2_ROUNDS):
    if salt is None:
        salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, rounds)
    return "pbkdf2_sha256$%d$%s$%s" % (
        rounds, base64.b64encode(salt).decode(), base64.b64encode(dk).decode())


def _verify_password(password, stored):
    """常数时间比较：不要把 == 用在口令上。"""
    try:
        algo, rounds, salt_b64, dk_b64 = str(stored).split("$")
        if algo != "pbkdf2_sha256":
            return False
        salt = base64.b64decode(salt_b64)
        want = base64.b64decode(dk_b64)
        got = hashlib.pbkdf2_hmac("sha256", (password or "").encode("utf-8"), salt, int(rounds))
        return hmac.compare_digest(got, want)
    except (ValueError, TypeError, base64.binascii.Error):
        return False


def _users_default():
    return {"version": 1, "users": []}


def _users_load():
    """读 users.json（按 mtime 缓存，手工改文件后也能立刻生效）"""
    with _users_lock:
        try:
            mt = os.path.getmtime(USERS_PATH)
        except OSError:
            return _users_default()
        if _users_cache["data"] is not None and mt == _users_cache["mtime"]:
            return json.loads(json.dumps(_users_cache["data"]))
        try:
            with open(USERS_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            data = _users_default()
        if not isinstance(data, dict) or not isinstance(data.get("users"), list):
            data = _users_default()
        _users_cache.update(mtime=mt, data=data)
        return json.loads(json.dumps(data))


def _users_save(data):
    tmp = USERS_PATH + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp, USERS_PATH)
        try:
            os.chmod(USERS_PATH, 0o600)     # 非 Windows 上让口令文件只对本人可读
        except OSError:
            pass
        _users_cache.update(mtime=os.path.getmtime(USERS_PATH), data=data)
        return True
    except OSError:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        return False


_NAME_RE = re.compile(r"^[^\x00-\x1f\x7f]{1,32}$")


def _valid_name(name):
    return bool(isinstance(name, str) and _NAME_RE.match(name.strip()))


def _valid_pass(pw):
    return isinstance(pw, str) and 6 <= len(pw) <= 128


def _users_public(data=None):
    """对外只给用户名/创建时间，绝不带 hash"""
    data = data or _users_load()
    return [{"name": u.get("name", ""), "created": u.get("created", 0),
             "note": u.get("note", "")} for u in data["users"]]


def _new_session(name, admin, ip):
    tok = secrets.token_urlsafe(32)
    now = time.time()
    with _sessions_lock:
        for k, v in list(_sessions.items()):     # 顺手清过期，别让字典无限长
            if v["exp"] < now:
                _sessions.pop(k, None)
        _sessions[tok] = {"name": name, "admin": bool(admin), "ip": ip,
                          "exp": now + _SESSION_TTL}
    return tok


def _session_get(tok):
    if not tok:
        return None
    now = time.time()
    with _sessions_lock:
        s = _sessions.get(tok)
        if not s:
            return None
        if s["exp"] < now:
            _sessions.pop(tok, None)
            return None
        return dict(s)


def _session_drop(tok):
    with _sessions_lock:
        _sessions.pop(tok, None)


def _sessions_drop_user(name):
    """改了名字或口令就把该用户所有会话踢掉（旧口令不该还能用）"""
    with _sessions_lock:
        for k, v in list(_sessions.items()):
            if v["name"] == name:
                _sessions.pop(k, None)


def _login_allowed(ip):
    now = time.time()
    with _fail_lock:
        rec = _login_fails.get(ip)
        if not rec:
            return True
        cnt, until = rec
        return not (until > now and cnt >= _MAX_FAILS)


def _login_fail(ip):
    with _fail_lock:
        cnt, until = _login_fails.get(ip, [0, 0])
        cnt += 1
        if cnt >= _MAX_FAILS:
            until = time.time() + _FAIL_LOCK_SEC
        _login_fails[ip] = [cnt, until]


def _login_ok(ip):
    with _fail_lock:
        _login_fails.pop(ip, None)


def _parse_cookies(header):
    out = {}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def _is_loopback(ip):
    ip = ip or ""
    return ip in ("::1", "::ffff:127.0.0.1") or ip.startswith("127.")


# ---------------- 系统采样（CPU / 内存 / 显卡） ----------------
# 为什么要自己写：不愿为一个「看看占用」的功能引入 psutil（exe 体积、依赖风险）。
# Windows 走 ctypes 调 GetSystemTimes / GlobalMemoryStatusEx；显卡用 nvidia-smi
# （没有 N 卡就没有这一项，前端显示「不可用」，不影响别的功能）。
_sys_prev = {"idle": 0, "total": 0, "t": 0.0, "cpu": 0.0}
_sys_lock = threading.Lock()
_gpu_cache = {"t": 0.0, "data": None}


def _sys_cpu_windows():
    import ctypes
    from ctypes import wintypes

    idle = wintypes.FILETIME()
    kernel = wintypes.FILETIME()
    user = wintypes.FILETIME()
    if not ctypes.windll.kernel32.GetSystemTimes(
            ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user)):
        return None

    def val(ft):
        return (ft.dwHighDateTime << 32) | ft.dwLowDateTime

    return val(idle), val(kernel) + val(user)


def _sys_mem_windows():
    import ctypes

    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

    st = MEMORYSTATUSEX()
    st.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(st)):
        return None
    total = st.ullTotalPhys
    used = total - st.ullAvailPhys
    return {"total": total, "used": used, "percent": round(used * 100.0 / total, 1) if total else 0}


def _sys_gpu():
    """nvidia-smi（每 3 秒才真正调一次，别把进程创建放进轮询里）"""
    now = time.time()
    if _gpu_cache["data"] is not None and now - _gpu_cache["t"] < 3:
        return _gpu_cache["data"]
    data = None
    try:
        out = subprocess.run(
            ["nvidia-smi",
             "--query-gpu=name,utilization.gpu,memory.used,memory.total",
             "--format=csv,noheader,nounits"],
            capture_output=True, timeout=2.0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        line = (out.stdout or b"").decode("utf-8", "replace").strip().splitlines()
        if line:
            name, util, mused, mtotal = [p.strip() for p in line[0].split(",")[:4]]
            data = {"name": name, "util": float(util),
                    "mem_used": int(float(mused)) * 1024 * 1024,
                    "mem_total": int(float(mtotal)) * 1024 * 1024}
    except Exception:
        data = None
    _gpu_cache.update(t=now, data=data)
    return data


def _sysinfo():
    with _sys_lock:
        cpu = None
        mem = None
        if os.name == "nt":
            try:
                sample = _sys_cpu_windows()
                if sample:
                    idle, total = sample
                    pi, pt = _sys_prev["idle"], _sys_prev["total"]
                    if pt and total > pt:
                        busy = 1.0 - float(idle - pi) / float(total - pt)
                        _sys_prev["cpu"] = max(0.0, min(100.0, busy * 100.0))
                    _sys_prev.update(idle=idle, total=total, t=time.time())
                    cpu = round(_sys_prev["cpu"], 1)
                mem = _sys_mem_windows()
            except Exception:
                cpu, mem = None, None
        if cpu is None:
            try:
                cpu = round(os.getloadavg()[0] / (os.cpu_count() or 1) * 100.0, 1)
            except (OSError, AttributeError):
                cpu = 0.0
        if mem is None and os.path.exists("/proc/meminfo"):
            try:
                info = {}
                with open("/proc/meminfo", "r", encoding="utf-8") as f:
                    for ln in f:
                        k, _, v = ln.partition(":")
                        info[k] = int(v.strip().split()[0]) * 1024
                total = info.get("MemTotal", 0)
                avail = info.get("MemAvailable", 0)
                mem = {"total": total, "used": total - avail,
                       "percent": round((total - avail) * 100.0 / total, 1) if total else 0}
            except (OSError, ValueError):
                mem = None
    return {"cpu": cpu, "mem": mem or {"total": 0, "used": 0, "percent": 0},
            "gpu": _sys_gpu(), "t": int(time.time() * 1000)}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "WebTerm/1.0"
    # 关键：WebSocket 升级握手要求 HTTP/1.1（HTTP/1.0 的 101 会被浏览器拒绝）
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[webterm] %s\n" % (fmt % args))

    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() == "websocket":
            self._websocket()
            return
        if self.path.startswith("/api/"):
            self._api_get()
            return
        # 浏览器标签图标：web/favicon.ico（Cyril 终端窗口图标）；缺文件时退回 204
        if self.path == "/favicon.ico":
            try:
                with open(os.path.join(_BASE, "web", "favicon.ico"), "rb") as f:
                    ico = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/x-icon")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(ico)))
                self.end_headers()
                self.wfile.write(ico)
            except OSError:
                self.send_response(204)
                self.end_headers()
            return
        if self.path in ("/", "/index.html"):
            body = _load_index()   # 实时读文件：覆盖即生效，无需重启
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")   # 强制浏览器每次取最新页面
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        # 组件资源：/comp/<id>/<file>（插件组件的 js / css / 图片）
        if self.path.startswith("/comp/"):
            self._serve_component()
            return
        # 插件自带后端：组件目录里的后端文件（默认 bridge.py）声明的 PREFIX
        if _plugin_route("GET", self, self.path.split("?", 1)[0]):
            return
        # 音乐插件：音源管理 / 代理 / 音频流
        if self.path.startswith("/lx/"):
            self._lx_get()
            return
        # web/ 目录静态资源（xterm.js、wallpaper.jpg 等）：仅单层文件名，防目录穿越
        name = self.path.lstrip("/")
        if name and "/" not in name and ".." not in name:
            self._serve_file(name)
            return
        try:
            self.send_error(404)
        except (BrokenPipeError, ConnectionResetError, OSError):
            # 客户端已断开（如 keep-alive 下提前关闭），静默，不刷异常日志
            pass

    def _serve_file(self, name):
        path = os.path.join(os.path.dirname(INDEX_PATH), name)
        try:
            with open(path, "rb") as f:
                body = f.read()
        except OSError:
            self.send_error(404)
            return
        ctype = {"js": "application/javascript", "css": "text/css",
                 "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
                 "bmp": "image/bmp", "svg": "image/svg+xml",
                 "ico": "image/x-icon",
                 "mp4": "video/mp4", "webm": "video/webm", "ogv": "video/ogg",
                 "m4v": "video/mp4", "mov": "video/quicktime"}.get(
            name.rsplit(".", 1)[-1], "application/octet-stream")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _serve_component(self):
        """组件静态资源。路径必须是组件目录里的白名单后缀文件，
        并且不能用 ../ 逃出该组件目录（_comp_asset 里已校验）。"""
        rel = self.path.split("?", 1)[0][len("/comp/"):]
        parts = rel.split("/", 1)
        cid = parts[0] if parts else ""
        name = parts[1] if len(parts) > 1 else ""
        p = _comp_asset(cid, name)
        if not p:
            try:
                self.send_error(404)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            return
        try:
            with open(p, "rb") as f:
                body = f.read()
        except OSError:
            try:
                self.send_error(404)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            return
        ext = p.rsplit(".", 1)[-1].lower()
        self.send_response(200)
        self.send_header("Content-Type", _COMP_MIME.get(ext, "application/octet-stream"))
        self.send_header("Cache-Control", "no-store")   # 改完组件脚本刷新即生效
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    # ---------- 音乐插件（/lx/*）：仅本机可用 ----------
    def _lx_local_ok(self):
        ip = (self.client_address or ("", 0))[0]
        return _is_loopback(ip)

    def _read_body(self, maxn=4 * 1024 * 1024):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if n <= 0 or n > maxn:
            return None
        try:
            data = json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _lx_stream(self, q):
        """音频流代理：支持 Range，供 <audio> 直接播放/拖动进度"""
        url = (q.get("u") or [""])[0]
        ref = (q.get("ref") or [""])[0]
        if not re.match(r"^https?://", url or "", re.I):
            self._send_json({"ok": False, "error": "地址不合法"}, 400)
            return
        hdrs = {"User-Agent": LX_DL_UA, "Accept-Encoding": "identity"}
        if ref:
            hdrs["Referer"] = ref
        rng = self.headers.get("Range")
        if rng:
            hdrs["Range"] = rng
        try:
            req = urllib.request.Request(url, headers=hdrs)
            r = urllib.request.urlopen(req, timeout=30)
        except urllib.error.HTTPError as e:
            self.send_response(e.code)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        except Exception:
            self.send_response(502)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        try:
            status = getattr(r, "status", 200) or 200
            self.send_response(206 if status == 206 else 200)
            self.send_header("Content-Type", r.headers.get("Content-Type") or "audio/mpeg")
            for h in ("Content-Length", "Content-Range", "Accept-Ranges"):
                v = r.headers.get(h)
                if v:
                    self.send_header(h, v)
            self.send_header("Cache-Control", "no-store")
            if not r.headers.get("Content-Length"):
                self.send_header("Connection", "close")
            self.end_headers()
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            try:
                r.close()
            except Exception:
                pass

    def _lx_get(self):
        if not self._lx_local_ok():
            self._send_json({"ok": False, "error": "仅本机可用"}, 403)
            return
        u = urllib.parse.urlparse(self.path)
        path = u.path
        q = urllib.parse.parse_qs(u.query)
        try:
            if path == "/lx/sources":
                self._send_json({"ok": True, "items": _lx_sources(),
                                 "dir": LX_SOURCES_DIR})
                return
            if path == "/lx/source":
                code = _lx_read_source((q.get("f") or [""])[0])
                if code is None:
                    self._send_json({"ok": False, "error": "音源不存在"}, 404)
                else:
                    self._send_json({"ok": True, "code": code})
                return
            if path == "/lx/config":
                cfg = _lx_cfg()
                cfg["_dir"] = _lx_music_dir()
                cfg["_can_write"] = os.access(os.path.dirname(LX_CONFIG_PATH) or ".", os.W_OK)
                self._send_json({"ok": True, "config": cfg})
                return
            if path == "/lx/progress":
                tid = (q.get("tid") or [""])[0]
                j = _lx_job_get(tid)
                if j is None:
                    self._send_json({"ok": False, "phase": "none"})
                else:
                    j["ok"] = True
                    self._send_json(j)
                return
            if path == "/lx/stream":
                self._lx_stream(q)
                return
        except Exception as e:
            self._send_json({"ok": False, "error": str(e)}, 500)
            return
        self._send_json({"ok": False, "error": "未知接口"}, 404)

    def _lx_post(self):
        if not self._lx_local_ok():
            self._send_json({"ok": False, "error": "仅本机可用"}, 403)
            return
        path = self.path.split("?", 1)[0]
        body = self._read_body(4 * 1024 * 1024)
        if body is None:
            self._send_json({"ok": False, "error": "请求体缺失或过大"}, 400)
            return
        try:
            if path == "/lx/proxy":
                res = _lx_http(body.get("url"), body.get("method") or "GET",
                               body.get("headers"), body.get("body"),
                               body.get("bodyEnc") or "utf8",
                               float(body.get("timeout") or 15))
                self._send_json(res)
                return
            if path == "/lx/source":
                fn, err = _lx_save_source(body.get("file"), body.get("code"))
                if err:
                    self._send_json({"ok": False, "error": err}, 400)
                else:
                    self._send_json({"ok": True, "file": fn, "items": _lx_sources()})
                return
            if path == "/lx/source/delete":
                ok, err = _lx_del_source(body.get("file"))
                self._send_json({"ok": ok, "error": err, "items": _lx_sources()})
                return
            if path == "/lx/config":
                cfg, err = _lx_cfg_save(body.get("patch") or {})
                if err:
                    self._send_json({"ok": False, "error": err}, 400)
                else:
                    cfg["_dir"] = _lx_music_dir()
                    self._send_json({"ok": True, "config": cfg})
                return
            if path == "/lx/open-dir":
                which = str(body.get("which") or "sources")
                explicit = str(body.get("path") or "").strip()
                if explicit:
                    d = explicit
                elif which == "music":
                    d = _lx_music_dir()
                else:
                    d = LX_SOURCES_DIR
                try:
                    os.makedirs(d, exist_ok=True)
                except OSError:
                    pass
                ok_d = os.path.isdir(d) and _lx_open_dir(d)
                self._send_json({"ok": ok_d, "dir": d,
                                 "error": "" if ok_d else "打不开目录：%s" % d})
                return
            if path == "/lx/download":
                self._send_json(_lx_download(body))
                return
            if path == "/lx/open":
                ok = _lx_open_dir(body.get("path"))
                self._send_json({"ok": ok})
                return
        except Exception as e:
            self._send_json({"ok": False, "error": str(e)}, 500)
            return
        self._send_json({"ok": False, "error": "未知接口"}, 404)

    # ---------- 会话 / 鉴权 ----------
    def _whoami(self):
        """回环地址直接是管理员；其他来源必须带有效会话 Cookie。"""
        ip = (self.client_address or ("", 0))[0]
        if _is_loopback(ip):
            return {"name": "local", "admin": True, "local": True, "ip": ip}
        tok = _parse_cookies(self.headers.get("Cookie")).get(_COOKIE_NAME)
        s = _session_get(tok)
        if not s:
            return None
        s["local"] = False
        return s

    def _send_json(self, obj, code=200, extra_headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra_headers or []):
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _drain_body(self, limit=8 << 20):
        """把不需要解析的请求体读掉。

        HTTP/1.1 keep-alive 下，POST 的 body 不消费会留在缓冲区里，被当成下一条
        请求的起始行（日志里表现为 Bad request syntax）。不需要 body 的接口调用这个。
        """
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return
        if 0 < n <= limit:
            try:
                self.rfile.read(n)
            except OSError:
                pass

    def _read_json(self, limit=8192):
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return None
        if n <= 0 or n > limit:
            return None
        try:
            data = json.loads(self.rfile.read(n).decode("utf-8", "replace"))
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _same_origin(self):
        """写操作必须同源：浏览器带 Origin 时校验，挡掉别站发起的跨域 POST（CSRF 兜底）"""
        org = self.headers.get("Origin")
        if not org:
            return True
        host = (self.headers.get("Host") or "").lower()
        return org.split("://", 1)[-1].lower() == host

    def _ws_origin_ok(self):
        """WebSocket 握手必须同源。

        回环连接在本服务里等同管理员：如果不校验 Origin，任意网页都能用
        用户浏览器连 ws://127.0.0.1:<port>/ 拿到 shell（跨站 WebSocket 劫持）。
        浏览器一定带 Origin，所以：
          · 有 Origin：必须与 Host 完全一致（协议之后的部分，含端口）；
          · 无 Origin：只允许本机 CLI（非浏览器客户端），远程一律拒绝；
          · Origin: null（沙箱 iframe / file://）：直接拒绝。
        """
        org = self.headers.get("Origin")
        host = (self.headers.get("Host") or "").lower()
        if not org:
            return _is_loopback((self.client_address or ("", 0))[0])
        if org.strip().lower() == "null":
            return False
        return org.split("://", 1)[-1].lower() == host

    def _api_get(self):
        who = self._whoami()
        path = self.path.split("?", 1)[0]
        if path == "/api/me":
            self._send_json({"ok": True, "authed": bool(who),
                             "user": (who or {}).get("name"),
                             "admin": bool(who and who.get("admin")),
                             "local": bool(who and who.get("local")),
                             "remote_mode": self.server.remote_mode})
            return
        if not who:
            self._send_json({"ok": False, "error": "未登录"}, 401)
            return
        if path == "/api/sysinfo":
            self._send_json({"ok": True, "data": _sysinfo()})
            return
        if path == "/api/plugins":
            # 插件后端登记表：本机看全量（含磁盘路径），远程只看状态
            local = bool(who.get("local") or who.get("admin"))
            items = _plugin_status(local)
            self._send_json({"ok": True, "items": items,
                             "count": len([i for i in items if i["state"] == "ok"]),
                             "auto_reload": True})
            return
        if path == "/api/users":
            if not who["admin"]:
                self._send_json({"ok": False, "error": "只有本机管理员能管理用户"}, 403)
                return
            self._send_json({"ok": True, "users": _users_public()})
            return
        self._send_json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        if not self._same_origin():
            self._send_json({"ok": False, "error": "跨站请求被拒绝"}, 403)
            return
        path = self.path.split("?", 1)[0]
        # 插件自带后端（组件目录里的后端文件，默认 bridge.py）
        if _plugin_route("POST", self, path):
            return
        # 音乐插件：全部 POST 走这里（代理 / 下载 / 音源 / 配置）
        if path.startswith("/lx/"):
            self._lx_post()
            return
        route = {
            "/api/login": self._api_login,
            "/api/logout": self._api_logout,
            "/api/users/add": self._api_user_add,
            "/api/users/del": self._api_user_del,
            "/api/users/update": self._api_user_update,
            "/api/plugins/reload": self._api_plugins_reload,
        }.get(path)
        if not route:
            self._send_json({"ok": False, "error": "not found"}, 404)
            return
        route()

    def _api_login(self):
        ip = (self.client_address or ("", 0))[0]
        if not _login_allowed(ip):
            self._send_json({"ok": False, "error": "尝试次数过多，请 10 分钟后再试"}, 429)
            return
        data = self._read_json()
        if not data:
            self._send_json({"ok": False, "error": "请求格式不对"}, 400)
            return
        name = str(data.get("name") or "").strip()
        pw = str(data.get("password") or "")
        user = None
        for u in _users_load()["users"]:
            if u.get("name") == name:
                user = u
                break
        if not (user and _verify_password(pw, user.get("pass", ""))):
            _login_fail(ip)
            time.sleep(0.25)          # 轻微延时，拖慢字典尝试
            self._send_json({"ok": False, "error": "用户名或密码不对"}, 401)
            return
        _login_ok(ip)
        tok = _new_session(name, False, ip)
        sys.stderr.write("[webterm] 登录成功：%s @ %s\n" % (name, ip))
        self._send_json({"ok": True, "user": name, "admin": False}, extra_headers=[
            ("Set-Cookie", "%s=%s; Path=/; Max-Age=%d; HttpOnly; SameSite=Strict"
             % (_COOKIE_NAME, tok, _SESSION_TTL))])

    def _api_logout(self):
        self._drain_body()
        tok = _parse_cookies(self.headers.get("Cookie")).get(_COOKIE_NAME)
        _session_drop(tok)
        self._send_json({"ok": True}, extra_headers=[
            ("Set-Cookie", "%s=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" % _COOKIE_NAME)])

    def _admin_or_403(self):
        who = self._whoami()
        if not who:
            self._send_json({"ok": False, "error": "未登录"}, 401)
            return None
        if not who["admin"]:
            self._send_json({"ok": False, "error": "只有本机管理员能管理用户"}, 403)
            return None
        return who

    def _api_user_add(self):
        if not self._admin_or_403():
            return
        data = self._read_json() or {}
        name = str(data.get("name") or "").strip()
        pw = str(data.get("password") or "")
        if not _valid_name(name):
            self._send_json({"ok": False, "error": "用户名 1~32 个字符，不能含控制字符"}, 400)
            return
        if not _valid_pass(pw):
            self._send_json({"ok": False, "error": "密码至少 6 位"}, 400)
            return
        doc = _users_load()
        if any(u.get("name") == name for u in doc["users"]):
            self._send_json({"ok": False, "error": "这个用户名已经存在"}, 409)
            return
        doc["users"].append({"name": name, "pass": _hash_password(pw),
                             "created": int(time.time()), "note": ""})
        if not _users_save(doc):
            self._send_json({"ok": False, "error": "写入 users.json 失败"}, 500)
            return
        sys.stderr.write("[webterm] 新增用户：%s\n" % name)
        self._send_json({"ok": True, "users": _users_public()})

    def _api_user_del(self):
        if not self._admin_or_403():
            return
        data = self._read_json() or {}
        name = str(data.get("name") or "").strip()
        doc = _users_load()
        before = len(doc["users"])
        doc["users"] = [u for u in doc["users"] if u.get("name") != name]
        if len(doc["users"]) == before:
            self._send_json({"ok": False, "error": "没有这个用户"}, 404)
            return
        _users_save(doc)
        _sessions_drop_user(name)          # 删了人，会话一起失效
        sys.stderr.write("[webterm] 删除用户：%s\n" % name)
        self._send_json({"ok": True, "users": _users_public()})

    def _api_user_update(self):
        """改名 / 改密码（管理员可操作任意用户）"""
        if not self._admin_or_403():
            return
        data = self._read_json() or {}
        old = str(data.get("name") or "").strip()
        new = str(data.get("new_name") or "").strip()
        pw = data.get("password")
        doc = _users_load()
        target = None
        for u in doc["users"]:
            if u.get("name") == old:
                target = u
                break
        if not target:
            self._send_json({"ok": False, "error": "没有这个用户"}, 404)
            return
        if new and new != old:
            if not _valid_name(new):
                self._send_json({"ok": False, "error": "新用户名不合法"}, 400)
                return
            if any(u.get("name") == new for u in doc["users"]):
                self._send_json({"ok": False, "error": "新用户名已被占用"}, 409)
                return
            target["name"] = new
        if pw not in (None, ""):
            if not _valid_pass(str(pw)):
                self._send_json({"ok": False, "error": "密码至少 6 位"}, 400)
                return
            target["pass"] = _hash_password(str(pw))
        if not _users_save(doc):
            self._send_json({"ok": False, "error": "写入 users.json 失败"}, 500)
            return
        _sessions_drop_user(old)           # 口令/名字变了，旧会话立刻失效
        if new and new != old:
            _sessions_drop_user(new)
        sys.stderr.write("[webterm] 更新用户：%s -> %s\n" % (old, new or old))
        self._send_json({"ok": True, "users": _users_public()})

    def _api_plugins_reload(self):
        """强制重扫并重载所有插件后端（正常情况下改后端文件会自动生效，
        这个接口只是给「签名没变但又想重来」的场景兜底）"""
        self._drain_body()
        who = self._whoami()
        if not (who and (who.get("local") or who.get("admin"))):
            self._send_json({"ok": False, "error": "只有本机管理员能重载插件"}, 403)
            return
        try:
            n = _plugin_reload_all()
        except Exception as exc:
            self._send_json({"ok": False, "error": "重载失败：%s" % exc}, 500)
            return
        self._send_json({"ok": True, "count": n, "items": _plugin_status(True)})

    # ---------- WebSocket 握手 ----------
    def _websocket(self):
        if not self._ws_origin_ok():
            sys.stderr.write("[webterm] 拒绝跨站 WS：origin=%r host=%r\n" % (
                self.headers.get("Origin"), self.headers.get("Host")))
            body = b"forbidden origin"
            self.send_response(403)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)
            return
        who = self._whoami()
        if not who:
            # 未登录不给升级：局域网里没有账号的人连终端都碰不到
            sys.stderr.write("[webterm] 拒绝未登录的 WS：%s\n" % (self.client_address,))
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            return
        self._who = who
        key = self.headers.get("Sec-WebSocket-Key")
        if not key:
            self.send_error(400)
            return
        accept = compute_accept(key)
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        sys.stderr.write("[webterm] WebSocket handshake OK\n")
        self._ws_loop(self.connection)

    # ---------- WebSocket 消息循环 ----------
    def _ws_loop(self, conn):
        global _active_conn
        session = WinSession(conn) if os.name == "nt" else PtySession(conn)
        # spawned：起 shell 的时机被推迟到「浏览器报出真实尺寸」那一刻。
        # 横幅不再由服务端发（见 build_ps_cmd 里的长注释）：它是 shell 的第一段
        # stdout，ConPTY 也知道它占了几行，光标模型才不会与屏幕脱节。
        state = {"spawned": False}

        def start_shell(rows=24, cols=80):
            """用浏览器报上来的真实尺寸起 shell。

            横幅在 spawn 里由 shell 自己打印，所以这里不能再抢先发一遍 ——
            服务端直发的内容 ConPTY 看不见，之后它重绘输入框会把字画到横幅上。
            """
            if state["spawned"]:
                return False
            state["spawned"] = True
            sys.stderr.write("[webterm] spawn shell at %dx%d\n" % (rows, cols))
            try:
                session.spawn(rows, cols)
            except Exception as exc:
                sys.stderr.write("[webterm] shell spawn failed: %r\n" % exc)
                session.send_text("\x1b[1;31m[ shell 启动失败: %r ]\x1b[0m\r\n" % exc)
                try:
                    conn.close()
                except Exception:
                    pass
                return False
            return True

        pending = {"rows": 0, "cols": 0, "timer": None}

        def _do_spawn():
            start_shell(pending["rows"] or 24, pending["cols"] or 80)

        def queue_spawn(rows, cols):
            """等尺寸稳定再起 shell。

            前端连上后 ~100ms 先报一帧尺寸，那一帧常常还是「字体没量完」的默认
            24×80，随后 fit 生效又报一帧真实尺寸。若照第一帧起 shell，横幅会选成
            精简版、PTY 宽度也偏窄（之后再改宽就要重排）。这里让 resize 静默
            180ms 才真正启动，始终用最后报上来的那帧尺寸。
            """
            pending["rows"], pending["cols"] = rows, cols
            if pending["timer"] is not None:
                pending["timer"].cancel()
            t = threading.Timer(0.18, _do_spawn)
            t.daemon = True
            pending["timer"] = t
            t.start()

        # 兜底：万一浏览器一直不报尺寸（老客户端/嵌框），2.2s 后按最后已知尺寸起。
        # 定这么长是因为前端会重试到尺寸量准为止（最多 ~1.4s），抢在它前面起会白等一次重排。
        _spawn_timer = threading.Timer(2.2, _do_spawn)
        _spawn_timer.daemon = True
        _spawn_timer.start()
        # 记录活跃连接用于 AI 主动推送（新连接覆盖旧连接）
        _active_conn = conn
        # 连接建立后，若 AI 就绪则打个招呼（节流由 _maybe_proactive 控制）
        _maybe_proactive("hello")

        auth_ok = TOKEN is None
        # 组件目录的增删 / 打开资源管理器只给本机（回环）用，局域网用户只能看和关
        local_conn = _is_loopback((self.client_address or ("", 0))[0])

        def on_message(payload):
            nonlocal auth_ok
            text = payload.decode("utf-8", "replace")
            try:
                msg = json.loads(text)
            except Exception:
                return
            if not auth_ok:
                if msg.get("type") == "auth" and msg.get("token") == TOKEN:
                    auth_ok = True
                    session.send_text("\x1b[1;32m[ authenticated ]\x1b[0m\r\n")
                else:
                    conn.close()
                return
            mtype = msg.get("type")
            if mtype == "input":
                session.write(msg.get("data", ""))
                # AI 感知终端输入：缓冲拼命令，回车时记入历史并可能主动点评
                _feed_terminal_input(msg.get("data", ""))
            elif mtype == "resize":
                rows = int(msg.get("rows", 24))
                cols = int(msg.get("cols", 80))
                if not state["spawned"]:
                    # shell 还没起：先攒尺寸，等它稳定下来再一起定横幅版本 + PTY 尺寸
                    queue_spawn(rows, cols)
                else:
                    session.resize(rows, cols)
            elif mtype == "fs_list":
                # 右侧文件树：只读列一层目录（不递归、不执行任何东西）
                ok, real, parent, entries, ferr, trunc = _fs_list(msg.get("path") or "")
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "fs_list_resp",
                    "id": msg.get("id", 0),
                    "ok": ok,
                    "path": real,
                    "parent": parent,
                    "entries": entries,
                    "drives": _fs_drives(),
                    "error": ferr,
                    "truncated": trunc,
                })))
            elif mtype == "comp_list":
                # 组件清单：内置两个 + components/ 下每个带 manifest.json 的文件夹
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "comp_list_resp",
                    "id": msg.get("id", 0),
                    "ok": True,
                    "items": _comp_scan(),
                    "dir": COMPONENTS_PATH if local_conn else "",
                }, ensure_ascii=False)))
            elif mtype == "comp_new":
                # 新建一个可用的模板组件（时钟），生成 manifest/index.js/index.css
                ok_c, err_c = (False, "只有本机能新建组件") if not local_conn else \
                    _comp_create(msg.get("id") or "", msg.get("name") or "",
                                 msg.get("desc") or "")
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "comp_new_resp", "id": msg.get("id", 0),
                    "ok": ok_c, "error": err_c,
                    "items": _comp_scan() if ok_c else [],
                }, ensure_ascii=False)))
            elif mtype == "comp_del":
                # 删除组件：只允许删用户目录里的，自带示例与内置组件拒绝
                ok_c, err_c = (False, "只有本机能删除组件") if not local_conn else \
                    _comp_delete(msg.get("id") or "")
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "comp_del_resp", "ok": ok_c, "error": err_c,
                    "cid": msg.get("id", 0),
                    "items": _comp_scan() if ok_c else [],
                }, ensure_ascii=False)))
            elif mtype == "comp_open":
                # 打开组件目录，方便用户把写好的组件文件夹丢进去
                ok_c, err_c = True, ""
                if not local_conn:
                    ok_c, err_c = False, "只有本机能打开组件目录"
                else:
                    try:
                        os.makedirs(COMPONENTS_PATH, exist_ok=True)
                        if os.name == "nt":
                            os.startfile(COMPONENTS_PATH)      # noqa: 只在 Windows 有
                        else:
                            subprocess.Popen(["xdg-open", COMPONENTS_PATH],
                                             stdout=subprocess.DEVNULL,
                                             stderr=subprocess.DEVNULL)
                    except Exception as exc:
                        ok_c, err_c = False, "打开失败：%r" % exc
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "comp_open_resp", "ok": ok_c, "error": err_c,
                    "dir": COMPONENTS_PATH if (ok_c and local_conn) else "",
                }, ensure_ascii=False)))
            elif mtype == "img_get":
                # 终端 Cyril-img：后端读文件、base64 回传（浏览器读不到磁盘路径）
                info = _img_read(msg.get("path") or "")
                info["type"] = "img_resp"
                info["id"] = msg.get("id", 0)
                conn.sendall(encode_text_frame(json.dumps(info)))
            elif mtype == "settings_get":
                # 前端连上后取整包配置（主题 / 背景明暗 / 树宽 / 浮层位置 / AI）
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "settings_resp",
                    "ok": True,
                    "path": SETTINGS_PATH if local_conn else "",
                    # 还没落过盘：回空对象，前端就不会用默认值覆盖当前 UI
                    # settings_public 一律剥掉 ai.api_key，浏览器端永远拿不到明文 key
                    "data": settings_public() if _settings_exist else {},
                }, ensure_ascii=False)))
            elif mtype == "settings_set":
                patch = msg.get("patch") or {}
                if not isinstance(patch, dict):
                    patch = {}
                if local_conn:
                    dropped = []
                else:
                    # 远程账号可以改主题 / 浮层位置，但不能动 AI 配置、监听端口和远程开关
                    patch, dropped = settings_remote_filter(patch)
                ok_saved = settings_patch(patch)
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "settings_saved",
                    "ok": ok_saved,
                    "path": SETTINGS_PATH if (ok_saved and local_conn) else "",
                    "dropped": dropped,
                }, ensure_ascii=False)))
            elif mtype == "ai_status":
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "ai_status_resp",
                    "ready": ai_ready(),
                    "model": ai_model_name(),
                    "error": _llama_err,
                    "mode": "custom" if ai_custom_active() else "local",
                    "base_url": (_ai_cfg.get("base_url") or "") if ai_custom_active() else "",
                })))
            elif mtype == "ai_config_get":
                # 回读自定义 API 配置（key 脱敏：只告知是否已设置）
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "ai_config_resp",
                    "ok": True,
                    "enabled": bool(_ai_cfg.get("enabled")),
                    "base_url": _ai_cfg.get("base_url") or "",
                    "model": _ai_cfg.get("model") or "",
                    "temperature": _ai_cfg.get("temperature", 0.7),
                    "max_tokens": int(_ai_cfg.get("max_tokens") or 0),
                    "has_key": bool((_ai_cfg.get("api_key") or "").strip()),
                })))
            elif mtype == "ai_config_set":
                # 保存自定义 API 配置到 Settings.json 的 ai 段；空串表示不修改该字段
                if not local_conn:
                    conn.sendall(encode_text_frame(json.dumps({
                        "type": "ai_config_resp",
                        "ok": False,
                        "error": "AI 配置只允许本机修改",
                    }, ensure_ascii=False)))
                    return
                for k in ("enabled", "base_url", "model", "api_key", "temperature"):
                    if k not in msg:
                        continue
                    v = msg.get(k)
                    if k == "enabled":
                        _ai_cfg[k] = bool(v)
                    elif k == "temperature":
                        try:
                            _ai_cfg[k] = max(0.0, min(2.0, float(v)))
                        except (TypeError, ValueError):
                            pass
                    else:
                        v = (v or "").strip() if isinstance(v, str) else v
                        if v:                      # 只填了的字段才覆盖
                            _ai_cfg[k] = v
                if "max_tokens" in msg:
                    # 0 合法（= 不限制），不能走上面「truthy 才覆盖」的分支
                    try:
                        _ai_cfg["max_tokens"] = max(0, min(32768, int(msg.get("max_tokens") or 0)))
                    except (TypeError, ValueError):
                        pass
                saved = save_ai_config()
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "ai_config_resp",
                    "ok": saved,
                    "enabled": bool(_ai_cfg.get("enabled")),
                    "base_url": _ai_cfg.get("base_url") or "",
                    "model": _ai_cfg.get("model") or "",
                    "temperature": _ai_cfg.get("temperature", 0.7),
                    "max_tokens": int(_ai_cfg.get("max_tokens") or 0),
                    "has_key": bool((_ai_cfg.get("api_key") or "").strip()),
                    "active": ai_custom_active(),
                    "saved_path": AI_CONFIG_PATH if saved else "",
                })))
            elif mtype == "library_sync":
                # 前端上报音乐库（切歌/导入/删除后同步），用于 AI 点歌与歌词感知
                global _library
                tracks = msg.get("tracks") or []
                clean = []
                for t in tracks[:300]:
                    if not isinstance(t, dict):
                        continue
                    title = (t.get("title") or "").strip()[:80]
                    if not title:
                        continue
                    clean.append({
                        "title": title,
                        "artist": (t.get("artist") or "").strip()[:60],
                        "hasLrc": bool(t.get("hasLrc")),
                    })
                _library = clean
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "library_sync_resp", "ok": True, "count": len(_library)})))
            elif mtype == "ai_chat":
                msgs = msg.get("messages") or []
                if not ai_ready():
                    if ai_custom_active():
                        why = "自定义 API 配置不完整或未启用"
                    else:
                        why = _llama_err or "llama-server 未运行"
                    conn.sendall(encode_text_frame(json.dumps({
                        "type": "ai_chunk",
                        "text": "\r\n[AI 未就绪] " + why + "\r\n",
                    })))
                    conn.sendall(encode_text_frame(json.dumps({"type": "ai_done"})))
                    return

                def runner():
                    global _ai_busy
                    try:
                        _ai_busy = True
                        # 注入人设 system prompt（含"正在听的歌"上下文），前端历史原样透传
                        msgs2 = [{"role": "system",
                                  "content": _build_system_prompt()}] + list(msgs)
                        ai_chat_stream(msgs2, lambda t: conn.sendall(
                            encode_text_frame(json.dumps({"type": "ai_chunk", "text": t}))))
                    finally:
                        _ai_busy = False
                        try:
                            conn.sendall(encode_text_frame(json.dumps({"type": "ai_done"})))
                        except Exception:
                            pass

                threading.Thread(target=runner, daemon=True).start()
            elif mtype == "ai_nowplaying":
                # 前端上报"正在听的歌"（含歌词），注入 AI 上下文并在切歌时主动聊一句
                global _now_playing
                title = (msg.get("title") or "").strip()
                artist = (msg.get("artist") or "").strip()
                raw_lyr = msg.get("lyrics") or []
                lyrics = []
                if isinstance(raw_lyr, list):
                    for ln in raw_lyr[:40]:
                        ln = str(ln).strip()
                        if ln:
                            lyrics.append(ln[:80])
                _now_playing = ({"title": title, "artist": artist, "lyrics": lyrics}
                                if title else None)
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "ai_nowplaying_resp", "ok": True})))
                _maybe_proactive("song")
            elif mtype == "ai_settings":
                # 设置面板：调节 AI 主动聊天（enabled 开关 / interval_min 1~10 分钟）
                # 不带参数 = 查询当前值
                global _proactive_enabled, _proactive_interval
                if "enabled" in msg:
                    _proactive_enabled = bool(msg.get("enabled"))
                if "interval_min" in msg:
                    try:
                        m = max(1, min(10, int(msg.get("interval_min"))))
                    except (TypeError, ValueError):
                        m = _proactive_interval // 60
                    _proactive_interval = m * 60
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "ai_settings_resp", "ok": True,
                    "enabled": _proactive_enabled,
                    "interval_min": _proactive_interval // 60,
                })))
            elif mtype == "ai_type":
                # AI 在终端里打字：把文本写入 PowerShell 会话（enter=True 则回车执行）
                data = msg.get("data") or ""
                enter = bool(msg.get("enter"))
                if data:
                    session.write(data)
                    if enter:
                        session.write("\r")
                    conn.sendall(encode_text_frame(json.dumps({
                        "type": "ai_type_resp", "ok": True})))
                else:
                    conn.sendall(encode_text_frame(json.dumps({
                        "type": "ai_type_resp", "ok": False, "error": "empty"})))
            elif mtype == "ai_write":
                path = msg.get("path") or ""
                content = msg.get("content") or ""
                try:
                    p = os.path.abspath(os.path.expanduser(path))
                    d = os.path.dirname(p)
                    if d and not os.path.isdir(d):
                        os.makedirs(d, exist_ok=True)
                    with open(p, "w", encoding="utf-8", newline="\n") as f:
                        f.write(content)
                    conn.sendall(encode_text_frame(json.dumps({
                        "type": "ai_write_resp", "ok": True, "path": p,
                    })))
                except Exception as exc:
                    conn.sendall(encode_text_frame(json.dumps({
                        "type": "ai_write_resp", "ok": False, "error": str(exc),
                    })))

        def on_close():
            conn.close()

        parser = FrameParser(on_message, on_close)
        try:
            while True:
                if session.fd is None:
                    # Windows：读线程负责转发 PowerShell 输出，主循环只等客户端帧
                    ready, _, _ = select.select([conn], [], [], 30)
                    if conn in ready:
                        raw = conn.recv(65536)
                        if not raw:
                            break
                        parser.feed(raw)
                    continue
                ready, _, _ = select.select([conn, session.fd], [], [], 30)
                if session.fd in ready:
                    data = os.read(session.fd, 65536)
                    if not data:
                        break
                    if auth_ok:
                        session.send_text(data.decode("utf-8", "replace"))
                if conn in ready:
                    raw = conn.recv(65536)
                    if not raw:
                        break
                    parser.feed(raw)
        except OSError:
            pass
        finally:
            if _active_conn is conn:
                _active_conn = None
            session.close()


def main():
    import argparse

    ap = argparse.ArgumentParser(
        description="WebTerm 后端（纯标准库，零依赖；Windows=PowerShell 会话）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python server.py                      # 仅本机访问 127.0.0.1:8765\n"
            "  python server.py --remote             # 局域网可访问 0.0.0.0:8765\n"
            "  python server.py --port 9000          # 自定义端口（仅本机）\n"
            "  python server.py --remote --port 9000 # 局域网 + 自定义端口\n"
            "  端口 / 局域网开关也可在网页【设置】里改并写进 Settings.json，重启后生效。\n"
        ),
    )
    ap.add_argument(
        "--remote", action="store_true",
        help="开启远程访问：监听 0.0.0.0，局域网内手机/其他设备可通过本机 IP 访问",
    )
    ap.add_argument(
        "--port", type=int, default=None,
        help="自定义监听端口（优先级高于 Settings.json；默认 8765 或 WEBTERM_PORT）",
    )
    ap.add_argument(
        "--no-browser", action="store_true",
        help="启动后不自动打开系统浏览器（Electron 桌面壳专用；Web 版行为不变）",
    )
    args = ap.parse_args()

    # 端口 / 局域网开关也能写进 Settings.json（前端【设置】面板改的），
    # 所以配置必须在 bind 之前读 —— 原来那次 load_settings() 在打印之后才跑，
    # 那时 host/port 早就定了。load_settings() 幂等，提前读不影响后面的 AI 初始化。
    load_settings()
    # 组件目录先建出来：用户打开文件夹就知道往哪放插件（建不出来也不影响运行，
    # 比如装在 Program Files 下没有写权限 —— 那时只能用随包自带的示例组件）
    try:
        os.makedirs(COMPONENTS_PATH, exist_ok=True)
    except OSError:
        pass
    _lx_ensure_dirs()          # 音乐插件的音源目录
    cfg_port = _settings.get("port")
    if not (isinstance(cfg_port, int) and 1 <= cfg_port <= 65535):
        cfg_port = None
    cfg_remote = bool(_settings.get("remote"))

    # 优先级：命令行 > Settings.json > 环境变量/默认值。
    # Electron 壳启动时会读同一份 Settings.json，再原样传给 resources/WebTerm.exe，
    # 这样【设置】里改的端口 / 局域网开关对双 EXE 都生效；命令行仍可临时覆盖。
    remote_mode = bool(args.remote or cfg_remote)
    host = "0.0.0.0" if remote_mode else HOST
    port = args.port if args.port is not None else (cfg_port or PORT)

    server = http.server.ThreadingHTTPServer((host, port), Handler)
    server.remote_mode = remote_mode          # /api/me 会回给前端（决定要不要显示登录）
    lan_ip = _lan_ip() if remote_mode else None
    print("=" * 56)
    print("  WebTerm is running  (Windows PowerShell 会话, 纯标准库, 零依赖)")
    print(f"  URL     : http://{host}:{port}/")
    if remote_mode:
        # 修复打包 EXE 后 --remote 局域网无法访问：自动放行防火墙入站 + 指引
        _try_firewall_rule(port)
        if lan_ip:
            print(f"  LAN URL : http://{lan_ip}:{port}/   (手机/局域网设备访问)")
        else:
            print("  LAN URL : 未检测到局域网 IP，请用 ipconfig 查看本机 IP")
        print("  远程模式已开启：监听 0.0.0.0，局域网内均可访问"
              + ("，若连接失败请放行防火墙端口 %d" % port if port != 80 else ""))
    print(f"  Token   : {'ENABLED (set WEBTERM_TOKEN)' if TOKEN else 'disabled'}")
    print(f"  Shell   : {'PowerShell (Windows)' if os.name == 'nt' else SHELL}")
    print(f"  Users   : {len(_users_load()['users'])} 个账号（users.json）")
    print("           本机回环地址免登录=管理员；局域网访问必须用这些账号登录，不能注册")
    print("  Press Ctrl+C to stop")
    # 统一配置已在 bind 前读过一次（端口/局域网开关要用），这里不再重复读；
    # 直接据此初始化 AI（含老 ai-config.json 的一次性迁移）。
    print(f"  Settings: {SETTINGS_PATH if os.path.isfile(SETTINGS_PATH) else '未创建（改任何设置后自动生成）'}")
    load_ai_config()
    start_llama()
    if ai_custom_active():
        print(f"  AI      : custom ({_ai_cfg.get('model') or '未填模型'} @ {_normalize_endpoint(_ai_cfg.get('base_url'))})")
    elif llama_ready():
        print(f"  AI      : ready  ({os.path.basename(_llama_model)})")
    else:
        print(f"  AI      : off    ({_llama_err or '未启动'})")
    print("=" * 56)
    # 定时主动聊天：每 60s 检查，到周期就主动找用户说话（守护线程）
    threading.Thread(target=_proactive_loop, daemon=True).start()
    # 服务已监听后自动打开浏览器（headless/无 DISPLAY 环境静默跳过）
    # Electron 桌面壳自己就是窗口，带 --no-browser，别再弹一个系统浏览器。
    if not args.no_browser:
        try:
            import webbrowser

            webbrowser.open(f"http://127.0.0.1:{port}/", new=2)
        except Exception:
            pass
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[WebTerm stopped]")
        server.server_close()
        sys.exit(0)


if __name__ == "__main__":
    main()
