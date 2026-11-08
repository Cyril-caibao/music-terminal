# -*- coding: utf-8 -*-
"""EXE 完整性自校验：由单次摘要升级为分块哈希链 + 交叉校验。

VMP 的自校验不是「算一遍 CRC」，而是把校验点散布在代码各处、彼此
互相验证。这里用**分块哈希链**实现同等思路：

    h_0 = SHA256(magic || salt || len)
    h_i = SHA256(h_{i-1} || i || block_i)
    root = h_n

任何一处字节改动都会让从该块起的所有后续哈希失效；两个互质分块
粒度各算一条链再交叉比对，篡改者必须同时维护两份一致的状态。

``runtime_check_source`` 生成**自包含**的壳内校验代码（只依赖标准库），
因为 PyInstaller 打出来的外壳运行时 import 不到 ``cyrilshield.*``。
"""
from __future__ import annotations

import hashlib
import hmac
import struct
from dataclasses import dataclass

CHAIN_MAGIC = b"CS-INTEGRITY-CHAIN-v1\x00"
DEFAULT_BLOCKS = (4096, 7777)      # 互质块长，避免对齐巧合


def _h(*parts: bytes) -> bytes:
    d = hashlib.sha256()
    for p in parts:
        d.update(p)
    return d.digest()


def chain_digest(blob: bytes, block_size: int, salt: bytes) -> bytes:
    """计算单条分块哈希链的根。"""
    if block_size <= 0:
        raise ValueError("block_size 必须为正")
    h = _h(CHAIN_MAGIC, salt, struct.pack("<Q", len(blob)))
    for i in range(0, len(blob), block_size):
        h = _h(h, struct.pack("<I", i // block_size), blob[i:i + block_size])
    return h


def chain_roots(blob: bytes, salt: bytes,
                blocks: tuple[int, ...] = DEFAULT_BLOCKS) -> tuple[bytes, ...]:
    return tuple(chain_digest(blob, b, salt) for b in blocks)


def verify_chain(blob: bytes, salt: bytes, roots: tuple[bytes, ...],
                 blocks: tuple[int, ...] = DEFAULT_BLOCKS) -> bool:
    """常数时间比对两条链；任一不符即判定被篡改。"""
    if len(roots) != len(blocks):
        return False
    got = chain_roots(blob, salt, blocks)
    return all(hmac.compare_digest(a, b) for a, b in zip(got, roots))


@dataclass(frozen=True)
class IntegrityPlan:
    salt: bytes
    roots: tuple[bytes, ...]
    blocks: tuple[int, ...]
    size: int

    @property
    def root_hex(self) -> str:
        """主链根，作为构建指纹的一部分对外暴露。"""
        return self.roots[0].hex()

    def as_dict(self) -> dict:
        return {
            "size": self.size,
            "blocks": list(self.blocks),
            "salt": self.salt.hex(),
            "roots": [r.hex() for r in self.roots],
        }


def make_plan(blob: bytes, salt: bytes | None = None,
              blocks: tuple[int, ...] = DEFAULT_BLOCKS) -> IntegrityPlan:
    import secrets
    salt = salt if salt is not None else secrets.token_bytes(16)
    return IntegrityPlan(salt, chain_roots(blob, salt, blocks), blocks, len(blob))


def runtime_check_source(var_blob: str, plan: IntegrityPlan,
                         fail_stmt: str = "raise SystemExit(1)") -> str:
    """生成壳内自校验源码。``var_blob`` 是存放待校验字节的模块级变量名。"""
    roots = repr(tuple(r.hex() for r in plan.roots))
    return f'''
# ---- CyrilShield integrity chain (generated) ----
import hashlib as _cih, hmac as _cix, struct as _cis
_CI_MAGIC = {CHAIN_MAGIC!r}
_CI_SALT = bytes.fromhex({plan.salt.hex()!r})
_CI_BLOCKS = {plan.blocks!r}
_CI_ROOTS = tuple(bytes.fromhex(x) for x in {roots})

def _ci_one(blob, bs):
    def h(*parts):
        d = _cih.sha256()
        for q in parts:
            d.update(q)
        return d.digest()
    acc = h(_CI_MAGIC, _CI_SALT, _cis.pack("<Q", len(blob)))
    for i in range(0, len(blob), bs):
        acc = h(acc, _cis.pack("<I", i // bs), blob[i:i + bs])
    return acc

def _ci_check(blob):
    for bs, root in zip(_CI_BLOCKS, _CI_ROOTS):
        if not _cix.compare_digest(_ci_one(blob, bs), root):
            return False
    return True

if not _ci_check({var_blob}):
    {fail_stmt}
# ---- end integrity chain ----
'''
