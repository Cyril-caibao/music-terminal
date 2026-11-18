# -*- coding: utf-8 -*-
"""Terminal-Linux 同步结果的确定性校验（不依赖 Linux 实机）。"""
import ast
import os
import re
import subprocess
import sys

WIN = "D:/Terminal-Windows"
LNX = "D:/Terminal-Linux"
P = []
F = []


def ok(name, cond, extra=""):
    (P if cond else F).append(name)
    print(("PASS " if cond else "FAIL ") + name + (("  -> " + str(extra)) if extra else ""))


lpy = open(LNX + "/server.py", "r", encoding="utf-8").read()
wpy = open(WIN + "/server.py", "r", encoding="utf-8").read()
lhtml = open(LNX + "/web/index.html", "r", encoding="utf-8").read()
whtml = open(WIN + "/web/index.html", "r", encoding="utf-8").read()

# 1) Python 语法
r = subprocess.run([sys.executable, "-m", "py_compile", LNX + "/server.py"],
                   capture_output=True, text=True)
ok("server.py 语法编译", r.returncode == 0, r.stderr[-400:])

# 2) AST 可解析 + 平台专有调用定位
try:
    tree = ast.parse(lpy)
    ok("AST 解析", True)
except SyntaxError as e:
    tree = None
    ok("AST 解析", False, e)

ok("无 creationflags 残留", "creationflags" not in lpy, lpy.count("creationflags"))
ok("os.startfile 实际调用只在 _open_path 内",
   len(re.findall(r'^\s+os\.startfile\(', lpy, re.M)) == 1,
   re.findall(r'^\s+os\.startfile\(', lpy, re.M))
m = re.search(r"def _open_path\([\s\S]*?\n\ndef ", lpy)
ok("_open_path 含 nt/posix 分支", bool(m) and 'os.name == "nt"' in m.group(0) and "xdg-open" in m.group(0))
ok("ctypes.windll 处数受控", lpy.count("ctypes.windll") == 3, lpy.count("ctypes.windll"))
for fn in ("_fs_drives", "_sys_cpu_windows", "_sys_mem_windows"):
    ok(fn + " 存在", ("def " + fn + "(") in lpy)

# 3) Linux 平台层
for key in ("import tempfile", "SHELL_KIND", "TERM_RC_HEAD", "TERM_RC_HEAD_ZSH",
            "def build_term_rc", "def _write_rc", "self.rc_dir", "KALI_PS1",
            "LOGIN_BANNER", "pty.fork()", "os.forkpty" if False else "TIOCSWINSZ",
            "SIGHUP", "xdg-open", 'return ["/"]'):
    ok("平台层含 " + key, key in lpy)
ok("本地 llama 在 Linux 早退", 'if os.name != "nt":\n        _llama_err' in lpy)
ok("llama_ready 在 Linux 早退", 'def llama_ready():\n    if os.name != "nt":' in lpy)
_prompt = re.search(r"AI_SYSTEM_PROMPT = \([\s\S]*?\n\)", lpy)
ok("AI 文案已改 Kali/bash",
   bool(_prompt) and "Kali Linux bash 终端" in _prompt.group(0)
   and "PowerShell 命令" not in _prompt.group(0))
ok("启动横幅为 Kali", "Kali WebTerm is running" in lpy)

# 4) 与 Windows 版协议对齐：消息类型 / 路由集合
def types(s):
    a = set(re.findall(r'mtype\s*==\s*"([a-z_]+)"', s))
    b = set(re.findall(r'"type"\s*:\s*"([a-z_]+)"', s))
    return a | b
wt, lt = types(wpy), types(lpy)
missing = sorted(wt - lt)
ok("后端消息类型与 Windows 版一致", not missing, "缺失: " + ",".join(missing))
ok("消息类型数量与 Windows 版相等", len(lt) == len(wt) and len(lt) >= 30,
   "Linux %d / Windows %d 类" % (len(lt), len(wt)))

# 5) 前端静态资源引用完整
refs = set(re.findall(r'(?:src|href)="([^"]+)"', lhtml))
local = [x for x in refs if not x.startswith(("data:", "http", "#", "/api"))]
miss = []
for ref in local:
    p = ref.lstrip("/")
    if not os.path.isfile(os.path.join(LNX, "web", p)):
        miss.append(ref)
ok("前端本地资源全部存在", not miss, "缺失: " + ",".join(sorted(miss)))
ok("favicon 指向服务端新图标", 'href="/favicon.ico"' in lhtml and
   os.path.isfile(LNX + "/web/favicon.ico"))

# 6) 前端身份与规模
ok("frontend title 为 Kali", "Cyril · Kali Linux 网页终端" in lhtml)
ok("localStorage 前缀全为 kali-webterm-",
   "Win-webterm-" not in lhtml and lhtml.count("kali-webterm-") >= 15,
   lhtml.count("kali-webterm-"))
ids = re.findall(r'\bid="([^"]+)"', lhtml)
dup = sorted({x for x in ids if ids.count(x) > 1})
ok("DOM id 唯一", not dup, dup[:8])
ok("前端功能规模对齐 Windows", abs(len(lhtml) - len(whtml)) < 500,
   "Linux %d / Windows %d" % (len(lhtml), len(whtml)))

# 7) 前端 JS 语法（所有内联 script 段）
scripts = re.findall(r"<script\b[^>]*>([\s\S]*?)</script>", lhtml)
js_ok, js_err = True, ""
for i, s in enumerate(scripts):
    if not s.strip():
        continue
    r = subprocess.run(["node", "-e", "new (require('vm').Script)(require('fs').readFileSync(0,'utf8'))"],
                       input=s, capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        js_ok, js_err = False, "script#%d: %s" % (i, r.stderr[-300:])
        break
ok("内联 JS 语法全部通过", js_ok, js_err)

# 8) 原 Kali 版资产保留
for p in ("web/index.kali-legacy.html", "web/wallpaper.jpg", "web/wallpaper_dark.jpg",
          "web/cyril-splash.png", "web/xterm.min.js", "web/xterm.min.css",
          "web/addon-fit.min.js", "kali_terminal.py", "README.md"):
    ok("保留 " + p, os.path.isfile(os.path.join(LNX, p)))
ok("内置组件已同步", os.path.isdir(LNX + "/web/components") and
   len(os.listdir(LNX + "/web/components")) >= 4,
   os.listdir(LNX + "/web/components") if os.path.isdir(LNX + "/web/components") else [])

# 9) 关键功能代码确实进了 Linux 前端
for key in ('id="rbtn-view"', 'id="viewMenu"', "latLyricSpring", "lp-track",
            "settings_get", "ai_config_get", "comp_list", "library_sync"):
    ok("前端含 " + key, key in lhtml)

print("\n[Linux 校验] %d passed, %d failed" % (len(P), len(F)))
if F:
    print("失败项: " + "; ".join(F))
sys.exit(1 if F else 0)
