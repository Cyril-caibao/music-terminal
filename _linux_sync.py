# -*- coding: utf-8 -*-
"""把 Terminal-Windows 的全量功能同步到 Terminal-Linux，保留 Linux 平台适配层。

一次性构建脚本：以 Windows 的 server.py / web/index.html 为基线，逐处应用
确定性替换。每处替换都断言命中次数，未命中即报错，不产出半成品。
"""
import os
import re
import shutil
import sys

WIN = "D:/Terminal-Windows"
LNX = "D:/Terminal-Linux"
report = []


def must_replace(text, old, new, label, count=1):
    n = text.count(old)
    if n != count:
        raise SystemExit("[FAIL] %s：预期命中 %d 次，实际 %d 次" % (label, count, n))
    report.append("OK  " + label + (" x%d" % n if n != 1 else ""))
    return text.replace(old, new)


def slice_lines(path, a, b):
    """取文件第 a..b 行（1-indexed，含两端）"""
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()
    return "".join(lines[a - 1:b])


def indent(block, n=4):
    """Kali 原版类方法缩进 4 空格，Windows 基线的 else 分支类方法缩进 8 空格。"""
    return "".join((" " * n + ln if ln.strip() else ln) for ln in block.splitlines(True))


# ---------------------------------------------------------------- 后端
win_srv = open(WIN + "/server.py", "r", encoding="utf-8").read()
kali_srv = open(LNX + "/server.py", "r", encoding="utf-8").read()
srv = win_srv

# 1) 文件头说明
new_doc = '''#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WebTerm for Kali Linux 后端 —— 与 Windows 版同一套功能，平台层换成 Linux PTY。

- 功能与 Windows 版对齐：Settings.json 统一配置、users.json 登录、组件/插件、
  图片、文件树、系统信息、AI（自定义 API）、电台与封面拼贴所需的全部协议。
- 终端会话走 os.forkpty + bash/zsh 临时 rc（横幅与 Cyril- 内建指令由 shell 自己打印）。
- 不使用 websockets 库：手写 RFC6455 WebSocket 协议（http.server + socket）。
- 每次 GET / 实时读取 web/index.html，覆盖后刷新即可生效。

运行（Kali / Debian）：
    python3 server.py                     # 仅本机访问 127.0.0.1:8765
    python3 server.py --remote            # 局域网可访问 0.0.0.0:8765
    python3 server.py --port 9000
    python3 server.py --remote --port 9000 --no-browser
"""
'''
srv = re.sub(r'^#![^\n]*\n(?:#[^\n]*\n)*"""[\s\S]*?"""\n', new_doc, srv, count=1)
if not srv.startswith("#!/usr/bin/env python3\n# -*- coding: utf-8 -*-\n\"\"\"\nWebTerm for Kali"):
    raise SystemExit("[FAIL] 文件头替换未生效")
report.append("OK  文件头说明改为 Kali/Linux")

# 2) tempfile（临时 rc 用）
srv = must_replace(srv,
                   "import sys\nimport threading\nimport time",
                   "import sys\nimport tempfile\nimport threading\nimport time",
                   "import tempfile")

# 3) SHELL_KIND
srv = must_replace(srv,
                   'SHELL = os.environ.get("WEBTERM_SHELL", os.environ.get("SHELL", "/bin/bash"))\n'
                   'TOKEN = os.environ.get("WEBTERM_TOKEN", "") or None',
                   'SHELL = os.environ.get("WEBTERM_SHELL", os.environ.get("SHELL", "/bin/bash"))\n'
                   '_SH_BASE = os.path.basename(SHELL).lower()\n'
                   'SHELL_KIND = ("zsh" if "zsh" in _SH_BASE else\n'
                   '              "bash" if "bash" in _SH_BASE else\n'
                   '              "fish" if "fish" in _SH_BASE else "sh")\n'
                   'TOKEN = os.environ.get("WEBTERM_TOKEN", "") or None',
                   "SHELL_KIND 识别 bash/zsh/fish")

# 4) 跨平台打开目录
srv = must_replace(srv,
                   "def _fs_drives():",
                   '''def _open_path(p):
    """打开目录/文件：Windows 用 os.startfile，Linux 用 xdg-open。"""
    try:
        if os.name == "nt":
            os.startfile(p)
        else:
            subprocess.Popen(["xdg-open", p], stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
        return True
    except Exception:
        return False


def _fs_drives():''',
                   "新增 _open_path")
srv = must_replace(srv, "os.startfile(p)                                  # noqa: 只在 Windows",
                   "_open_path(p)", "os.startfile(p) -> _open_path")
srv = must_replace(srv, "os.startfile(COMPONENTS_PATH)      # noqa: 只在 Windows 有",
                   "_open_path(COMPONENTS_PATH)", "os.startfile(COMPONENTS_PATH) -> _open_path")

# 5) 文件树根：Linux 给 "/" 入口
srv = must_replace(srv,
                   'def _fs_drives():\n    """Windows 盘符列表（文件树切换盘符用）。非 Windows 返回空列表。',
                   'def _fs_drives():\n    """盘符列表：Windows 返回各盘符，Linux 返回根 "/"。''',
                   "_fs_drives 文档")
srv = must_replace(srv,
                   '    if os.name != "nt":\n        return []\n    try:\n        import ctypes',
                   '    if os.name != "nt":\n        return ["/"]\n    try:\n        import ctypes',
                   '_fs_drives Linux 返回 ["/"]')

# 6) subprocess 的 creationflags 在 Linux 上不是合法参数
srv, n_inline = re.subn(r',\s*creationflags=getattr\(subprocess,\s*"CREATE_NO_WINDOW",\s*0\)', '', srv)
srv, n_line = re.subn(r'\n\s*creationflags=getattr\(subprocess,\s*"CREATE_NO_WINDOW",\s*0\),', '', srv)
if n_inline + n_line < 3:
    raise SystemExit("[FAIL] creationflags 替换命中过少: %d + %d" % (n_inline, n_line))
report.append("OK  移除 creationflags（行内 %d / 独立行 %d）" % (n_inline, n_line))

# 7) 本地 llama 只在 Windows 提供；自定义 API 两端都可用
srv = must_replace(srv,
                   "def llama_ready():\n    return (_llama_proc is not None and _llama_proc.poll() is None)",
                   'def llama_ready():\n'
                   '    if os.name != "nt":\n'
                   '        return False\n'
                   '    return (_llama_proc is not None and _llama_proc.poll() is None)',
                   "llama_ready Linux 早退")
srv = must_replace(srv,
                   'def start_llama():\n    """启动 llama-server 子进程（无模型/无程序时记录错误，不阻塞主服务）"""\n'
                   '    global _llama_proc, _llama_model, _llama_err, _llama_started',
                   'def start_llama():\n    """启动 llama-server 子进程（Linux 只走自定义 API，不启本地模型）"""\n'
                   '    global _llama_proc, _llama_model, _llama_err, _llama_started\n'
                   '    if os.name != "nt":\n'
                   '        _llama_err = "本地 llama 仅 Windows 版提供；Linux 请在设置里填自定义 API"\n'
                   '        return',
                   "start_llama Linux 早退")

# 8) AI 人设文案
srv = must_replace(srv,
                   '"不要官方、不要说教。他在 Windows PowerShell 终端里工作。规则：中文回答，"',
                   '"不要官方、不要说教。他在 Kali Linux bash 终端里工作。规则：中文回答，"',
                   "AI 人设平台文案")
srv = must_replace(srv,
                   '"一般 2~4 句，不写长教程；他问怎么做就给可直接粘贴的 PowerShell 命令（用 ``` 包裹），"',
                   '"一般 2~4 句，不写长教程；他问怎么做就给可直接粘贴的 bash 命令（用 ``` 包裹），"',
                   "AI 命令类型文案")

# 9) Linux 专属 rc 模板 + build_term_rc（从原 Kali 版原样搬）
rc_block = slice_lines(LNX + "/server.py", 298, 408)
if "TERM_RC_HEAD" not in rc_block or "def build_term_rc" not in rc_block:
    raise SystemExit("[FAIL] rc 模板切片边界不对")
anchor = 'KALI_PS1 = (\n    "\\\\[\\\\e[1;31m\\\\]\\\\u\\\\[\\\\e[0m\\\\]@"\n    "\\\\[\\\\e[1;32m\\\\]\\\\h\\\\[\\\\e[0m\\\\]:"\n    "\\\\[\\\\e[1;34m\\\\]\\\\w\\\\[\\\\e[0m\\\\]\\\\$ "\n)\n'
srv = must_replace(srv, anchor, anchor + "\n" + rc_block.rstrip() + "\n",
                   "插入 TERM_RC_HEAD / build_term_rc")

# 10) Linux PtySession.__init__ 补 rc 状态
srv = must_replace(srv,
                   "    class PtySession:\n        def __init__(self, conn):\n"
                   "            self.conn = conn\n            self.fd = None\n            self.pid = None\n",
                   "    class PtySession:\n        def __init__(self, conn):\n"
                   "            self.conn = conn\n            self.fd = None\n            self.pid = None\n"
                   "            self.rc_path = None\n            self.rc_dir = None\n",
                   "PtySession 增加 rc 状态")

# 11) Linux PtySession.spawn 换成带 rc 注入的完整实现
win_spawn = '''        def spawn(self, rows=24, cols=80):
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
'''
kali_spawn = indent(slice_lines(LNX + "/server.py", 556, 620))
if "def _write_rc" not in kali_spawn or "def spawn" not in kali_spawn:
    raise SystemExit("[FAIL] spawn 切片边界不对")
srv = must_replace(srv, win_spawn, kali_spawn, "PtySession.spawn 带横幅与 Cyril 指令")

# 12) Linux PtySession.close 补 rc 清理与 fd 复位
win_close = '''        def close(self):
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
'''
kali_close = indent(slice_lines(LNX + "/server.py", 649, 675))
if "def close" not in kali_close or "rc_dir" not in kali_close:
    raise SystemExit("[FAIL] close 切片边界不对")
srv = must_replace(srv, win_close, kali_close, "PtySession.close 清理临时 rc")

# 13) main() 文案
srv = must_replace(srv,
                   'description="WebTerm 后端（纯标准库，零依赖；Windows=PowerShell 会话）"',
                   'description="Kali WebTerm 后端（与 Windows 版功能对齐，Linux PTY 会话）"',
                   "argparse 描述")
srv = must_replace(srv,
                   'print("  WebTerm is running  (Windows PowerShell 会话, 纯标准库, 零依赖)")',
                   'print("  Kali WebTerm is running  (Linux PTY 会话, 纯标准库, 零依赖)")',
                   "启动横幅")
srv = must_replace(srv,
                   '请用 ipconfig 查看本机 IP',
                   '请用 ip a 查看本机 IP',
                   "局域网提示命令")

# ---------------------------------------------------------------- 前端
win_html = open(WIN + "/web/index.html", "r", encoding="utf-8").read()
html = win_html
html = must_replace(html,
                    "<title>Cyril · Windows 网页终端</title>",
                    "<title>Cyril · Kali Linux 网页终端</title>",
                    "前端 title")
html = must_replace(html,
                    '<span class="subtitle">Web Terminal for Windows</span>',
                    '<span class="subtitle">Web Terminal for Kali Linux</span>',
                    "前端副标题")
n = html.count("Win-webterm-")
if n < 5:
    raise SystemExit("[FAIL] localStorage 前缀命中过少: %d" % n)
html = html.replace("Win-webterm-", "kali-webterm-")
report.append("OK  localStorage 前缀 Win-webterm- -> kali-webterm- x%d" % n)

# ---------------------------------------------------------------- 落盘
os.makedirs(LNX + "/web", exist_ok=True)
# 原 Kali 前端保留一份，不覆盖用户已有实现
legacy = LNX + "/web/index.kali-legacy.html"
if not os.path.exists(legacy):
    shutil.copyfile(LNX + "/web/index.html", legacy)
    report.append("OK  原 Kali 前端备份为 index.kali-legacy.html")

with open(LNX + "/server.py", "w", encoding="utf-8", newline="\n") as f:
    f.write(srv)
with open(LNX + "/web/index.html", "w", encoding="utf-8", newline="\n") as f:
    f.write(html)
report.append("OK  写入 server.py (%d 行) / web/index.html (%d 行)" %
              (srv.count("\n") + 1, html.count("\n") + 1))

# 静态资源：Windows 有、Linux 没有的全部补齐（不覆盖 Linux 已有壁纸/启动图）
assets = ["folia-adapter.js", "folia-bridge.js", "lumiere.js",
          "sonic-audio-monitor.js", "sonic-host.js", "sonic-topography-preset.js",
          "three.r128.min.js", "favicon.ico"]
for name in assets:
    src, dst = WIN + "/web/" + name, LNX + "/web/" + name
    shutil.copyfile(src, dst)
    report.append("OK  复制 " + name)
# 内置组件目录（整目录同步，保留 Linux 端可能已有的用户组件）
comp_src, comp_dst = WIN + "/web/components", LNX + "/web/components"
if os.path.isdir(comp_src):
    for root, dirs, files in os.walk(comp_src):
        rel = os.path.relpath(root, comp_src)
        target = comp_dst if rel == "." else os.path.join(comp_dst, rel)
        os.makedirs(target, exist_ok=True)
        for fn in files:
            shutil.copyfile(os.path.join(root, fn), os.path.join(target, fn))
    report.append("OK  同步内置组件 web/components/")

print("\n".join(report))
print("\n[同步完成] 共 %d 项" % len(report))
