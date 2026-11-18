# -*- coding: utf-8 -*-
"""超级终端启动页的确定性校验（不截图、不评观感）。"""
import ast
import io
import os
import re
import subprocess
import sys
import tempfile

W_HTML = "D:/Terminal-Windows/web/index.html"
W_BRIDGE = "D:/Terminal-Windows/web/folia-bridge.js"
L_HTML = "D:/Terminal-Linux/web/index.html"
W_SRV = "D:/Terminal-Windows/server.py"
L_SRV = "D:/Terminal-Linux/server.py"
LEGACY = "D:/Terminal-Linux/web/index.kali-legacy.html"

PASS, FAIL = [], []


def ck(cond, label, extra=""):
    (PASS if cond else FAIL).append(label)
    print(("PASS " if cond else "FAIL ") + label + ("  " + str(extra) if extra else ""))


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def inline_scripts(html):
    """返回所有无 src 的 <script> 内容（含新加的超级终端脚本）。"""
    return re.findall(r"<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>", html, re.S | re.I)


def js_check(html, label):
    scripts = inline_scripts(html)
    tmp = tempfile.mkdtemp(prefix="_shjs_")
    ok = True
    for i, code in enumerate(scripts):
        p = os.path.join(tmp, "%s-%02d.js" % (label, i))
        with io.open(p, "w", encoding="utf-8") as f:
            f.write(code)
        r = subprocess.run(["node", "--check", p], capture_output=True, text=True)
        if r.returncode != 0:
            ok = False
            print("     脚本 %d 语法错误: %s" % (i, (r.stderr or "").strip()[:300]))
    ck(ok, label + " 内联 JS 全部通过 node --check", "%d 段" % len(scripts))
    return scripts


print("=== 1. 语法与结构 ===")
wh, lh = read(W_HTML), read(L_HTML)
ws, ls = read(W_SRV), read(L_SRV)
wjs = js_check(wh, "Win")
ljs = js_check(lh, "Lnx")

for label, html in (("Win", wh), ("Lnx", lh)):
    ids = re.findall(r'\bid="([^"]+)"', html)
    dup = sorted({x for x in ids if ids.count(x) > 1})
    ck(not dup, label + " DOM id 无重复", dup or "%d 个 id" % len(ids))
    need = ["superHome", "superGalaxy", "superVignette", "superStage",
            "superLinks", "shCore", "btnHome", "setStartSuper",
            "setStartTerm", "setOpenHome"]
    ck(not [x for x in need if x not in ids], label + " 超级终端关键 id 齐全",
       "缺少 %s" % [x for x in need if x not in ids] or "10/10")
    # 节点百分比定位；Win 版的连线已改成脚本按实际坐标生成（拖到哪连到哪），
    # Kali 版仍是写死的四条线，所以两种校验分开写。
    nodes = re.findall(r'data-sh-go="(\w+)" style="left:(\d+)%;top:(\d+)%"', html)
    if label == "Win":
        ck(len(nodes) == 4, label + " 四个模块节点齐全（百分比定位）", len(nodes))
        ck('id="superLinkGlow"' in html and 'id="superLinkMain"' in html,
           label + " 连线容器为空组，坐标由脚本生成")
        ck('function drawLinks()' in html and 'drawLinks();' in html,
           label + " 连线按节点坐标实时重算")
        ck('drawLinks();                     /* 线跟着手走，不是抬手才动 */' in html,
           label + " 拖动过程中就重算连线")
        dpos = re.search(r'var DEFAULT_POS = \{([^}]*)\}', html)
        ck(dpos is not None and all((' %s: [%s, %s]' % (n, x, y)) in dpos.group(1) for n, x, y in nodes),
           label + " 默认坐标与节点初始百分比一致", dpos.group(1) if dpos else "缺 DEFAULT_POS")
        ck('id="shCoreWrap"' in html and 'data-sh-drag="core"' in html,
           label + " 中央终端包在可拖动定位块里")
    else:
        pts = set(re.findall(r'class="sh-link"\s+x1="500" y1="500" x2="(\d+)" y2="(\d+)"', html))
        expect = {(str(int(x) * 10), str(int(y) * 10)) for _, x, y in nodes}
        ck(len(nodes) == 4 and pts == expect,
           label + " 四节点与 SVG 连线坐标一致", "%s / %s" % (sorted(pts), sorted(expect)))
    ck(sorted(n for n, _, _ in nodes) == ["comp", "file", "music", "settings"],
       label + " 四个模块节点齐全")
    # 底层只做 visibility 隐藏，不能 display:none（xterm 行列度量会塌）
    ck("body.super-on > header, body.super-on > #workArea { visibility: hidden; }" in html,
       label + " 底层用 visibility 隐藏（不影响 xterm 度量）")
    ck("body.super-on #superHome { display: block; }" in html,
       label + " super-on 才显示全屏层")

print("\n=== 2. 银河参数照搬（与 Kali legacy 逐项对照）===")
legacy = read(LEGACY)
galaxy_consts = [
    "var BAND_COLORS = ['#ffffff', '#eef4ff', '#dce8ff', '#c3d3ff', '#b9bcff', '#d8ccff'];",
    "var DUST_COLORS = ['#ffffff', '#e2ecff', '#cdd9ff'];",
    "Math.sin(seed * 3187.917) * 43758.5453",
    "Math.min(2200, Math.max(900, Math.round((innerWidth * innerHeight) / 1100)))",
    "grad.addColorStop(0, '#ffffff');",
    "grad.addColorStop(0.16, col);",
    "Math.pow(0.5 + 0.5 * Math.sin(now * (0.7 + rand(p.seed) * 0.5) + p.seed * 6.283), 3)",
    "* H * 0.038",
    "* H * 0.014",
    "Math.max(3, r * 7)",
    "0.12 + edge * 0.42 + tw * 0.16",
    "0.10 + tw * 0.32",
]
for label, html in (("Win", wh), ("Lnx", lh)):
    miss_l = [c for c in galaxy_consts if c not in legacy]
    miss_n = [c for c in galaxy_consts if c not in html]
    ck(not miss_l and not miss_n,
       label + " 银河常量/公式与 legacy 一致",
       ("legacy 缺 %s；新版缺 %s" % (miss_l, miss_n)) if (miss_l or miss_n)
       else "%d 项" % len(galaxy_consts))
    ck("var flow = now * 0.030;" in html and "var bass = 0;" in html,
       label + " 未播放态漂移 0.030 / bass=0（无音频依赖）")
    ck("galaxyStop" in html and "cancelAnimationFrame(handle)" in html,
       label + " 退出首页停 rAF（不留后台绘制）")

print("\n=== 3. 启动页设置与配置落盘 ===")
for label, html in (("Win", wh), ("Lnx", lh)):
    ck("start_page: 'super'," in html, label + " 前端 DEF 默认超级终端")
    ck("function startPageVal()" in html and "start_page: startPageVal()," in html,
       label + " saveAll 整包带 start_page")
    ck("patch({ start_page: v })" in html, label + " 点选即 settings_set 落盘")
    ck("if (d.start_page === 'super' || d.start_page === 'terminal') syncStartUI(d.start_page);" in html,
       label + " settings_resp 回读同步控件")
    ck("__cyrilSuperHomeApply(data.start_page)" in html, label + " cfgApply 启动页桥接")
    ck("syncStartUI('super');" in html, label + " 恢复默认回超级终端")
    ck("window.__cyrilSuperHome.show()" in html, label + " 「立即打开」接上首页")
    if label == "Win":
        ck("if (isOpen(key)) { closeBranch(key); return; }" in html,
           label + " 四个模块统一走分叉（再点同一个收起）")
        ck("branches[key] = br" in html and "closeAllBranches" in html,
           label + " 多分叉并存（按 key 各存一份）")
        ck("GO[" not in html, label + " 未残留旧的按钮映射表")
        # 音乐视图 / 独立窗口组件都留在启动页上（不切走）
        ck("return snap.filter(function (c) { return c.kind === 'panel'; })" in html,
           label + " 组件分叉只列独立窗口型（栏型不上启动页）")
        ck("body.super-on .comp-win { z-index: 892 !important; }" in html,
           label + " 组件窗口浮在启动页之上（892 < 登录层 900）")
        ck("body.super-view #superHome" in html and "pointer-events: none;" in html,
           label + " 视图模式下启动页背景让出且不拦指针")
        ck("window.__cyrilViewClose = function () { exitRadio(); };" in html,
           label + " 视图可收回照片背景")
        ck("window.__cyrilCompReady = function () { return _compList.length > 0; };" in html,
           label + " 清单到货与空清单可区分")
        ck("if (viewIsOn(v)) closeViewBg(); else openViewBg(v);" in html,
           label + " 音乐叶子是开/收两态且不退出启动页")
        # 绘光：舞台暗场 + 没载入曲目时走演示态（否则被当成暂停态、ticker 停掉只剩一帧暗场）
        ck("background: #050208;" in html,
           label + " 绘光层自带舞台暗场（不让壁纸从透明处透出）")
        ck("hasTrack: function () { return !!(audio.currentSrc || audio.getAttribute('src')); }" in html,
           label + " 绘光能判断「有没有真的载入曲目」")
        bridge = read(W_BRIDGE)
        ck("h.hasTrack" in bridge and "hooks.hasTrack" in bridge,
           label + " 桥接层接收 hasTrack 钩子")
        ck("var drift = !hasTrack();" in bridge and "demoClock" in bridge,
           label + " 无曲目走演示态（时间轴自走，不暂停 ticker）")
        ck("audioBands.bass.set(bassV);" in bridge,
           label + " 演示态给了可见的舞台光呼吸基线（不是 0..40 的暂停呼吸）")
        ck("debug: function () {" in bridge,
           label + " 绘光暴露自检接口（demo / frames）")
        # 封面拼贴：字体回到终端等宽
        lat_css = re.search(r"#lattice \{(.*?)\n  \}", html, re.S)
        ck(bool(lat_css) and "font-family: inherit;" in lat_css.group(1),
           label + " 拼贴字体继承终端等宽栈（不用网页黑体）")
        ck("#lattice button { font-family: inherit; }" in html,
           label + " 拼贴控制条按钮也继承终端字体（button 不自动继承）")
        # 拼贴里的终端浮窗：拼贴仍是全屏可拖可点的墙，终端搬进玻璃面板
        tf_css = re.search(r"#termFloat \{(.*?)\n  \}", html, re.S)
        ck(bool(tf_css) and "z-index: 890" in tf_css.group(1),
           label + " 终端浮窗层级 890（高于拼贴 80 / 启动页 880，低于组件窗 892）")
        ck("body.lattice-on #termFloat { display: flex; }" in html,
           label + " 浮窗只在封面拼贴里出现")
        ck("body.lattice-on.super-on #termFloat { display: none; }" in html,
           label + " 超级终端启动页上不叠浮窗")
        ck("tfBody.appendChild(termEl);" in html and
           "tfHome.parent.insertBefore(termEl, tfHome.next || null)" in html,
           label + " 终端进出拼贴时搬进浮窗 / 搬回原位")
        ck("new MutationObserver(tfSync).observe(document.body, { attributes: true, attributeFilter: ['class'] });" in html,
           label + " 浮窗挂载按 body 类集中判定（三条进拼贴路径都不会漏）")
        ck("if (latOnNow) base = THEMES.dark;" in html and
           "const theme = (hasBg || hasVideo || radioOn || latOnNow)" in html,
           label + " 拼贴里终端强制深色字 + 透明底（压深玻璃可读）")
        ck("window.addEventListener('pointermove', tfMove, true);" in html,
           label + " 浮窗拖动走 window 临时监听（不吞面板内按钮的 click）")
        ck("window.__cyrilTermFit = function () { try { if (fit) fit.fit(); } catch (e) {} };" in html,
           label + " 搬窗后先按面板尺寸重排行列（不等 WS 鉴权，行尾不被裁）")
        ck("var maxY = Math.max(4, innerHeight - h - 4);" in html,
           label + " 换档 / 缩窗口后浮窗整块夹回可视区")
    else:
        ck("var GO = { music: 'btnMusic', file: 'btnTree', comp: 'btnComp', settings: 'btnSettings' };" in html,
           label + " 节点走既有按钮 click，不复制开窗逻辑")

for label, src in (("Win", ws), ("Lnx", ls)):
    tree = ast.parse(src)
    fn = next((n for n in ast.walk(tree)
               if isinstance(n, ast.FunctionDef) and n.name == "_settings_default"), None)
    keys = {}
    if fn:
        ret = next((n for n in ast.walk(fn) if isinstance(n, ast.Return)), None)
        if ret and isinstance(ret.value, ast.Dict):
            for k, v in zip(ret.value.keys, ret.value.values):
                if isinstance(k, ast.Constant):
                    keys[k.value] = (v.value if isinstance(v, ast.Constant) else "<expr>")
    ck(keys.get("start_page") == "super", label + " 后端默认 start_page='super'", keys.get("start_page"))
    r = subprocess.run([sys.executable, "-m", "py_compile", W_SRV if label == "Win" else L_SRV],
                       capture_output=True, text=True)
    ck(r.returncode == 0, label + " server.py py_compile 通过", (r.stderr or "").strip()[:200])

print("\n=== 4. 两版对齐与前缀 ===")
ck("Win-webterm-start-page" in wh and "kali-webterm-start-page" not in wh,
   "Win 前端用 Win-webterm- 前缀")
ck("kali-webterm-start-page" in lh and "Win-webterm-start-page" not in lh,
   "Lnx 前端用 kali-webterm- 前缀")
ck(lh.count("kali-webterm-") == 18, "Lnx 前缀总数 = 17 + 新增 1", lh.count("kali-webterm-"))
# 除 title/subtitle/前缀外，两版新增片段应完全一致
for label, frag in (("CSS", '#superHome {'), ("脚本", "__cyrilSuperHomeApply")):
    ck(frag in wh and frag in lh, "两版都含 %s" % label)
ck("<title>Cyril · Kali Linux 网页终端</title>" in lh and
   "<title>Cyril · Windows 网页终端</title>" in wh, "两版 title 保持各自身份")

print("\n=== 5. 既有功能未被破坏 ===")
for label, html in (("Win", wh), ("Lnx", lh)):
    for key in ["id=\"viewMenu\"", "latLyricSpring", "lp-track", "settings_get",
                "ai_config_get", "comp_list", "library_sync", "panelMap()"]:
        ck(key in html, label + " 保留 " + key)
    ck("type: 'settings_set'" in html, label + " settings_set 协议仍在")

print("\n[超级终端校验] %d passed, %d failed" % (len(PASS), len(FAIL)))
if FAIL:
    print("失败项: " + "; ".join(FAIL))
sys.exit(1 if FAIL else 0)
