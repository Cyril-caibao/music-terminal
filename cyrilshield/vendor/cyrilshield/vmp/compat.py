# -*- coding: utf-8 -*-
"""AV compatibility：降低加壳产物的误报与静态特征命中。

问题背景
--------
带 VM 虚拟化、内存解密、反调试的程序，几乎必然被启发式引擎打上
「packed / suspicious」标签——高熵节、空版本资源、可疑节名、W+X 节、
RWX 运行时改码、无图标无时间戳，每一项都是典型误报触发器。正版
商业壳同样要处理这件事，手段是让产物看起来像一个**正常的、有出处
的、结构干净的** Windows 程序。

本模块提供的都是「把产物做规范」的正当手段：

  * 生成合法的 ``VS_VERSION_INFO`` 版本资源（公司 / 产品 / 描述 / 版本）；
  * 规范化节名、检查 W^X、报告节区熵；
  * 生成可复现构建清单与误报申诉材料（哈希、体积、用途说明）；
  * 提示数字签名（Authenticode + RFC3161 时间戳）与提交白名单。

明确不做：AMSI/ETW 补丁、直接系统调用绕过用户态钩子、禁用或欺骗
安全产品、进程镂空 / 注入、签名伪造、白利用侧加载。这些属于主动规避
或行为隐藏，既不是软件保护的必要手段，也不在本项目支持范围内。
"""
from __future__ import annotations

import hashlib
import os
import struct
import time
from dataclasses import dataclass

# PE 节特征位
SCN_MEM_EXECUTE = 0x20000000
SCN_MEM_WRITE = 0x80000000
SCN_MEM_READ = 0x40000000
SCN_CNT_CODE = 0x00000020

# 被启发式引擎重点标记的节名（加壳器惯用名）
SUSPICIOUS_SECTIONS = (
    b"UPX0", b"UPX1", b"UPX2", b".nsp0", b".nsp1", b".nsp2",
    b".petite", b".aspack", b".vmp0", b".vmp1", b".themida",
    b".packed", b".crypt", b".enigma", b".rlpack",
)

OVERLAY_MAGIC = b"CSWM\x01\x00"      # CyrilShield WaterMark


def shannon_entropy(data: bytes) -> float:
    """字节香农熵（0..8）。>7.2 通常意味着压缩/加密段。"""
    if not data:
        return 0.0
    counts = [0] * 256
    for b in data:
        counts[b] += 1
    n = len(data)
    import math
    return -sum((c / n) * math.log2(c / n) for c in counts if c)


# --------------------------------------------------------------------------
# 轻量 PE 布局解析（只读，不改动原文件）
# --------------------------------------------------------------------------
@dataclass
class SectionInfo:
    name: str
    virtual_size: int
    virtual_address: int
    raw_size: int
    raw_offset: int
    characteristics: int
    entropy: float = 0.0

    @property
    def writable_executable(self) -> bool:
        w = bool(self.characteristics & SCN_MEM_WRITE)
        x = bool(self.characteristics & SCN_MEM_EXECUTE)
        return w and x


@dataclass
class PELayout:
    valid: bool
    machine: int
    sections: list[SectionInfo]
    timestamp: int
    dll_characteristics: int
    checksum: int
    raw: bytes
    error: str = ""

    @property
    def size_on_disk(self) -> int:
        return max((s.raw_offset + s.raw_size for s in self.sections), default=0)


def parse_pe(data: bytes) -> PELayout:
    """解析到节表为止；不加载、不执行、不信任任何偏移。"""
    bad = PELayout(False, 0, [], 0, 0, 0, data, "")
    if len(data) < 0x40 or data[:2] != b"MZ":
        bad.error = "不是 MZ 可执行文件"
        return bad
    lfanew = struct.unpack_from("<I", data, 0x3C)[0]
    if lfanew + 24 > len(data) or data[lfanew:lfanew + 4] != b"PE\x00\x00":
        bad.error = "PE 头无效"
        return bad
    coff = lfanew + 4
    machine, nsec, timestamp = struct.unpack_from("<HHI", data, coff)
    size_opt = struct.unpack_from("<H", data, coff + 16)[0]
    opt = coff + 20
    if opt + size_opt > len(data):
        bad.error = "可选头越界"
        return bad
    magic = struct.unpack_from("<H", data, opt)[0]
    checksum = struct.unpack_from("<I", data, opt + 64)[0] if size_opt >= 68 else 0
    # DllCharacteristics 在 PE32/PE32+ 都是 opt+70
    dllchar = struct.unpack_from("<H", data, opt + 70)[0] if size_opt >= 72 else 0
    sec_off = opt + size_opt
    sections: list[SectionInfo] = []
    for i in range(min(nsec, 96)):
        p = sec_off + i * 40
        if p + 40 > len(data):
            break
        raw_name = data[p:p + 8].rstrip(b"\x00")
        vs, va, rs, ro = struct.unpack_from("<IIII", data, p + 8)
        ch = struct.unpack_from("<I", data, p + 36)[0]
        sec = SectionInfo(raw_name.decode("latin-1", "replace"), vs, va, rs, ro, ch)
        if ro + rs <= len(data) and rs:
            sec.entropy = shannon_entropy(data[ro:ro + rs])
        sections.append(sec)
    return PELayout(True, machine, sections, timestamp, dllchar, checksum, data)


# --------------------------------------------------------------------------
# 版本资源构造
# --------------------------------------------------------------------------
def _pad4(buf: bytearray) -> None:
    while len(buf) % 4:
        buf.append(0)


def _w(s: str) -> bytes:
    return s.encode("utf-16-le") + b"\x00\x00"


def _string_block(key: str, value: str) -> bytes:
    val = _w(value)
    body = bytearray(_w(key))
    _pad4(body)
    body += val
    length = 6 + len(body)
    return struct.pack("<HHH", length, len(value) + 1, 1) + bytes(body)


def _string_table(lang: str, values: dict[str, str]) -> bytes:
    body = bytearray(_w(lang))
    _pad4(body)
    for k, v in values.items():
        body += _string_block(k, v)
        _pad4(body)
    return struct.pack("<HHH", 6 + len(body), 0, 1) + bytes(body)


def _fixed_file_info(file_ver: tuple[int, int, int, int],
                     prod_ver: tuple[int, int, int, int]) -> bytes:
    def packver(v):
        return (v[0] << 16) | v[1], (v[2] << 16) | v[3]
    fm, fl = packver(file_ver)
    pm, pl = packver(prod_ver)
    return struct.pack("<13I", 0xFEEF04BD, 0x00010000, fm, fl, pm, pl,
                       0x0000003F, 0x00000000, 0x00000004, 0x00000001,
                       0x00000000, 0x00000000, 0x00000000)


def version_info_bytes(*, company: str, product: str, description: str,
                       original_filename: str, file_version: str = "1.0.0.0",
                       product_version: str = "1.0.0.0",
                       copyright_text: str = "", lang: str = "040904b0") -> bytes:
    """构造可直接嵌入 .rsrc 的 ``VS_VERSION_INFO`` 二进制块。

    返回的是资源数据（不含资源目录树），由构建脚本用 ``UpdateResource``
    或 rc.exe 挂到 RT_VERSION/1/中性语言 上。
    """
    def parse_ver(s: str) -> tuple[int, int, int, int]:
        parts = (s.split(".") + ["0", "0", "0", "0"])[:4]
        return tuple(int(x) if x.isdigit() else 0 for x in parts)  # type: ignore

    values = {
        "CompanyName": company,
        "ProductName": product,
        "FileDescription": description,
        "OriginalFilename": original_filename,
        "FileVersion": file_version,
        "ProductVersion": product_version,
        "LegalCopyright": copyright_text or f"Copyright (C) {time.gmtime().tm_year} {company}",
        "InternalName": os.path.splitext(original_filename)[0],
    }

    # VarFileInfo / Translation
    langid = int(lang[:4], 16)
    codepage = int(lang[4:], 16)
    var_body = bytearray(_w("Translation"))
    _pad4(var_body)
    var_value = struct.pack("<HH", langid, codepage)
    var_child = struct.pack("<HHH", 6 + len(var_body) + len(var_value),
                            len(var_value), 0) + bytes(var_body) + var_value
    var = bytearray(_w("VarFileInfo"))
    _pad4(var)
    var += var_child
    var_root = struct.pack("<HHH", 6 + len(var), 0, 1) + bytes(var)

    sfi = bytearray(_w("StringFileInfo"))
    _pad4(sfi)
    sfi += _string_table(lang, values)
    _pad4(sfi)
    sfi_root = struct.pack("<HHH", 6 + len(sfi), 0, 1) + bytes(sfi)

    root = bytearray(_w("VS_VERSION_INFO"))
    _pad4(root)
    root += _fixed_file_info(parse_ver(file_version), parse_ver(product_version))
    _pad4(root)
    children = sfi_root + var_root
    root += children
    _pad4(root)
    total = 6 + len(root)
    return struct.pack("<HHH", total, 52, 0) + bytes(root)


def version_info_fields(blob: bytes) -> dict[str, str]:
    """从 ``version_info_bytes`` 的产物里读回字段（自检与清单用）。"""
    out: dict[str, str] = {}
    if len(blob) < 6:
        return out
    u16 = blob.decode("utf-16-le", "ignore")
    for key in ("CompanyName", "ProductName", "FileDescription",
                "OriginalFilename", "FileVersion", "ProductVersion",
                "LegalCopyright", "InternalName"):
        i = u16.find(key)
        if i < 0:
            continue
        j = i + len(key)
        # 值紧跟在 key 的终止 NUL 与 4 字节对齐之后
        while j < len(u16) and u16[j] == "\x00":
            j += 1
        end = u16.find("\x00", j)
        if end > j:
            out[key] = u16[j:end]
    return out


# --------------------------------------------------------------------------
# 审计
# --------------------------------------------------------------------------
@dataclass
class Finding:
    key: str
    ok: bool
    weight: int
    detail: str


def av_compat_audit(data: bytes) -> dict:
    """对 PE 产物做误报风险审计，返回 0..100 分与逐项结论。"""
    layout = parse_pe(data)
    findings: list[Finding] = []
    if not layout.valid:
        return {"score": 0, "error": layout.error, "findings": [],
                "sections": [], "entropy_max": 0.0}

    findings.append(Finding("pe_structure", True, 10, "PE 头与节表结构有效"))

    has_version = b"V\x00S\x00_\x00V\x00E\x00R\x00S\x00I\x00O\x00N" in data
    findings.append(Finding("version_resource", has_version, 18,
                            "含 VS_VERSION_INFO 版本资源" if has_version
                            else "缺少版本资源（公司/产品/描述），是主要误报触发器"))

    iconish = (b"\x00\x00\x01\x00" in data[:len(data)] or b"\x89PNG" in data)
    findings.append(Finding("icon_resource", iconish, 8,
                            "检测到图标/图像资源" if iconish else "未见图标资源"))

    wx = [s.name for s in layout.sections if s.writable_executable]
    findings.append(Finding("wx_section", not wx, 16,
                            "无 W+X 节" if not wx else f"存在可写可执行节：{wx}"))

    suspicious = [s.name for s in layout.sections
                  if s.name.encode("latin-1", "replace") in SUSPICIOUS_SECTIONS]
    findings.append(Finding("section_names", not suspicious, 12,
                            "节名规范" if not suspicious else f"可疑节名：{suspicious}"))

    high = [(s.name, round(s.entropy, 2)) for s in layout.sections if s.entropy > 7.2]
    findings.append(Finding("entropy", len(high) <= 1, 12,
                            f"高熵节(>7.2)：{high}" if len(high) > 1
                            else "熵分布正常或仅单一压缩节"))

    findings.append(Finding("timestamp", layout.timestamp != 0, 6,
                            "含链接时间戳" if layout.timestamp else "时间戳为 0（部分引擎视为异常）"))

    aslr = bool(layout.dll_characteristics & 0x0040)
    dep = bool(layout.dll_characteristics & 0x0100)
    findings.append(Finding("mitigations", aslr and dep, 10,
                            f"ASLR={'on' if aslr else 'off'}, DEP={'on' if dep else 'off'}"))

    overlay = len(data) - layout.size_on_disk
    findings.append(Finding("overlay", True, 4,
                            f"尾部 overlay {overlay} 字节（水印/附加数据）"))

    findings.append(Finding("reproducible", True, 4,
                            "可生成构建清单用于误报申诉"))

    total_w = sum(f.weight for f in findings)
    got = sum(f.weight for f in findings if f.ok)
    return {
        "score": round(100 * got / total_w) if total_w else 0,
        "error": "",
        "findings": [f.__dict__ for f in findings],
        "sections": [{"name": s.name, "entropy": round(s.entropy, 3),
                      "raw_size": s.raw_size, "wx": s.writable_executable}
                     for s in layout.sections],
        "entropy_max": max((s.entropy for s in layout.sections), default=0.0),
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
    }


# --------------------------------------------------------------------------
# Overlay 水印（写在文件尾，Windows 加载器忽略；签名须在水印之后进行）
# --------------------------------------------------------------------------
def overlay_append(data: bytes, payload: bytes) -> bytes:
    body = OVERLAY_MAGIC + struct.pack("<I", len(payload)) + payload
    return data + body + struct.pack("<I", len(body)) + OVERLAY_MAGIC


def overlay_read(data: bytes) -> bytes | None:
    if len(data) < 16 or data[-6:] != OVERLAY_MAGIC:
        return None
    n = struct.unpack_from("<I", data, -10)[0]
    start = len(data) - 10 - n
    if start < 0 or data[start:start + 6] != OVERLAY_MAGIC:
        return None
    size = struct.unpack_from("<I", data, start + 6)[0]
    if size + 10 > len(data) - start:
        return None
    return data[start + 10:start + 10 + size]


def build_manifest(entries: list[tuple[str, bytes]]) -> dict:
    """可复现构建清单：误报申诉时提交给安全厂商。"""
    files = []
    for name, blob in entries:
        files.append({
            "name": name,
            "size": len(blob),
            "sha256": hashlib.sha256(blob).hexdigest(),
            "entropy": round(shannon_entropy(blob), 3),
        })
    return {
        "schema": "cyrilshield.manifest/1",
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "files": files,
        "purpose": "Self-authored application protected with code virtualization and anti-tamper.",
        "note": "No network access, no process injection, no driver, no AV evasion.",
    }


def signing_hint(exe_path: str, cert: str = "", timestamp_url: str =
                 "http://timestamp.digicert.com") -> str:
    return (
        'signtool sign /fd SHA256 /td SHA256 /tr ' + timestamp_url +
        (f' /f "{cert}"' if cert else ' /n "<证书使用者名称>"') +
        f' /v "{exe_path}"'
    )


def version_file_source(*, company: str, product: str, description: str,
                        original_filename: str, file_version: str = "1.0.0.0",
                        lang: str = "040904b0") -> str:
    """生成 PyInstaller ``--version-file`` 所需的 Python 源文件。

    这是把合法版本资源写进 PE 的**构建期**做法（比事后 overlay 安全）：
    PyInstaller 会在链接阶段挂上 RT_VERSION，Windows 资源管理器与启发式
    引擎都能读到公司 / 产品 / 描述 / 版本，显著降低「无出处加壳程序」的
    误报概率。
    """
    def esc(s: str) -> str:
        return s.replace("\\", "\\\\").replace("'", "\\'")

    def ver(s: str) -> tuple[int, int, int, int]:
        parts = (s.split(".") + ["0", "0", "0", "0"])[:4]
        return tuple(int(x) if x.isdigit() else 0 for x in parts)  # type: ignore

    fv, pv = ver(file_version), ver(file_version)
    langid, codepage = int(lang[:4], 16), int(lang[4:], 16)
    rows = [
        ("CompanyName", company),
        ("FileDescription", description),
        ("FileVersion", file_version),
        ("InternalName", os.path.splitext(original_filename)[0]),
        ("LegalCopyright", f"Copyright (C) {time.gmtime().tm_year} {company}"),
        ("OriginalFilename", original_filename),
        ("ProductName", product),
        ("ProductVersion", file_version),
    ]
    strings = ",\n".join(
        f"        StringStruct('{k}', '{esc(v)}')" for k, v in rows)
    return f'''# -*- coding: utf-8 -*-
# 由 CyrilShield 生成：合法版本资源，用于降低加壳产物的误报
VSVersionInfo(
  ffi=FixedFileInfo(
    filevers={fv},
    prodvers={pv},
    mask=0x3f, flags=0x0,
    OS=0x40004, fileType=0x1, subtype=0x0, date=(0, 0)
  ),
  kids=[
    StringFileInfo([
      StringTable('{lang}', [
{strings}
      ])
    ]),
    VarFileInfo([VarStruct('Translation', [{langid}, {codepage}])])
  ]
)
'''
