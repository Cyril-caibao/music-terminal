# -*- coding: utf-8 -*-
"""VMP 能力对标层（除「多平台」外的完整能力矩阵）。

设计立场
--------
* 面向使用者**自己拥有合法权利**的程序，目标是提高逆向与篡改的门槛；
* 「免杀强度」在本项目中严格定义为 **AV compatibility / 减少误报与
  静态特征命中**（加壳产物被启发式引擎误判是行业普遍痛点），
  不实现针对安全产品的主动规避、行为隐藏或检测绕过；
* 与 ``antitamper.py`` 的既有边界一致：不做 AMSI/ETW 补丁、不做进程
  注入、不做内核驱动、不做 DLL 劫持。

按用户要求，**多平台**（Windows x86/x64 + .NET + ARM64 + macOS + Linux
全矩阵）明确排除；目标平台聚焦 Windows x64 PE 与现有 APK 链路，
Linux 仅作为开发自检环境。

VMP 是十余年积累的商业产品，「全面对标」指**能力项齐全 + 架构可扩展**，
不代表单点强度已与商业壳等价；矩阵里 ``state`` 字段如实标注落地程度。
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict

__all__ = [
    "VMPProfile", "CAPABILITY_MATRIX", "parity_report",
    "excluded_capabilities", "profile_from_dict",
]

# state: implemented(已落地可自测) | strengthened(本轮增强) | partial(有基础/需后续)
#       | roadmap(架构已留位/需专项工程)
CAPABILITY_MATRIX: tuple[dict, ...] = (
    dict(key="virtualization", vmp="代码虚拟化（x86→自定义字节码 VM）",
         state="implemented", module="vmgen.py",
         note="壳引导链全程由自定义 VM 解释：随机操作码、字段排列、角色寄存器轮转、垃圾指令；"
              "本轮未改解释器内核，新增的是壳源码级混淆层与完整性链（见下列项）"),
    dict(key="mutation", vmp="代码变异 / 等价指令替换",
         state="implemented", module="vmp/obfuscation.py",
         note="死代码块、大整数异或分裂、常量编码，每次构建不同"),
    dict(key="string_encryption", vmp="字符串与常量加密",
         state="implemented", module="vmp/obfuscation.py",
         note="字面量加盐 + 标签派生密钥流编码，stub 里只剩 base85 串，运行时解码"),
    dict(key="import_protection", vmp="导入表保护 / 动态解析",
         state="implemented", module="vmp/obfuscation.py",
         note="编码名 + importlib 延迟解析，避免可疑 API 组合直接出现在静态视图"),
    dict(key="anti_debug", vmp="反调试（多探针）",
         state="strengthened", module="antitamper.py",
         note="5 类探针：基础 API / 远程调试 / 调试端口与对象 / 硬件断点 Dr0-Dr3 / 单步计时；默认关闭"),
    dict(key="anti_vm", vmp="反虚拟机 / 反沙箱",
         state="strengthened", module="antitamper.py",
         note="6 类判据：进程 / 用户名 / 虚拟化驱动文件 / 规格 / CPU 品牌 / 显示规格；默认关闭"),
    dict(key="memory_protection", vmp="内存保护 / 抗 dump",
         state="partial", module="vmp/memory.py",
         note="分块独立解密、明文用完即擦、delete-on-close 缩短落盘窗口；"
              "真·不落地需驱动级方案，本项目不做"),
    dict(key="integrity", vmp="完整性自校验（CRC / 自校验链）",
         state="strengthened", module="vmp/integrity.py",
         note="由单次摘要升级为双分块哈希链（4096/7777）+ 交叉校验 + 壳内运行时自校验"),
    dict(key="licensing", vmp="授权系统（序列号 / HWID / 到期 / 功能位）",
         state="implemented", module="vmp/license.py, native_bridge.py",
         note="HMAC-SHA256 离线许可 + 侧载层/标准库 HWID 绑定 + 到期与功能位，不联网"),
    dict(key="watermark", vmp="水印（追踪泄露副本）",
         state="implemented", module="vmp/license.py, vmp/compat.py",
         note="带魔数的水印记录随 --add-data 打进包并写入构建清单；独立 PE 可用 overlay 嵌入"),
    dict(key="sdk_markers", vmp="SDK 标记（Begin/End 选择保护范围）",
         state="implemented", module="vmp/sdk.py",
         note="Python 装饰器 + AST 静态扫描，生成保护范围清单并注入壳内"),
    dict(key="compression", vmp="加壳压缩",
         state="implemented", module="upx.py",
         note="内置 UPX 5.3.0，1–9 / best / brute / LZMA"),
    dict(key="polymorphism", vmp="多态（每次构建不同、无稳定特征）",
         state="strengthened", module="vmp/polymorph.py",
         note="opcode / 槽位 / 符号名 / 盐 / 编码串全量随机，输出构建指纹并自检互异"),
    dict(key="av_compatibility", vmp="（本项目扩展）降低误报与静态特征命中",
         state="implemented", module="vmp/compat.py",
         note="合法版本资源（--version-file）、图标沿用、熵与节名规范、W^X 审计、误报申诉清单"),
)

EXCLUDED = (
    dict(key="multi_platform", vmp="Windows x86/x64 + .NET + ARM64 + macOS + Linux 全矩阵",
         reason="用户明确排除；聚焦 Windows x64 PE 与既有 APK 链路"),
)


@dataclass
class VMPProfile:
    """一次构建的保护配置。默认值 = 对标 VMP 的推荐强度。"""
    virtualization: str = "full"        # off | standard | full
    mutation_level: int = 2             # 0..3
    string_encryption: bool = True
    import_protection: bool = True
    integrity: str = "chain"            # off | basic | chain
    anti_debug: bool = False            # 有误伤风险，默认关
    anti_vm: bool = False
    memory: str = "chunked"             # off | basic | chunked
    licensing: bool = False
    license_secret: str = ""
    license_expires: str = ""           # ISO 日期，空 = 永不过期
    license_features: tuple[str, ...] = ()
    watermark: str = ""
    av_compatibility: bool = True
    polymorphism: bool = True
    product_name: str = "CyrilShield Protected Application"
    company_name: str = "CyrilShield"
    file_version: str = "1.0.0.0"
    extras: dict = field(default_factory=dict)

    def validate(self) -> None:
        if self.virtualization not in ("off", "standard", "full"):
            raise ValueError("virtualization 必须是 off/standard/full")
        if not 0 <= self.mutation_level <= 3:
            raise ValueError("mutation_level 必须是 0..3")
        if self.integrity not in ("off", "basic", "chain"):
            raise ValueError("integrity 必须是 off/basic/chain")
        if self.memory not in ("off", "basic", "chunked"):
            raise ValueError("memory 必须是 off/basic/chunked")
        if self.licensing and not self.license_secret:
            raise ValueError("启用 licensing 时必须提供 license_secret")

    def as_dict(self) -> dict:
        d = asdict(self)
        d["license_features"] = list(self.license_features)
        return d

    def enabled_keys(self) -> list[str]:
        """返回本配置实际启用的能力 key。"""
        out = []
        if self.virtualization != "off":
            out.append("virtualization")
        if self.mutation_level:
            out.append("mutation")
        if self.string_encryption:
            out.append("string_encryption")
        if self.import_protection:
            out.append("import_protection")
        if self.anti_debug:
            out.append("anti_debug")
        if self.anti_vm:
            out.append("anti_vm")
        if self.memory != "off":
            out.append("memory_protection")
        if self.integrity != "off":
            out.append("integrity")
        if self.licensing:
            out += ["licensing"]
        if self.watermark:
            out.append("watermark")
        if self.av_compatibility:
            out.append("av_compatibility")
        if self.polymorphism:
            out.append("polymorphism")
        out += ["sdk_markers", "compression"]
        return out


def profile_from_dict(d: dict) -> VMPProfile:
    """从前端 JSON 构建配置，忽略未知键并做类型收敛。"""
    known = {f for f in VMPProfile.__dataclass_fields__}
    clean = {k: v for k, v in (d or {}).items() if k in known}
    if "license_features" in clean and clean["license_features"] is not None:
        clean["license_features"] = tuple(str(x) for x in clean["license_features"])
    p = VMPProfile(**clean)
    p.validate()
    return p


def excluded_capabilities() -> list[dict]:
    return [dict(x) for x in EXCLUDED]


def parity_report(profile: VMPProfile | None = None) -> str:
    """生成 Markdown 对标表，供文档与 UI 使用。"""
    p = profile or VMPProfile()
    enabled = set(p.enabled_keys())
    lines = [
        "| key | VMP 能力项 | 对标状态 | 本配置 | 落地模块 | 说明 |",
        "|---|---|---|---|---|---|",
    ]
    for row in CAPABILITY_MATRIX:
        used = "✔" if row["key"] in enabled else "—"
        lines.append(
            f"| `{row['key']}` | {row['vmp']} | `{row['state']}` | {used} "
            f"| `{row['module']}` | {row['note']} |"
        )
    lines.append("")
    lines.append("**明确排除**：" + "；".join(
        f"{e['vmp']}（{e['reason']}）" for e in EXCLUDED))
    return "\n".join(lines)
