"""壳引导链的 VM 虚拟化编译器（Python 侧）。

把「重建密钥材料 → 派生根密钥 → 逐片派生子密钥 → 解封载荷 → 汇总摘要 →
完整性判定」这条最敏感的引导链，编译成一套自定义字节码，交给壳内的通用
解释器执行。反编译壳 dex 只能看到一段 opaque 的 int[] 数据和一个 switch-less
解释器，看不到 HMAC 调用顺序、分片与密钥的对应关系、校验阈值。

随机化维度（每次构建都不同）：
  * 操作码数值随机映射到 0..255，未使用值填噪声
  * 逻辑寄存器 → 物理槽位随机置换
  * 常量池顺序打乱，且每项用 per-build 掩码编码
  * 随机插入 NOP / 空跳转 / 无效 MOVE 等垃圾指令与死分支
  * OPS 数组在 Java 源码中的排列顺序随机

边界（务必看清）：虚拟化的是**壳自己的引导逻辑**，不是 APK 业务 dex 的
Dalvik 字节码。后者需要完整 Dalvik 反汇编器 + 能在 ART 上接管方法执行的
解释器 + 全指令集语义覆盖（invoke/异常/泛型/JNI），属于 VMP 级工程，
本项目不做，详见 docs/APK-SECURITY-NOTES.md。

Python 的 interp() 与 Java 的 run() 语义严格对称，用于双端一致性自测：
两侧喂同一份 code/pool/mask/ops，必须还原出逐字节相同的明文分片。
"""
from __future__ import annotations

import hashlib
import hmac
import random
import struct
from dataclasses import dataclass, field

from . import _stream

# 逻辑操作码。数值在编译时随机分配，这里的顺序无关紧要。
LOGIC_OPS = (
    "LOADC", "LOADA", "LOADPART", "KTXOR", "HMAC", "UNSEAL", "CONCAT",
    "SHA256", "WIPE", "MOVE", "DIGINIT", "DIGADD", "DIGEND", "EMIT",
    "EQ", "JZ", "JMP", "HALT", "NOP",
)
W = 5                 # 每条指令 5 个 int: op dst a b imm
NREGS = 24
EMPTY = b""


# ---------------------------------------------------------------- 数据结构
@dataclass
class Instr:
    op: str
    dst: int = 0
    a: int = 0
    b: int = 0
    imm: int = 0
    target: str = ""     # 跳转目标 label（JMP / JZ）
    label: str = ""      # 本指令的 label
    raw: bool = False    # True = 槽位字段已是物理编号，不再参与置换


@dataclass
class VmProgram:
    code: list[int] = field(default_factory=list)
    pool: list[bytes] = field(default_factory=list)   # 已掩码编码
    mask: bytes = b""
    ops: dict[str, int] = field(default_factory=dict)
    ops_perm: dict[str, int] = field(default_factory=dict)
    nparts: int = 0
    verify: bool = True
    junk: int = 0
    regs: int = NREGS

    def as_java_ints(self) -> str:
        return ", ".join(_s32(v) for v in self.code)

    def as_java_pool(self) -> str:
        return ", ".join("new byte[]{%s}" % _blit(x) for x in self.pool)

    def as_java_mask(self) -> str:
        return "new byte[]{%s}" % _blit(self.mask)

    def as_java_ops(self) -> str:
        arr = [0] * len(LOGIC_OPS)
        for name, pos in self.ops_perm.items():
            arr[pos] = self.ops[name]
        return ", ".join(_s32(v) for v in arr)


def _blit(data: bytes) -> str:
    return ", ".join(str(b - 256 if b > 127 else b) for b in data)


def _s32(v: int) -> str:
    return str(v - (1 << 32) if v >= (1 << 31) else v)


# ---------------------------------------------------------------- 编译
@dataclass
class VmPlan:
    """构建期材料。rows 为 8×4 个 32 位随机整数（前四组与后四组异或成密钥材料）。

    emit_order[i] 表示「第 i 个 asset 解出的分片，应放回原序的第几位」。
    载荷在磁盘上是乱序的，这个重排关系只存在于 EMIT 指令的立即数里。
    """
    rows: list[list[int]]
    salt_root: bytes
    salt_part: bytes
    prefixes: list[bytes]        # 每片 3 字节：0x11 || uint16_be(片序号)
    want: bytes                  # 32 字节摘要；空表示跳过整体校验
    emit_order: list[int] | None = None
    seed: int = 0


def compile_program(plan: VmPlan) -> VmProgram:
    rng = random.Random(plan.seed or random.SystemRandom().randrange(1 << 30))
    n = len(plan.prefixes)
    verify = bool(plan.want)

    # --- 逻辑寄存器（用名字，最后统一置换到物理槽）
    names = ["kt", "mat", "sr", "root", "pfx", "node", "sp", "key",
             "blob", "pt", "dig", "want", "tmp", "tmp2"]
    R = {name: i for i, name in enumerate(names)}

    # --- 常量池（逻辑项，稍后打乱顺序并掩码）
    kt_blob = b"".join(v.to_bytes(4, "big") for row in plan.rows for v in row)
    pool_items: list[tuple[str, bytes]] = [("KT", kt_blob),
                                           ("SL", plan.salt_root),
                                           ("SP", plan.salt_part)]
    for i in range(n):
        pool_items.append(("PFX%d" % i, plan.prefixes[i]))
    pool_items.append(("WANT", plan.want if verify else bytes(32)))
    order = list(range(len(pool_items)))
    rng.shuffle(order)
    pos_of = {pool_items[i][0]: order.index(i) for i in range(len(pool_items))}

    mask = rng.randbytes(8)
    pool = [bytes(c ^ mask[i % 8] for i, c in enumerate(pool_items[j][1]))
            for j in order]

    P = {k: pos_of[k] for k in pos_of}
    ins: list[Instr] = []
    emit = ins.append

    emit(Instr("LOADC", dst=R["kt"], imm=P["KT"]))
    emit(Instr("KTXOR", dst=R["mat"], a=R["kt"]))
    emit(Instr("WIPE", a=R["kt"]))
    emit(Instr("LOADC", dst=R["sr"], imm=P["SL"]))
    emit(Instr("HMAC", dst=R["root"], a=R["sr"], b=R["mat"]))
    emit(Instr("WIPE", a=R["mat"]))
    emit(Instr("WIPE", a=R["sr"]))
    order = plan.emit_order if plan.emit_order is not None else list(range(n))
    if len(order) != n or sorted(order) != list(range(n)):
        raise ValueError("emit_order 必须是 0..n-1 的排列")

    for i in range(n):
        emit(Instr("LOADC", dst=R["pfx"], imm=P["PFX%d" % i]))
        emit(Instr("HMAC", dst=R["node"], a=R["root"], b=R["pfx"]))
        emit(Instr("LOADC", dst=R["sp"], imm=P["SP"]))
        emit(Instr("HMAC", dst=R["key"], a=R["node"], b=R["sp"]))
        emit(Instr("LOADA", dst=R["blob"], imm=i))
        emit(Instr("UNSEAL", dst=R["pt"], a=R["blob"], b=R["key"]))
        # 关键：EMIT 的立即数是「原序位置」，磁盘上是乱序的 asset，
        # 重排关系只写在这里，静态拿到一堆 .dat 也不知道该怎么拼回去。
        emit(Instr("EMIT", a=R["pt"], imm=order[i]))
        emit(Instr("WIPE", a=R["key"]))
        emit(Instr("WIPE", a=R["node"]))
        emit(Instr("WIPE", a=R["pfx"]))
        emit(Instr("WIPE", a=R["blob"]))
        emit(Instr("WIPE", a=R["sp"]))

    emit(Instr("WIPE", a=R["root"]))
    # 摘要按「原序」逐片喂入，与 Python 侧 want 的计算顺序严格一致
    emit(Instr("DIGINIT"))
    for j in range(n):
        emit(Instr("LOADPART", dst=R["tmp"], imm=j))
        emit(Instr("DIGADD", a=R["tmp"]))
        emit(Instr("WIPE", a=R["tmp"]))
    emit(Instr("DIGEND", dst=R["dig"]))
    if verify:
        emit(Instr("LOADC", dst=R["want"], imm=P["WANT"]))
        emit(Instr("EQ", a=R["dig"], b=R["want"]))
        emit(Instr("JZ", target="fail"))
    emit(Instr("HALT", imm=1))
    if verify:
        emit(Instr("HALT", imm=0, label="fail"))

    # --- 逻辑寄存器 → 物理槽位随机置换
    phys = list(range(NREGS))
    rng.shuffle(phys)
    rmap = {logical: phys[i] for i, logical in enumerate(R.values())}

    # --- 插入垃圾指令（不改变语义：NOP / 跳到紧邻下一条 / 空槽 MOVE / 空槽 WIPE）
    junk_count = 0
    if n:
        density = max(1, len(ins) // 8)
        out: list[Instr] = []
        for k, it in enumerate(ins):
            out.append(it)
            for _ in range(rng.randint(0, 2)):
                kind = rng.choice(("nop", "jmp", "move", "wipe"))
                spare_a = phys[len(R) + rng.randrange(NREGS - len(R))]
                spare_b = phys[len(R) + rng.randrange(NREGS - len(R))]
                if kind == "nop":
                    out.append(Instr("NOP", raw=True))
                elif kind == "jmp":
                    lab = "_j%d_%d" % (k, junk_count)
                    out.append(Instr("JMP", target=lab, raw=True))
                    out.append(Instr("NOP", label=lab, raw=True))
                elif kind == "move":
                    out.append(Instr("MOVE", dst=spare_a, a=spare_b, raw=True))
                else:
                    out.append(Instr("WIPE", a=spare_a, raw=True))
                junk_count += 1
        ins = out

    # --- 解析 label → 指令索引
    labels: dict[str, int] = {}
    for idx, it in enumerate(ins):
        if it.label:
            labels[it.label] = idx
    for it in ins:
        if it.op in ("JMP", "JZ"):
            if it.target not in labels:
                raise ValueError("未解析的跳转标签: " + it.target)
            it.imm = labels[it.target]

    # --- 操作码随机映射（unused 值保留为噪声，解释器遇到直接当 NOP）
    vals = list(range(256))
    rng.shuffle(vals)
    ops = {name: vals[i] for i, name in enumerate(LOGIC_OPS)}
    perm = list(range(len(LOGIC_OPS)))
    rng.shuffle(perm)
    ops_perm = {name: perm[i] for i, name in enumerate(LOGIC_OPS)}

    # --- 展平
    def slot(v: int, raw: bool) -> int:
        return v if raw else rmap[v]

    code: list[int] = []
    for it in ins:
        code += [ops[it.op],
                 slot(it.dst, it.raw), slot(it.a, it.raw),
                 slot(it.b, it.raw), it.imm]
    return VmProgram(code=code, pool=pool, mask=mask, ops=ops,
                     ops_perm=ops_perm, nparts=n, verify=verify, junk=junk_count)


# ---------------------------------------------------------------- Python 解释器
def _ktxor(blob: bytes) -> bytes:
    out = bytearray()
    for g in range(4):
        for i in range(4):
            a = int.from_bytes(blob[(g * 4 + i) * 4:(g * 4 + i) * 4 + 4], "big")
            b = int.from_bytes(blob[((g + 4) * 4 + i) * 4:((g + 4) * 4 + i) * 4 + 4], "big")
            out += (a ^ b).to_bytes(4, "big")
    return bytes(out)


def interp(prog: VmProgram, assets: list[bytes]) -> tuple[list[bytes], bool]:
    """Python 侧参考解释器。与 Java run() 语义一致，用于双端自测。"""
    code, pool, mask, ops = prog.code, prog.pool, prog.mask, prog.ops
    by_op = {v: k for k, v in ops.items()}
    s: list[bytes] = [EMPTY] * NREGS
    parts: list[bytes] = [EMPTY] * prog.nparts
    dig = None
    flag = True
    pc = 0
    nins = len(code) // W
    steps = 0
    while 0 <= pc < nins:
        steps += 1
        if steps > 100000:
            raise RuntimeError("VM 步数超限")
        op, dst, a, b, imm = code[pc * W:pc * W + W]
        name = by_op.get(op, "NOP")
        nxt = pc + 1
        if name == "LOADC":
            raw = pool[imm]
            s[dst] = bytes(c ^ mask[i % 8] for i, c in enumerate(raw))
        elif name == "LOADA":
            s[dst] = assets[imm] if 0 <= imm < len(assets) else EMPTY
        elif name == "LOADPART":
            s[dst] = parts[imm] if 0 <= imm < len(parts) else EMPTY
        elif name == "KTXOR":
            s[dst] = _ktxor(s[a])
        elif name == "HMAC":
            s[dst] = hmac.new(s[a], s[b], hashlib.sha256).digest()
        elif name == "UNSEAL":
            s[dst] = _stream.open_sealed(s[b], s[a])
        elif name == "CONCAT":
            s[dst] = s[a] + s[b]
        elif name == "SHA256":
            s[dst] = hashlib.sha256(s[a]).digest()
        elif name == "WIPE":
            s[a] = EMPTY
        elif name == "MOVE":
            s[dst] = s[a]
        elif name == "DIGINIT":
            dig = hashlib.sha256()
        elif name == "DIGADD":
            if dig is not None:
                dig.update(s[a])
        elif name == "DIGEND":
            s[dst] = dig.digest() if dig is not None else bytes(32)
        elif name == "EMIT":
            parts[imm] = s[a]
        elif name == "EQ":
            flag = hmac.compare_digest(s[a], s[b])
        elif name == "JZ":
            if not flag:
                nxt = imm
        elif name == "JMP":
            nxt = imm
        elif name == "HALT":
            return parts, bool(imm != 0)
        # NOP / 未知 opcode：什么都不做
        pc = nxt
    return parts, False


# ---------------------------------------------------------------- Java 解释器
JAVA_VM_TEMPLATE = r"""package @PKG@;

import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 通用字节码解释器。指令与常量均为构建期生成的数据，不含业务语义。 */
final class @CLS@ {
    interface Host { byte[] asset(int i); }

    private static byte[] unmask(byte[] p, byte[] m) {
        if (p.length == 0 || m.length == 0) return new byte[0];
        byte[] o = new byte[p.length];
        for (int i = 0; i < p.length; i++) o[i] = (byte) (p[i] ^ m[i % m.length]);
        return o;
    }

    private static byte[] ktxor(byte[] b) {
        if (b.length < 128) return new byte[0];
        byte[] out = new byte[64];
        int p = 0;
        for (int g = 0; g < 4; g++) {
            for (int i = 0; i < 4; i++) {
                int v = 0, w = 0;
                for (int k = 0; k < 4; k++) {
                    v = (v << 8) | (b[(g * 4 + i) * 4 + k] & 0xff);
                    w = (w << 8) | (b[((g + 4) * 4 + i) * 4 + k] & 0xff);
                }
                int x = v ^ w;
                out[p++] = (byte) (x >>> 24);
                out[p++] = (byte) (x >>> 16);
                out[p++] = (byte) (x >>> 8);
                out[p++] = (byte) x;
            }
        }
        return out;
    }

    private static byte[] mac(byte[] key, byte[] data) throws Throwable {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    private static byte[] unseal(byte[] blob, byte[] key) throws Throwable {
        if (blob == null || blob.length <= 24) return null;
        int len = blob.length - 24;
        byte[] nonce = Arrays.copyOfRange(blob, len, len + 16);
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        m.update((byte) 0x74);
        m.update(nonce);
        m.update(blob, 0, len);
        byte[] tg = m.doFinal();
        for (int j = 0; j < 8; j++) {
            if (tg[j] != blob[len + 16 + j]) return null;
        }
        byte[] out = new byte[len];
        byte[] ctr = new byte[8];
        long c = 0;
        int p = 0;
        while (p < len) {
            m.reset();
            m.update(nonce);
            for (int j = 7; j >= 0; j--) ctr[j] = (byte) (c >>> ((7 - j) * 8));
            m.update(ctr);
            byte[] b = m.doFinal();
            int take = Math.min(32, len - p);
            for (int j = 0; j < take; j++) out[p + j] = (byte) (blob[p + j] ^ b[j]);
            p += take;
            c++;
        }
        return out;
    }

    /** 返回解出的明文分片；校验失败或任何异常返回 null（由壳回落到直白路径）。 */
    static byte[][] run(int[] c, byte[][] pool, byte[] mask, int[] OPS, Host host) {
        byte[][] s = new byte[24][];
        for (int i = 0; i < s.length; i++) s[i] = new byte[0];
        byte[][] parts = new byte[@NPARTS@][];
        MessageDigest dig = null;
        boolean flag = true;
        int pc = 0, steps = 0;
        final int n = c.length / 5;
        try {
            while (pc >= 0 && pc < n) {
                if (++steps > 100000) return null;
                int op = c[pc * 5], d = c[pc * 5 + 1], a = c[pc * 5 + 2];
                int b = c[pc * 5 + 3], im = c[pc * 5 + 4];
                int nx = pc + 1;
                if (op == OPS[@OPS_LOADC@]) {
                    s[d] = unmask(pool[im], mask);
                } else if (op == OPS[@OPS_LOADA@]) {
                    s[d] = host.asset(im);
                    if (s[d] == null) s[d] = new byte[0];
                } else if (op == OPS[@OPS_LOADPART@]) {
                    // 必须复制：后面的 WIPE 会原地清零槽位，不能擦掉已输出的分片
                    byte[] q = parts[im];
                    s[d] = (q == null) ? new byte[0] : Arrays.copyOf(q, q.length);
                } else if (op == OPS[@OPS_KTXOR@]) {
                    s[d] = ktxor(s[a]);
                } else if (op == OPS[@OPS_HMAC@]) {
                    s[d] = mac(s[a], s[b]);
                } else if (op == OPS[@OPS_UNSEAL@]) {
                    byte[] r = unseal(s[a], s[b]);
                    if (r == null) return null;
                    s[d] = r;
                } else if (op == OPS[@OPS_CONCAT@]) {
                    byte[] x = s[a], y = s[b];
                    byte[] o = Arrays.copyOf(x, x.length + y.length);
                    System.arraycopy(y, 0, o, x.length, y.length);
                    s[d] = o;
                } else if (op == OPS[@OPS_SHA256@]) {
                    s[d] = MessageDigest.getInstance("SHA-256").digest(s[a]);
                } else if (op == OPS[@OPS_WIPE@]) {
                    Arrays.fill(s[a], (byte) 0);
                    s[a] = new byte[0];
                } else if (op == OPS[@OPS_MOVE@]) {
                    s[d] = s[a];
                } else if (op == OPS[@OPS_DIGINIT@]) {
                    dig = MessageDigest.getInstance("SHA-256");
                } else if (op == OPS[@OPS_DIGADD@]) {
                    if (dig != null) dig.update(s[a]);
                } else if (op == OPS[@OPS_DIGEND@]) {
                    s[d] = (dig != null) ? dig.digest() : new byte[32];
                } else if (op == OPS[@OPS_EMIT@]) {
                    // 复制一份：后续 WIPE 会原地清零槽位，不能让已输出的分片被擦掉
                    parts[im] = Arrays.copyOf(s[a], s[a].length);
                } else if (op == OPS[@OPS_EQ@]) {
                    flag = MessageDigest.isEqual(s[a], s[b]);
                } else if (op == OPS[@OPS_JZ@]) {
                    if (!flag) nx = im;
                } else if (op == OPS[@OPS_JMP@]) {
                    nx = im;
                } else if (op == OPS[@OPS_HALT@]) {
                    return im != 0 ? parts : null;
                }
                // NOP / 未知操作码：静默跳过
                pc = nx;
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }
}
"""


def java_source(prog: VmProgram, package: str, cls_name: str) -> str:
    src = (JAVA_VM_TEMPLATE
           .replace("@PKG@", package)
           .replace("@CLS@", cls_name)
           .replace("@NPARTS@", str(prog.nparts)))
    for name in LOGIC_OPS:
        src = src.replace("@OPS_%s@" % name, str(prog.ops_perm[name]))
    return src


def java_ops_literal(prog: VmProgram) -> str:
    return prog.as_java_ops()
