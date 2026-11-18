# -*- coding: utf-8 -*-
"""CyrilShield 的 WebTerm 后端桥（插件自带后端）。

宿主 server.py 会扫描 ``components/<id>/bridge.py``，只要模块导出了
``PREFIX`` 与 ``handle_get`` / ``handle_post``，就把该前缀的请求交给它。
删掉本文件夹 = 插件完全卸载，宿主不需要改动。

路由（全部仅限本机回环）：

    GET  /cs/env                  工具链 / 项目位置 / 输出目录
    GET  /cs/job?id=              任务状态 + 增量日志
    GET  /cs/get?job=&i=          取任务产物（只允许本任务产出的文件）
    POST /cs/upload?name=         原始字节上传，返回 fid
    POST /cs/run                  起任务 {fid, tool, opts, outdir, outname}
    POST /cs/open                 用资源管理器打开目录 {dir}
    POST /cs/cancel?id=           尽力中断（仅标记，子进程不会被强杀）

CyrilShield 本体位置按以下顺序探测，找到含 ``cyrilshield/apk.py`` 的目录即用：
环境变量 CYRILSHIELD_HOME → 插件目录内 vendor/ → 宿主同级目录
→ D:\\PEShieldStudio → %LOCALAPPDATA%\\CyrilShield\\app
"""
import json
import os
import sys
import threading
import time
import uuid
from urllib.parse import parse_qs, urlparse

PREFIX = "/cs/"
MAX_UPLOAD = 1 << 30          # 1 GiB

_JOBS = {}                    # job_id -> dict
_UPLOADS = {}                 # fid -> {"path","name","size"}
_LOCK = threading.Lock()
_MODS = None                  # 延迟导入的 CyrilShield 模块集合
_MOD_ERR = ""


# --------------------------------------------------------------------------
# 定位 CyrilShield
# --------------------------------------------------------------------------
def _app_base():
    """宿主可写目录：打包后是 exe 所在目录，源码运行时是 server.py 所在目录"""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(sys.argv[0])) or os.getcwd()


def _here():
    return os.path.dirname(os.path.abspath(__file__))


def _candidates():
    env = (os.environ.get("CYRILSHIELD_HOME") or "").strip().strip('"')
    app = _app_base()
    up = os.path.dirname(app)
    out = []
    for p in (env,
              os.path.join(_here(), "vendor"),
              os.path.join(app, "CyrilShield"),
              os.path.join(app, "PEShieldStudio"),
              os.path.join(up, "PEShieldStudio"),
              os.path.join(up, "CyrilShield"),
              r"D:\PEShieldStudio",
              r"D:\CyrilShield",
              os.path.join(os.environ.get("LOCALAPPDATA", ""), "CyrilShield", "app")):
        if p:
            out.append(os.path.abspath(p))
    return out


def project_home():
    for p in _candidates():
        if os.path.isfile(os.path.join(p, "cyrilshield", "apk.py")):
            return p
    return ""


def _load():
    """延迟导入 CyrilShield 的四个模块；失败时把原因留给前端显示"""
    global _MODS, _MOD_ERR
    if _MODS is not None or _MOD_ERR:
        return _MODS
    home = project_home()
    if not home:
        _MOD_ERR = ("插件的 vendor/cyrilshield 缺失。重新解压本组件（vendor 目录必须一起），"
                    "或用环境变量 CYRILSHIELD_HOME 指向一个含 cyrilshield/apk.py 的目录。")
        return None
    if home not in sys.path:
        sys.path.insert(0, home)
    try:
        from cyrilshield import apk, pe, upx, vmgen  # noqa: F401
        from cyrilshield.vmp import protect_elf     # noqa: F401
        _MODS = {"apk": apk, "pe": pe, "upx": upx, "vmgen": vmgen,
                 "elf": protect_elf, "home": home}
    except Exception as exc:                     # 依赖装错 / 文件缺失
        _MOD_ERR = "加载 CyrilShield 失败：%s" % exc
        _MODS = None
    return _MODS


def _version():
    m = _load()
    if not m:
        return ""
    try:
        from cyrilshield import __version__
        return str(__version__)
    except Exception:
        return ""


# --------------------------------------------------------------------------
# 输出目录 / 文件
# --------------------------------------------------------------------------
def default_outdir():
    for name in ("Desktop", "Downloads"):
        p = os.path.join(os.path.expanduser("~"), name)
        if os.path.isdir(p):
            return os.path.join(p, "CyrilShield-Output")
    return os.path.join(os.path.expanduser("~"), "CyrilShield-Output")


def _stem(name):
    return os.path.splitext(os.path.basename(name or ""))[0] or "output"


def _upload_root():
    """上传暂存目录：优先插件自己的 ``.work/upload``。

    插件是「整包即全部环境」的：文件、工具链、Python 都在本文件夹里，
    暂存也留在本文件夹，卸载时删掉文件夹就干净了。目录不可写（比如插件
    放在只读位置）才回退到系统临时目录。
    """
    base = os.path.join(_here(), ".work", "upload")
    try:
        os.makedirs(base, exist_ok=True)
        probe = os.path.join(base, ".w")
        with open(probe, "wb"):
            pass
        os.remove(probe)
        return base
    except OSError:
        pass
    tmp = os.environ.get("TEMP") or os.environ.get("TMP") or os.path.expanduser("~")
    return os.path.join(tmp, "cyrilshield-webterm")


def _safe_name(name):
    name = os.path.basename((name or "").replace("\\", "/")).strip()
    name = "".join(c for c in name if c not in '\r\n\t"<>|:*?')
    return name or "upload.bin"


# --------------------------------------------------------------------------
# 任务
# --------------------------------------------------------------------------
def _new_job(kind, src_name):
    jid = uuid.uuid4().hex[:10]
    job = {
        "id": jid, "kind": kind, "src": src_name,
        "state": "running", "t0": time.time(), "logs": [],
        "result": None, "error": "", "files": [], "cancelled": False,
    }
    with _LOCK:
        _JOBS[jid] = job
    return job


def _job_log(job):
    def log(msg, tag="INFO"):
        with _LOCK:
            job["logs"].append([round(time.time() - job["t0"], 2), str(tag), str(msg)])
    return log


def _finish(job, result=None, error=""):
    with _LOCK:
        if error:
            job["state"] = "error"
            job["error"] = error
        else:
            job["state"] = "done"
            job["result"] = result or {}
            files = []
            r = result or {}
            for k in ("path", "output"):
                if isinstance(r.get(k), str) and os.path.isfile(r[k]):
                    files.append(r[k])
            for f in (r.get("files") or []):
                p = f.get("path") if isinstance(f, dict) else f
                if isinstance(p, str) and os.path.isfile(p):
                    files.append(p)
            job["files"] = files
        job["t1"] = time.time()


def _start(job, fn):
    def runner():
        log = _job_log(job)
        try:
            res = fn(log)
            _finish(job, result=res)
        except Exception as exc:
            _finish(job, error=str(exc) or exc.__class__.__name__)
    threading.Thread(target=runner, daemon=True).start()


# ---- 各工具 ---------------------------------------------------------------
def _run_icon(src, opts, outdir, outname, log):
    m = _load()
    pe = m["pe"]
    root = os.path.abspath(outdir or default_outdir())
    target = os.path.join(root, "icons_" + _stem(os.path.basename(src)))
    os.makedirs(target, exist_ok=True)
    log("载入 PE：%s" % os.path.basename(src))
    pf = pe.load(src)
    old = pf.path
    pf.path = os.path.basename(src)          # 导出文件名用原始名，不用临时路径
    try:
        files = pe.export_icons(pf, target,
                                bool(opts.get("ico", True)),
                                bool(opts.get("png", True)),
                                log=lambda s: log(s, "DIM"))
    finally:
        pf.path = old
    if not files:
        raise RuntimeError("没有导出任何文件")
    log("完成，共 %d 个文件" % len(files), "OK")
    listing = [{"name": os.path.basename(p), "path": p,
                "size": os.path.getsize(p)} for p in files]
    return {"dir": target, "files": listing, "count": len(listing)}


def _run_upx(src, opts, outdir, outname, log):
    m = _load()
    upx = m["upx"]
    tool = upx.find_upx()
    if not tool:
        raise RuntimeError("找不到内置 UPX（tools/upx.exe）")
    log("UPX：%s" % tool, "DIM")
    root = os.path.abspath(outdir or default_outdir())
    os.makedirs(root, exist_ok=True)
    before = os.path.getsize(src)
    name = (outname or "").strip() or (_stem(os.path.basename(src)) + "_upx.exe")
    if not name.lower().endswith(".exe"):
        name += ".exe"
    out = os.path.join(root, name)
    po = upx.PackOptions(
        level=int(opts.get("level", 7)),
        best=bool(opts.get("best")),
        brute=bool(opts.get("brute")),
        lzma=bool(opts.get("lzma")),
        force=bool(opts.get("force", True)),
        compress_icons=int(opts.get("icons", 2)),
        compress_resources=not opts.get("noRes"),
        strip_relocs=not opts.get("keepReloc"),
        extra=str(opts.get("extra", "")),
    )
    rc, lines, of = upx.execute("pack", tool, src, out, po,
                                on_line=lambda s: log(s, "DIM"))
    if rc != 0:
        raise RuntimeError("UPX 返回退出码 %d：%s" % (rc, " ".join(lines[-2:])))
    after = os.path.getsize(of)
    log("输出：%s（%s → %s，-%.1f%%）" % (of, "{:,}".format(before),
                                        "{:,}".format(after),
                                        (1 - after / before) * 100 if before else 0), "OK")
    return {"path": of, "name": os.path.basename(of), "size_before": before,
            "size_after": after,
            "ratio": round((1 - after / before) * 100, 1) if before else 0}


def _icon_path(opts):
    """把前端的自定义图标 fid 解析成本地路径；未启用时返回空串。"""
    fid = str(opts.get("iconFid") or "").strip()
    if not fid:
        return ""
    up = _UPLOADS.get(fid)
    if not up:
        raise RuntimeError("自定义图标文件不存在，请重新选择")
    return up["path"]


def _run_vm(src, opts, outdir, outname, log):
    m = _load()
    vmgen = m["vmgen"]
    root = os.path.abspath(outdir or default_outdir())
    os.makedirs(root, exist_ok=True)
    name = (outname or "").strip() or (_stem(os.path.basename(src)) + "_vm.exe")
    if not name.lower().endswith(".exe"):
        name += ".exe"
    out = os.path.join(root, name)
    vo = vmgen.VMOptions(
        password=str(opts.get("password", "")),
        junk_level=int(opts.get("junk", 1)),
        verify=bool(opts.get("verify", True)),
        cleanup=bool(opts.get("cleanup", True)),
        forward_args=bool(opts.get("forwardArgs", True)),
        extra_args=str(opts.get("extraArgs", "")),
        keep_icon=bool(opts.get("keepIcon", True)),
        icon_path=_icon_path(opts),
    )
    r = vmgen.build(src, out, vo, on_line=lambda s: log(s, "DIM"))
    log("输出：%s（%s 字节）" % (r.output, "{:,}".format(r.output_size)), "OK")
    return {"path": r.output, "name": os.path.basename(r.output),
            "size_before": r.original_size, "size_after": r.output_size,
            "fingerprint": r.fingerprint}


_ELF_EXTS = (".so", ".elf", ".out", ".ko", ".axf", ".bin")


def _is_elf(name):
    return os.path.splitext(name)[1].lower() in _ELF_EXTS


def _run_elf_info(src, opts, log):
    m = _load()
    d = m["elf"].inspect(src)
    log("%s · %s · %d 字节" % (d["arch"], d["type"], d["size"]), "OK")
    if d["stripped"]:
        log("已 strip（无符号表），只能用「RVA:长度」指定函数", "WARN")
    else:
        log("可保护函数 %d 个，解析时跳过 %d 个" % (d["protectable"], d["skipped"]), "OK")
    for f in d["functions"][:20]:
        log("  %s  RVA 0x%x  %d B%s" % (
            f["name"][:44], f["rva"], f["size"],
            "  endbr64" if f.get("endbr64") else ""), "DIM")
    if len(d["functions"]) > 20:
        log("  …共 %d 个，仅显示前 20" % len(d["functions"]), "DIM")
    return d


def _run_elf(src, opts, outdir, outname, log):
    m = _load()
    root = os.path.abspath(outdir or default_outdir())
    os.makedirs(root, exist_ok=True)
    out = os.path.join(root, (outname or "").strip() or
                        (_stem(os.path.basename(src)) + ".csvmpayload"))
    def _aslist(v):
        """前端可能传 JSON 数组，也可能传逗号分隔字符串，两种都收。"""
        if isinstance(v, (list, tuple)):
            return [str(x).strip() for x in v if str(x).strip()]
        return [s.strip() for s in str(v or "").replace(",", "\n").split("\n") if s.strip()]

    syms = _aslist(opts.get("symbols"))
    ranges = _aslist(opts.get("ranges"))
    targets = syms + ranges
    if not targets:
        raise RuntimeError("请先扫描并至少勾选一个函数，或在输入框填符号名 / RVA:长度")
    log("目标 %d 个：%s" % (len(targets), ", ".join(targets[:6]) + ("…" if len(targets) > 6 else "")))
    r = m["elf"].protect(src, names=syms, ranges=ranges, output=out)
    skipped = r.get("skipped") or []
    for s in skipped:
        log("跳过 %s：%s" % (s.get("name", "?"), s.get("reason", "?")), "WARN")
    if not r["functions"]:
        raise RuntimeError("所有目标都无法编译，payload 未生成")
    log("输出：%s（%s 字节，%d 个函数入壳）" % (
        r["output"], "{:,}".format(r["payload_size"]), r["count"]), "OK")
    log("运行（Linux）：%s" % r["command"], "DIM")
    return {"path": r["output"], "name": os.path.basename(r["output"]),
            "size": r["payload_size"], "count": r["count"],
            "skipped": skipped, "command": r["command"]}


def _run_apk_info(src, opts, log):
    m = _load()
    apk = m["apk"]
    tools = apk.find_tools()
    if not tools["ok"]:
        log("工具链不完整，缺少：" + "、".join(tools["missing"]), "WARN")
    data = apk.inspect(src, tools)
    log("包名 %s · %s 个 dex · %s 个条目" % (data.get("package") or "未知",
                                            len(data.get("dexes") or []),
                                            (data.get("entry") or {}).get("count", 0)), "OK")
    if data.get("packers"):
        log("检测到既有加固特征：" + "、".join(data["packers"]), "WARN")
    data["tools"] = {"ok": tools["ok"], "missing": tools["missing"],
                     "build_tools": os.path.basename(tools["build_tools"] or ""),
                     "platform": tools["platform"] or ""}
    # v3.2：把可替换的图标资源一起回显给插件面板
    try:
        import zipfile
        with zipfile.ZipFile(src) as zf:
            emap = {i.filename: zf.read(i.filename) for i in zf.infolist()}
        data["icons"] = apk.icons.apk_icon_candidates(emap)
    except Exception as exc:
        data["icons"] = {"candidates": [], "error": str(exc)}
    return data


def _run_apk_protect(src, opts, outdir, outname, log):
    m = _load()
    apk = m["apk"]
    root = os.path.abspath(outdir or default_outdir())
    os.makedirs(root, exist_ok=True)
    name = (outname or "").strip() or (_stem(os.path.basename(src)) + "_protected.apk")
    if not name.lower().endswith(".apk"):
        name += ".apk"
    out = os.path.join(root, name)
    ao = apk.ApkOptions(
        level=str(opts.get("level", "standard")),
        strip_debug=bool(opts.get("stripDebug", True)),
        harden_backup=bool(opts.get("hardenBackup", True)),
        keystore=str(opts.get("keystore", "")),
        ks_alias=str(opts.get("ksAlias", "")),
        ks_pass=str(opts.get("ksPass", "")),
        key_pass=str(opts.get("keyPass", "")),
        v1=bool(opts.get("v1", False)),
        icon_path=_icon_path(opts),
        icon_fit=str(opts.get("iconFit", "contain")),
        # 插件用简化面板：默认替换全部探测到的图标资源
        icon_entries=list(opts.get("iconEntries") or []),
        extra={"debug": bool(opts.get("debug", False))},
    )
    r = apk.protect(src, out, ao, log=lambda s, t="INFO": log(s, t))
    return dict(r)


# --------------------------------------------------------------------------
# HTTP 辅助
# --------------------------------------------------------------------------
def _local(h):
    ip = (h.client_address or ("", 0))[0]
    return ip in ("127.0.0.1", "::1", "::ffff:127.0.0.1")


def _q(h):
    return {k: v[0] for k, v in parse_qs(urlparse(h.path).query).items() if v}


def _deny_remote(h):
    if _local(h):
        return False
    h._send_json({"ok": False, "error": "CyrilShield 插件仅允许本机访问"}, 403)
    return True


# --------------------------------------------------------------------------
# GET
# --------------------------------------------------------------------------
def handle_get(h, path):
    if _deny_remote(h):
        return
    if path == "/cs/env":
        m = _load()
        out = {"ok": bool(m), "error": _MOD_ERR, "home": project_home(),
               "version": _version(), "outdir": default_outdir(),
               "plugin": _here(), "work": _upload_root()}
        if m:
            try:
                tools = m["apk"].find_tools()
                bt = os.path.basename(tools["build_tools"] or "")
                if bt in ("", "build-tools"):
                    # 便携工具链里 build_tools 指向目录本身，用平台版本代替
                    bt = (tools["platform"] or "").replace("android-", "")
                out["tools"] = {"ok": tools["ok"], "missing": tools["missing"],
                                "build_tools": bt,
                                "platform": tools["platform"] or "",
                                "bundled": bool(tools.get("bundled"))}
            except Exception as exc:
                out["tools"] = {"ok": False, "missing": [str(exc)]}
            try:
                up = m["upx"].find_upx()
                ver = (m["upx"].version(up) or "").splitlines() if up else []
                out["upx"] = {"ok": bool(up), "path": up,
                              "version": ver[0].replace("upx ", "").strip() if ver else ""}
            except Exception:
                out["upx"] = {"ok": False, "path": ""}
            try:
                ok, info = m["vmgen"].check_backend()
                out["vm"] = {"ok": bool(ok), "msg": str(info)}
            except Exception as exc:
                out["vm"] = {"ok": False, "msg": str(exc)}
        h._send_json(out)
        return

    if path == "/cs/job":
        jid = _q(h).get("id", "")
        job = _JOBS.get(jid)
        if not job:
            h._send_json({"ok": False, "error": "任务不存在"}, 404)
            return
        with _LOCK:
            h._send_json({"ok": True, "job": {
                "id": job["id"], "kind": job["kind"], "state": job["state"],
                "src": job["src"], "elapsed": round(time.time() - job["t0"], 1),
                "logs": job["logs"], "result": job["result"],
                "error": job["error"],
                "files": [os.path.basename(p) for p in job["files"]],
            }})
        return

    if path == "/cs/get":
        q = _q(h)
        job = _JOBS.get(q.get("job", ""))
        if not job or not job["files"]:
            h._send_json({"ok": False, "error": "没有可下载的文件"}, 404)
            return
        try:
            i = int(q.get("i", "0"))
        except ValueError:
            i = 0
        files = job["files"]
        if i < 0 or i >= len(files):
            h._send_json({"ok": False, "error": "序号越界"}, 404)
            return
        p = files[i]
        _send_file(h, p)
        return

    if path == "/cs/ui/ping":
        with _UI_LOCK:
            proc = _UI.get("proc")
            alive = proc is not None and proc.poll() is None
            port = int(_UI.get("port") or 0)
            url = _UI.get("url") or ""
            up = float(time.time() - _UI.get("started", 0)) if alive else 0.0
            if alive:
                _UI["seen"] = time.time()
        h._send_json({"ok": True, "running": bool(alive and port), "url": url,
                      "port": port, "uptime": round(up, 1)})
        return

    if path == "/cs/ui/log":
        h._send_json({"ok": True, "log": _ui_tail(_ui_log_path(), 4000)})
        return

    h._send_json({"ok": False, "error": "not found"}, 404)


def _send_file(h, path):
    import mimetypes
    try:
        n = os.path.getsize(path)
        f = open(path, "rb")
    except OSError as exc:
        h._send_json({"ok": False, "error": str(exc)}, 404)
        return
    ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
    h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(n))
    h.send_header("Content-Disposition",
                  'attachment; filename="%s"' % os.path.basename(path))
    h.send_header("Cache-Control", "no-store")
    h.end_headers()
    try:
        with f:
            while True:
                chunk = f.read(1 << 20)
                if not chunk:
                    break
                h.wfile.write(chunk)
    except (BrokenPipeError, ConnectionResetError, OSError):
        pass


# --------------------------------------------------------------------------
# POST
# --------------------------------------------------------------------------
def handle_post(h, path):
    if _deny_remote(h):
        return
    if path == "/cs/upload":
        _upload(h)
        return

    if path == "/cs/run":
        _run(h)
        return

    if path == "/cs/open":
        data = h._read_json(limit=8192) or {}
        d = str(data.get("dir") or "")
        if not d or not os.path.isdir(d):
            h._send_json({"ok": False, "error": "目录不存在"}, 400)
            return
        try:
            os.startfile(d)                       # noqa: S606  (仅本机回环)
            h._send_json({"ok": True})
        except Exception as exc:
            h._send_json({"ok": False, "error": str(exc)}, 500)
        return

    if path == "/cs/cancel":
        data = h._read_json(limit=8192) or {}
        job = _JOBS.get(str(data.get("id") or ""))
        if job:
            job["cancelled"] = True
        h._send_json({"ok": bool(job)})
        return

    if path == "/cs/ui/start":
        h._send_json(_ui_start())
        return

    if path == "/cs/ui/stop":
        _ui_stop()
        h._send_json({"ok": True})
        return

    h._send_json({"ok": False, "error": "not found"}, 404)


def _upload(h):
    try:
        n = int(h.headers.get("Content-Length") or 0)
    except ValueError:
        n = 0
    if n <= 0:
        h._send_json({"ok": False, "error": "空文件"}, 400)
        return
    if n > MAX_UPLOAD:
        h._send_json({"ok": False, "error": "文件超过 1 GB"}, 413)
        return
    name = _safe_name(_q(h).get("name") or "upload.bin")
    fid = uuid.uuid4().hex[:12]
    d = os.path.join(_upload_root(), fid)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, name)
    left = n
    try:
        with open(p, "wb") as f:
            while left > 0:
                chunk = h.rfile.read(min(1 << 20, left))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
    except OSError as exc:
        h._send_json({"ok": False, "error": "写入失败：%s" % exc}, 500)
        return
    if left != 0:
        h._send_json({"ok": False, "error": "上传中断"}, 400)
        return
    _UPLOADS[fid] = {"path": p, "name": name, "size": os.path.getsize(p)}
    h._send_json({"ok": True, "fid": fid, "name": name, "size": os.path.getsize(p)})


def _run(h):
    data = h._read_json(limit=65536) or {}
    fid = str(data.get("fid") or "")
    up = _UPLOADS.get(fid)
    if not up:
        h._send_json({"ok": False, "error": "请先上传文件"}, 400)
        return
    if not _load():
        h._send_json({"ok": False, "error": _MOD_ERR}, 503)
        return
    src = up["path"]
    src_name = up["name"]
    tool = str(data.get("tool") or "")
    opts = data.get("opts") or {}
    outdir = str(data.get("outdir") or "")
    outname = str(data.get("outname") or "")

    if tool == "inspect":
        job = _new_job("inspect", src_name)
        _start(job, lambda log: _run_apk_info(src, opts, log))
        h._send_json({"ok": True, "id": job["id"]})
        return

    if tool == "elf_info":
        job = _new_job("elf_info", src_name)
        _start(job, lambda log: _run_elf_info(src, opts, log))
        h._send_json({"ok": True, "id": job["id"]})
        return

    ext = os.path.splitext(src_name)[1].lower()
    if tool in ("icon", "upx", "vm") and ext not in (".exe", ".dll", ".scr", ".cpl", ".ocx", ".sys"):
        h._send_json({"ok": False, "error": "该工具需要 PE 文件（.exe/.dll 等）"}, 400)
        return
    if tool in ("elf_info", "elf") and ext not in _ELF_EXTS:
        h._send_json({"ok": False, "error": "该工具需要 ELF 文件（.so/.elf/.out/.ko/.axf/.bin）"}, 400)
        return
    if tool == "upx" and ext != ".exe":
        h._send_json({"ok": False, "error": "UPX 只处理 .exe"}, 400)
        return
    if tool in ("apk", "inspect") and ext != ".apk":
        h._send_json({"ok": False, "error": "需要 .apk 文件"}, 400)
        return

    job = _new_job(tool, src_name)
    if tool == "icon":
        fn = lambda log: _run_icon(src, opts, outdir, outname, log)      # noqa: E731
    elif tool == "upx":
        fn = lambda log: _run_upx(src, opts, outdir, outname, log)       # noqa: E731
    elif tool == "vm":
        fn = lambda log: _run_vm(src, opts, outdir, outname, log)        # noqa: E731
    elif tool == "apk":
        fn = lambda log: _run_apk_protect(src, opts, outdir, outname, log)  # noqa: E731
    elif tool == "elf":
        fn = lambda log: _run_elf(src, opts, outdir, outname, log)       # noqa: E731
    else:
        h._send_json({"ok": False, "error": "未知工具：%s" % tool}, 400)
        return
    _start(job, fn)
    h._send_json({"ok": True, "id": job["id"]})


# --------------------------------------------------------------------------
# 清理：超过 6 小时的上传与任务
# --------------------------------------------------------------------------
def _gc():
    now = time.time()
    for jid, job in list(_JOBS.items()):
        if now - job["t0"] > 6 * 3600:
            _JOBS.pop(jid, None)
    for fid, up in list(_UPLOADS.items()):
        try:
            if now - os.path.getmtime(up["path"]) > 6 * 3600:
                import shutil
                shutil.rmtree(os.path.dirname(up["path"]), ignore_errors=True)
                _UPLOADS.pop(fid, None)
        except OSError:
            pass


def _gc_loop():
    while True:
        time.sleep(1800)
        try:
            _gc()
        except Exception:
            pass


threading.Thread(target=_gc_loop, daemon=True).start()


# --------------------------------------------------------------------------
# 面板界面的子进程（/cs/ui/*）
# --------------------------------------------------------------------------
# 宿主是 PyInstaller 单文件包，进程内 import 插件模块会踩到它归档里损坏的
# 条目（dataclasses / logging / platform / __future__ / PIL … 一律
# "Error -3 while decompressing data: incorrect header check"）。
# 所以真正的界面服务跑在插件自带的解释器里（vendor/python/python.exe
# + ui_host.py），本文件只管生命周期：开、ping、关。
_UI = {"proc": None, "port": 0, "url": "", "seen": 0.0, "started": 0.0}
_UI_LOCK = threading.RLock()
_UI_IDLE = 900.0          # 15 分钟没人 ping 就收掉（界面自己还有 5 分钟空闲退出）
_UI_BOOT = 45.0           # 冷启动上限：要 import PIL / 工具链，比一般脚本慢
_UI_REAPER = [None]


def _ui_log_path():
    d = os.path.join(_here(), ".work")
    try:
        os.makedirs(d, exist_ok=True)
    except OSError:
        d = os.environ.get("TEMP") or "."
    return os.path.join(d, "ui.log")


def _ui_tail(path, n=1600):
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - n))
            return f.read().decode("utf-8", "replace")
    except OSError:
        return ""


def _ui_python():
    for rel in ("python/python.exe", "python/pythonw.exe"):
        p = os.path.join(_here(), "vendor", "python", rel.split("/")[1])
        if os.path.isfile(p):
            return p
    return ""


def _ui_free_port():
    import socket
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])
    finally:
        s.close()


def _ui_http_ok(port, timeout=1.0):
    """裸 socket 探活：状态行 200 就算起来了。"""
    import socket
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout) as s:
            s.sendall(b"GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n")
            head = s.recv(80)
        return head.startswith(b"HTTP/1.") and b" 200" in head[:24]
    except OSError:
        return False


def _ui_kill(proc):
    if proc is None:
        return
    try:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except Exception:
                proc.kill()
    except Exception:
        pass


def _ui_stop():
    with _UI_LOCK:
        proc = _UI.get("proc")
        _UI.update({"proc": None, "port": 0, "url": "", "started": 0.0})
    _ui_kill(proc)


def _ui_start():
    import subprocess
    py = _ui_python()
    if not py:
        return {"ok": False, "error": "组件不完整：缺 vendor/python/python.exe，"
                                     "请把整个 cyrilshield 文件夹一起解压"}
    log = _ui_log_path()
    with _UI_LOCK:
        proc = _UI.get("proc")
        port = int(_UI.get("port") or 0)
        if proc is not None and proc.poll() is None and port and _ui_http_ok(port, 0.4):
            _UI["seen"] = time.time()
            return {"ok": True, "url": _UI["url"], "port": port, "reused": True}
        _ui_kill(_UI.get("proc"))
        _UI.update({"proc": None, "port": 0, "url": "", "started": 0.0})

        port = _ui_free_port()
        flags = 0
        if os.name == "nt":
            flags = (getattr(subprocess, "CREATE_NO_WINDOW", 0)
                     | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
        fh = None
        try:
            try:
                fh = open(log, "wb")
            except OSError:
                fh = subprocess.DEVNULL
            proc = subprocess.Popen(
                [py, "-X", "utf8", "ui_host.py", str(port)],
                cwd=_here(), stdin=subprocess.DEVNULL,
                stdout=fh, stderr=subprocess.STDOUT,
                creationflags=flags, close_fds=True)
        except Exception as exc:
            return {"ok": False, "error": "启动 vendor/python 失败：%s" % exc}
        finally:
            if fh is not None and fh is not subprocess.DEVNULL:
                try:
                    fh.close()
                except Exception:
                    pass
        _UI.update({"proc": proc, "port": port,
                    "url": "http://127.0.0.1:%d/" % port,
                    "seen": time.time(), "started": time.time()})
        _ui_start_reaper()

    deadline = time.time() + _UI_BOOT
    while time.time() < deadline:
        if proc.poll() is not None:
            break
        if _ui_http_ok(port, 0.8):
            return {"ok": True, "url": _UI["url"], "port": port}
        time.sleep(0.25)
    rc = proc.poll()
    tail = _ui_tail(log, 2400)
    _ui_stop()
    return {"ok": False,
            "error": ("界面服务没有起来（子进程退出码 %r）" % (rc,)) if rc is not None
                     else "界面服务启动超时（%d 秒）" % int(_UI_BOOT),
            "log": tail}


def _ui_reap_loop():
    while True:
        time.sleep(60)
        with _UI_LOCK:
            proc = _UI.get("proc")
            seen = float(_UI.get("seen") or 0)
            idle = time.time() - seen if seen else 0.0
            if proc is None or proc.poll() is not None:
                continue
            if idle > _UI_IDLE:
                _ui_kill(proc)
                _UI.update({"proc": None, "port": 0, "url": "", "started": 0.0})
                continue
            if not _ui_http_ok(int(_UI.get("port") or 0), 0.6):
                # 端口不应答但进程还活着：多半卡死，收掉等下一次重新拉起
                _ui_kill(proc)
                _UI.update({"proc": None, "port": 0, "url": "", "started": 0.0})


def _ui_start_reaper():
    if _UI_REAPER[0] is not None and _UI_REAPER[0].is_alive():
        return
    _UI_REAPER[0] = threading.Thread(target=_ui_reap_loop, daemon=True)
    _UI_REAPER[0].start()


def unload():
    """组件被卸载 / 后端被热重载时收掉子进程，不留孤儿。"""
    _ui_stop()
