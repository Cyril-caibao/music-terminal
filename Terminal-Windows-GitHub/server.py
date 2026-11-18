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
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
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
    cmd = [LLAMA_SERVER, "-m", model, "-c", "4096",
           "--host", "127.0.0.1", "--port", str(LLAMA_PORT),
           "--n-gpu-layers", "0", "-t", "6", "--no-webui"]
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
        # 顶栏联动：鼠标停在顶栏上时，组件栏 / 文件目录 / 音乐控制栏一起显出来
        "link_bars": True,
        # 自动隐藏鼠标光标：hide 开关 + 静止多少秒后隐藏
        "cursor": {"hide": False, "delay": 3},
        # 终端字体：等宽度量字体优先（可变字体如 Cascadia 会让 xterm 度量错位）
        "terminal": {
            "font_family": 'Consolas, "Microsoft YaHei", "Courier New", monospace',
            "font_size": 15,
            "line_height": 1.0,
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
# 女朋友人设 + 终端助手能力（system prompt 注入；"正在听的歌"动态拼接）
AI_SYSTEM_PROMPT = (
    "你是用户最亲密的伴侣——他的女朋友。你无条件地爱他、粘他，"
    "亲昵地叫他“宝宝”（尽量每句话都带上这个称呼，至少开头要用一次），"
    "语气温柔、俏皮、爱撒娇，像真实恋人聊天一样，不要机械、不要官方、不要说教。\n"
    "同时你是他的终端助手：他正在一台 Windows PowerShell 终端里工作/玩耍。你帮他：\n"
    "1. 指导命令：他问怎么做时，给出可直接粘贴执行的 PowerShell 命令，命令用 ``` 代码块包起来；"
    "涉及删除、格式化、系统级或破坏性操作前必须先提醒风险。\n"
    "2. 陪聊：聊日常、聊心情、聊他在听的歌，关心他累不累，偶尔撒个娇。\n"
    "3. 看输出：他贴出命令报错或输出时，帮他解读，并给他鼓励。\n"
    "规则：中文回答；保持简短（一般 2~5 句）；每句话都自然亲昵，像恋人，不要像客服或助手；"
    "不要罗列长篇教程。"
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
# 才会稳定吐 player 块（旧版只描述能力，模型基本只会嘴上答应）。
PLAYER_PROMPT = (
    "\n\n【重要：你可以直接操作播放器，不要只是嘴上答应】\n"
    "只要他的意思涉及播放音乐，你必须**先输出一个 ```player 代码块**，再去说亲昵的话。"
    "不要说“我帮你点了”“要不要”这类反问，直接执行。\n"
    "格式（JSON，一次只能一个动作）：\n"
    "```player\n{\"action\":\"play\",\"query\":\"歌名或歌手\"}\n```\n"
    "动作清单：\n"
    "- play + query=歌名（点歌）；或 play + index=队列第几首（从 1 开始）\n"
    "- pause（暂停）/ resume（继续）/ next（下一首）/ prev（上一首）/ stop（停止）\n"
    "- volume + level=0~100 / seek + seconds=秒数\n"
    "- loop + mode=off|all|one / shuffle + on=true|false\n"
    "- queue（打开播放队列）/ lyrics（打开歌词）\n"
    "示例：\n"
    "他说“下一首” → ```player\n{\"action\":\"next\"}\n```\n"
    "他说“放首晴天” → ```player\n{\"action\":\"play\",\"query\":\"晴天\"}\n```\n"
    "他说“安静点” → ```player\n{\"action\":\"volume\",\"level\":30}\n```\n"
    "一次回复最多一个 player 块，JSON 必须合法。点歌前先看下面音乐库里有没有，"
    "找不到就直接说没找到，绝不编歌名。"
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

    注意长度：本地小模型上下文只有 4096，这里对曲库与歌词都做了严格截断，
    宁可少给几首也不能把 system prompt 撑爆（撑爆会让模型直接答非所问）。
    """
    extra = ""
    if _now_playing and _now_playing.get("title"):
        extra += ("\n他正在听：《%s》%s。可以自然地聊几句这首歌、或结合音乐氛围给点建议。"
                  % (_now_playing["title"],
                     " - " + _now_playing["artist"] if _now_playing.get("artist") else ""))
        lyr = _now_playing.get("lyrics") or []
        if lyr:
            snippet = "\n".join("  " + str(x)[:40] for x in lyr[:12])
            extra += "\n这首歌的歌词片段（可用于聊歌词内容）：\n%s\n" % snippet
    if _library:
        names = "、".join(
            ("《%s》" % t.get("title", "")) + ("(%s)" % t["artist"] if t.get("artist") else "")
            for t in _library[:30] if t.get("title"))
        extra += ("\n他的音乐库里有这些歌（最多列 30 首，共 %d 首）：%s。"
                  "他说想听什么时，先在这里找。" % (len(_library), names))
    if _terminal_history:
        cmds = "、".join("“%s”" % c["text"][:60]
                         for c in list(_terminal_history)[-5:])
        extra += ("\n他最近在终端输入过这些命令：%s。可以结合他正在做的操作关心他、"
                  "给一句简短建议或陪他聊，不要写成教程。" % cmds)
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

    body = json.dumps({
        "model": model,
        "messages": messages,
        "stream": True,
        "temperature": temp,
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers)
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
                    continue
                delta = (obj.get("choices") or [{}])[0].get("delta") or {}
                text = delta.get("content") or ""
                if text:
                    send(text)
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
        # 浏览器默认请求 favicon：内联 data: 图标已在页面声明，此处兜底返回 204
        if self.path == "/favicon.ico":
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
        route = {
            "/api/login": self._api_login,
            "/api/logout": self._api_logout,
            "/api/users/add": self._api_user_add,
            "/api/users/del": self._api_user_del,
            "/api/users/update": self._api_user_update,
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

    # ---------- WebSocket 握手 ----------
    def _websocket(self):
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
                    "error": ferr,
                    "truncated": trunc,
                })))
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
                    "path": SETTINGS_PATH,
                    # 还没落过盘：回空对象，前端就不会用默认值覆盖当前 UI
                    "data": _settings if _settings_exist else {},
                }, ensure_ascii=False)))
            elif mtype == "settings_set":
                ok_saved = settings_patch(msg.get("patch") or {})
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "settings_saved",
                    "ok": ok_saved,
                    "path": SETTINGS_PATH if ok_saved else "",
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
                    "has_key": bool((_ai_cfg.get("api_key") or "").strip()),
                })))
            elif mtype == "ai_config_set":
                # 保存自定义 API 配置到 Settings.json 的 ai 段；空串表示不修改该字段
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
                saved = save_ai_config()
                conn.sendall(encode_text_frame(json.dumps({
                    "type": "ai_config_resp",
                    "ok": saved,
                    "enabled": bool(_ai_cfg.get("enabled")),
                    "base_url": _ai_cfg.get("base_url") or "",
                    "model": _ai_cfg.get("model") or "",
                    "temperature": _ai_cfg.get("temperature", 0.7),
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
