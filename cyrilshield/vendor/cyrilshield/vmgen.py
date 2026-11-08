# -*- coding: utf-8 -*-
"""
VM 虚拟壳生成器（v2 · 多态加固版）。

工作原理（用于保护自有软件，防止被轻易还原 / 篡改）：
  1. 目标 EXE 以计数器流密钥加密后作为载荷，元数据单独加密；
  2. 生成自定义字节码程序，由内置虚拟机逐条解释执行：重建密钥、驱动分块解密、
     校验完整性、最后释放并启动原程序；
  3. **每次构建的下列要素全部随机**：载荷魔数、密钥盐、分块大小、密钥在虚拟机
     内存中的位置、操作码数值、指令字段排列顺序、立即数混淆掩码、变量 / 类名、
     垃圾指令与死分支，因此不同批次产出的外壳没有固定特征；
  4. 字节码与载荷各有 SHA-256 摘要校验，被改写即拒绝运行。

可选的运行时守卫（默认关闭，由使用者显式勾选）：反调试、反分析环境检测，
以及缩短明文驻留窗口的临时释放。它们只用于保护使用者自己拥有合法权利的程序，
检测失败或任何异常一律放行，绝不误伤正常用户。

刻意不含：杀软对抗（AMSI / ETW 打补丁等）、进程注入 / 进程镂空、驱动、
持久化等隐蔽或规避手段。真正的「内存加载 / 不落地」依赖进程镂空，本项目不做，
详见 docs/SECURITY-NOTES.md。
"""
from __future__ import annotations

import base64
import glob
import hashlib
import os
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from dataclasses import dataclass, field

from . import antitamper, pe
from .vmp import VMPProfile, parity_report, profile_from_dict
from .vmp import compat as _compat
from .vmp import integrity as _integrity
from .vmp import license as _license
from .vmp import memory as _memory
from .vmp import obfuscation as _obf
from .vmp import polymorph as _poly
from .vmp import sdk as _sdk

MAGIC_LEN = 8
KEY_LEN = 32
MASK64 = (1 << 64) - 1

FLAG_VERIFY = 1 << 0
FLAG_CLEANUP = 1 << 1
FLAG_FORWARD_ARGS = 1 << 2

OPCODES = [
    "NOP", "LDI", "MOV", "ADDI", "ADDR", "SUBR", "XORI", "XORR",
    "SHLI", "SHRI", "ANDR", "LOADB", "STOREB", "CMPR", "CMPI",
    "JMP", "JZ", "JNZ", "JL", "JGE", "NATIVE", "HALT",
]

# 会被随机重命名的模块级标识符（只换名字，不动语义）
RENAMEABLE = [
    "PROG_DIGEST", "PROG_LEN", "PROG", "APP_TITLE", "IMM_XOR", "MEM_SIZE",
    "KEY_OFF", "PAYLOAD", "FORDER", "CHUNK", "MAGIC", "SALT", "MASK",
    "OPS", "INV", "VM", "_msgbox", "_keystream",
]


class VMError(Exception):
    pass


# --------------------------------------------------------------------------
# 构建参数（每次生成都不同）
# --------------------------------------------------------------------------
@dataclass
class BuildPlan:
    magic: bytes = b""
    salt: bytes = b""
    chunk: int = 256 * 1024
    order: tuple = (0, 1, 2)          # order[0]=dst 所在字节位
    imm_xor: int = 0
    key_off: int = 0
    mem_size: int = 256
    payload_name: str = "payload.bin"
    names: dict = field(default_factory=dict)

    def fingerprint(self) -> str:
        return hashlib.sha256(self.magic + self.salt + bytes(str(self.order), "utf8")).hexdigest()[:16]


def _rand_name(rng: random.Random, used: set) -> str:
    alphabet = "abcdefghijkmnopqrstuvwxyz0123456789"
    while True:
        n = "_" + "".join(rng.choice(alphabet) for _ in range(rng.randrange(6, 11)))
        if n not in used:
            used.add(n)
            return n


def make_plan(rng: random.Random) -> BuildPlan:
    used: set = set()
    names = {k: _rand_name(rng, used) for k in RENAMEABLE}
    mem_size = rng.choice([128, 160, 192, 224, 256])
    return BuildPlan(
        magic=rng.randbytes(MAGIC_LEN),
        salt=rng.randbytes(16),
        chunk=rng.choice([64 * 1024, 96 * 1024, 128 * 1024, 192 * 1024, 256 * 1024]),
        order=tuple(rng.sample(range(3), 3)),
        imm_xor=rng.randrange(1, 0xFFFFFFFF),
        key_off=rng.randrange(0, mem_size - KEY_LEN + 1),
        mem_size=mem_size,
        payload_name="p" + base64.b16encode(rng.randbytes(5)).decode("ascii").lower() + ".bin",
        names=names,
    )


# --------------------------------------------------------------------------
# 载荷加解密（构建端与壳内解释器算法一致）
# --------------------------------------------------------------------------
def _keystream(key: bytes, plan: BuildPlan, tag: bytes, counter: int, n: int) -> bytes:
    if n <= 0:
        return b""
    return hashlib.shake_256(key + plan.salt + tag + counter.to_bytes(8, "little")).digest(n)


def crypt_chunk(data: bytes, key: bytes, plan: BuildPlan, tag: bytes, counter: int) -> bytes:
    ks = _keystream(key, plan, tag, counter, len(data))
    if not data:
        return b""
    return (int.from_bytes(data, "big") ^ int.from_bytes(ks, "big")).to_bytes(len(data), "big")


def encrypt_payload(plain: bytes, key: bytes, plan: BuildPlan) -> bytes:
    out = bytearray()
    for i in range(0, len(plain), plan.chunk):
        seg = plain[i:i + plan.chunk]
        out += crypt_chunk(seg, key, plan, b"P", i // plan.chunk)
    return bytes(out)


def pack_payload(plain: bytes, key: bytes, flags: int, name: str,
                 extra_args: str, plan: BuildPlan | None = None) -> bytes:
    plan = plan or make_plan(random.SystemRandom())
    name_b = name.encode("utf-8", "replace")[:255]
    args_b = extra_args.encode("utf-8", "replace")[:1023]
    meta = (struct.pack("<Q", len(plain))
            + hashlib.sha256(plain).digest()
            + struct.pack("<II", flags, len(name_b))
            + name_b
            + struct.pack("<I", len(args_b))
            + args_b)
    meta_enc = crypt_chunk(meta, key, plan, b"M", 0)
    ct = encrypt_payload(plain, key, plan)
    return (plan.magic
            + struct.pack("<I", len(meta_enc))
            + meta_enc
            + ct)


def unpack_payload(blob: bytes, key: bytes, plan: BuildPlan) -> tuple[dict, bytes]:
    if not blob.startswith(plan.magic):
        raise VMError("载荷魔数不匹配（可能不是本壳生成的包）")
    p = len(plan.magic)
    mlen = struct.unpack_from("<I", blob, p)[0]
    p += 4
    meta = crypt_chunk(blob[p:p + mlen], key, plan, b"M", 0)
    p += mlen
    q = 0
    orig_size = struct.unpack_from("<Q", meta, q)[0]
    q += 8
    digest = meta[q:q + 32]
    q += 32
    flags, name_len = struct.unpack_from("<II", meta, q)
    q += 8
    name = meta[q:q + name_len].decode("utf-8", "replace")
    q += name_len
    args_len = struct.unpack_from("<I", meta, q)[0]
    q += 4
    extra_args = meta[q:q + args_len].decode("utf-8", "replace")
    return ({"orig_size": orig_size, "digest": digest, "flags": flags,
             "name": name, "extra_args": extra_args}, blob[p:])


# --------------------------------------------------------------------------
# 字节码汇编
# --------------------------------------------------------------------------
@dataclass
class Instr:
    op: str
    dst: int = 0
    a: int = 0
    b: int = 0
    imm: int = 0
    label: str = ""


def _pack_instr(opcode: int, dst: int, a: int, b: int, imm: int, plan: BuildPlan) -> bytes:
    slots = [0, 0, 0]
    slots[plan.order[0]] = dst & 0xFF
    slots[plan.order[1]] = a & 0xFF
    slots[plan.order[2]] = b & 0xFF
    v = (imm ^ plan.imm_xor) & 0xFFFFFFFF
    if v >= 0x80000000:
        v -= 0x100000000
    return struct.pack("<BBBBi", opcode, slots[0], slots[1], slots[2], v)


class Program:
    def __init__(self, rng: random.Random, junk_level: int):
        self.rng = rng
        self.junk_level = junk_level
        self.items: list = []
        self._junk_seq = 0

    def emit(self, op: str, dst: int = 0, a: int = 0, b: int = 0,
             imm: int = 0, label: str = "") -> None:
        self.items.append(Instr(op, dst, a, b, imm, label))

    def label(self, name: str) -> None:
        self.items.append(("L", name))

    # -- 语义中立的垃圾指令：不改标志位、不改 R0~R2 -------------------------
    def _junk_block(self, out: list) -> None:
        r = self.rng
        n = {0: 0, 1: 2, 2: 5}.get(self.junk_level, 2)
        for _ in range(n):
            kind = r.randrange(9)
            reg = r.randrange(3, 16)
            if kind == 0:
                out.append(Instr("NOP"))
            elif kind == 1:
                out.append(Instr("MOV", dst=reg, a=reg))
            elif kind == 2:
                k = r.randrange(1, 1 << 16)
                out.append(Instr("ADDI", dst=reg, imm=k))
                out.append(Instr("ADDI", dst=reg, imm=-k))
            elif kind == 3:
                k = r.randrange(1, 1 << 16)
                out.append(Instr("XORI", dst=reg, imm=k))
                out.append(Instr("XORI", dst=reg, imm=k))
            elif kind == 4:
                out.append(Instr("SHLI", dst=reg, imm=0))
            elif kind == 5:
                out.append(Instr("LDI", dst=15, imm=r.randrange(0, 1 << 20)))
            elif kind == 6:
                # 无条件跳转越过两条垃圾指令（不触碰标志位，标签保证地址精确）
                name = "junk%d" % self._junk_seq
                self._junk_seq += 1
                out.append(Instr("JMP", label=name))
                out.append(Instr("NOP"))
                out.append(Instr("MOV", dst=15, a=15))
                out.append(("L", name))
            elif kind == 7:
                out.append(Instr("ADDR", dst=reg, a=reg, b=15))
                out.append(Instr("SUBR", dst=reg, a=reg, b=15))
            else:
                out.append(Instr("ANDR", dst=reg, a=reg, b=reg))

    def assemble(self, ops: dict[str, int], plan: BuildPlan) -> bytes:
        expanded: list = []
        for item in self.items:
            if isinstance(item, tuple):
                expanded.append(item)
            else:
                self._junk_block(expanded)
                expanded.append(item)

        labels: dict[str, int] = {}
        idx = 0
        for item in expanded:
            if isinstance(item, tuple):
                labels[item[1]] = idx
            else:
                idx += 1

        raw = bytearray()
        for ins in expanded:
            if isinstance(ins, tuple):
                continue
            imm = ins.imm
            if ins.label:
                if ins.label not in labels:
                    raise VMError(f"未定义的标签: {ins.label}")
                imm = labels[ins.label]
            raw += _pack_instr(ops[ins.op], ins.dst, ins.a, ins.b, imm, plan)
        return bytes(raw)


def make_program(key: bytes, junk_level: int, rng: random.Random,
                 plan: BuildPlan | None = None) -> tuple[bytes, dict[str, int], BuildPlan]:
    """生成随机化操作码映射 + 虚拟机字节码。"""
    plan = plan or make_plan(rng)
    values = rng.sample(range(1, 256), len(OPCODES))
    ops = dict(zip(OPCODES, values))

    # 随机分配角色寄存器（R0~R2 为原生调用参数，R13~R15 为暂存）
    pool = rng.sample(range(3, 13), 7)
    total, chunk, off, ctr, addr, tmp, one = pool

    p = Program(rng, junk_level)

    # 1) 由字节码逐字节重建密钥（顺序打乱 + 一次一密混淆）
    order = list(range(KEY_LEN))
    rng.shuffle(order)
    for i in order:
        obf = rng.randrange(0, 256)
        p.emit("LDI", dst=tmp, imm=(key[i] ^ obf))
        p.emit("XORI", dst=tmp, imm=obf)
        p.emit("LDI", dst=addr, imm=plan.key_off + i)
        p.emit("STOREB", a=addr, b=tmp)

    # 2) 原生调用 0：读取并解密元数据、取回载荷
    p.emit("NATIVE", imm=0)
    p.emit("MOV", dst=total, a=0)
    p.emit("LDI", dst=chunk, imm=plan.chunk)
    p.emit("LDI", dst=off, imm=0)
    p.emit("LDI", dst=ctr, imm=0)

    # 3) 分块解密循环（循环控制完全在虚拟机里）
    p.label("LOOP")
    p.emit("CMPR", a=off, b=total)
    p.emit("JGE", label="AFTER")
    p.emit("MOV", dst=0, a=off)
    p.emit("SUBR", dst=tmp, a=total, b=off)
    p.emit("CMPI", dst=tmp, imm=plan.chunk)
    p.emit("JL", label="USE_REM")
    p.emit("MOV", dst=1, a=chunk)
    p.emit("JMP", label="DO_DECRYPT")
    p.label("USE_REM")
    p.emit("MOV", dst=1, a=tmp)
    p.label("DO_DECRYPT")
    p.emit("MOV", dst=2, a=ctr)
    p.emit("NATIVE", imm=1)
    p.emit("MOV", dst=ctr, a=0)
    p.emit("ADDR", dst=off, a=off, b=1)
    p.emit("JMP", label="LOOP")

    # 4) 完整性校验 + 释放运行
    p.label("AFTER")
    p.emit("NATIVE", imm=2)
    p.emit("LDI", dst=one, imm=1)
    p.emit("CMPR", a=0, b=one)
    p.emit("JNZ", label="ERROR")
    p.emit("NATIVE", imm=3)
    p.emit("HALT")
    p.label("ERROR")
    p.emit("NATIVE", imm=4)
    p.emit("HALT")

    return p.assemble(ops, plan), ops, plan


# --------------------------------------------------------------------------
# 壳内解释器源码
# --------------------------------------------------------------------------
STUB_TEMPLATE = r'''# -*- coding: utf-8 -*-
import base64, ctypes, hashlib, os, struct, subprocess, sys, tempfile, shutil

__INJECTED_SOURCE__

OPS = __OPS__
INV = {v: k for k, v in OPS.items()}
PROG = base64.b85decode(__PROG__)
PROG_LEN = __PROG_LEN__
PROG_DIGEST = __PROG_DIGEST__
MASK = (1 << 64) - 1
KEY_OFF = __KEY_OFF__
MEM_SIZE = __MEM_SIZE__
MAGIC = __MAGIC__
SALT = __SALT__
CHUNK = __CHUNK__
IMM_XOR = __IMM_XOR__
FORDER = __FORDER__
APP_TITLE = __APP_TITLE__
PAYLOAD = __PAYLOAD__


def _msgbox(text):
    try:
        ctypes.windll.user32.MessageBoxW(0, str(text), APP_TITLE, 0x10)
    except Exception:
        pass


def _keystream(key, tag, counter, n):
    if n <= 0:
        return b""
    return hashlib.shake_256(key + SALT + tag + counter.to_bytes(8, "little")).digest(n)


class VM:
    def __init__(self):
        self.r = [0] * 16
        self.mem = bytearray(MEM_SIZE)
        self.flag = 0
        self.meta = None
        self.ct = bytearray()

    def _base(self):
        return getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(sys.argv[0])))

    def _key(self):
        return bytes(self.mem[KEY_OFF:KEY_OFF + 32])

    def native(self, n):
        r = self.r
        if n == 0:
            with open(os.path.join(self._base(), PAYLOAD), "rb") as f:
                blob = f.read()
            if not blob.startswith(MAGIC):
                raise RuntimeError("payload header mismatch")
            p = len(MAGIC)
            mlen = struct.unpack_from("<I", blob, p)[0]
            p += 4
            raw = blob[p:p + mlen]
            p += mlen
            ks = _keystream(self._key(), b"M", 0, mlen)
            meta = bytes(a ^ b for a, b in zip(raw, ks))
            q = 0
            size = struct.unpack_from("<Q", meta, q)[0]
            q += 8
            digest = meta[q:q + 32]
            q += 32
            flags, nlen = struct.unpack_from("<II", meta, q)
            q += 8
            name = meta[q:q + nlen].decode("utf-8", "replace")
            q += nlen
            alen = struct.unpack_from("<I", meta, q)[0]
            q += 4
            args = meta[q:q + alen].decode("utf-8", "replace")
            self.meta = dict(size=size, digest=digest, flags=flags, name=name, args=args)
            self.ct = bytearray(blob[p:])
            return len(self.ct)
        if n == 1:
            off, ln, ctr = r[0], r[1], r[2]
            end = min(off + ln, len(self.ct))
            seg = bytes(self.ct[off:end])
            ks = _keystream(self._key(), b"P", ctr, len(seg))
            if seg:
                self.ct[off:end] = (int.from_bytes(seg, "big") ^ int.from_bytes(ks, "big")).to_bytes(len(seg), "big")
            return ctr + 1
        if n == 2:
            if not (self.meta["flags"] & 1):
                return 1
            data = bytes(self.ct)
            return 1 if (len(data) == self.meta["size"]
                         and hashlib.sha256(data).digest() == self.meta["digest"]) else 0
        if n == 3:
            # 释放逻辑由注入片段提供：secure 模式用 delete-on-close，
            # 让磁盘上不存在可被随手复制的稳定明文文件。
            return _at_spawn(bytes(self.ct), self.meta)
        if n == 4:
            _msgbox("完整性校验失败：文件可能已损坏或被修改。")
            return 1
        raise RuntimeError("unknown native call %d" % n)

    def run(self):
        if len(PROG) != PROG_LEN or hashlib.sha256(PROG).hexdigest() != PROG_DIGEST:
            raise RuntimeError("stub integrity check failed")
        r, mem = self.r, self.mem
        pc = 0
        total = PROG_LEN // 8
        steps = 0
        while pc < total:
            op, b1, b2, b3, ix = struct.unpack_from("<BBBBi", PROG, pc * 8)
            pc += 1
            slots = (b1, b2, b3)
            dst = slots[FORDER[0]]
            a = slots[FORDER[1]]
            b = slots[FORDER[2]]
            imm = (ix ^ IMM_XOR) & 0xFFFFFFFF
            if imm >= 0x80000000:
                imm -= 0x100000000
            name = INV.get(op)
            steps += 1
            if steps > 50000000:
                raise RuntimeError("virtual machine step limit exceeded")
            if name == "NOP":
                pass
            elif name == "LDI":
                r[dst] = imm & MASK
            elif name == "MOV":
                r[dst] = r[a]
            elif name == "ADDI":
                r[dst] = (r[dst] + imm) & MASK
            elif name == "ADDR":
                r[dst] = (r[a] + r[b]) & MASK
            elif name == "SUBR":
                r[dst] = (r[a] - r[b]) & MASK
            elif name == "XORI":
                r[dst] = (r[dst] ^ imm) & MASK
            elif name == "XORR":
                r[dst] = (r[a] ^ r[b]) & MASK
            elif name == "SHLI":
                r[dst] = (r[dst] << (imm & 63)) & MASK
            elif name == "SHRI":
                r[dst] = (r[dst] >> (imm & 63)) & MASK
            elif name == "ANDR":
                r[dst] = r[a] & r[b]
            elif name == "LOADB":
                r[dst] = mem[r[a] & 0xFF]
            elif name == "STOREB":
                mem[r[a] & 0xFF] = r[b] & 0xFF
            elif name == "CMPR":
                self.flag = r[a] - r[b]
            elif name == "CMPI":
                self.flag = r[dst] - imm
            elif name == "JMP":
                pc = imm
                continue
            elif name == "JZ":
                if self.flag == 0:
                    pc = imm
                    continue
            elif name == "JNZ":
                if self.flag != 0:
                    pc = imm
                    continue
            elif name == "JL":
                if self.flag < 0:
                    pc = imm
                    continue
            elif name == "JGE":
                if self.flag >= 0:
                    pc = imm
                    continue
            elif name == "NATIVE":
                r[0] = self.native(imm) & MASK
            elif name == "HALT":
                return
            else:
                raise RuntimeError("illegal opcode")
        raise RuntimeError("bytecode out of range")


def main():
    # 命中调试器 / 分析环境时静默退出，不解密、不弹窗、不暴露判定逻辑
    if not _at_guard():
        return
    try:
        VM().run()
    except Exception as exc:
        _msgbox("startup failed: %s" % exc)


if __name__ == "__main__":
    main()
'''


@dataclass
class RenderedStub:
    source: str
    names: dict


def _inject_before_main(src: str, *chunks: str) -> str:
    """在 ``if __name__ == "__main__":`` 之前插入模块级代码。

    此时所有模块级常量（PROG / SALT / ...）都已定义，适合放运行时
    完整性校验与许可校验，且不改动 STUB_TEMPLATE 的控制流。
    """
    body = "".join(c for c in chunks if c)
    if not body:
        return src
    marker = '\nif __name__ == "__main__":'
    i = src.rfind(marker)
    if i < 0:
        return src + "\n" + body + "\n"
    return src[:i] + "\n" + body + "\n" + src[i:]


def _vmp_postprocess(src: str, ctx: dict, repl: dict[str, str]) -> tuple[str, dict]:
    """把 VMP 对标能力应用到壳源码（EXE 专用）。"""
    rng: random.Random = ctx["rng"]
    profile: VMPProfile = ctx["profile"]
    report: dict = {}

    if profile.mutation_level or profile.string_encryption:
        # 常量加密 + 整数分裂 + 死块 + 动态解析，一步完成占位符替换
        imports = ([("ctypes", "windll"), ("hashlib", "sha256")]
                   if profile.import_protection else None)
        src, ob = _obf.obfuscate_stub(src, repl, profile.mutation_level,
                                      rng, extra_imports=imports)
        report["obfuscation"] = ob.as_dict()
    else:
        for k, v in repl.items():
            src = src.replace(k, v)

    # 守卫/释放逻辑：压缩编码 + 随机盐，静态反编译只见编码串
    src = src.replace("__INJECTED_SOURCE__",
                      antitamper.bootstrap_source(ctx["mode"], ctx["secure_temp"]))

    tail: list[str] = []
    if profile.memory != "off":
        tail.append(_memory.runtime_memory_guard_source())
    tail.append(_sdk.runtime_marker_source(ctx.get("markers", [])))
    if profile.integrity != "off" and ctx.get("integrity_plan"):
        tail.append(_integrity.runtime_check_source(
            ctx["prog_var"], ctx["integrity_plan"],
            fail_stmt="raise SystemExit(1)"))
    if profile.licensing and ctx.get("license_token"):
        tail.append(_license.runtime_license_source(
            ctx["license_token"], profile.license_secret))
    src = _inject_before_main(src, *tail)
    report["enabled"] = profile.enabled_keys()
    return src, report


def render_stub(program: bytes, ops: dict[str, int], title: str,
                plan: BuildPlan, mode: int = 0,
                secure_temp: bool = False,
                vmp_ctx: dict | None = None) -> RenderedStub:
    src = STUB_TEMPLATE
    # 先用随机名替换标识符，再填入真实数据，避免替换污染字符串字面量
    pattern = re.compile(r"\b(" + "|".join(
        re.escape(k) for k in sorted(plan.names, key=len, reverse=True)) + r")\b")
    src = pattern.sub(lambda m: plan.names[m.group(1)], src)

    repl = {
        "__OPS__": repr(ops),
        "__PROG__": repr(base64.b85encode(program).decode("ascii")),
        "__PROG_LEN__": str(len(program)),
        "__PROG_DIGEST__": repr(hashlib.sha256(program).hexdigest()),
        "__KEY_OFF__": str(plan.key_off),
        "__MEM_SIZE__": str(plan.mem_size),
        "__MAGIC__": repr(plan.magic),
        "__SALT__": repr(plan.salt),
        "__CHUNK__": str(plan.chunk),
        "__IMM_XOR__": str(plan.imm_xor),
        "__FORDER__": repr(tuple(plan.order)),
        "__APP_TITLE__": repr(title),
        "__PAYLOAD__": repr(plan.payload_name),
    }
    if vmp_ctx:
        src, report = _vmp_postprocess(src, vmp_ctx, repl)
        vmp_ctx["report"] = report
    else:
        # 原路径：不启用对标层（自检 / 兼容旧调用）
        for k, v in repl.items():
            src = src.replace(k, v)
        src = src.replace("__INJECTED_SOURCE__",
                          antitamper.bootstrap_source(mode, secure_temp))
    return RenderedStub(src, dict(plan.names))


def _make_vmp_context(profile: VMPProfile, program: bytes, plan: BuildPlan,
                      rng: random.Random, opts: "VMOptions") -> dict:
    """构造一次 EXE 构建的对标层上下文。"""
    ctx: dict = {
        "rng": rng,
        "profile": profile,
        "mode": antitamper.guard_mode(opts.anti_debug, opts.anti_sandbox),
        "secure_temp": opts.secure_temp,
        "prog_var": plan.names["PROG"],
        "markers": [],
    }
    if profile.integrity != "off":
        ctx["integrity_plan"] = _integrity.make_plan(program)
    if profile.licensing:
        days = 0
        if profile.license_expires:
            try:
                import datetime as _dt
                end = _dt.date.fromisoformat(profile.license_expires)
                days = max(0, (end - _dt.date.today()).days)
            except ValueError:
                days = 0
        hwid = str(profile.extras.get("license_hwid", "") or _license.shell_hwid())
        bound = bool(profile.extras.get("license_hwid"))
        pol = _license.LicensePolicy(
            product_id="CYRILSHIELD-EXE",
            features=profile.license_features,
            expires_days=days,
            hwid_bound=bound,
        )
        ctx["license_token"] = _license.issue(pol, hwid, profile.license_secret)
        ctx["license_policy"] = pol.as_dict()
        ctx["license_hwid"] = hwid if bound else ""
    return ctx


# --------------------------------------------------------------------------
# 构建
# --------------------------------------------------------------------------
@dataclass
class VMOptions:
    password: str = ""             # 空 = 每次随机密钥
    junk_level: int = 1            # 0/1/2 混淆强度
    verify: bool = True
    cleanup: bool = True
    forward_args: bool = True
    extra_args: str = ""
    keep_icon: bool = True
    keep_workdir: bool = False
    name: str = ""
    # 运行时守卫：默认全关。反调试 / 反分析环境有概率误伤虚拟桌面、
    # 云主机、企业终端，必须由使用者显式勾选。
    anti_debug: bool = False
    anti_sandbox: bool = False
    # 缩短明文落盘窗口（delete-on-close）；非 Windows 或失败时自动回落。
    secure_temp: bool = True
    # VMP 对标层（EXE 专用）。True = 使用推荐强度；config 可逐项覆盖。
    vmp_enabled: bool = True
    vmp_config: dict = field(default_factory=dict)


@dataclass
class VMResult:
    output: str
    original_size: int
    output_size: int
    ops: dict[str, int]
    program_size: int
    key: str
    fingerprint: str = ""
    workdir: str = ""
    guard_mode: int = 0
    secure_temp: bool = False
    # VMP 对标产物：能力清单、混淆报告、完整性链、误报审计、多态指纹
    vmp: dict = field(default_factory=dict)


_PY_CACHE = {}


def _has_pyinstaller(exe: str) -> bool:
    try:
        r = subprocess.run([exe, "-E", "-s", "-c", "import PyInstaller"],
                           capture_output=True, timeout=30,
                           creationflags=0x08000000 if os.name == "nt" else 0)
        return r.returncode == 0
    except Exception:
        return False


def _bundled_python() -> str:
    """插件自带的便携 Python（``vendor/python``，内含 PyInstaller）。

    布局：``<插件根>/vendor/python/python.exe``；本文件位于
    ``<插件根>/vendor/cyrilshield/vmgen.py``，所以往上一级就是 vendor。
    """
    if "_BUNDLED_PY" in _PY_CACHE:
        return _PY_CACHE["_BUNDLED_PY"]
    here = os.path.dirname(os.path.abspath(__file__))
    bases = [os.path.join(os.path.dirname(here), "python")]
    meipass = getattr(sys, "_MEIPASS", "")
    if meipass:
        bases.append(os.path.join(meipass, "python"))
    if getattr(sys, "frozen", False):
        bases.append(os.path.join(os.path.dirname(os.path.abspath(sys.executable)), "python"))
    found = ""
    for b in bases:
        exe = os.path.join(b, "python.exe" if os.name == "nt" else os.path.join("bin", "python3"))
        if os.path.isfile(exe) and _has_pyinstaller(exe):
            found = exe
            break
    _PY_CACHE["_BUNDLED_PY"] = found
    return found


def _python_exe() -> str:
    """返回可执行 `python -m PyInstaller` 的解释器。

    优先插件自带的便携 Python（``vendor/python``），没有时才回退本机 Python。
    """
    if "_PY_EXE" in _PY_CACHE:
        return _PY_CACHE["_PY_EXE"]
    bundled = _bundled_python()
    if bundled:
        _PY_CACHE["_PY_EXE"] = bundled
        return bundled

    exe = sys.executable or "python"
    if getattr(sys, "frozen", False):
        candidates = []
        found = shutil.which("python")
        if found:
            candidates.append(found)
        local = os.environ.get("LOCALAPPDATA", "")
        candidates += sorted(glob.glob(os.path.join(local, "Programs", "Python",
                                                    "Python*", "python.exe")), reverse=True)
        candidates += sorted(glob.glob(r"C:\Python*\python.exe"), reverse=True)
        for c in candidates:
            if not os.path.isfile(c) or "WindowsApps" in c:
                continue
            try:
                r = subprocess.run([c, "-c", "import PyInstaller"],
                                   capture_output=True, timeout=20,
                                   creationflags=0x08000000 if os.name == "nt" else 0)
                if r.returncode == 0:
                    _PY_CACHE["_PY_EXE"] = c
                    return c
            except Exception:
                continue
        raise VMError("找不到可用的 Python + PyInstaller：插件的 vendor/python 与系统 Python 都不可用")
    if os.path.basename(exe).lower() == "pythonw.exe":
        candidate = os.path.join(os.path.dirname(exe), "python.exe")
        if os.path.isfile(candidate):
            _PY_CACHE["_PY_EXE"] = candidate
            return candidate
    _PY_CACHE["_PY_EXE"] = exe
    return exe


def _python_cmd() -> list:
    """PyInstaller 调用前缀；用插件自带解释器时隔离宿主环境的 PYTHON* 变量。"""
    exe = _python_exe()
    bundled = _bundled_python()
    args = [exe]
    if bundled and os.path.normcase(os.path.abspath(exe)) == os.path.normcase(os.path.abspath(bundled)):
        args += ["-E", "-s"]
    return args


def _plugin_root() -> str:
    """插件根目录（含 ``manifest.json`` 的那一层）；非插件运行时返回空串。"""
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)                    # <插件根>/vendor
    for cand in (os.path.dirname(root), root):
        if os.path.isfile(os.path.join(cand, "manifest.json")):
            return cand
    return ""


def _work_dir(prefix: str) -> str:
    """构建中间目录：优先插件内 ``.work``，不可写时回退系统临时目录。"""
    root = _plugin_root()
    if root:
        base = os.path.join(root, ".work")
        try:
            os.makedirs(base, exist_ok=True)
            return tempfile.mkdtemp(prefix=prefix, dir=base)
        except OSError:
            pass
    return tempfile.mkdtemp(prefix=prefix)


def check_backend() -> tuple[bool, str]:
    """检查 PyInstaller 后端是否可用。"""
    if getattr(sys, "frozen", False) or _bundled_python():
        try:
            exe = _python_exe()
            tag = "插件自带便携 Python" if _bundled_python() else "外部 Python"
            return True, tag + " + PyInstaller（" + exe + "）"
        except Exception as exc:
            return False, str(exc)
    try:
        import PyInstaller  # noqa: F401
        return True, f"PyInstaller {PyInstaller.__version__}"
    except Exception as exc:
        return False, f"未安装 PyInstaller：{exc}（插件 vendor/python 缺失）"


def build(input_path: str, output_path: str, opts: VMOptions | None = None,
          on_line=None, timeout: int = 900) -> VMResult:
    """生成一个受 VM 虚拟壳保护的独立 EXE。"""
    opts = opts or VMOptions()
    if on_line is None:
        on_line = lambda _s: None
    if not os.path.isfile(input_path):
        raise FileNotFoundError("请选择有效的输入 EXE")
    ok, info = check_backend()
    if not ok:
        raise VMError(info)

    with open(input_path, "rb") as f:
        plain = f.read()
    if len(plain) < 0x40 or plain[:2] != b"MZ":
        raise VMError("输入文件不是有效的 PE 可执行文件")
    on_line(f"目标文件：{os.path.basename(input_path)}  ({len(plain):,} 字节)")

    rng = random.SystemRandom()
    if opts.password:
        key = hashlib.pbkdf2_hmac("sha256", opts.password.encode("utf-8"),
                                  b"CyrilShield/VM/v3", 200_000, KEY_LEN)
        on_line("密钥来源：口令派生（PBKDF2-SHA256 200k）")
    else:
        key = rng.randbytes(KEY_LEN)
        on_line("密钥来源：每次构建随机生成")

    plan = make_plan(rng)
    flags = 0
    flags |= FLAG_VERIFY if opts.verify else 0
    flags |= FLAG_CLEANUP if opts.cleanup else 0
    flags |= FLAG_FORWARD_ARGS if opts.forward_args else 0

    program, ops, plan = make_program(key, max(0, min(2, opts.junk_level)), rng, plan)
    on_line(f"字节码：{len(program) // 8} 条虚拟指令，{len(ops)} 个操作码映射已随机化")
    on_line(f"多态参数：分块 {plan.chunk // 1024} KB · 字段排列 {plan.order} · "
            f"指纹 {plan.fingerprint()}")

    # ---- VMP 对标层（EXE 专用）----
    profile: VMPProfile | None = None
    vmp_ctx: dict | None = None
    if opts.vmp_enabled:
        profile = profile_from_dict(opts.vmp_config)
        profile.anti_debug = profile.anti_debug or opts.anti_debug
        profile.anti_vm = profile.anti_vm or opts.anti_sandbox
        vmp_ctx = _make_vmp_context(profile, program, plan, rng, opts)
        keys = profile.enabled_keys()
        on_line(f"VMP 对标层：已启用 {len(keys)} 项（{'、'.join(keys[:6])} …）")

    mode = antitamper.guard_mode(opts.anti_debug, opts.anti_sandbox)
    guard_desc = []
    if opts.anti_debug:
        guard_desc.append("反调试")
    if opts.anti_sandbox:
        guard_desc.append("反分析环境")
    on_line("运行时守卫：" + ("、".join(guard_desc) if guard_desc else "关闭")
            + (" · 加固临时释放" if opts.secure_temp else " · 常规临时释放"))

    workdir = _work_dir("vmsbuild_")
    try:
        payload_path = os.path.join(workdir, plan.payload_name)
        with open(payload_path, "wb") as f:
            f.write(pack_payload(plain, key, flags,
                                 opts.name or os.path.basename(input_path),
                                 opts.extra_args, plan))
        stub = render_stub(program, ops,
                           f"{os.path.splitext(os.path.basename(input_path))[0]}",
                           plan, mode=mode, secure_temp=opts.secure_temp,
                           vmp_ctx=vmp_ctx)
        stub_path = os.path.join(workdir, "boot.py")
        with open(stub_path, "w", encoding="utf-8") as f:
            f.write(stub.source)

        # 版本资源 + 构建清单：都是构建期写入，不追加 overlay
        # （PyInstaller onefile 的 CArchive cookie 在文件尾部，追加会破坏自解压）
        version_file = ""
        manifest_path = ""
        manifest_obj: dict = {}
        if profile:
            if profile.av_compatibility:
                version_file = os.path.join(workdir, "cs_version.py")
                with open(version_file, "w", encoding="utf-8") as f:
                    f.write(_compat.version_file_source(
                        company=profile.company_name,
                        product=profile.product_name,
                        description=f"{os.path.basename(input_path)} (protected build)",
                        original_filename=os.path.basename(output_path),
                        file_version=profile.file_version,
                    ))
            ip = vmp_ctx.get("integrity_plan") if vmp_ctx else None
            watermark = _license.watermark_payload(
                str((vmp_ctx or {}).get("license_policy", {}).get("serial", "")),
                str((vmp_ctx or {}).get("license_hwid", "")))
            manifest_obj = {
                "schema": "cyrilshield.exe-build/1",
                "target": "PE",
                "watermark": watermark.decode("utf-8", "replace"),
                "integrity": ip.as_dict() if ip else None,
                "license_token": (vmp_ctx or {}).get("license_token", ""),
                "license_policy": (vmp_ctx or {}).get("license_policy", {}),
                "capabilities": profile.enabled_keys(),
                "obfuscation": (vmp_ctx or {}).get("report", {}),
                "build_manifest": _compat.build_manifest([
                    ("payload.bin", open(payload_path, "rb").read()),
                    ("boot.py", stub.source.encode("utf-8")),
                ]),
            }
            manifest_path = os.path.join(workdir, "cs_manifest.json")
            import json as _json
            with open(manifest_path, "w", encoding="utf-8") as f:
                _json.dump(manifest_obj, f, ensure_ascii=False, indent=2)
            on_line("已生成版本资源与可复现构建清单（误报申诉材料）")

        icon_path = ""
        if opts.keep_icon:
            try:
                groups = pe.PEFile(plain, input_path).extract_icon_groups()
                if groups:
                    icon_path = os.path.join(workdir, "app.ico")
                    with open(icon_path, "wb") as f:
                        f.write(groups[0].to_ico())
                    on_line("已沿用原程序图标")
            except Exception as exc:
                on_line(f"图标沿用失败（不影响加壳）：{exc}")
                icon_path = ""

        dist = os.path.join(workdir, "dist")
        builddir = os.path.join(workdir, "build")
        build_name = "s" + base64.b16encode(rng.randbytes(5)).decode("ascii").lower()
        cmd = _python_cmd() + [
            "-m", "PyInstaller",
            "--noconfirm", "--clean", "--onefile", "--windowed", "--noupx",
            "--log-level=WARN",
            "--name", build_name,
            "--distpath", dist,
            "--workpath", builddir,
            "--specpath", workdir,
            "--add-data", payload_path + os.pathsep + ".",
        ]
        if version_file:
            cmd += ["--version-file", version_file]
        if manifest_path:
            cmd += ["--add-data", manifest_path + os.pathsep + "."]
        if icon_path:
            cmd += ["--icon", icon_path]
        cmd += [stub_path]
        on_line("正在编译独立外壳（首次约需 10~40 秒）...")
        proc = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
            creationflags=0x08000000 if os.name == "nt" else 0,
            cwd=workdir,
        )
        for line in proc.stdout:
            line = line.rstrip()
            if line:
                on_line("  " + line)
        proc.wait(timeout=timeout)
        if proc.returncode != 0:
            raise VMError(f"外壳编译失败（退出码 {proc.returncode}）")

        built = os.path.join(dist, build_name + ".exe")
        if not os.path.isfile(built):
            raise VMError("编译完成但未找到输出 EXE")
        os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
        shutil.copyfile(built, output_path)
        out_size = os.path.getsize(output_path)

        vmp_out: dict = {}
        if profile and vmp_ctx:
            with open(output_path, "rb") as f:
                audit = _compat.av_compat_audit(f.read())
            ip = vmp_ctx.get("integrity_plan")
            fp = _poly.collect(
                ops=ops, plan=plan, profile=profile,
                integrity_root=ip.root_hex if ip else "",
                extras={"av_score": audit.get("score", 0)},
            )
            pdict = profile.as_dict()
            # 签名密钥绝不回传到前端 / 日志
            pdict["license_secret"] = "***" if pdict.get("license_secret") else ""
            vmp_out = {
                "profile": pdict,
                "capabilities": profile.enabled_keys(),
                "obfuscation": vmp_ctx.get("report", {}),
                "integrity": ip.as_dict() if ip else None,
                "license": {
                    "token": vmp_ctx.get("license_token", ""),
                    "policy": vmp_ctx.get("license_policy", {}),
                    "hwid": vmp_ctx.get("license_hwid", ""),
                },
                "av_audit": audit,
                "build_fingerprint": fp.as_dict(),
                "manifest": manifest_obj,
                "parity_markdown": parity_report(profile),
            }
            bad = [x for x in audit.get("findings", []) if not x["ok"]]
            on_line(f"误报风险审计：{audit.get('score', 0)}/100"
                    f"（{len(bad)} 项可优化，详见构建清单）")
            on_line(f"VMP 构建指纹：{fp.short}")

        on_line(f"完成：{output_path}  ({out_size:,} 字节)")
        return VMResult(
            output=output_path, original_size=len(plain), output_size=out_size,
            ops=ops, program_size=len(program) // 8,
            key=hashlib.sha256(key).hexdigest()[:16],
            fingerprint=plan.fingerprint(),
            workdir=workdir if opts.keep_workdir else "",
            guard_mode=mode, secure_temp=opts.secure_temp,
            vmp=vmp_out,
        )
    finally:
        if not opts.keep_workdir:
            shutil.rmtree(workdir, ignore_errors=True)


# --------------------------------------------------------------------------
# 不打包、直接在当前进程里验证 VM 字节码（自检用）
# --------------------------------------------------------------------------
def selftest(program: bytes, ops: dict[str, int], plan: BuildPlan,
             payload_path: str, temp_exe_dir: str) -> bool:
    """把生成的 stub 源码在本进程执行，验证加壳逻辑是否正确。"""
    import types

    stub = render_stub(program, ops, "selftest", plan)
    src = stub.source
    cls = stub.names["VM"]
    mod = types.ModuleType("vms_selftest")
    mod.__file__ = os.path.join(os.path.dirname(payload_path), "boot.py")
    sys.modules["vms_selftest"] = mod
    old_meipass = getattr(sys, "_MEIPASS", None)
    old_argv = sys.argv
    sys._MEIPASS = os.path.dirname(payload_path)
    sys.argv = [os.path.join(temp_exe_dir, "dummy.exe")]
    try:
        exec(compile(src, mod.__file__, "exec"), mod.__dict__)
        vm = getattr(mod, cls)()
        orig = vm.native

        def patched(n):
            if n == 3:
                out = os.path.join(temp_exe_dir, "restored.exe")
                with open(out, "wb") as f:
                    f.write(bytes(vm.ct))
                vm.r[0] = 0
                return 0
            if n == 4:
                raise RuntimeError("完整性校验失败")
            return orig(n)

        vm.native = patched
        vm.run()
        restored = os.path.join(temp_exe_dir, "restored.exe")
        return os.path.isfile(restored) and os.path.getsize(restored) > 0
    finally:
        if old_meipass is None:
            if hasattr(sys, "_MEIPASS"):
                delattr(sys, "_MEIPASS")
        else:
            sys._MEIPASS = old_meipass
        sys.argv = old_argv
        sys.modules.pop("vms_selftest", None)
