# -*- coding: utf-8 -*-
"""EXE SDK 标记：对标 VMP 的 ``VMProtectBegin/End`` 选择保护范围。

VMP 靠源码里的宏标记告诉构建器「哪段函数要虚拟化」。本项目里用
Python 装饰器 + 构建期 AST 扫描实现同样的效果：标记本身不改变运行
行为，只生成一份保护范围清单，交给 EXE 构建链决定虚拟化/变异强度。

用法::

    from cyrilshield.vmp.sdk import protect

    @protect(scope="vm", mutation=3)
    def check_serial(s):
        ...
"""
from __future__ import annotations

import ast
from dataclasses import dataclass, asdict

MARKER_ATTR = "__cyrilshield_protect__"
DECORATOR_NAMES = frozenset({"protect", "vmp_protect", "cyrilshield_protect"})


def protect(*, scope: str = "vm", mutation: int = 2,
            virtualize: bool = True, note: str = ""):
    """标记函数需要保护。装饰器本身是 no-op，不包裹、不改行为。"""
    if scope not in ("vm", "mutation", "skip"):
        raise ValueError("scope 必须是 vm/mutation/skip")
    if not 0 <= mutation <= 3:
        raise ValueError("mutation 必须是 0..3")

    def deco(fn):
        setattr(fn, MARKER_ATTR, {
            "scope": scope,
            "mutation": mutation,
            "virtualize": bool(virtualize),
            "note": note,
        })
        return fn
    return deco


@dataclass(frozen=True)
class Marker:
    name: str
    lineno: int
    scope: str
    mutation: int
    virtualize: bool
    note: str
    file: str

    def as_dict(self) -> dict:
        return asdict(self)


def _decorator_name(node: ast.expr) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    if isinstance(node, ast.Call):
        return _decorator_name(node.func)
    return ""


def _kwargs(call: ast.Call) -> dict:
    out: dict = {}
    for kw in call.keywords:
        if kw.arg is None:
            continue
        try:
            out[kw.arg] = ast.literal_eval(kw.value)
        except (ValueError, TypeError, SyntaxError):
            out[kw.arg] = None
    return out


def scan_markers(src: str, filename: str = "<source>") -> list[Marker]:
    """静态扫描源码里的保护标记，不执行被扫描的代码。"""
    try:
        tree = ast.parse(src, filename=filename)
    except SyntaxError:
        return []
    out: list[Marker] = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for dec in node.decorator_list:
            if _decorator_name(dec) not in DECORATOR_NAMES:
                continue
            opts = _kwargs(dec) if isinstance(dec, ast.Call) else {}
            out.append(Marker(
                name=node.name,
                lineno=int(getattr(node, "lineno", 0)),
                scope=str(opts.get("scope", "vm")),
                mutation=int(opts.get("mutation", 2) or 0),
                virtualize=bool(opts.get("virtualize", True)),
                note=str(opts.get("note", "") or ""),
                file=filename,
            ))
            break
    return sorted(out, key=lambda m: (m.file, m.lineno))


def protection_manifest(markers: list[Marker]) -> dict:
    """构建期消费的清单。"""
    by_scope: dict[str, list[str]] = {}
    for m in markers:
        by_scope.setdefault(m.scope, []).append(m.name)
    return {
        "schema": "cyrilshield.protection-manifest/1",
        "target": "PE",
        "count": len(markers),
        "max_mutation": max((m.mutation for m in markers), default=0),
        "by_scope": by_scope,
        "markers": [m.as_dict() for m in markers],
    }


def runtime_marker_source(markers: list[Marker]) -> str:
    """把清单作为常量注入壳内（供授权/自检读取，不参与控制流）。"""
    import json
    return "_CS_SCOPE = " + repr(json.dumps(
        protection_manifest(markers), sort_keys=True)) + "\n"
