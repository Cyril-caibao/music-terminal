# -*- coding: utf-8 -*-
"""EXE 多态构建指纹：证明「每次构建都不同」，并对标 VMP 的去特征化。

加壳产物最容易被按静态哈希入库。多态的目标是让同一份输入、同一套
配置，两次构建产出的字节流、操作码映射、符号名、盐、编码串、版本
资源全部不同；本模块把这些随机量的摘要汇总成一份**构建指纹**，用于
自检比对与误报申诉材料。
"""
from __future__ import annotations

import hashlib
import json
import random
from dataclasses import dataclass, asdict, field


@dataclass(frozen=True)
class BuildFingerprint:
    seed: str
    opcode_map: str
    names: str
    salt: str
    magic: str
    chunk: int
    mem_size: int
    order: tuple[int, ...]
    integrity_root: str
    profile: str
    extras: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        d = asdict(self)
        d["order"] = list(self.order)
        return d

    @property
    def short(self) -> str:
        return hashlib.sha256(
            json.dumps(self.as_dict(), sort_keys=True).encode()).hexdigest()[:16]


def _hex(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()[:16]


def collect(*, ops: dict, plan, profile, integrity_root: str = "",
            seed: str = "", extras: dict | None = None) -> BuildFingerprint:
    """从一次构建的实际随机量生成指纹。"""
    opblob = json.dumps(ops, sort_keys=True).encode()
    names = json.dumps(getattr(plan, "names", {}), sort_keys=True).encode()
    pdict = profile.as_dict() if hasattr(profile, "as_dict") else dict(profile or {})
    return BuildFingerprint(
        seed=seed or secrets_hex(),
        opcode_map=_hex(opblob),
        names=_hex(names),
        salt=_hex(getattr(plan, "salt", b"")),
        magic=_hex(getattr(plan, "magic", b"")),
        chunk=int(getattr(plan, "chunk", 0)),
        mem_size=int(getattr(plan, "mem_size", 0)),
        order=tuple(getattr(plan, "order", ())),
        integrity_root=integrity_root,
        profile=_hex(json.dumps(pdict, sort_keys=True, default=str).encode()),
        extras=dict(extras or {}),
    )


def secrets_hex() -> str:
    import secrets
    return secrets.token_hex(8)


def diff(a: BuildFingerprint, b: BuildFingerprint) -> list[str]:
    """列出两份指纹中发生变化的分量。"""
    da, db = a.as_dict(), b.as_dict()
    keys = set(da) | set(db)
    return sorted(k for k in keys if da.get(k) != db.get(k))


def assert_distinct(fps: list[BuildFingerprint], min_changes: int = 3) -> bool:
    """自检：任意两次构建至少要有 min_changes 个分量不同。"""
    for i in range(len(fps)):
        for j in range(i + 1, len(fps)):
            if len(diff(fps[i], fps[j])) < min_changes:
                return False
    return len(fps) >= 2


def random_identifiers(rng: random.Random, n: int,
                       used: set[str] | None = None) -> list[str]:
    used = used if used is not None else set()
    out = []
    alphabet = "abcdefghijkmnopqrstuvwxyz0123456789"
    while len(out) < n:
        name = "_" + "".join(rng.choice(alphabet) for _ in range(rng.randrange(6, 12)))
        if name not in used:
            used.add(name)
            out.append(name)
    return out
