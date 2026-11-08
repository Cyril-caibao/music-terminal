"""APK 加固：dex 分片交错加密 + 引导链 VM 虚拟化 + 壳 Application + 重签名。

依赖本机 Android 工具链（JDK / build-tools / platform），启动时自动探测。
设计目标：加固后的 APK 能被系统正常安装启动，业务代码行为不变。

防护构成（全部为对自己应用做保护，不含任何规避杀软 / 免杀手段）：
  * 全部 classes*.dex 拼成流后切成多片，跨文件乱序存进随机 assets 路径，
    每片独立 nonce + 独立子密钥；重排关系只写在 VM 字节码的立即数里
  * 载荷容器不带固定头部，nonce 与校验码附在尾部，任意一位被改即拒绝解密
  * 「重建密钥材料 → 派生子密钥 → 逐片解封 → 摘要判定」编译成自定义字节码，
    由壳内通用 VM 解释器执行（opcode / 槽位 / 常量池 / 垃圾指令每次随机）；
    壳 dex 里不再有可直读的 HMAC 调用链，详见 dexvm.py
  * 壳自身方法名随机化、反射用字符串常量 XOR 编码，消除固定工具指纹
  * 明文用 direct buffer 交给 InMemoryDexClassLoader 后，Java 堆里的明文
    分片 / 拼接缓冲 / dex 副本逐一律零
  * 可选：签名校验（防重打包）、反调试与反注入框架检测、反模拟器
  * Android 8.0+ 使用 InMemoryDexClassLoader，API 29+ 整包内存加载不落盘
  * 包名 / 类名 / 资产路径 / 密钥分片 / 签名别名每次构建随机

边界：虚拟化的是**壳的引导逻辑**，不是业务 dex 的 Dalvik 字节码。
详见 docs/APK-SECURITY-NOTES.md。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import random
import re
import shutil
import struct
import string
import subprocess
import sys
import tempfile
import time
import zipfile
import zlib
from dataclasses import dataclass, field

from . import _stream, axml, dexvm


class ApkError(Exception):
    pass


# ---------------------------------------------------------------- 工具链
def _sdk_roots():
    roots = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(env)
        if v and os.path.isdir(v):
            roots.append(v)
    local = os.environ.get("LOCALAPPDATA")
    if local:
        roots.append(os.path.join(local, "Android", "Sdk"))
    home = os.path.expanduser("~")
    roots += [
        os.path.join(home, "AppData", "Local", "Android", "Sdk"),
        os.path.join(home, "Android", "Sdk"),
        r"C:\Android\Sdk",
        r"C:\Android\android-sdk",
        "/usr/lib/android-sdk",
        os.path.join(home, "Library", "Android", "sdk"),
    ]
    seen, out = set(), []
    for r in roots:
        r = os.path.normpath(os.path.abspath(r))
        if r not in seen and os.path.isdir(r):
            seen.add(r)
            out.append(r)
    return out


def _vkey(name):
    return [int(x) for x in re.findall(r"\d+", name)] or [0]


def bundled_toolchain() -> str:
    """随程序分发的内置工具链根目录，没有则返回空字符串。

    便携版把精简 JRE + build-tools 子集 + android.jar 放在 toolchain/ 下，
    与程序同目录（源码运行时在项目根目录），优先级高于任何系统安装。
    """
    bases = []
    # 插件形态：本文件位于 <vendor>/cyrilshield/apk.py，先看插件自带的 vendor/toolchain
    bases.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    meipass = getattr(sys, "_MEIPASS", "")
    if meipass:
        bases.append(meipass)
    if getattr(sys, "frozen", False):
        bases.append(os.path.dirname(os.path.abspath(sys.executable)))
    for b in bases:
        p = os.path.join(b, "toolchain")
        if os.path.isdir(os.path.join(p, "jre", "bin")):
            return p
    return ""


def _tools_from_bundled(root: str) -> dict:
    """按内置工具链的固定布局填充工具信息。"""
    bin_dir = os.path.join(root, "jre", "bin")
    bt = os.path.join(root, "android", "build-tools")
    info = {"ok": False, "missing": [], "sdk": os.path.join(root, "android"),
            "build_tools": bt, "platform": "", "javac": os.path.join(bin_dir, "javac.exe"),
            "java": os.path.join(bin_dir, "java.exe"),
            "keytool": os.path.join(bin_dir, "keytool.exe"),
            "d8": os.path.join(bt, "d8.bat" if os.name == "nt" else "d8"),
            "android_jar": "", "apksigner": os.path.join(bt, "apksigner.bat" if os.name == "nt" else "apksigner"),
            "zipalign": os.path.join(bt, "zipalign.exe" if os.name == "nt" else "zipalign"),
            "aapt2": "", "java_home": os.path.join(root, "jre"), "bundled": True}

    platforms = os.path.join(root, "android", "platforms")
    if os.path.isdir(platforms):
        cands = [d for d in os.listdir(platforms)
                 if os.path.isfile(os.path.join(platforms, d, "android.jar"))]
        for plat in sorted(cands, key=_vkey, reverse=True):
            info["platform"] = plat
            info["android_jar"] = os.path.join(platforms, plat, "android.jar")
            break

    for key, label in (("javac", "JDK（javac）"), ("d8", "Android build-tools（d8）"),
                       ("android_jar", "Android platform（android.jar）"),
                       ("apksigner", "apksigner"), ("zipalign", "zipalign"),
                       ("keytool", "keytool")):
        if not os.path.isfile(info[key]) and not os.path.isdir(info[key]):
            info["missing"].append(label)
    info["ok"] = not info["missing"]
    return info


def find_tools() -> dict:
    """返回工具链信息；``ok`` 表示可以完成完整加固。"""
    root = bundled_toolchain()
    if root:
        info = _tools_from_bundled(root)
        if info["ok"]:
            return info

    info = {"ok": False, "missing": [], "sdk": "", "build_tools": "",
            "platform": "", "javac": "", "java": "", "d8": "", "android_jar": "",
            "apksigner": "", "zipalign": "", "aapt2": "", "java_home": "",
            "keytool": "", "bundled": False}

    java = shutil.which("javac")
    if java:
        info["javac"] = java
        info["java_home"] = os.path.dirname(os.path.dirname(java))
    jre = shutil.which("java")
    if jre:
        info["java"] = jre
    kt = shutil.which("keytool")
    if kt:
        info["keytool"] = kt

    for root in _sdk_roots():
        bt_dir = os.path.join(root, "build-tools")
        if os.path.isdir(bt_dir):
            versions = sorted((d for d in os.listdir(bt_dir)
                               if os.path.isdir(os.path.join(bt_dir, d))), key=_vkey)
            for ver in reversed(versions):
                base = os.path.join(bt_dir, ver)
                d8 = os.path.join(base, "d8.bat" if os.name == "nt" else "d8")
                apk_signer = os.path.join(base, "apksigner.bat" if os.name == "nt" else "apksigner")
                zip_align = os.path.join(base, "zipalign.exe" if os.name == "nt" else "zipalign")
                if os.path.isfile(d8) and os.path.isfile(apk_signer) and os.path.isfile(zip_align):
                    info.update(sdk=root, build_tools=base, d8=d8,
                                apksigner=apk_signer, zipalign=zip_align)
                    aapt2 = os.path.join(base, "aapt2.exe" if os.name == "nt" else "aapt2")
                    if os.path.isfile(aapt2):
                        info["aapt2"] = aapt2
                    break
        platforms = os.path.join(root, "platforms")
        if os.path.isdir(platforms):
            cands = [d for d in os.listdir(platforms)
                     if os.path.isfile(os.path.join(platforms, d, "android.jar"))]
            for plat in sorted(cands, key=_vkey, reverse=True):
                info["platform"] = plat
                info["android_jar"] = os.path.join(platforms, plat, "android.jar")
                break
        if info["build_tools"] and info["android_jar"]:
            break

    for key, label in (("javac", "JDK（javac）"), ("d8", "Android build-tools（d8）"),
                       ("android_jar", "Android platform（android.jar）"),
                       ("apksigner", "apksigner"), ("zipalign", "zipalign"),
                       ("keytool", "keytool")):
        if not info[key]:
            info["missing"].append(label)
    info["ok"] = not info["missing"]
    return info


def _no_window():
    if os.name == "nt":
        return subprocess.CREATE_NO_WINDOW
    return 0


def _decode(raw: bytes) -> str:
    """工具链输出解码：优先 UTF-8，退回本地代码页。"""
    if not raw:
        return ""
    for enc in ("utf-8", "gbk", "mbcs"):
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("latin-1", "replace")


def _run(cmd, cwd=None, timeout=900, log=None):
    if log:
        log("$ " + " ".join(os.path.basename(str(c)) if i == 0 else str(c)
                            for i, c in enumerate(cmd)))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, timeout=timeout,
                       creationflags=_no_window())
    out = _decode(p.stdout or b"")
    err = _decode(p.stderr or b"")
    if p.returncode != 0 and log:
        tail = (out + err).strip().splitlines()[-8:]
        for line in tail:
            log("  " + line.strip(), "WARN")
    return p.returncode, (out + err).strip()


# ---------------------------------------------------------------- DEX 解析
def dex_header(data: bytes) -> dict:
    if len(data) < 112 or data[:4] != b"dex\n":
        raise ApkError("不是有效的 dex 文件")
    ver = data[4:7].decode("ascii", "replace").strip("\x00")
    (checksum,) = struct.unpack_from("<I", data, 8)
    file_size, header_size = struct.unpack_from("<II", data, 32)
    string_ids, = struct.unpack_from("<I", data, 56)
    type_ids, = struct.unpack_from("<I", data, 64)
    proto_ids, = struct.unpack_from("<I", data, 72)
    field_ids, = struct.unpack_from("<I", data, 80)
    method_ids, = struct.unpack_from("<I", data, 88)
    class_defs, = struct.unpack_from("<I", data, 96)
    return {"version": ver, "file_size": file_size, "strings": string_ids,
            "types": type_ids, "protos": proto_ids, "fields": field_ids,
            "methods": method_ids, "classes": class_defs,
            "checksum_ok": (zlib.adler32(data[12:]) & 0xFFFFFFFF) == checksum}


# ---------------------------------------------------------------- 签名信息
SIG_FILE_RE = re.compile(r"^META-INF/[^/]+\.(SF|RSA|DSA|EC)$", re.I)


def _signature_info(path, tools, log=None) -> dict:
    out = {"v1": False, "v2": False, "v3": False, "dn": "", "sha256": "",
           "schemes": [], "valid": None}
    try:
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
    except Exception:
        return out
    out["v1"] = any(SIG_FILE_RE.match(n) for n in names)
    try:
        with open(path, "rb") as fh:
            fh.seek(0, 2)
            size = fh.tell()
            fh.seek(max(0, size - 65536 - 32))
            tail = fh.read()
        out["v2"] = b"APK Sig Block 42" in tail
    except Exception:
        pass
    apk = tools.get("apksigner") if tools else None
    if apk and os.path.isfile(apk):
        rc, txt = _run([apk, "verify", "--print-certs", path], timeout=180)
        for line in txt.splitlines():
            low = line.lower()
            if "certificate dn" in low:
                out["dn"] = line.split(":", 1)[1].strip()
            elif "sha-256 digest" in low:
                out["sha256"] = line.split(":", 1)[1].strip()
            elif "verified using v1 scheme" in low:
                out["v1"] = "true" in low
            elif "verified using v2 scheme" in low:
                out["v2"] = "true" in low
            elif "verified using v3 scheme" in low:
                out["v3"] = "true" in low
        out["valid"] = rc == 0
    schemes = [n for n, k in (("v1", "v1"), ("v2", "v2"), ("v3", "v3")) if out[k]]
    out["schemes"] = schemes
    return out


# ---------------------------------------------------------------- 加固特征
PACKER_MARKS = [
    ("360 加固", ("libjiagu.so", "libjiagu_art.so", "libjiagu_x86.so", "com/qihoo/util/")),
    ("腾讯乐固", ("libshell.so", "libshella.so", "com/tencent/StubShell", "0OO00l111l1l")),
    ("梆梆加固", ("libsecexe.so", "libsecmain.so", "libDexHelper.so", "com/secneo/apkwrapper")),
    ("爱加密", ("libexec.so", "libexecmain.so", "com/shell/MyWrapperProxyApplication", "ijiami.dat")),
    ("娜迦加固", ("libchaosvmp.so", "libddog.so", "libfdog.so", "libnagain")),
    ("阿里聚安全", ("libmobisec.so", "aliprotect.dat", "libfakejni.so")),
    ("百度加固", ("libbaiduprotect.so", "com/baidu/protect")),
    ("网易易盾", ("libnesec.so", "libyidun.so", "com/netease/nis")),
    ("通付盾", ("libegis.so", "com/payegis")),
    ("腾讯御安全", ("libtup.so", "com/tencent/wxpay")),
]


def inspect(path: str, tools=None) -> dict:
    """解析 APK 结构、清单、签名与加固特征。"""
    if not os.path.isfile(path):
        raise ApkError("文件不存在")
    size = os.path.getsize(path)
    try:
        zf = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        raise ApkError("不是有效的 APK（zip 解析失败）")

    with zf:
        names = zf.namelist()
        if "AndroidManifest.xml" not in names:
            raise ApkError("APK 内缺少 AndroidManifest.xml")
        raw_manifest = zf.read("AndroidManifest.xml")
        try:
            doc = axml.Document.parse(raw_manifest)
            info = axml.manifest_info(doc)
        except axml.AxmlError as exc:
            raise ApkError(f"清单解析失败：{exc}")

        dexes = []
        for n in names:
            if re.match(r"^classes\d*\.dex$", n):
                data = zf.read(n)
                try:
                    h = dex_header(data)
                except ApkError:
                    h = {}
                h.update(name=n, size=len(data))
                dexes.append(h)
        dexes.sort(key=lambda d: d["name"])

        abis = {}
        for n in names:
            m = re.match(r"^lib/([^/]+)/(.+)$", n)
            if m:
                abis.setdefault(m.group(1), []).append(m.group(2))

        assets = [n for n in names if n.startswith("assets/") and not n.endswith("/")]
        packers = []
        joined = "\n".join(names)
        for label, marks in PACKER_MARKS:
            if any(m in joined for m in marks):
                packers.append(label)

        total_dex = sum(d.get("size", 0) for d in dexes)
        entry = {"count": len(names), "dex_bytes": total_dex,
                 "asset_count": len(assets), "compressed": size}

    sig = _signature_info(path, tools or find_tools())
    warnings = []
    if info["debuggable"]:
        warnings.append("清单开启了 android:debuggable")
    if info["allow_backup"]:
        warnings.append("允许 adb backup（android:allowBackup=true）")
    if not dexes:
        warnings.append("未发现 classes.dex")
    if info["min_sdk"] and info["min_sdk"] < 21:
        warnings.append(f"minSdkVersion={info['min_sdk']}，低于 21 的机型兼容性风险较高")
    if packers:
        warnings.append("检测到已有加固特征：" + "、".join(packers))

    return {
        "name": os.path.basename(path), "size": size,
        "package": info["package"], "version_name": info["version_name"],
        "version_code": info["version_code"], "min_sdk": info["min_sdk"],
        "target_sdk": info["target_sdk"], "app_class": info["app_class"],
        "label": info["label"], "permissions": info["permissions"],
        "activities": info["activities"], "services": info["services"],
        "receivers": info["receivers"], "providers": info["providers"],
        "debuggable": info["debuggable"], "allow_backup": info["allow_backup"],
        "dexes": dexes, "abis": {k: len(v) for k, v in abis.items()},
        "entry": entry, "signature": sig, "packers": packers,
        "warnings": warnings,
    }


# ---------------------------------------------------------------- 壳源码
STUB_TEMPLATE = r"""package @PKG@;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;

public class @CLS@ extends Application {
    // 引导链已被编译成字节码：这里只有数据，没有可直读的解密/派生逻辑
    private static final int[] CODE = new int[]{@CODE@};
    private static final byte[][] POOL = new byte[][]{@POOL@};
    private static final byte[] MASK = @MASK@;
    private static final int[] OPS = new int[]{@OPS@};
    private static final byte[][] ASSETS = new byte[][]{@ASSETS@};
    private static final byte[] AK = new byte[]{@AK@};
    private static final int[] LENS = new int[]{@LENS@};
    private static final byte[] RS = new byte[]{@RS@};
    private static final byte[] RK = new byte[]{@RK@};
    private static final byte[] SG = new byte[]{@SG@};
    private static final byte[] SK = new byte[]{@SK@};
    private static final int MODE = @MODE@;
    private static final String DIR = "@DIR@";

    private Application inner;
    private boolean ready;

    //DBG private static void dbg(String s) { android.util.Log.i("PSH", s); }

    private static String dec(byte[] s, byte[] k) {
        if (s.length == 0 || k.length == 0) return "";
        byte[] b = new byte[s.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (s[i] ^ k[i % k.length]);
        return new String(b);
    }

    private static byte[] decb(byte[] s, byte[] k) {
        if (s.length == 0 || k.length == 0) return new byte[0];
        byte[] b = new byte[s.length];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (s[i] ^ k[i % k.length]);
        return b;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            boot(base);
        } catch (Throwable t) {
            ready = false;
            //DBG dbg("boot fail: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        String real = dec(RS, RK);
        if (!ready || real.length() == 0) return;
        try {
            Application app = build(real);
            if (app == null) return;
            inner = app;
            swap(app);
            app.onCreate();
            //DBG dbg("delegate onCreate done");
        } catch (Throwable t) {
            //DBG dbg("delegate fail: " + t);
        }
    }

    private void boot(Context ctx) throws Throwable {
        // bit0 反调试 / bit2 反模拟器都走环境检测，任一开启即执行
        if ((MODE & 5) != 0 && !envOk()) return;
        if ((MODE & 2) != 0 && !sigOk(ctx)) return;

        final Context c = ctx;
        byte[][] parts = @VMCLS@.run(CODE, POOL, MASK, OPS, new @VMCLS@.Host() {
            public byte[] asset(int i) {
                try {
                    InputStream in = c.getAssets().open(dec(ASSETS[i], AK));
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] t = new byte[8192];
                    int n;
                    while ((n = in.read(t)) > 0) bo.write(t, 0, n);
                    in.close();
                    return bo.toByteArray();
                } catch (Throwable x) {
                    return null;
                }
            }
        });
        // VM 失败（校验不符 / 载荷被改 / 解释器异常）直接放弃加载。
        // 不保留明文的"回落解密路径"：那会让反编译者绕过整个虚拟化。
        // VM 类是纯 Java、不依赖任何 Android API，已在 JDK 上与 Python 侧双端验证。
        if (parts == null) return;

        // 拼回连续流，再按 LENS 切回各 dex；随后擦掉 Java 堆里的所有明文副本
        int total = 0;
        for (int i = 0; i < parts.length; i++) total += parts[i].length;
        byte[] all = new byte[total];
        int p = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, all, p, parts[i].length);
            p += parts[i].length;
            Arrays.fill(parts[i], (byte) 0);
        }
        parts = null;
        byte[][] dexes = new byte[LENS.length][];
        int q = 0;
        for (int i = 0; i < LENS.length; i++) {
            dexes[i] = Arrays.copyOfRange(all, q, q + LENS[i]);
            q += LENS[i];
        }
        Arrays.fill(all, (byte) 0);
        ClassLoader extra = load(ctx, dexes);
        for (int i = 0; i < dexes.length; i++) {
            Arrays.fill(dexes[i], (byte) 0);
        }
        dexes = null;
        if (extra == null) return;
        inject(extra);
        ready = true;
        //DBG dbg("ready");
    }

    private ClassLoader load(Context ctx, byte[][] dexes) throws Throwable {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 29) {
            // direct buffer 是独立内存，擦除上面的 Java 数组不会影响已加载的 dex
            java.nio.ByteBuffer[] bufs = new java.nio.ByteBuffer[dexes.length];
            for (int i = 0; i < dexes.length; i++) {
                java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(dexes[i].length);
                b.put(dexes[i]);
                b.flip();
                bufs[i] = b;
            }
            return new dalvik.system.InMemoryDexClassLoader(bufs, getClassLoader());
        } else if (sdk >= 26 && dexes.length == 1) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(dexes[0].length);
            b.put(dexes[0]);
            b.flip();
            return new dalvik.system.InMemoryDexClassLoader(b, getClassLoader());
        } else {
            // API 21-25：必须先落盘再交给 DexClassLoader
            File dir = new File(ctx.getCodeCacheDir(), DIR);
            if (!dir.exists()) dir.mkdirs();
            StringBuilder cp = new StringBuilder();
            for (int i = 0; i < dexes.length; i++) {
                File f = new File(dir, DIR + i + ".d");
                if (f.exists()) f.delete();
                FileOutputStream fo = new FileOutputStream(f);
                fo.write(dexes[i]);
                fo.flush();
                fo.close();
                f.setReadOnly();
                if (i > 0) cp.append(File.pathSeparator);
                cp.append(f.getAbsolutePath());
            }
            return new dalvik.system.DexClassLoader(
                    cp.toString(), dir.getAbsolutePath(), null, getClassLoader());
        }
    }

    private boolean sigOk(Context ctx) {
        try {
            byte[] want = decb(SG, SK);
            if (want.length != 32) return true;
            PackageManager pm = ctx.getPackageManager();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                // 反射取 signingInfo：避免编译期依赖 API 28 的类/常量，
                // 低版本 android.jar 也能编译通过
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0x08000000);
                Object si = pi.getClass().getField("signingInfo").get(pi);
                sigs = (Signature[]) si.getClass()
                        .getMethod("getApkContentsSigners").invoke(si);
            } else {
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                        PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null) return true;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < sigs.length; i++) {
                if (MessageDigest.isEqual(md.digest(sigs[i].toByteArray()), want)) return true;
            }
            return false;   // 明确不匹配：拒绝加载（防重打包）
        } catch (Throwable t) {
            return true;    // 取不到签名信息时不误伤正常用户
        }
    }

    private static boolean envOk() {
        try {
            if (android.os.Debug.isDebuggerConnected()) return false;
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"));
            String l;
            boolean traced = false;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("TracerPid:")) {
                    traced = !"0".equals(l.substring(10).trim());
                    break;
                }
            }
            r.close();
            if (traced) return false;
            // 只检测常见动态注入框架痕迹，不做任何对抗/规避动作
            String[] marks = {
                "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
                "/system/lib/libxposed_art.so", "/system/framework/XposedBridge.jar",
                "/data/data/de.robv.android.xposed.installer",
            };
            for (int i = 0; i < marks.length; i++) {
                if (new File(marks[i]).exists()) return false;
            }
            if ((MODE & 4) != 0) {
                String fp = Build.FINGERPRINT.toLowerCase();
                if (fp.contains("generic") || fp.contains("emulator") || fp.contains("vbox")) return false;
                String m = Build.MODEL.toLowerCase();
                if (m.contains("emulator") || m.contains("sdk")) return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private void inject(ClassLoader extra) throws Throwable {
        ClassLoader cl = getClassLoader();
        Field plf = find(cl.getClass(), "pathList");
        if (plf == null) return;
        Object pathList = plf.get(cl);
        if (pathList == null) return;
        Field epf = find(extra.getClass(), "pathList");
        if (epf == null) return;
        Object extraList = epf.get(extra);
        if (extraList == null) return;
        Field ef = find(extraList.getClass(), "dexElements");
        Field of = find(pathList.getClass(), "dexElements");
        if (ef == null || of == null) return;
        Object[] add = (Object[]) ef.get(extraList);
        Object[] old = (Object[]) of.get(pathList);
        if (add == null || add.length == 0 || old == null) return;
        Object[] merged = (Object[]) Array.newInstance(
                old.getClass().getComponentType(), add.length + old.length);
        System.arraycopy(add, 0, merged, 0, add.length);
        System.arraycopy(old, 0, merged, add.length, old.length);
        of.set(pathList, merged);
    }

    private Application build(String real) throws Throwable {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("currentActivityThread").invoke(null);
            Field f = find(at, "mInstrumentation");
            if (f != null) {
                Object instr = f.get(th);
                Class<?> ic = Class.forName("android.app.Instrumentation");
                Method m = ic.getMethod("newApplication", ClassLoader.class,
                        String.class, Context.class);
                Object app = m.invoke(instr, getClassLoader(), real, getBaseContext());
                if (app instanceof Application) return (Application) app;
            }
        } catch (Throwable t) {
        }
        Class<?> c = Class.forName(real, true, getClassLoader());
        Application app = (Application) c.newInstance();
        try {
            Method a = Application.class.getDeclaredMethod("attach", Context.class);
            a.setAccessible(true);
            a.invoke(app, getBaseContext());
        } catch (Throwable t) {
        }
        return app;
    }

    private void swap(Application app) {
        try {
            Context bc = getBaseContext();
            Field pf = find(bc.getClass(), "mPackageInfo");
            if (pf != null) {
                Object lp = pf.get(bc);
                if (lp != null) {
                    Field af = find(lp.getClass(), "mApplication");
                    if (af != null) af.set(lp, app);
                }
            }
        } catch (Throwable t) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("currentActivityThread").invoke(null);
            Field f = find(at, "mInitialApplication");
            if (f != null) f.set(th, app);
            Field g = find(at, "mAllApplications");
            if (g != null) {
                Object list = g.get(th);
                if (list instanceof java.util.ArrayList) {
                    java.util.ArrayList l = (java.util.ArrayList) list;
                    for (int i = 0; i < l.size(); i++) {
                        if (l.get(i) == this) l.set(i, app);
                    }
                }
            }
        } catch (Throwable t) {
        }
    }

    private static Field find(Class<?> c, String name) {
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
"""


def _rand_ident(rng, n=10):
    first = rng.choice("abcdefghijklmnopqrstuvwxyz")
    rest = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return first + "".join(rng.choice(rest) for _ in range(n - 1))


def _blit(data: bytes) -> str:
    """把字节序列写成 Java 的 byte[] 字面量（有符号）。"""
    return ", ".join(str(b - 256 if b > 127 else b) for b in data)


def _s32(v: int) -> str:
    """Java 的 int 字面量没有无符号形式，超过 2^31 要写成负数。"""
    return str(v - (1 << 32) if v >= (1 << 31) else v)


def _obfb(data: bytes, rng):
    """把字节序列做成 XOR 掩码数组（Java 侧用 dec/decb 还原）。"""
    key = rng.randbytes(8)
    enc = bytes(c ^ key[i % 8] for i, c in enumerate(data))
    return _blit(enc), _blit(key)


def _obf(text: str, rng):
    """把字符串拆成 XOR 掩码数组，避免明文常量出现在壳 dex 的字符串表里。"""
    if not text:
        return "", ""
    return _obfb(text.encode("utf-8"), rng)


# 壳里这些固定字符串一旦明文出现，就等于给静态扫描器留了工具指纹
# （"dexElements"/"pathList" 是几乎所有 dex 壳的共同特征）。
_ENCODE_LITERALS = (
    "pathList", "dexElements", "android.app.ActivityThread",
    "android.app.Instrumentation", "mInstrumentation", "mInitialApplication",
    "mAllApplications", "mPackageInfo", "mApplication", "newApplication",
    "currentActivityThread", "attach", "SHA-256", "TracerPid:",
    "/proc/self/status", "/data/local/tmp/frida-server",
    "/data/local/tmp/re.frida.server", "/system/lib/libxposed_art.so",
    "/system/framework/XposedBridge.jar",
    "/data/data/de.robv.android.xposed.installer",
)

# 壳自身的方法名（不含 VM 类的 run / Host.asset，也不含 override 的生命周期方法）
_RENAME_METHODS = ("decb", "dec", "boot", "load", "sigOk", "envOk",
                   "inject", "build", "swap", "find", "dbg")


def _encode_literals(src: str, rng) -> str:
    """把指定的字符串字面量换成运行时 XOR 解码调用。"""
    for lit in _ENCODE_LITERALS:
        enc, key = _obfb(lit.encode("utf-8"), rng)
        src = src.replace('"%s"' % lit,
                          "dec(new byte[]{%s}, new byte[]{%s})" % (enc, key))
    return src


def _rename_methods(src: str, rng) -> tuple[str, dict]:
    """把壳自己定义的方法/字段名换成每次构建随机的标识符。"""
    mapping = {n: _rand_ident(rng, 9) for n in _RENAME_METHODS}
    for old in _RENAME_METHODS:                 # 已按长度降序，dec 不会吃掉 decb
        src = re.sub(r"\b%s\b" % re.escape(old), mapping[old], src)
    return src, mapping


def _cert_sha256(tools: dict, ks: str, alias: str, pwd: str) -> bytes:
    """从签名密钥库导出证书 DER 并算 SHA-256，供壳做防重打包校验。

    keytool -exportcert -rfc 输出的 PEM 主体就是证书 DER 的 base64，
    与运行时 Signature.toByteArray() 的内容一致。
    """
    kt = tools.get("keytool") or shutil.which("keytool") or ""
    if not kt or not os.path.isfile(ks):
        return b""
    rc, txt = _run([kt, "-exportcert", "-rfc", "-keystore", ks,
                    "-alias", alias, "-storepass", pwd], log=None)
    if rc != 0:
        return b""
    # 只取 PEM 主体的 base64，避免 keytool 的警告文本混进解码结果
    m = re.search(r"-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----",
                  txt, re.S)
    if not m:
        return b""
    body = "".join(l.strip() for l in m.group(1).splitlines() if l.strip())
    try:
        der = base64.b64decode(body)
        if len(der) < 128:
            return b""
        return hashlib.sha256(der).digest()
    except Exception:
        return b""


def render_stub_sources(*, stub_pkg: str, stub_cls: str, prog, lens: list[int],
                        asset_enc: list[str], ak: bytes, real_name: str,
                        sig: bytes, mode: int, wipe_memory: bool,
                        debug: bool, rng) -> dict:
    """生成壳 Application 与 VM 解释器的 Java 源码。

    构建流程与自测共用这一份实现，保证 selftest 验证的就是真实产出的源码。
    返回 {"app": 壳源码, "vm": 解释器源码, "vm_class": 解释器类名}。
    """
    rs, rk = _obf(real_name, rng)
    sg, sk = _obfb(sig, rng) if sig else ("", "")
    vm_cls = "zx" + _rand_ident(rng, 11)
    src = (STUB_TEMPLATE
           .replace("@PKG@", stub_pkg)
           .replace("@CLS@", stub_cls)
           .replace("@VMCLS@", vm_cls)
           .replace("@CODE@", prog.as_java_ints())
           .replace("@POOL@", prog.as_java_pool())
           .replace("@MASK@", prog.as_java_mask())
           .replace("@OPS@", prog.as_java_ops())
           .replace("@ASSETS@", ", ".join(asset_enc))
           .replace("@AK@", _blit(ak))
           .replace("@LENS@", ", ".join(str(x) for x in lens))
           .replace("@RS@", rs)
           .replace("@RK@", rk)
           .replace("@SG@", sg)
           .replace("@SK@", sk)
           .replace("@MODE@", str(mode))
           .replace("@DIR@", "." + _rand_ident(rng, 7)))
    # 顺序有讲究：先删诊断行 → 再编码字符串字面量 → 最后随机化方法名
    if debug:
        src = re.sub(r"(?m)^[ \t]*//DBG ?", "", src)
    else:
        src = re.sub(r"(?m)^[ \t]*//DBG[^\n]*\n?", "", src)
    src = _encode_literals(src, rng)
    src, _names = _rename_methods(src, rng)
    if not wipe_memory:
        src = re.sub(r"(?m)^[ \t]*Arrays\.fill\([^;]*\(byte\) 0\);[ \t]*\n?",
                     "", src)
    return {"app": src,
            "vm": dexvm.java_source(prog, stub_pkg, vm_cls),
            "vm_class": vm_cls}


@dataclass
class ApkOptions:
    level: str = "standard"          # compat | standard | strong
    strip_debug: bool = True
    harden_backup: bool = True
    keystore: str = ""
    ks_alias: str = ""
    ks_pass: str = ""
    key_pass: str = ""
    v1: bool = False
    seed: int = 0
    # ---- 新增运行时加固开关 ----
    shards: int = 0                  # 载荷分片数，0 = 自动（2..8）
    wipe_memory: bool = True         # 解密后擦除 Java 堆里的明文副本
    check_signature: bool = False    # 防重打包（需与签名密钥库绑定，默认关）
    anti_debug: bool = False         # 反调试 / 反注入框架（启发式，默认关）
    anti_emulator: bool = False      # 反模拟器（易误伤，默认关）
    extra: dict = field(default_factory=dict)


def _default_keystore(log=None, keytool: str = "") -> tuple[str, str, str]:
    """获取（必要时生成）默认签名密钥库，返回 (路径, 别名, 密码)。

    别名随机生成并记录在旁挂文件里：apksigner 用别名给 META-INF 下的 v1 签名块
    命名，固定别名等于在每个产物里写下工具指纹，会被静态特征直接命中。
    """
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    d = os.path.join(base, "CyrilShield")
    os.makedirs(d, exist_ok=True)
    ks = os.path.join(d, "android_default.jks")
    meta = os.path.join(d, "android_default.key.json")
    pwd = "cyrilshield2026"
    keytool = keytool or shutil.which("keytool") or ""

    # 旧版本的密钥库在 PEShieldStudio 目录下。它的证书主体是 CN=PEShield Studio，
    # 会原样印在每一个产物的签名里，所以不能直接继承；只留一份副本备查，
    # 需要复用它签名时在界面上手工指定密钥库路径即可。
    old_dir = os.path.join(base, "PEShieldStudio")
    old_ks = os.path.join(old_dir, "android_default.jks")
    legacy = os.path.join(d, "android_legacy.jks")
    if os.path.isfile(old_ks) and not os.path.isfile(legacy):
        try:
            shutil.copy2(old_ks, legacy)
        except Exception:
            pass

    if os.path.isfile(meta):
        try:
            with open(meta, "r", encoding="utf-8") as fh:
                info = json.load(fh)
            alias = str(info["alias"])
            if os.path.isfile(ks):
                return ks, alias, str(info.get("store_pass") or pwd)
        except Exception:
            pass

    # 每次安装只随机一次，之后复用，保证同一台机器上产物可连续升级
    alias = "k" + "".join(random.SystemRandom().choice(string.ascii_lowercase + string.digits)
                          for _ in range(7))

    if os.path.isfile(ks) and os.path.getsize(ks) > 0:
        # 旧版本生成的密钥库：把固定别名换掉，密钥本身不变，已发布应用的升级链不受影响
        done = False
        if keytool:
            rc, _ = _run([keytool, "-changealias", "-keystore", ks, "-alias", "cyrilshield",
                          "-destalias", alias, "-storepass", pwd,
                          "-keypass", pwd], log=None)
            done = rc == 0
        if done and log:
            log("默认密钥库别名已随机化（密钥不变）", "DIM")
        if not done:
            if log:
                log("沿用默认密钥库的既有别名", "WARN")
            return ks, "cyrilshield", pwd
    else:
        if not keytool:
            raise ApkError("找不到 keytool，无法生成签名密钥库")
        rc, txt = _run([keytool, "-genkeypair", "-keystore", ks, "-storetype", "JKS",
                        "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10950", "-storepass", pwd, "-keypass", pwd,
                        "-dname", "CN=CyrilShield, O=Self Signed, C=CN"], log=log)
        if rc != 0 or not os.path.isfile(ks):
            raise ApkError("生成签名密钥库失败：" + txt[-300:])
        if log:
            log(f"已生成默认签名密钥库：{ks}", "WARN")
            log("该密钥为工具自带默认值，正式发布请换用你自己的密钥库", "WARN")

    try:
        with open(meta, "w", encoding="utf-8") as fh:
            json.dump({"alias": alias, "store_pass": pwd}, fh)
    except Exception:
        pass
    return ks, alias, pwd


def _payload_asset(rng):
    """生成随机资产路径。返回 (zip 内完整路径, 资产管理器相对路径)。"""
    rel = _rand_ident(rng, 6) + "/" + _rand_ident(rng, 8) + ".dat"
    return "assets/" + rel, rel


def protect(path: str, out_path: str, opts: ApkOptions, log=print) -> dict:
    """对 APK 执行加固，返回结果摘要。"""
    t0 = time.time()
    rng = random.Random(opts.seed or int(time.time() * 1000) ^ os.getpid())
    tools = find_tools()
    if not tools["ok"]:
        raise ApkError("Android 工具链不完整，缺少：" + "、".join(tools["missing"]))
    # d8.bat / apksigner.bat 靠 JAVA_HOME 找 java，一律指向刚探测到的那套 JRE，
    # 避免被机器上残留的、缺少 javac 的旧 JAVA_HOME 带偏
    if tools["java_home"]:
        os.environ["JAVA_HOME"] = tools["java_home"]

    where = "内置" if tools.get("bundled") else "本机"
    log(f"工具链（{where}）：build-tools {os.path.basename(tools['build_tools'])} · "
        f"platform {tools['platform']} · JRE {tools['java_home']}", "DIM")

    meta = inspect(path, tools)
    if not meta["dexes"]:
        raise ApkError("APK 内没有 classes.dex，无法加固")
    if meta["packers"]:
        raise ApkError("该 APK 已加固（" + "、".join(meta["packers"]) +
                       "），需先脱壳取得原始 dex 才能处理")
    min_sdk = meta["min_sdk"] or 21
    if min_sdk < 19:
        log(f"minSdkVersion={min_sdk} 偏低，壳依赖的类加载器接口可能不存在", "WARN")

    tmp = tempfile.mkdtemp(prefix="cyrilshield_apk_")
    try:
        return _protect_inner(path, out_path, opts, rng, tools, meta, min_sdk, tmp, log, t0)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _protect_inner(path, out_path, opts, rng, tools, meta, min_sdk, tmp, log, t0):
    level = opts.level if opts.level in ("compat", "standard", "strong") else "standard"
    with zipfile.ZipFile(path) as zf:
        entries = [(i, zf.read(i.filename)) for i in zf.infolist()]

    orig_app = meta["app_class"] or ""
    pkg = meta["package"]
    if not pkg:
        raise ApkError("清单缺少 package 属性")

    # ---- 1. 清单改写
    log("解析并改写 AndroidManifest.xml", "DIM")
    raw_manifest = dict((i.filename, d) for i, d in entries)["AndroidManifest.xml"]
    doc = axml.Document.parse(raw_manifest)
    app_node = doc.app_node()
    if app_node is None:
        raise ApkError("清单缺少 <application> 节点")

    if level == "compat":
        stub_pkg = stub_cls = stub_full = ""
    else:
        stub_pkg = pkg + ".ps" + _rand_ident(rng, 5)
        stub_cls = _rand_ident(rng, 11)
        stub_full = stub_pkg + "." + stub_cls
        doc.set_android_attr(app_node, axml.ATTR_NAME, string=stub_full, attr_name="name")

    if opts.strip_debug:
        if doc.remove_attr(app_node, axml.ATTR_DEBUGGABLE, "debuggable"):
            log("已移除 android:debuggable", "DIM")
    if opts.harden_backup:
        doc.set_android_attr(app_node, axml.ATTR_ALLOW_BACKUP, boolean=False, attr_name="allowBackup")
        log("已设置 android:allowBackup=false", "DIM")
    new_manifest = doc.to_bytes()
    # 立即回读校验
    check = axml.manifest_info(axml.Document.parse(new_manifest))
    if check["package"] != pkg:
        raise ApkError("清单改写后包名异常，已中止")
    if stub_full and check["app_class"] != stub_full:
        raise ApkError("壳类名写入失败，已中止")

    # ---- 2. 签名密钥库（提前决议：壳的签名绑定校验需要它）
    if opts.keystore:
        ks, alias, pwd = opts.keystore, opts.ks_alias or "key0", opts.ks_pass
        kpwd = opts.key_pass or opts.ks_pass
        if not os.path.isfile(ks):
            raise ApkError("指定的密钥库不存在")
    else:
        ks, alias, pwd = _default_keystore(log, tools.get("keytool", ""))
        kpwd = pwd

    # ---- 3. dex 分片交错加密 + 引导链 VM 编译 + 壳生成
    payloads = {}
    total_plain = 0
    stub_bytes = b""
    vm_stats = {}
    mode = 0
    if opts.anti_debug:
        mode |= 1
    if opts.check_signature:
        mode |= 2
    if opts.anti_emulator:
        mode |= 4

    if level == "compat":
        log("兼容模式：不抽取 dex，仅重打包与重签名", "WARN")
    else:
        # 八组常量 → 前四组与后四组异或出 64 字节材料 → HMAC 压成根密钥
        rows = [[rng.getrandbits(32) for _ in range(4)] for _ in range(8)]
        material = b"".join(
            (rows[g][i] ^ rows[g + 4][i]).to_bytes(4, "big")
            for g in range(4) for i in range(4))
        salt_root = rng.randbytes(32)
        salt_part = rng.randbytes(32)
        root = _stream.root_key(salt_root, material)

        entry_map = dict((i.filename, d) for i, d in entries)
        dex_names = [d["name"] for d in meta["dexes"]]
        dex_blobs = [entry_map[n] for n in dex_names]
        lens = [len(x) for x in dex_blobs]
        total_plain = sum(lens)
        log(f"抽取 {len(dex_names)} 个 dex：{', '.join(dex_names)}", "DIM")

        # 拼成一条流再切片：片边界故意不落在 dex 边界上。静态拿到一堆 .dat
        # 既不知道拼回顺序，也不知道该从哪个偏移切回各个 dex。
        stream = b"".join(dex_blobs)
        if opts.shards:
            # 用户显式指定：只按「每片至少 256 字节」兜底，避免切出空片
            nsh = max(2, min(12, opts.shards, max(1, len(stream) // 256)))
        else:
            # 自动模式：按 dex 数量与总体积共同收敛，小包不切太多碎片段
            nsh = max(2, min(8, len(dex_blobs) * 2,
                             max(2, len(stream) // 4096)))
        base, rem = divmod(len(stream), nsh)
        chunks, off = [], 0
        for i in range(nsh):
            ln = base + (1 if i < rem else 0)
            chunks.append(stream[off:off + ln])
            off += ln
        if off != len(stream) or not all(chunks):
            raise ApkError("载荷分片未完整覆盖 dex 流")

        # 磁盘顺序随机打乱：order[i] = 第 i 个 asset 装的是原序第几片。
        # 这个重排关系只写进 VM 的 EMIT 立即数，壳 dex 里没有明文顺序表。
        order = list(range(nsh))
        rng.shuffle(order)
        if nsh > 1 and order == list(range(nsh)):
            order[0], order[1] = order[1], order[0]
        prefixes = [bytes([0x11]) + struct.pack(">H", i) for i in range(nsh)]

        want = hashlib.sha256(stream).digest() if level == "strong" else b""
        vm_plan = dexvm.VmPlan(rows=rows, salt_root=salt_root,
                               salt_part=salt_part, prefixes=prefixes,
                               want=want, emit_order=order,
                               seed=rng.randrange(1 << 30))
        prog = dexvm.compile_program(vm_plan)
        if len(prog.code) > 20000:
            raise ApkError("VM 字节码过大，请减少分片数")
        vm_stats = {"instructions": len(prog.code) // dexvm.W,
                    "junk": prog.junk, "shards": nsh}

        ak = rng.randbytes(8)
        asset_enc = []
        for i in range(nsh):
            zip_path, rel_path = _payload_asset(rng)
            part_key = _stream.sub_key(root, salt_part, i)
            payloads[zip_path] = _stream.seal(
                part_key, rng.randbytes(16), chunks[order[i]])
            enc = bytes(c ^ ak[j % 8] for j, c in enumerate(rel_path.encode()))
            asset_enc.append("new byte[]{%s}" % _blit(enc))
        log(f"分片交错：{total_plain:,} 字节 → {nsh} 片，磁盘顺序已打乱", "DIM")
        log(f"引导链 VM：{vm_stats['instructions']} 条虚拟指令"
            f"（含 {vm_stats['junk']} 条垃圾），opcode/槽位/常量池已随机", "DIM")

        # ---- 3b. 生成壳 Application + VM 解释器源码
        log("生成壳 Application 与 VM 解释器源码", "DIM")
        real_name = orig_app if not orig_app.startswith(".") else pkg + orig_app

        sig = b""
        if opts.check_signature:
            sig = _cert_sha256(tools, ks, alias, pwd)
            if sig:
                log("已绑定签名证书 SHA-256（防重打包）", "DIM")
            else:
                log("无法从密钥库读取证书，已跳过签名绑定校验", "WARN")

        gen = render_stub_sources(
            stub_pkg=stub_pkg, stub_cls=stub_cls, prog=prog, lens=lens,
            asset_enc=asset_enc, ak=ak, real_name=real_name, sig=sig,
            mode=mode, wipe_memory=bool(opts.wipe_memory),
            debug=bool(opts.extra.get("debug")), rng=rng)
        vm_cls = gen["vm_class"]

        src_dir = os.path.join(tmp, "src", *stub_pkg.split("."))
        os.makedirs(src_dir, exist_ok=True)
        java_file = os.path.join(src_dir, stub_cls + ".java")
        vm_java_file = os.path.join(src_dir, vm_cls + ".java")
        with open(java_file, "w", encoding="utf-8") as fh:
            fh.write(gen["app"])
        with open(vm_java_file, "w", encoding="utf-8") as fh:
            fh.write(gen["vm"])

        obj = os.path.join(tmp, "obj")
        os.makedirs(obj, exist_ok=True)
        rc, txt = _run([tools["javac"], "-J-Duser.language=en", "-J-Duser.country=US",
                        "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-cp", tools["android_jar"], "-d", obj,
                        java_file, vm_java_file], log=log)
        if rc != 0:
            raise ApkError("壳类编译失败：" + txt[-400:])
        classes = []
        for root, _d, files in os.walk(obj):
            for f in files:
                if f.endswith(".class"):
                    classes.append(os.path.join(root, f))
        if not classes:
            raise ApkError("壳类编译未产出 class 文件")
        dexdir = os.path.join(tmp, "dex")
        os.makedirs(dexdir, exist_ok=True)
        api = max(21, min_sdk)
        rc, txt = _run([tools["d8"], "--release", "--min-api", str(api),
                        "--lib", tools["android_jar"], "--output", dexdir] + classes, log=log)
        if rc != 0:
            raise ApkError("dex 生成失败：" + txt[-400:])
        stub_dex = os.path.join(dexdir, "classes.dex")
        if not os.path.isfile(stub_dex):
            raise ApkError("d8 未产出 classes.dex")
        stub_bytes = open(stub_dex, "rb").read()
        log(f"壳 dex：{len(stub_bytes):,} 字节，{nsh} 片载荷，"
            f"{len(classes)} 个 class", "DIM")

    # ---- 4. 重打包
    log("重打包 APK", "DIM")
    unsigned = os.path.join(tmp, "unsigned.apk")
    drop_dex = {d["name"] for d in meta["dexes"]} if level != "compat" else set()
    with zipfile.ZipFile(unsigned, "w") as zo:
        for info, data in entries:
            name = info.filename
            if SIG_FILE_RE.match(name) or name.upper() == "META-INF/MANIFEST.MF":
                continue
            if name == "AndroidManifest.xml":
                continue
            if name in drop_dex:
                continue
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = info.compress_type
            zi.external_attr = info.external_attr
            zo.writestr(zi, data)
        zi = zipfile.ZipInfo("AndroidManifest.xml", date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        zo.writestr(zi, new_manifest)
        if level != "compat":
            zi = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zo.writestr(zi, stub_bytes)
            for name, data in payloads.items():
                zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                zi.compress_type = zipfile.ZIP_DEFLATED
                zo.writestr(zi, data)

    # ---- 5. 对齐 + 签名
    aligned = os.path.join(tmp, "aligned.apk")
    log("zipalign 4 字节对齐", "DIM")
    rc, txt = _run([tools["zipalign"], "-f", "-p", "4", unsigned, aligned], log=log)
    if rc != 0 or not os.path.isfile(aligned):
        raise ApkError("对齐失败：" + txt[-300:])

    # ks / alias / pwd / kpwd 已在第 2 步决议

    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    log("apksigner 签名", "DIM")
    # Android 7.0 以下没有 v2 校验，minSdk < 24 必须同时启用 v1，否则安装失败
    use_v1 = True if min_sdk < 24 else bool(opts.v1)
    if use_v1:
        log(f"minSdkVersion={min_sdk} < 24，自动启用 v1 签名", "DIM")
    rc, txt = _run([tools["apksigner"], "sign", "--ks", ks, "--ks-key-alias", alias,
                    "--ks-pass", "pass:" + pwd, "--key-pass", "pass:" + kpwd,
                    "--v1-signing-enabled", "true" if use_v1 else "false",
                    "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                    # v4 会额外吐一个 .idsig 旁路文件，只有 adb 增量安装才用得上，
                    # 普通安装/分发反而多一个让人困惑的产物，关掉。
                    "--v4-signing-enabled", "false",
                    "--out", out_path, aligned], log=log)
    if rc != 0 or not os.path.isfile(out_path):
        raise ApkError("签名失败：" + txt[-400:])

    rc, txt = _run([tools["apksigner"], "verify", "--print-certs", out_path], timeout=180)
    verified = rc == 0
    cert_dn = ""
    for line in txt.splitlines():
        if "certificate DN" in line:
            cert_dn = line.split(":", 1)[1].strip()
            break
    if not verified:
        raise ApkError("签名校验未通过：" + txt[-300:])

    # ---- 6. 产物自检
    with zipfile.ZipFile(out_path) as zf:
        names = zf.namelist()
        out_manifest = zf.read("AndroidManifest.xml")
        out_dex = {n: hashlib.sha256(zf.read(n)).hexdigest()
                   for n in names if re.match(r"^classes\d*\.dex$", n)}
    out_info = axml.manifest_info(axml.Document.parse(out_manifest))
    orig_dex_hash = {d["name"]: hashlib.sha256(
        dict((i.filename, x) for i, x in entries)[d["name"]]).hexdigest()
        for d in meta["dexes"]}
    new_sig = _signature_info(out_path, tools)
    checks = {
        "包名一致": out_info["package"] == pkg,
        "版本号一致": out_info["version_code"] == meta["version_code"],
        "壳类已注入": (out_info["app_class"] == stub_full) if level != "compat" else True,
        "原 dex 已替换": not (set(out_dex.values()) & set(orig_dex_hash.values()))
                      if level != "compat" else True,
        "载荷分片齐全": len(payloads) == vm_stats.get("shards", 0)
                      if level != "compat" else True,
        "签名有效": verified,
    }
    if not all(checks.values()):
        failed = [k for k, v in checks.items() if not v]
        raise ApkError("产物自检失败：" + "、".join(failed))

    elapsed = time.time() - t0
    out_size = os.path.getsize(out_path)
    log(f"完成：{meta['size']:,} → {out_size:,} 字节，用时 {elapsed:.1f}s", "OK")
    return {
        "path": out_path, "name": os.path.basename(out_path),
        "package": pkg, "level": level, "size_before": meta["size"],
        "size_after": out_size, "elapsed": round(elapsed, 1),
        "stub_class": stub_full, "payload_parts": len(payloads),
        "dex_bytes": total_plain, "checks": checks, "cert_dn": cert_dn,
        "cert_changed": bool(meta["signature"].get("sha256")) and
                        new_sig.get("sha256") != meta["signature"].get("sha256"),
        "schemes": new_sig.get("schemes", []),
        "keystore": ks, "min_sdk": min_sdk,
        # 新增：本次实际启用的加固能力，供界面回显
        "virtualized": bool(vm_stats),
        "vm_instructions": vm_stats.get("instructions", 0),
        "vm_junk": vm_stats.get("junk", 0),
        "shards": vm_stats.get("shards", 0),
        "wipe_memory": bool(opts.wipe_memory) if level != "compat" else False,
        "guard_mode": mode if level != "compat" else 0,
        "sig_bound": bool(mode & 2) and bool(sig) if level != "compat" else False,
    }
