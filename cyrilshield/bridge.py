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
        _MODS = {"apk": apk, "pe": pe, "upx": upx, "vmgen": vmgen, "home": home}
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
    )
    r = vmgen.build(src, out, vo, on_line=lambda s: log(s, "DIM"))
    log("输出：%s（%s 字节）" % (r.output, "{:,}".format(r.output_size)), "OK")
    return {"path": r.output, "name": os.path.basename(r.output),
            "size_before": r.original_size, "size_after": r.output_size,
            "fingerprint": r.fingerprint}


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

    ext = os.path.splitext(src_name)[1].lower()
    if tool in ("icon", "upx", "vm") and ext not in (".exe", ".dll", ".scr", ".cpl", ".ocx", ".sys"):
        h._send_json({"ok": False, "error": "该工具需要 PE 文件（.exe/.dll 等）"}, 400)
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
