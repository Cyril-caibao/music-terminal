# -*- coding: utf-8 -*-
"""运行时守卫：反调试 / 反分析环境检测，以及"缩短明文驻留"的载荷释放。

这两个能力以**源码字符串**的形式提供，由 ``vmgen.render_stub`` 注入到壳内
``boot.py``。原因：壳是用 PyInstaller 单独打包的独立 EXE，运行时无法 import
``cyrilshield.*``，所有逻辑必须自包含在 stub 源码里。

设计原则（与项目一贯立场一致）：
  * 只用于保护使用者**自己拥有合法权利**的程序，提高动态分析的入门门槛；
  * 检测失败 / 非 Windows 环境 / 任何异常，一律**放行**，绝不误伤正常用户；
  * 不包含也不会实现杀软对抗、AMSI/ETW 打补丁、进程注入、持久化、驱动等
    规避性手段；
  * 检测点到静默退出，不弹窗暴露判定逻辑。

两个占位符在注入前由 ``render_stub`` 替换：
  * ``__GUARD_MODE__``  -> 0=关闭, 1=反调试, 2=反分析环境, 3=两者
  * ``__SECURE_TEMP__`` -> True/False，是否启用 delete-on-close 释放
"""
from __future__ import annotations

import base64 as _b64
import random as _random
import zlib as _zlib

# 能力清单：供自检 / 文档 / 界面展示使用，不参与运行时
CAPABILITIES = (
    ("anti_debug", "反调试（基础 API / 远程调试 / 调试端口与对象 / 硬件断点 / 单步计时）"),
    ("anti_sandbox", "反分析环境（进程 / 用户名 / 虚拟化驱动 / 规格 / CPU 品牌 / 显示规格）"),
    ("secure_temp", "加固临时释放（delete-on-close，缩短明文落盘窗口）"),
)


def guard_mode(anti_debug: bool, anti_sandbox: bool) -> int:
    """把两个布尔开关压成位掩码。"""
    return (1 if anti_debug else 0) | (2 if anti_sandbox else 0)


# ---------------------------------------------------------------------------
# 注入片段 1：反调试 / 反分析环境
# 全部标识符以 _at_ 开头，避开 vmgen.RENAMEABLE 的随机重命名；
# 只依赖 stub 已 import 的 ctypes / os / sys / subprocess / tempfile / shutil。
# ---------------------------------------------------------------------------
GUARD_SOURCE = r'''
# ---- CyrilShield runtime guard (injected) ----
_AT_MODE = __GUARD_MODE__


def _at_safe(fn, default=False):
    """任何异常都视为"未命中"，保证检测逻辑本身不会拖垮被保护的程序。"""
    try:
        return fn()
    except Exception:
        return default


def _at_win():
    return sys.platform == "win32"


def _at_debugger():
    """调试器检测：常规 API + NT 调试端口 + 单步计时。"""
    if not _at_win():
        return False

    def _probe():
        k32 = ctypes.windll.kernel32
        ntdll = ctypes.windll.ntdll

        # 1) 用户态最基础的两个标志
        if k32.IsDebuggerPresent():
            return True
        hproc = k32.GetCurrentProcess()
        remote = ctypes.c_int(0)
        if k32.CheckRemoteDebuggerPresent(hproc, ctypes.byref(remote)) and remote.value:
            return True

        # 2) NtQueryInformationProcess：ProcessDebugPort(7) / DebugObjectHandle(30)
        ntdll.NtQueryInformationProcess.restype = ctypes.c_long
        for cls in (7, 30):
            val = ctypes.c_void_p(0)
            retlen = ctypes.c_ulong(0)
            st = ntdll.NtQueryInformationProcess(
                hproc, cls, ctypes.byref(val), ctypes.sizeof(val),
                ctypes.byref(retlen))
            if st == 0 and val.value:
                return True

        # 3) ProcessDebugFlags(31)：返回 0 表示正在被调试
        flags = ctypes.c_ulong(0)
        retlen = ctypes.c_ulong(0)
        st = ntdll.NtQueryInformationProcess(
            hproc, 31, ctypes.byref(flags), ctypes.sizeof(flags),
            ctypes.byref(retlen))
        if st == 0 and flags.value == 0:
            return True
        return False

    if _at_safe(_probe, False):
        return True

    # 4) 硬件断点：Dr0~Dr3 非零说明被下了硬件断点。
    #    GetThreadContext 对运行中的线程可能失败，失败一律放行。
    def _hwbp():
        k32 = ctypes.windll.kernel32
        is64 = ctypes.sizeof(ctypes.c_void_p) == 8
        if is64:
            class _CTX(ctypes.Structure):
                _fields_ = [
                    ("p", ctypes.c_uint64 * 6), ("flags", ctypes.c_uint32),
                    ("mx", ctypes.c_uint32), ("seg", ctypes.c_uint16 * 6),
                    ("ef", ctypes.c_uint32), ("dr", ctypes.c_uint64 * 6),
                ]
            flag = 0x00100010
        else:
            class _CTX(ctypes.Structure):
                _fields_ = [
                    ("flags", ctypes.c_uint32), ("dr", ctypes.c_uint32 * 6),
                    ("pad", ctypes.c_uint8 * 112),
                ]
            flag = 0x00010010
        ctx = _CTX()
        ctx.flags = flag
        k32.GetCurrentThread.restype = ctypes.c_void_p
        if not k32.GetThreadContext(k32.GetCurrentThread(), ctypes.byref(ctx)):
            return False
        return any(int(x) for x in ctx.dr[:4])

    if _at_safe(_hwbp, False):
        return True

    # 5) 单步 / 指令断点会让一段确定性工作耗时异常放大
    def _timing():
        import time
        t0 = time.perf_counter_ns()
        acc = 0
        for _i in range(20000):
            acc ^= _i
        elapsed = time.perf_counter_ns() - t0
        # 现代 CPU 上这段代码 < 5ms；阈值放宽到 200ms，只拦明显单步
        return elapsed > 200_000_000

    return _at_safe(_timing, False)


_AT_TOOL_NAMES = (
    "ollydbg", "x64dbg", "x32dbg", "windbg", "ida", "ida64", "idaq",
    "scylla", "pestudio", "processhacker", "procexp", "procmon", "regmon",
    "filemon", "wireshark", "fiddler", "charles", "apimonitor", "cheatengine",
    "die", "detectiteasy", "petools", "importrec", "lordpe", "immunity",
    "vboxservice", "vboxtray", "vmtoolsd", "vmwareuser", "vmwaretray",
    "qemu-ga", "prl_cc", "prl_tools", "sandboxiedcomlaunch", "sbiesvc",
    "joeboxserver", "cuckoomon", "analyzer", "sandbox",
)

_AT_ENV_HINTS = (
    "sandbox", "malware", "virus", "sample", "cuckoo", "joe", "innotek",
    "virtualbox", "vmware", "qemu", "xen", "analyst", "lab",
)


def _at_process_names():
    """用 Toolhelp32 枚举进程名；失败返回空集合（放行）。"""
    if not _at_win():
        return set()

    def _snapshot():
        k32 = ctypes.windll.kernel32

        class PROCESSENTRY32W(ctypes.Structure):
            _fields_ = [
                ("dwSize", ctypes.c_ulong),
                ("cntUsage", ctypes.c_ulong),
                ("th32ProcessID", ctypes.c_ulong),
                ("th32DefaultHeapID", ctypes.POINTER(ctypes.c_ulong)),
                ("th32ModuleID", ctypes.c_ulong),
                ("cntThreads", ctypes.c_ulong),
                ("th32ParentProcessID", ctypes.c_ulong),
                ("pcPriClassBase", ctypes.c_long),
                ("dwFlags", ctypes.c_ulong),
                ("szExeFile", ctypes.c_wchar * 260),
            ]

        TH32CS_SNAPPROCESS = 0x00000002
        INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value
        k32.CreateToolhelp32Snapshot.restype = ctypes.c_void_p
        k32.Process32FirstW.restype = ctypes.c_int
        k32.Process32NextW.restype = ctypes.c_int

        snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
        if not snap or snap == INVALID_HANDLE_VALUE:
            return set()
        names = set()
        try:
            entry = PROCESSENTRY32W()
            entry.dwSize = ctypes.sizeof(PROCESSENTRY32W)
            ok = k32.Process32FirstW(snap, ctypes.byref(entry))
            while ok:
                names.add(entry.szExeFile.lower())
                ok = k32.Process32NextW(snap, ctypes.byref(entry))
        finally:
            k32.CloseHandle(snap)
        return names

    return _at_safe(_snapshot, set())


def _at_analysis_env():
    """分析环境启发式检测：进程、用户/机器名、虚拟机痕迹、规格异常。"""
    if not _at_win():
        return False

    def _probe():
        # 1) 已知分析 / 虚拟化进程
        procs = _at_process_names()
        for tool in _AT_TOOL_NAMES:
            needle = tool.strip().lower()
            if needle and any(needle in p for p in procs):
                return True

        # 2) 用户名 / 计算机名含沙箱特征
        who = ((os.environ.get("USERNAME") or "") + " "
               + (os.environ.get("COMPUTERNAME") or "")).lower()
        for hint in _AT_ENV_HINTS:
            if hint in who:
                return True

        # 3) 常见虚拟机驱动 / 客户机附加组件文件
        sysroot = os.environ.get("SystemRoot", r"C:\Windows")
        vm_files = (
            r"System32\drivers\VBoxGuest.sys",
            r"System32\drivers\vmmouse.sys",
            r"System32\drivers\vmhgfs.sys",
            r"System32\vboxdisp.dll",
            r"System32\vmGuestLib.dll",
        )
        for rel in vm_files:
            if os.path.isfile(os.path.join(sysroot, rel)):
                return True

        # 4) 规格异常（沙箱常配 1 核 / 极小内存）
        import shutil as _sh
        cpu = os.cpu_count() or 0
        if 0 < cpu <= 1:
            total, _used, _free = _sh.disk_usage(sysroot[:3] if len(sysroot) >= 3 else "C:\\")
            if total < 60 * 1024 * 1024 * 1024:  # < 60 GB
                return True

        # 5) 固件 / CPU 品牌特征（沙箱常见虚拟 CPU 标识）
        brand = (os.environ.get("PROCESSOR_IDENTIFIER") or "").lower()
        for hint in ("qemu", "virtual cpu", "vmware", "vbox", "xen", "kvm"):
            if hint in brand:
                return True

        # 6) 显示规格异常（无头分析机常为 800x600 或更低）
        user32 = ctypes.windll.user32
        if user32.GetSystemMetrics(0) < 800 or user32.GetSystemMetrics(1) < 600:
            return True
        return False

    return _at_safe(_probe, False)


def _at_guard():
    """返回 True 表示放行。命中检测时静默退出，不泄露判定逻辑。

    注意：默认关闭（_AT_MODE=0）；只有使用者显式勾选才启用，
    因为反分析启发式存在误伤虚拟桌面 / 云主机 / 企业终端的可能。
    """
    if not _AT_MODE:
        return True
    try:
        if (_AT_MODE & 1) and _at_debugger():
            return False
        if (_AT_MODE & 2) and _at_analysis_env():
            return False
    except Exception:
        return True
    return True
# ---- end runtime guard ----
'''


# ---------------------------------------------------------------------------
# 注入片段 2：载荷释放。默认路径与原实现等价；secure 模式在 Windows 上用
# FILE_FLAG_DELETE_ON_CLOSE 打开文件，写入明文后立即交给 CreateProcess，
# 并在句柄关闭后由系统删除文件——磁盘上不存在"可被随手复制"的稳定明文。
#
# 这不是"内存加载 / 不落地"：见 docs/SECURITY-NOTES.md 的完整说明。
# ---------------------------------------------------------------------------
SPAWN_SOURCE = r'''
# ---- CyrilShield payload drop (injected) ----
_AT_SECURE = __SECURE_TEMP__


def _at_argv(meta):
    argv = []
    if meta["args"]:
        argv += meta["args"].split()
    if meta["flags"] & 4:
        argv += sys.argv[1:]
    return argv


def _at_drop_delete_on_close(exe, ct_bytes):
    """Windows：以 delete-on-close 写入明文，返回待关闭的句柄。

    文件在最后一个句柄关闭时由系统删除，磁盘上不留稳定明文。
    注意 FILE_FLAG_DELETE_ON_CLOSE 要求后续打开者也带 FILE_SHARE_DELETE，
    而 CreateProcess 不保证如此，故调用方必须保留失败回退。
    """
    import ctypes as _c
    k32 = _c.WinDLL("kernel32", use_last_error=True)
    k32.CreateFileW.restype = _c.c_void_p
    k32.CreateFileW.argtypes = [
        _c.c_wchar_p, _c.c_uint32, _c.c_uint32, _c.c_void_p,
        _c.c_uint32, _c.c_uint32, _c.c_void_p]
    k32.WriteFile.argtypes = [
        _c.c_void_p, _c.c_void_p, _c.c_uint32,
        _c.POINTER(_c.c_uint32), _c.c_void_p]
    GENERIC_WRITE = 0x40000000
    FILE_SHARE_READ = 0x00000001
    FILE_SHARE_WRITE = 0x00000002
    FILE_SHARE_DELETE = 0x00000004
    CREATE_NEW = 1
    FILE_FLAG_DELETE_ON_CLOSE = 0x04000000
    FILE_ATTRIBUTE_TEMPORARY = 0x00000100
    handle = k32.CreateFileW(
        exe, GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        None, CREATE_NEW,
        FILE_FLAG_DELETE_ON_CLOSE | FILE_ATTRIBUTE_TEMPORARY, None)
    invalid = _c.c_void_p(-1).value
    if not handle or handle == invalid:
        raise OSError("CreateFileW failed")
    buf = _c.create_string_buffer(ct_bytes)
    written = _c.c_uint32(0)
    if not k32.WriteFile(handle, buf, len(ct_bytes), _c.byref(written), None):
        k32.CloseHandle(handle)
        raise OSError("WriteFile failed")
    return k32, handle


def _at_spawn(ct_bytes, meta):
    """把解密后的原程序释放并运行，返回退出码。"""
    argv = _at_argv(meta)
    cleanup = bool(meta["flags"] & 2)
    # 载荷名来自加密元数据，仍做一次 basename 清洗，避免路径穿越
    name = os.path.basename(str(meta["name"] or "payload.exe").replace("\\", "/"))
    name = name or "payload.exe"
    if not name.lower().endswith(".exe"):
        name += ".exe"

    # 优先尝试 delete-on-close：磁盘上不存在可被随手复制的稳定明文。
    # 用独立目录，失败后彻底丢弃，避免 delete-pending 文件干扰回退。
    if _AT_SECURE and sys.platform == "win32":
        folder = tempfile.mkdtemp(prefix="_cs")
        try:
            exe = os.path.join(folder, name)
            k32, handle = _at_drop_delete_on_close(exe, ct_bytes)
            try:
                # 保持句柄，让 CreateProcess 能读到该路径
                proc = subprocess.Popen([exe] + argv, cwd=folder)
                rc = proc.wait()
                return rc if rc is not None else 0
            finally:
                k32.CloseHandle(handle)   # 触发系统删除
        except Exception:
            pass  # 该方案不通用，回落到常规路径
        finally:
            shutil.rmtree(folder, ignore_errors=True)

    # 常规路径（非 Windows / 未启用 / 加固失败）：这是降级路径，
    # 保证「一定能正确运行」优先——等进程结束后再清理，绝不在运行期删文件
    # （那样会打断脚本解释器 / 动态链接器读取载荷）。缩短明文窗口的职责
    # 由上面的 Windows delete-on-close 路径承担。
    folder = tempfile.mkdtemp(prefix="_cs")
    try:
        exe = os.path.join(folder, name)
        with open(exe, "wb") as f:
            f.write(ct_bytes)
        # 本工具面向 Windows；回落路径在 POSIX 上补可执行位，便于自检与移植
        if os.name == "posix":
            try:
                os.chmod(exe, 0o700)
            except OSError:
                pass
        proc = subprocess.Popen([exe] + argv, cwd=folder)
        rc = proc.wait()
        return rc if rc is not None else 0
    finally:
        if cleanup:
            shutil.rmtree(folder, ignore_errors=True)
# ---- end payload drop ----
'''


# 注入片段自带的依赖，保证它可以独立 exec（也便于自检），
# 在 stub 里与原有 import 重复但无害。注意这些名字不在 vmgen.RENAMEABLE
# 中，且在标识符随机化之后才注入，不会被重命名。
_INJECT_IMPORTS = (
    "import ctypes, os, sys, subprocess, tempfile, shutil\n"
)


def injected_source(mode: int, secure_temp: bool) -> str:
    """拼出最终注入 stub 的源码（自包含、占位符已替换）。"""
    src = _INJECT_IMPORTS + GUARD_SOURCE + "\n" + SPAWN_SOURCE
    return (src.replace("__GUARD_MODE__", str(int(mode)))
               .replace("__SECURE_TEMP__", repr(bool(secure_temp))))


def bootstrap_source(mode: int, secure_temp: bool,
                     rng: "_random.Random | None" = None) -> str:
    """把守卫/释放源码压缩编码后，生成一行自解码的引导代码。

    效果：stub 里不再出现可读的反调试逻辑明文，静态反编译只能看到一段
    base85 字符串；随机盐保证**每次构建的引导串都不同**，避免成为新特征。
    运行时由 exec 解码执行，函数名仍与 STUB_TEMPLATE 的调用点一致。
    """
    rng = rng or _random.SystemRandom()
    src = injected_source(mode, secure_temp)
    salt = rng.randbytes(rng.randrange(8, 25))
    packed = salt + _zlib.compress(src.encode("utf-8"), 9)
    blob = _b64.b85encode(packed).decode("ascii")
    return (
        "import base64 as _csb, zlib as _csz\n"
        "_csp = _csb.b85decode(%r)[%d:]\n"
        "exec(compile(_csz.decompress(_csp).decode('utf-8'), '<runtime>', 'exec'))\n"
        % (blob, len(salt))
    )
