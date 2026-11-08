# -*- coding: utf-8 -*-
"""EXE 授权系统：序列号 / HWID 绑定 / 到期 / 功能位 / 水印。

对标 VMP 的 licensing。硬件指纹来自 :mod:`cyrilshield.native_bridge`
（原生侧载层），这正是「Python 不能直接操作硬件」需要侧载架构的原因：
纯 Python 拿不到 SMBIOS UUID、卷序列号、CPUID 特性字这类稳定标识，
授权绑定就只能退化为易伪造的 ``uuid.getnode()``。

许可令牌是「明文载荷 + HMAC-SHA256 签名」，不加密、可离线校验、
不联网、不做在线激活。水印写在 PE overlay 尾部，Windows 加载器忽略，
不影响节区与校验和语义；**签名必须在写完水印之后进行**。
"""
from __future__ import annotations

import base64
import datetime as _dt
import hashlib
import hmac
import json
import secrets
from dataclasses import dataclass, field

from . import compat

TOKEN_VERSION = 1


class LicenseError(Exception):
    """许可校验失败。"""


@dataclass(frozen=True)
class LicensePolicy:
    product_id: str = "CYRILSHIELD-EXE"
    serial: str = ""
    features: tuple[str, ...] = ()
    expires_days: int = 0               # 0 = 不过期
    hwid_bound: bool = True

    def as_dict(self) -> dict:
        return {
            "product_id": self.product_id,
            "serial": self.serial,
            "features": list(self.features),
            "expires_days": self.expires_days,
            "hwid_bound": self.hwid_bound,
        }


@dataclass(frozen=True)
class LicenseResult:
    ok: bool
    reason: str = "ok"
    payload: dict = field(default_factory=dict)

    def as_dict(self) -> dict:
        return {"ok": self.ok, "reason": self.reason, "payload": self.payload}


def _b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode("ascii").rstrip("=")


def _b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _canonical(obj: dict) -> bytes:
    # 与 runtime_license_source 里的壳内校验保持同一套序列化规则
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _sign(payload: dict, secret: str) -> str:
    mac = hmac.new(secret.encode("utf-8"), _canonical(payload), hashlib.sha256)
    return mac.hexdigest()[:32]


def issue(policy: LicensePolicy, hwid_short: str, secret: str) -> str:
    """签发离线许可令牌。``hwid_short`` 取 ``HardwareId.short``。"""
    if not secret:
        raise LicenseError("签发许可必须提供 secret")
    now = _dt.datetime.now(_dt.timezone.utc)
    payload = {
        "v": TOKEN_VERSION,
        "p": policy.product_id,
        "s": policy.serial or secrets.token_hex(6).upper(),
        "h": hwid_short if policy.hwid_bound else "",
        "f": list(policy.features),
        "iat": int(now.timestamp()),
        "exp": int((now + _dt.timedelta(days=policy.expires_days)).timestamp())
               if policy.expires_days > 0 else 0,
        "n": secrets.token_hex(8),
    }
    raw = _canonical(payload)
    sig = _sign(payload, secret)
    return _b64e(raw) + "." + sig


def verify(token: str, secret: str, hwid_short: str,
           now: int | None = None) -> LicenseResult:
    """离线校验。失败原因明确区分，便于界面给出可操作的提示。"""
    try:
        body, sig = (token or "").split(".", 1)
        payload = json.loads(_b64d(body))
        if not isinstance(payload, dict):
            return LicenseResult(False, "payload_not_object")
        if payload.get("v") != TOKEN_VERSION:
            return LicenseResult(False, "unsupported_version")
        if not hmac.compare_digest(_sign(payload, secret), sig):
            return LicenseResult(False, "bad_signature")
        want = payload.get("h", "")
        if want and not hmac.compare_digest(want, hwid_short or ""):
            return LicenseResult(False, "hwid_mismatch")
        exp = int(payload.get("exp", 0) or 0)
        if exp:
            now = int(now if now is not None else _dt.datetime.now(_dt.timezone.utc).timestamp())
            if now > exp:
                return LicenseResult(False, "expired")
        return LicenseResult(True, "ok", payload)
    except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        return LicenseResult(False, f"malformed_token:{type(exc).__name__}")


# --------------------------------------------------------------------------
# 水印
# --------------------------------------------------------------------------
def watermark_payload(serial: str, hwid_short: str, issued: str = "") -> bytes:
    rec = {
        "vendor": "CyrilShield",
        "target": "PE",
        "serial": serial,
        "hwid": hwid_short,
        "issued": issued or _dt.date.today().isoformat(),
        "note": "protected build watermark; no tracking, no network",
    }
    return _canonical(rec)


def embed_watermark(exe_bytes: bytes, serial: str, hwid_short: str) -> bytes:
    """把水印追加到 PE overlay；调用方随后再做数字签名。"""
    return compat.overlay_append(exe_bytes, watermark_payload(serial, hwid_short))


def extract_watermark(exe_bytes: bytes) -> dict | None:
    raw = compat.overlay_read(exe_bytes)
    if raw is None:
        return None
    try:
        obj = json.loads(raw.decode("utf-8"))
        return obj if isinstance(obj, dict) else None
    except (ValueError, UnicodeDecodeError):
        return None


def shell_hwid() -> str:
    """构建端 / 壳内共用的纯标准库硬件指纹（32 hex）。

    PyInstaller 打出的独立外壳里没有 ``cyrilshield.*``，也没有侧载 DLL，
    所以壳内只能用标准库采集一个稳定的机器标识。工具端（有侧载层时）
    可用 ``HardwareId.short`` 得到更强、更难伪造的指纹并传入签发函数。
    """
    import os
    import platform
    import uuid
    raw = "|".join((
        f"{uuid.getnode():012x}",
        os.environ.get("COMPUTERNAME", ""),
        platform.machine(),
        platform.system(),
    )).encode("utf-8", "replace")
    return hashlib.sha256(raw).hexdigest()[:32]


def runtime_license_source(token: str, secret: str,
                           hwid_expr: str = "_cl_hwid()",
                           fail_stmt: str = "raise SystemExit(1)") -> str:
    """生成壳内离线校验源码（自包含，只依赖标准库）。"""
    return f'''
# ---- CyrilShield license check (generated) ----
import hmac as _clh, hashlib as _clH, json as _clj, base64 as _clb, time as _clt
import os as _clo, platform as _clp, uuid as _clu
_CL_TOKEN = {token!r}
_CL_SECRET = {secret!r}

def _cl_hwid():
    raw = "|".join((f"{{_clu.getnode():012x}}", _clo.environ.get("COMPUTERNAME", ""),
                    _clp.machine(), _clp.system())).encode("utf-8", "replace")
    return _clH.sha256(raw).hexdigest()[:32]

_CL_HWID = str({hwid_expr})

def _cl_verify(tok, secret, hwid):
    try:
        body, sig = tok.split(".", 1)
        raw = _clb.urlsafe_b64decode(body + "=" * (-len(body) % 4))
        obj = _clj.loads(raw.decode("utf-8"))
        canon = _clj.dumps(obj, sort_keys=True, separators=(",", ":")).encode()
        want = _clh.new(secret.encode(), canon, _clH.sha256).hexdigest()[:32]
        if not _clh.compare_digest(want, sig):
            return False
        if obj.get("h") and not _clh.compare_digest(str(obj["h"]), hwid):
            return False
        exp = int(obj.get("exp", 0) or 0)
        if exp and int(_clt.time()) > exp:
            return False
        return True
    except Exception:
        return False

if not _cl_verify(_CL_TOKEN, _CL_SECRET, _CL_HWID):
    {fail_stmt}
# ---- end license check ----
'''
