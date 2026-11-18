# -*- coding: utf-8 -*-
"""大更新前的完整快照：主包（代码 + 当前成品 + 工具链）+ 轻量源码包。

用 Python 标准库流式写入，逐条目记录 SHA256；已压缩的大二进制用 STORED
（不再二次压缩，速度快、体积几乎不变），文本/脚本用 DEFLATED。

不修改任何源文件，只往 _backup/ 写新文件；同名旧备份不覆盖。
"""
import hashlib
import os
import time
import zipfile
from datetime import datetime

W = "D:/Terminal-Windows"
L = "D:/Terminal-Linux"
M = "C:/Users/Cuisi/WorkBuddy/2026-11-01-17-41-59/.workbuddy/memory"
OUT = os.path.join(W, "_backup")

# 只按「当前」状态备份：历史旧版归档、构建中间产物、第三方模型不进包。
SKIP_DIR_NAMES = {"__pycache__", ".git", ".venv"}
SKIP_PREFIX = (
    "build/", "build-folia/", "llama/", "_backup/",
    "dist/llama/", "dist/components/", "dist/lxmusic-data/", "dist/extracted_icons/",
    "electron-dist/_prev_archive/", "electron-dist/_runtime_bak/",
    "electron-dist/_staging/",
    "installer/_prev/",
)
NO_ZIP_EXT = {
    ".exe", ".dll", ".nupkg", ".zip", ".7z", ".msi", ".asar", ".pak",
    ".jpg", ".jpeg", ".png", ".gif", ".ico", ".webp", ".bin", ".dat",
    ".gguf", ".so", ".pyd", ".woff", ".woff2", ".ttf",
}

TAG = datetime.now().strftime("%Y%m%d-%H%M")
MAIN = os.path.join(OUT, "WebTerm-更新前-代码与成品-" + TAG + ".zip")
LITE = os.path.join(OUT, "WebTerm-更新前-源码轻量-" + TAG + ".zip")


def skip_file(fn):
    if fn in ("desktop.ini", "_srv.log", "_srv.log.err") or fn.startswith("._"):
        return True
    if ".prev" in fn or fn.endswith((".new", ".pack-tmp", ".tmp")):
        return True
    return os.path.splitext(fn)[1].lower() in (".pyc", ".log")


def classify(rel):
    p = [
        ("dist/", "成品/01-Web版后端/"),
        ("installer/", "成品/02-安装包-1.0.3/"),
        ("electron-dist/WebTerm-Electron-win32-x64/", "成品/03-Electron桌面版/"),
        ("electron-tools/vendor/", "成品/04-构建工具链/vendor/"),
        ("electron-tools/node_modules/", "成品/04-构建工具链/node_modules/"),
        ("components/", "成品/05-用户组件/"),
    ]
    for prefix, dest in p:
        if rel.startswith(prefix):
            return dest + rel[len(prefix):], False
    return "源码/Terminal-Windows/" + rel, True


def walk(root, prefix, skip_prefixes=()):
    out = []
    for dp, dns, fns in os.walk(root):
        dns[:] = sorted(d for d in dns if d not in SKIP_DIR_NAMES and not d.startswith("."))
        reldir = os.path.relpath(dp, root).replace("\\", "/")
        reldir = "" if reldir == "." else reldir + "/"
        for fn in sorted(fns):
            if skip_file(fn):
                continue
            rel = reldir + fn
            if any(rel.startswith(p) for p in skip_prefixes):
                continue
            full = os.path.join(dp, fn)
            if not os.path.isfile(full):        # 跳过符号链接 / 目录项
                continue
            out.append((prefix + rel, full))
    return out


def build_entries():
    e = []
    for arc, full in walk(W, "", SKIP_PREFIX):
        dest, is_src = classify(arc)
        e.append((dest, full, is_src))
    for arc, full in walk(L, "源码/Terminal-Linux/"):
        e.append((arc, full, True))
    if os.path.isdir(M):
        for arc, full in walk(M, "元数据/工作日志/"):
            e.append((arc, full, True))
    return e


def dir_size(p, only_prev=False):
    """统计目录体积；only_prev 只累加 .prev* 旧版文件，避免把整个 dist 算进去。"""
    if not os.path.isdir(p):
        return 0
    total = 0
    for dp, _, fs in os.walk(p):
        for f in fs:
            if only_prev and ".prev" not in f:
                continue
            fp = os.path.join(dp, f)
            if os.path.isfile(fp):
                total += os.path.getsize(fp)
    return total


def make_manifest(rows, title, now):
    lines = ["# %s  生成于 %s" % (title, now),
             "# SHA256  字节数  压缩方式  相对路径", ""]
    for arc, h, sz, ct in rows:
        lines.append("%s  %d  %s  %s" % (h, sz, ct, arc))
    return "\n".join(lines) + "\n"


def write_zip(path, items, header, now, title):
    """流式写入并在同一个归档内补上清单与说明，避免 append 模式改写中央目录。"""
    rows = []
    with zipfile.ZipFile(path, "w", allowZip64=True) as z:
        for arc, full, _is_src in items:
            st = os.stat(full)
            lt = time.localtime(st.st_mtime)
            zi = zipfile.ZipInfo(arc, (lt.tm_year, lt.tm_mon, lt.tm_mday,
                                       lt.tm_hour, lt.tm_min, lt.tm_sec))
            ext = os.path.splitext(full)[1].lower()
            zi.compress_type = (zipfile.ZIP_STORED if ext in NO_ZIP_EXT
                                else zipfile.ZIP_DEFLATED)
            zi.external_attr = 0o644 << 16
            zi.create_system = 3
            h = hashlib.sha256()
            with z.open(zi, "w") as dst, open(full, "rb") as src:
                while True:
                    b = src.read(1024 * 1024)
                    if not b:
                        break
                    dst.write(b)
                    h.update(b)
            rows.append((arc, h.hexdigest(), st.st_size,
                         "STORED" if zi.compress_type == zipfile.ZIP_STORED else "DEFLATED"))

        for name, text in (("备份说明.md", header),
                           ("备份清单.txt", make_manifest(rows, title, now))):
            zi = zipfile.ZipInfo(name, tuple(time.localtime()[:6]))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            zi.create_system = 3
            with z.open(zi, "w") as dst:
                dst.write(text.encode("utf-8"))
    return rows


def main():
    os.makedirs(OUT, exist_ok=True)
    entries = build_entries()
    main_items = [(a, f, s) for a, f, s in entries]
    lite_items = [(a, f, s) for a, f, s in entries if s]

    excluded = [
        ("dist/WebTerm.exe.prev1..prev6（6 个旧版后端 exe）",
         dir_size(os.path.join(W, "dist"), only_prev=True)),
        ("installer/_prev/（1.0.2 与旧 1.0.3 安装包）",
         dir_size(os.path.join(W, "installer/_prev"))),
        ("electron-dist/_prev_archive/（旧 Electron 副本）",
         dir_size(os.path.join(W, "electron-dist/_prev_archive"))),
        ("electron-dist/_runtime_bak/（运行时数据副本）",
         dir_size(os.path.join(W, "electron-dist/_runtime_bak"))),
        ("electron-dist/_staging/（硬链接目录，与成品共用 inode，不额外占盘）", None),
        ("build/ + build-folia/（PyInstaller / Folia 中间产物）",
         dir_size(os.path.join(W, "build")) + dir_size(os.path.join(W, "build-folia"))),
        ("llama/（本地模型与推理运行时，第三方依赖）", dir_size(os.path.join(W, "llama"))),
        ("dist/llama、dist/components、dist/lxmusic-data（运行时数据副本，主目录另有收录）", None),
        ("__pycache__ / *.pyc / *_srv.log / *.tmp / *.new（缓存与临时文件）", None),
    ]
    ex_lines = ["  - %s%s" % (label, ("  [%.1f MB]" % (sz / 1048576)) if sz else "")
                for label, sz in excluded]

    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    header = (
        "WebTerm 大更新前快照\n"
        "生成时间：%s\n"
        "主包：%s\n"
        "轻量源码包：%s\n\n"
        "【目录映射】\n"
        "  源码/Terminal-Windows/     → D:/Terminal-Windows 的源码、配置、文档、构建脚本（成品另见下方）\n"
        "  源码/Terminal-Linux/       → D:/Terminal-Linux（全量）\n"
        "  成品/01-Web版后端/         → dist/ 当前 WebTerm.exe 与运行时配置\n"
        "  成品/02-安装包-1.0.3/      → installer/ 当前 Setup.exe / nupkg / RELEASES\n"
        "  成品/03-Electron桌面版/    → electron-dist/WebTerm-Electron-win32-x64/\n"
        "  成品/04-构建工具链/        → electron-tools/vendor + node_modules\n"
        "  成品/05-用户组件/          → components/（含 cyrilshield 插件）\n"
        "  元数据/工作日志/           → 项目 .workbuddy/memory 日志\n\n"
        "【未纳入（当前状态不需要，或属第三方/历史归档）】\n%s\n\n"
        "【敏感数据】\n"
        "  包含 Settings.json 与 users.json（PBKDF2 账号哈希）。仅供本机恢复，勿外传。\n\n"
        "【恢复】\n"
        "  按目录映射解压回原路径即可；两份包均附 备份清单.txt，\n"
        "  每行格式：SHA256  字节数  压缩方式  相对路径，可逐项校验。\n"
        "  恢复前端/后端改动后，务必关闭所有已开的 WebTerm 窗口再重开。\n"
        % (now, os.path.basename(MAIN), os.path.basename(LITE), "\n".join(ex_lines))
    )

    print("主包条目:", len(main_items), " 轻量包条目:", len(lite_items))
    t0 = time.time()
    main_rows = write_zip(MAIN, main_items, header, now, "主包")
    print("主包写完，耗时 %.1fs，大小 %d 字节" %
          (time.time() - t0, os.path.getsize(MAIN)))
    lite_rows = write_zip(LITE, lite_items, header, now, "轻量源码包")
    print("轻量包写完，大小 %d 字节" % os.path.getsize(LITE))

    for path, rows, title in ((MAIN, main_rows, "主包"), (LITE, lite_rows, "轻量源码包")):
        with open(os.path.join(OUT, os.path.basename(path).replace(".zip", "-清单.txt")),
                  "w", encoding="utf-8") as f:
            f.write(make_manifest(rows, title, now))
    print("清单与说明已写入两个 zip，并另存一份到 _backup/")
    print("MAIN=", MAIN)
    print("LITE=", LITE)


if __name__ == "__main__":
    main()
