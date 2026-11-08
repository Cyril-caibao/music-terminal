# -*- coding: utf-8 -*-
"""Python ↔ 原生侧载层（Native Sidecar）桥接。

解决的核心问题
--------------
CPython 运行在受保护的用户态解释层里，无法直接触达硬件：
  * 不能执行特权指令 / 读 MSR / 访问 MMIO 与端口；
  * 不能可靠拿到 SMBIOS UUID、卷序列号、CPUID 特性字等硬件身份；
  * 对象模型与 GIL 让「精确时序 + 原始内存」这类操作无从落地。

本模块用**侧载（side-car）**方式补上这一层，而不是让 Python 越权：

    ┌──────────────────────────────────────────────┐
    │ L3  Python 应用层   UI / 编排 / 保护策略        │
    ├──────────────────────────────────────────────┤
    │ L2  桥接层          本文件：能力门 + ABI + 降级  │
    ├──────────────────────────────────────────────┤
    │ L1  原生侧载层      CyrilShield.Native.dll/.so  │
    │                    显式加载 + 摘要校验           │
    ├──────────────────────────────────────────────┤
    │ L0  公开 OS 接口    Win32/WMI/sysfs/procfs      │
    │                    （不装驱动、不提权、不注入）   │
    └──────────────────────────────────────────────┘

「侧载」的确切含义是：与应用同目录部署、由应用**显式 LoadLibrary /
dlopen**、并在加载后校验 ABI 与自身 SHA-256 的伴随库。它不是 DLL
劫持或白利用——加载路径固定、不依赖搜索顺序、不寄生于第三方进程。

安全边界
--------
  * 能力门（CapabilityGate）：调用方必须先声明需要的能力，未声明即拒绝；
  * 只读：侧载层不写系统状态、不改其他进程、不申请管理员权限；
  * 可降级：找不到 DLL / ABI 不匹配 / 校验失败时自动回落到纯 Python
    参考后端，绝不因缺件而崩溃；
  * 防替换：可用 ``expected_sha256`` 钉住侧载库摘要。

目标平台是 Windows x64；Linux 后端仅用于开发期编译与自检。
"""
from __future__ import annotations

import ctypes
import hashlib
import os
import platform
import struct
import sys
import uuid
from dataclasses import dataclass, field

ABI_VERSION = 1

CAP_HWID = "hwid"
CAP_TICKS = "ticks"
CAP_CPU_FEATURES = "cpu_features"
CAP_FILE_DIGEST = "file_digest"
CAP_SELF_DIGEST = "self_digest"

ALL_CAPABILITIES = frozenset({
    CAP_HWID, CAP_TICKS, CAP_CPU_FEATURES, CAP_FILE_DIGEST, CAP_SELF_DIGEST,
})

# C 侧的能力位 -> Python 侧能力名
_CAP_BITS = {
    1 << 0: CAP_HWID,
    1 << 1: CAP_TICKS,
    1 << 2: CAP_CPU_FEATURES,
    1 << 3: CAP_SELF_DIGEST,
    1 << 4: CAP_FILE_DIGEST,
}

LIB_NAMES = (
    "CyrilShield.Native.dll",
    "libcyrilshield_native.so",
    "libcyrilshield_native.dylib",
)


class SidecarError(Exception):
    """侧载层不可用或校验失败。"""


@dataclass(frozen=True)
class HardwareId:
    """规范化硬件指纹。字段含义与 C 侧 ``csn_hwid`` 一一对应。"""
    machine: bytes
    volume: bytes
    cpu: str
    os: str
    cpu_count: int
    mem_mb: int
    digest: bytes

    @property
    def hex(self) -> str:
        return self.digest.hex()

    @property
    def short(self) -> str:
        """用于授权绑定的短指纹（取前 16 字节）。"""
        return self.digest.hex()[:32]

    def as_dict(self) -> dict:
        return {
            "machine": self.machine.hex(),
            "volume": self.volume.hex(),
            "cpu": self.cpu,
            "os": self.os,
            "cpu_count": self.cpu_count,
            "mem_mb": self.mem_mb,
            "digest": self.digest.hex(),
        }


class _CHwid(ctypes.Structure):
    _fields_ = [
        ("size", ctypes.c_uint32),
        ("flags", ctypes.c_uint32),
        ("machine", ctypes.c_ubyte * 16),
        ("volume", ctypes.c_ubyte * 8),
        ("cpu", ctypes.c_char * 64),
        ("os", ctypes.c_char * 64),
        ("cpu_count", ctypes.c_uint32),
        ("mem_mb", ctypes.c_uint64),
        ("digest", ctypes.c_ubyte * 32),
    ]


class CapabilityGate:
    """最小权限门：调用方显式声明能力，未声明的一律拒绝。"""

    def __init__(self, granted: set[str] | frozenset[str] | None = None):
        self._granted = frozenset(granted or ALL_CAPABILITIES)
        unknown = self._granted - ALL_CAPABILITIES
        if unknown:
            raise SidecarError(f"未知能力：{sorted(unknown)}")

    @property
    def granted(self) -> frozenset[str]:
        return self._granted

    def check(self, cap: str) -> None:
        if cap not in self._granted:
            raise SidecarError(f"能力未授权：{cap}（已授权：{sorted(self._granted)}）")


# --------------------------------------------------------------------------
# 后端 A：原生侧载层
# --------------------------------------------------------------------------
class NativeBackend:
    """通过 ctypes 调用 L1 侧载层。"""

    kind = "native"

    def __init__(self, lib_path: str, expected_sha256: str = ""):
        self.path = os.path.abspath(lib_path)
        self.expected_sha256 = expected_sha256.lower().replace("-", "")
        self._lib = None
        self._available: set[str] = set()
        self._load()

    # -- 定位 ---------------------------------------------------------------
    @staticmethod
    def discover(explicit: str = "") -> str:
        """按固定顺序查找侧载库；不依赖系统 DLL 搜索顺序。"""
        if explicit:
            return explicit if os.path.isfile(explicit) else ""
        here = os.path.dirname(os.path.abspath(__file__))
        roots = [
            os.environ.get("CYRILSHIELD_NATIVE", ""),
            getattr(sys, "_MEIPASS", ""),          # PyInstaller onefile 解包目录
            os.path.dirname(here),                  # CyrilShield/
            os.path.join(os.path.dirname(here), "native", "out"),
            here,
        ]
        seen: set[str] = set()
        for root in roots:
            if not root or root in seen:
                continue
            seen.add(root)
            for name in LIB_NAMES:
                p = os.path.join(root, name)
                if os.path.isfile(p):
                    return p
        return ""

    # -- 加载与校验 ---------------------------------------------------------
    def _load(self) -> None:
        try:
            lib = ctypes.CDLL(self.path)
        except OSError as exc:
            raise SidecarError(f"侧载库加载失败：{exc}") from exc

        lib.csn_abi_version.argtypes = []
        lib.csn_abi_version.restype = ctypes.c_int
        lib.csn_capabilities.argtypes = []
        lib.csn_capabilities.restype = ctypes.c_uint64
        lib.csn_probe_hwid.argtypes = [ctypes.POINTER(_CHwid)]
        lib.csn_probe_hwid.restype = ctypes.c_int
        lib.csn_ticks.argtypes = [ctypes.POINTER(ctypes.c_uint64)]
        lib.csn_ticks.restype = ctypes.c_int
        lib.csn_cpu_features.argtypes = [ctypes.POINTER(ctypes.c_uint32)]
        lib.csn_cpu_features.restype = ctypes.c_int
        lib.csn_file_digest.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_ubyte * 32)]
        lib.csn_file_digest.restype = ctypes.c_int
        lib.csn_self_digest.argtypes = [ctypes.POINTER(ctypes.c_ubyte * 32)]
        lib.csn_self_digest.restype = ctypes.c_int
        lib.csn_last_error.argtypes = [ctypes.c_char_p, ctypes.c_size_t]
        lib.csn_last_error.restype = ctypes.c_int

        ver = lib.csn_abi_version()
        if ver != ABI_VERSION:
            raise SidecarError(f"侧载库 ABI 不匹配：期望 {ABI_VERSION}，实际 {ver}")

        bits = int(lib.csn_capabilities())
        for bit, name in _CAP_BITS.items():
            if bits & bit:
                self._available.add(name)

        # 防替换：钉住侧载库自身摘要，避免同名 DLL 被换成别的实现
        if self.expected_sha256:
            actual = self._digest_file(self.path).hex()
            if actual != self.expected_sha256:
                raise SidecarError("侧载库摘要与期望值不符，已拒绝加载")

        self._lib = lib

    @staticmethod
    def _digest_file(path: str) -> bytes:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.digest()

    def _err(self) -> str:
        buf = ctypes.create_string_buffer(256)
        self._lib.csn_last_error(buf, 256)
        return buf.value.decode("utf-8", "replace")

    @property
    def capabilities(self) -> frozenset[str]:
        return frozenset(self._available)

    # -- 能力实现 -----------------------------------------------------------
    def hwid(self) -> HardwareId:
        st = _CHwid()
        st.size = ctypes.sizeof(_CHwid)
        rc = self._lib.csn_probe_hwid(ctypes.byref(st))
        if rc != 0:
            raise SidecarError(f"硬件指纹采集失败：{self._err()} (rc={rc})")
        return HardwareId(
            machine=bytes(st.machine),
            volume=bytes(st.volume),
            cpu=st.cpu.decode("utf-8", "replace").rstrip("\x00"),
            os=st.os.decode("utf-8", "replace").rstrip("\x00"),
            cpu_count=int(st.cpu_count),
            mem_mb=int(st.mem_mb),
            digest=bytes(st.digest),
        )

    def ticks(self) -> int:
        v = ctypes.c_uint64(0)
        if self._lib.csn_ticks(ctypes.byref(v)) != 0:
            raise SidecarError(f"时钟读取失败：{self._err()}")
        return int(v.value)

    def cpu_features(self) -> int:
        v = ctypes.c_uint32(0)
        if self._lib.csn_cpu_features(ctypes.byref(v)) != 0:
            raise SidecarError(f"CPU 特性读取失败：{self._err()}")
        return int(v.value)

    def file_digest(self, path: str) -> bytes:
        out = (ctypes.c_ubyte * 32)()
        rc = self._lib.csn_file_digest(os.fsencode(path), ctypes.byref(out))
        if rc != 0:
            raise SidecarError(f"文件摘要失败：{self._err()} (rc={rc})")
        return bytes(out)

    def self_digest(self) -> bytes:
        out = (ctypes.c_ubyte * 32)()
        if self._lib.csn_self_digest(ctypes.byref(out)) != 0:
            raise SidecarError(f"自身摘要失败：{self._err()}")
        return bytes(out)


# --------------------------------------------------------------------------
# 后端 B：纯 Python 参考实现（无侧载库时降级）
# --------------------------------------------------------------------------
class PurePythonBackend:
    """不依赖任何原生库的参考后端，接口与 :class:`NativeBackend` 完全一致。

    能力弱于原生层（拿不到 SMBIOS/CPUID 原始值），但保证功能不中断。
    """

    kind = "python"

    def __init__(self):
        self._available = set(ALL_CAPABILITIES)

    @property
    def capabilities(self) -> frozenset[str]:
        return frozenset(self._available)

    @staticmethod
    def _machine_bytes() -> bytes:
        raw = b""
        for p in ("/sys/class/dmi/id/product_uuid", "/etc/machine-id"):
            try:
                with open(p, "rb") as f:
                    raw = f.read(128).strip()
                if raw:
                    break
            except OSError:
                continue
        if not raw:
            raw = uuid.getnode().to_bytes(6, "big") + platform.node().encode("utf-8", "replace")
        return hashlib.sha256(raw).digest()[:16]

    @staticmethod
    def _volume_bytes() -> bytes:
        try:
            return struct.pack("<Q", os.stat(os.path.abspath(os.sep)).st_dev)
        except OSError:
            return b"\x00" * 8

    def hwid(self) -> HardwareId:
        machine = self._machine_bytes()
        volume = self._volume_bytes()
        cpu = platform.processor() or platform.machine() or "Generic"
        osname = f"{platform.system()} {platform.release()}"
        try:
            cpus = os.cpu_count() or 1
        except Exception:
            cpus = 1
        mem_mb = 0
        try:
            with open("/proc/meminfo", "rb") as f:
                for line in f:
                    if line.startswith(b"MemTotal:"):
                        mem_mb = int(line.split()[1]) // 1024
                        break
        except OSError:
            pass
        h = hashlib.sha256()
        for part in (machine, volume, cpu.encode(), osname.encode(),
                     struct.pack("<I", cpus)):
            h.update(part)
        return HardwareId(machine, volume, cpu, osname, cpus, mem_mb, h.digest())

    def ticks(self) -> int:
        import time
        return time.monotonic_ns()

    def cpu_features(self) -> int:
        return 0

    def file_digest(self, path: str) -> bytes:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.digest()

    def self_digest(self) -> bytes:
        return self.file_digest(os.path.abspath(__file__))


# --------------------------------------------------------------------------
# 统一入口
# --------------------------------------------------------------------------
@dataclass
class Bridge:
    """带能力门的统一访问点。

    用法::

        bridge = get_bridge(granted={"hwid", "file_digest"})
        hw = bridge.hwid()              # 通过
        bridge.ticks()                  # 抛 SidecarError：能力未授权
    """
    backend: object
    gate: CapabilityGate = field(default_factory=CapabilityGate)
    reason: str = ""

    @property
    def kind(self) -> str:
        return self.backend.kind

    @property
    def capabilities(self) -> frozenset[str]:
        return self.gate.granted & self.backend.capabilities

    def _need(self, cap: str) -> None:
        self.gate.check(cap)
        if cap not in self.backend.capabilities:
            raise SidecarError(f"后端不支持能力：{cap}")

    def hwid(self) -> HardwareId:
        self._need(CAP_HWID)
        return self.backend.hwid()

    def ticks(self) -> int:
        self._need(CAP_TICKS)
        return self.backend.ticks()

    def cpu_features(self) -> int:
        self._need(CAP_CPU_FEATURES)
        return self.backend.cpu_features()

    def file_digest(self, path: str) -> bytes:
        self._need(CAP_FILE_DIGEST)
        return self.backend.file_digest(path)

    def self_digest(self) -> bytes:
        self._need(CAP_SELF_DIGEST)
        return self.backend.self_digest()


def get_bridge(lib_path: str = "", expected_sha256: str = "",
               granted=None, strict: bool = False) -> Bridge:
    """构建桥接：优先原生侧载层，失败时（非 strict）降级到纯 Python。"""
    gate = CapabilityGate(granted)
    path = lib_path or NativeBackend.discover()
    if path:
        try:
            return Bridge(NativeBackend(path, expected_sha256), gate, f"native:{path}")
        except SidecarError as exc:
            if strict:
                raise
            reason = str(exc)
    else:
        reason = "未找到侧载库"
    return Bridge(PurePythonBackend(), gate, f"fallback:{reason}")


def describe() -> dict:
    """给自检 / UI 用的能力快照。"""
    b = get_bridge()
    return {
        "backend": b.kind,
        "reason": b.reason,
        "capabilities": sorted(b.capabilities),
        "abi_version": ABI_VERSION,
    }
