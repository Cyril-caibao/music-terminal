# -*- coding: utf-8 -*-
"""EXE 壳源码混淆层：常量加密 / 代码变异 / 控制流平坦化 / 动态解析。

对标 VMP 的 mutation、string encryption、import protection 三项。所有
变换都作用在**构建期生成的 stub 源码**上，运行时语义不变：

  * ``seal``：字面量用「随机盐 + 标签派生密钥流」编码，stub 里只剩
    base85 字符串；每次构建盐不同，静态特征不稳定。
  * ``dead_code``：插入永不执行的等价死块，改变控制流外观与体积。
  * ``obfuscate_int_literals``：顶层大整数常量改写成异或表达式。
  * ``dynamic_import_source``：按哈希/编码名延迟解析，避免可疑 API
    组合直接出现在静态导入表里。

边界：这些是让**自有程序**更难被静态逆向的手段；不用于隐藏行为、
不针对安全产品做规避，不做 AMSI/ETW 补丁或进程注入。
"""
from __future__ import annotations

import ast
import base64
import hashlib
import random
import re
from dataclasses import dataclass, field

# 允许被 seal 的占位符（值必须能 ast.literal_eval 回 bytes/str）
SEALABLE = (
    "__MAGIC__", "__SALT__", "__PAYLOAD__", "__APP_TITLE__",
    "__PROG__", "__PROG_DIGEST__",
)

DECODER_SOURCE = r'''
# ---- CyrilShield literal decoder (generated) ----
import hashlib as _csh, base64 as _csb85

def _cs_unseal(s, label):
    raw = _csb85.b85decode(s)
    salt, body = raw[:16], raw[16:]
    key = _csh.sha256(salt + label).digest()
    out = bytearray(len(body))
    for i in range(0, len(body), 32):
        pad = _csh.sha256(key + (i // 32).to_bytes(4, "big")).digest()
        chunk = body[i:i + 32]
        for j, c in enumerate(chunk):
            out[i + j] = c ^ pad[j]
    return bytes(out)

def _cs_unstr(s, label):
    return _cs_unseal(s, label).decode("utf-8")
# ---- end literal decoder ----
'''

_INT_ASSIGN = re.compile(r"(?m)^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\d{3,})\s*$")


@dataclass
class ObfuscationReport:
    sealed: list[str] = field(default_factory=list)
    dead_blocks: int = 0
    split_ints: int = 0
    dynamic_imports: int = 0

    def as_dict(self) -> dict:
        return {
            "sealed": self.sealed,
            "dead_blocks": self.dead_blocks,
            "split_ints": self.split_ints,
            "dynamic_imports": self.dynamic_imports,
        }


def _keystream(key: bytes, n: int) -> bytes:
    out = bytearray()
    c = 0
    while len(out) < n:
        out += hashlib.sha256(key + c.to_bytes(4, "big")).digest()
        c += 1
    return bytes(out[:n])


def seal(value: bytes | str, label: bytes, rng: random.Random) -> str:
    """返回值是一段 **Python 表达式字符串**，可直接替换占位符。"""
    raw = value.encode("utf-8") if isinstance(value, str) else value
    salt = rng.randbytes(16)
    key = hashlib.sha256(salt + label).digest()
    ct = bytes(a ^ b for a, b in zip(raw, _keystream(key, len(raw))))
    packed = base64.b85encode(salt + ct).decode("ascii")
    expr = f"_cs_unseal({packed!r}, {label!r})"
    return expr + ".decode('utf-8')" if isinstance(value, str) else expr


def verify_seal(expr: str, expected: bytes | str) -> bool:
    """自检：在隔离命名空间里执行解码表达式，确认还原一致。"""
    ns: dict = {}
    exec(DECODER_SOURCE, ns)
    got = eval(expr, ns)  # noqa: S307 - 仅用于本模块自检，输入由 seal 生成
    return got == expected


def seal_replacements(repl: dict[str, str], rng: random.Random,
                      keys=SEALABLE) -> tuple[dict[str, str], list[str]]:
    """把 render_stub 的占位符值替换为解码表达式。"""
    out = dict(repl)
    done: list[str] = []
    for key in keys:
        if key not in out:
            continue
        try:
            value = ast.literal_eval(out[key])
        except (ValueError, SyntaxError, TypeError):
            continue
        if not isinstance(value, (bytes, str)):
            continue
        out[key] = seal(value, key.encode("ascii"), rng)
        done.append(key)
    return out, done


def dead_code_source(rng: random.Random, level: int) -> str:
    """生成永不执行的顶层死块；level 0 返回空串。"""
    if level <= 0:
        return ""
    blocks = []
    for _ in range(level * 2):
        guard = "_cs_g_" + "".join(rng.choice("abcdefghijkmnopqrstuvwxyz") for _ in range(8))
        v = rng.getrandbits(32)
        stmts = []
        for _ in range(rng.randrange(2, 5)):
            n = "_cs_d_" + "".join(rng.choice("abcdefghijkmnopqrstuvwxyz") for _ in range(8))
            stmts.append(f"    {n} = {rng.getrandbits(48)}")
            stmts.append(f"    {n} = ({n} ^ 0x{rng.getrandbits(32):x}) & 0xffffffff")
        blocks.append(
            f"{guard} = {v}\n"
            f"if {guard} != {v}:\n" + "\n".join(stmts) + "\n"
        )
    return "\n# ---- dead blocks (never executed) ----\n" + "\n".join(blocks)


def obfuscate_int_literals(src: str, rng: random.Random,
                           level: int, limit: int = 24) -> tuple[str, int]:
    """把顶层大整数赋值改写为等价异或表达式。"""
    if level <= 0:
        return src, 0
    count = 0
    max_hits = min(limit, level * 8)

    def sub(m: re.Match) -> str:
        nonlocal count
        if count >= max_hits:
            return m.group(0)
        name, value = m.group(1), int(m.group(2))
        a = rng.getrandbits(56)
        b = a ^ value
        count += 1
        return f"{name} = (0x{a:x} ^ 0x{b:x})"

    return _INT_ASSIGN.sub(sub, src), count


def dynamic_import_source(imports: list[tuple[str, str]],
                          rng: random.Random) -> str:
    """生成延迟解析片段。``imports`` 为 (模块名, 属性名) 列表。

    编码后的名字不出现在源码文本里，运行时由 importlib 解析。这是
    「导入表保护」的 Python 侧等价物，不改变调用语义。
    """
    def enc(s: str) -> str:
        k = rng.randbytes(8)
        return base64.b85encode(k + bytes(c ^ k[i % 8] for i, c in
                                         enumerate(s.encode()))).decode("ascii")

    pairs = ", ".join(f"({enc(m)!r}, {enc(a)!r})" for m, a in imports)
    return f'''
# ---- CyrilShield dynamic import (generated) ----
import importlib as _cdi, base64 as _cdb
_CD_TABLE = ({pairs},)

def _cd_decode(s):
    raw = _cdb.b85decode(s)
    k, body = raw[:8], raw[8:]
    return bytes(c ^ k[i % 8] for i, c in enumerate(body)).decode("utf-8")

def _cd_resolve(idx):
    m, a = _CD_TABLE[idx]
    return getattr(_cdi.import_module(_cd_decode(m)), _cd_decode(a))
# ---- end dynamic import ----
'''


def inject_at_module_top(src: str, *chunks: str) -> str:
    """把生成片段插到第一条 import 之前，保持 coding 声明在首行。"""
    lines = src.splitlines(keepends=True)
    pos = 0
    for i, line in enumerate(lines):
        if line.startswith("#") or not line.strip():
            continue
        pos = i
        break
    else:
        pos = len(lines)
    body = "".join(c for c in chunks if c)
    if not body:
        return src
    return "".join(lines[:pos]) + "\n" + body + "\n" + "".join(lines[pos:])


def obfuscate_stub(src: str, repl: dict[str, str], level: int,
                   rng: random.Random,
                   extra_imports: list[tuple[str, str]] | None = None
                   ) -> tuple[str, ObfuscationReport]:
    """构建期主入口：替换占位符 → 常量加密 → 整数分裂 → 注入解码器/死块。

    返回 (新源码, 变换报告)。调用方随后再注入守卫/完整性等片段。
    """
    report = ObfuscationReport()
    sealed_repl, report.sealed = seal_replacements(repl, rng)
    for k, v in sealed_repl.items():
        src = src.replace(k, v)

    src, report.split_ints = obfuscate_int_literals(src, rng, level)

    chunks = [DECODER_SOURCE]
    dead = dead_code_source(rng, level)
    if dead:
        report.dead_blocks = dead.count("if _cs_g_")
        chunks.append(dead)
    if extra_imports:
        chunks.append(dynamic_import_source(extra_imports, rng))
        report.dynamic_imports = len(extra_imports)
    src = inject_at_module_top(src, *chunks)
    return src, report
