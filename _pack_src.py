# -*- coding: utf-8 -*-
"""把 Windows / Linux 两版源码分别打成独立 zip 放到桌面。

只收源码、构建脚本、文档与运行所需资源（含深色壁纸）；排除编译产物、依赖、
运行时数据和大二进制素材。真实账号哈希（users.json）与本机布局（Settings.json）不进包。
"""
import os
import zipfile
from datetime import date

DESKTOP = "C:/Users/Cuisi/Desktop"
TAG = date.today().isoformat()
PROJECTS = [
    ("D:/Terminal-Windows", "WebTerm-Windows-源码-" + TAG + ".zip"),
    ("D:/Terminal-Linux", "WebTerm-Linux-源码-" + TAG + ".zip"),
]

SKIP_DIRS = {
    "dist", "build", "build-folia", "llama", "electron-dist", "installer",
    "node_modules", "vendor", "_backup", "_icon_stage", "__pycache__", ".git",
    "_staging", "_prev_archive", "_runtime_bak", "_prev", ".venv",
}
SKIP_FILES = {"users.json", "Settings.json", "_srv.log", "_srv.log.err",
              "desktop.ini", "package-lock.json"}
SKIP_EXT = {".exe", ".dll", ".zip", ".nupkg", ".gguf", ".pyc", ".lnk",
            ".pyd", ".so", ".pak", ".bin", ".dat", ".tmp", ".log"}
TEXT_EXT = {".py", ".js", ".mjs", ".cjs", ".css", ".html", ".json", ".md",
            ".txt", ".spec", ".sh", ".bat", ".ico", ".nuspectemplate",
            ".npy", ".cfg", ".toml", ".yaml", ".yml"}
IMG_EXT = {".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"}
MAX_SIZE = 3 * 1024 * 1024          # 3 MB：小图标/壁纸收，大素材不收
# 运行时真正被代码引用的大素材必须进包，否则解压后深色壁纸功能直接缺失。
# 前端 web/index.html 用 web/wallpaper_dark.jpg，桌面端 kali_terminal.py 用根目录同名文件。
KEEP_LARGE = {"wallpaper_dark.jpg"}

stats = []
for root, zipname in PROJECTS:
    out = os.path.join(DESKTOP, zipname)
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".")]
        for fn in filenames:
            if fn in SKIP_FILES or fn.startswith("._"):
                continue
            ext = os.path.splitext(fn)[1].lower()
            if ext in SKIP_EXT or ".prev" in fn or fn.endswith((".pack-tmp", ".new")):
                continue
            if ext not in TEXT_EXT and ext not in IMG_EXT:
                continue
            full = os.path.join(dirpath, fn)
            try:
                size = os.path.getsize(full)
            except OSError:
                continue
            if size > MAX_SIZE and fn not in KEEP_LARGE:
                continue
            files.append((full, os.path.relpath(full, root).replace("\\", "/")))
    files.sort(key=lambda x: x[1])
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for full, arc in files:
            z.write(full, arc)
    total = sum(os.path.getsize(f) for f, _ in files)
    stats.append((zipname, len(files), total, os.path.getsize(out)))

for name, n, raw, packed in stats:
    print("%s\n   %d 个文件，原始 %.1f MB，压缩后 %.1f MB"
          % (name, n, raw / 1048576, packed / 1048576))
print("\n输出目录:", DESKTOP)
