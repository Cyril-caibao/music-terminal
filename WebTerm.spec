# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置（D:\\Terminal-Windows）。

相对旧版的两处改动：
1. winpty 目录不再写死 Doubao sandbox 的绝对路径，改为从当前解释器动态解析，
   换机器 / 换虚拟环境也能构建。
2. llama 运行时只打 DLL 与 exe，**排除 llama/models**：GGUF 模型放在 exe 同目录的
   llama/models/ 下（运行时 _llama_dirs() 就是这么找的），打进包里纯属重复，
   旧配置因此把 exe 撑到了 900MB+。
"""
import os
from PyInstaller.utils.hooks import collect_submodules


def _winpty_dir():
    # 优先按当前解释器找，找不到再退回旧路径（兼容历史环境）
    try:
        import winpty
        d = os.path.dirname(os.path.abspath(winpty.__file__))
        if os.path.isdir(d):
            return d
    except Exception:
        pass
    legacy = ('C:/Users/Cuisi/AppData/Local/Doubao/User Data/sandbox_runtime/bases/'
              '772984f6fd3a8ae0ae7d2422cd7c2bfa/python/Lib/site-packages/winpty')
    return legacy if os.path.isdir(legacy) else None


def _llama_binaries(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        if 'models' in dirnames:
            dirnames.remove('models')      # 模型不进包
        rel = os.path.relpath(dirpath, root)
        dest = 'llama' if rel == '.' else os.path.join('llama', rel)
        for fn in filenames:
            out.append((os.path.join(dirpath, fn), dest))
    return out


_WINPTY = _winpty_dir()
_BINARIES = _llama_binaries('llama')
if _WINPTY:
    _BINARIES.append((_WINPTY, 'winpty'))
else:
    print('[spec] WARNING: 未找到 winpty 目录，打包后终端将无法启动')

# 插件自带后端（components/<id>/bridge.py）跑在宿主的 Python 解释器里，
# 用到的标准库宿主自己未必 import 过，PyInstaller 默认不会收 —— 缺一个就整个
# 插件加载失败（报 "No module named 'xxx'"）。这里补一份常用清单。
_PLUGIN_STDLIB = [
    'uuid', 'secrets', 'base64', 'binascii', 'hmac', 'hashlib',
    'struct', 'zipfile', 'tarfile', 'gzip', 'bz2', 'lzma', 'zlib',
    'mimetypes', 'locale', 'glob', 'fnmatch', 'shutil', 'tempfile',
    'dataclasses', 'string', 'random', 'typing', 'types', 'io',
    'urllib.request', 'urllib.parse', 'urllib.error', 'http.client',
    'csv', 'configparser', 'platform', 'queue', 'concurrent.futures',
    'xml.etree.ElementTree', 'html', 'unicodedata', 'colorsys',
    'email', 'email.header', 'email.utils',
]

a = Analysis(
    ['server.py'],
    pathex=[],
    binaries=_BINARIES,
    datas=[('web', 'web')],
    hiddenimports=['winpty'] + collect_submodules('mutagen') + _PLUGIN_STDLIB,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='WebTerm',
    # 应用图标：与安装包 / Electron 壳共用 electron-tools/assets/app.ico（Cyril 终端窗口外框）
    icon=os.path.abspath(os.path.join('electron-tools', 'assets', 'app.ico')),
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
