# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置（仓库根目录）。

相对旧版的两处改动：
1. winpty 目录不再写死第三方沙箱运行时的绝对路径，改为从当前解释器动态解析，
   换机器 / 换虚拟环境也能构建。
2. llama 运行时只打 DLL 与 exe，**排除 llama/models**：GGUF 模型放在 exe 同目录的
   llama/models/ 下（运行时 _llama_dirs() 就是这么找的），打进包里纯属重复，
   旧配置因此把 exe 撑到了 900MB+。
"""
import os


def _winpty_dir():
    # 优先按当前解释器找，找不到再退回旧路径（兼容历史环境）
    try:
        import winpty
        d = os.path.dirname(os.path.abspath(winpty.__file__))
        if os.path.isdir(d):
            return d
    except Exception:
        pass
    # 便携环境可用 WEBTERM_WINPTY_DIR 指定 winpty 目录，不写死本机绝对路径
    legacy = os.environ.get('WEBTERM_WINPTY_DIR', '')
    return legacy if legacy and os.path.isdir(legacy) else None


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

a = Analysis(
    ['server.py'],
    pathex=[],
    binaries=_BINARIES,
    datas=[('web', 'web')],
    hiddenimports=['winpty'],
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
