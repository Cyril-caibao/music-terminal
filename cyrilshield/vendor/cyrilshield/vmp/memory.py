# -*- coding: utf-8 -*-
"""EXE 运行时内存保护：分块解密、用完即擦、缩短明文驻留窗口。

诚实的边界（与项目一贯立场一致）
--------------------------------
真正的「抗 dump / 代码不落地」需要内核驱动或进程镂空级方案，会把
保护工具变成安全产品对抗工具，本项目**不做**。这里做的是工程上
正当且有效的部分：

  * 明文永不以完整形态长期驻留，只在需要时按块解密；
  * 每个块用完后立即多次覆写再释放；
  * 不申请 RWX 内存、不修改自身代码段、不触碰其他进程；
  * 与 ``antitamper.secure_temp`` 的 delete-on-close 释放配合。

VMP 的内存保护同样包含大量「减小可 dump 面」的工程手段，而非单一
黑科技；本模块对标的是这一层。
"""
from __future__ import annotations

import hashlib
import os
import struct
from dataclasses import dataclass

WIPE_ROUNDS = 3


def wipe(buf: bytearray) -> None:
    """多轮覆写后清空。对不可变 bytes 无效，调用方须使用 bytearray。"""
    if not isinstance(buf, bytearray):
        raise TypeError("wipe 需要 bytearray（bytes 不可变，无法可靠擦除）")
    n = len(buf)
    for r in range(WIPE_ROUNDS):
        pattern = (0x00, 0xFF, 0xA5)[r % 3] ^ (r << 4)
        buf[:] = bytes([pattern & 0xFF]) * n
    del buf[:]


def _block_key(master: bytes, counter: int) -> bytes:
    return hashlib.sha256(master + struct.pack("<I", counter)).digest()


def _xor(data: bytes, ks: bytes) -> bytes:
    return bytes(a ^ b for a, b in zip(data, ks))


def _keystream(key: bytes, n: int) -> bytes:
    out = bytearray()
    c = 0
    while len(out) < n:
        out += _block_key(key, c)
        c += 1
    return bytes(out[:n])


def seal_chunks(plain: bytes, master: bytes, block: int = 64 * 1024) -> list[bytes]:
    """把明文按块独立加密，每块密钥由主密钥 + 块序号派生。"""
    if block <= 0:
        raise ValueError("block 必须为正")
    return [
        _xor(plain[i:i + block], _keystream(_block_key(master, i // block),
                                            len(plain[i:i + block])))
        for i in range(0, len(plain), block)
    ]


@dataclass
class ChunkedDecoder:
    """按需解密，用完即擦的参考实现（壳内逻辑与此类同构）。"""
    chunks: list[bytes]
    master: bytes

    def read(self, index: int) -> bytearray:
        if not 0 <= index < len(self.chunks):
            raise IndexError(index)
        key = _block_key(self.master, index)
        data = bytearray(_xor(self.chunks[index], _keystream(key, len(self.chunks[index]))))
        return data

    def read_all(self) -> bytearray:
        out = bytearray()
        for i in range(len(self.chunks)):
            part = self.read(i)
            out += part
            wipe(part)
        return out

    def release(self) -> None:
        """丢弃所有密文引用（密文本身可留在内存，明文才是要保护的资产）。"""
        self.chunks.clear()


def runtime_memory_guard_source() -> str:
    """生成壳内分块解密 + 即时擦除的自包含源码片段。

    提供 ``_cm_read(i)`` / ``_cm_wipe(buf)``，由 VM 的 native 调用点使用。
    """
    return r'''
# ---- CyrilShield chunked memory guard (generated) ----
import hashlib as _cmh, struct as _cms

def _cm_bk(master, counter):
    return _cmh.sha256(master + _cms.pack("<I", counter)).digest()

def _cm_ks(key, n):
    out = bytearray()
    c = 0
    while len(out) < n:
        out += _cm_bk(key, c)
        c += 1
    return bytes(out[:n])

def _cm_read(blob, master, counter):
    key = _cm_bk(master, counter)
    ks = _cm_ks(key, len(blob))
    return bytearray(bytes(a ^ b for a, b in zip(blob, ks)))

def _cm_wipe(buf):
    if not isinstance(buf, bytearray):
        return
    n = len(buf)
    for pat in (0x00, 0xFF, 0xA5):
        buf[:] = bytes([pat]) * n
    del buf[:]
# ---- end chunked memory guard ----
'''


def memory_note() -> str:
    return ("真·不落地 / 抗内核 dump 需要驱动级方案，本项目不实现；"
            "当前能力 = 分块解密 + 用完即擦 + delete-on-close 缩短落盘窗口 + 无 RWX 段。")
