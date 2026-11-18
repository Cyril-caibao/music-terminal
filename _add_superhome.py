# -*- coding: utf-8 -*-
"""【大更新 · 第一项】设置里增加「初始页面」选择，默认「超级终端」。

超级终端是独立全屏界面：银河星轨背景（照搬 Mineradio 星河预设 / 原 Kali 版
galaxy-canvas 的 Canvas 2D 粒子层）+ 中央终端标志 + 四个模块节点
（音乐 / 文件 / 组件 / 设置），节点之间用 SVG 连线。

本脚本以「锚点精确替换」把同一组补丁打到两版前端与两版后端：
  · Windows: D:/Terminal-Windows/web/index.html, server.py
  · Linux  : D:/Terminal-Linux/web/index.html,   server.py
Linux 前端只把 localStorage 前缀换成 kali-webterm-，其余与 Windows 完全一致。

每处替换都断言命中 1 次，任何一处不匹配就整体中止，不产出半成品。
"""
import io
import os
import sys

WIN_HTML = "D:/Terminal-Windows/web/index.html"
LNX_HTML = "D:/Terminal-Linux/web/index.html"
WIN_SRV = "D:/Terminal-Windows/server.py"
LNX_SRV = "D:/Terminal-Linux/server.py"

PFX_WIN = "Win-webterm-start-page"
PFX_LNX = "kali-webterm-start-page"


# --------------------------------------------------------------------------
# 1) CSS：插在 </style> 之前
# --------------------------------------------------------------------------
CSS = r"""
  /* ================= 超级终端（启动页） =================
     独立全屏视图：银河星轨背景（照搬 Mineradio「星河」预设的 Canvas 2D 粒子层）
     + 中央终端标志 + 四个模块节点（音乐 / 文件 / 组件 / 设置）。
     z-index 880：高于所有浮层(60~80)，低于登录页(900)，保证远程登录仍盖得住。
     底层只做 visibility:hidden（不 display:none），xterm 的行列度量不受影响。 */
  #superHome {
    position: fixed; inset: 0; z-index: 880; display: none;
    background: #000; overflow: hidden; color: #eaf6ff;
    font-family: "Inter", "Noto Sans CJK SC", "PingFang SC", "Microsoft YaHei", Arial, sans-serif;
    -webkit-user-select: none; user-select: none;
  }
  body.super-on #superHome { display: block; }
  body.super-on > header, body.super-on > #workArea { visibility: hidden; }
  #superGalaxy { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
  #superVignette {
    position: absolute; inset: 0; pointer-events: none;
    background: radial-gradient(120% 92% at 50% 48%, rgba(0,0,0,0) 26%, rgba(0,0,0,.62) 100%);
  }
  #superStage { position: absolute; inset: 0; }
  #superLinks { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
  /* viewBox 0 0 1000 1000 + preserveAspectRatio=none：坐标就是百分比，
     节点用 left/top 百分比定位，窗口任意比例下线都正好落在节点圆心。 */
  .sh-link-glow { stroke: rgba(120,200,255,.10); stroke-width: 7; vector-effect: non-scaling-stroke; }
  .sh-link { stroke: rgba(156,255,223,.30); stroke-width: 1.2; vector-effect: non-scaling-stroke; }
  .sh-core {
    position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
    width: 148px; height: 148px; border-radius: 50%; cursor: pointer;
    display: flex; align-items: center; justify-content: center; padding: 0;
    background: radial-gradient(circle at 50% 42%, rgba(28,58,72,.72), rgba(4,10,18,.82) 72%);
    border: 1px solid rgba(156,255,223,.46);
    box-shadow: 0 0 0 10px rgba(156,255,223,.045), 0 0 42px rgba(88,190,220,.22), inset 0 0 28px rgba(120,220,255,.10);
    transition: transform .28s cubic-bezier(.32,.72,0,1), box-shadow .28s;
  }
  .sh-core:hover, .sh-core:focus-visible {
    transform: translate(-50%, -50%) scale(1.06);
    box-shadow: 0 0 0 14px rgba(156,255,223,.07), 0 0 62px rgba(120,220,255,.42), inset 0 0 34px rgba(120,220,255,.16);
    outline: none;
  }
  .sh-core svg { width: 76px; height: 76px; }
  .sh-core-title {
    position: absolute; left: 50%; top: calc(50% + 104px); transform: translateX(-50%);
    font-size: 22px; font-weight: 600; letter-spacing: .34em; white-space: nowrap;
    color: #eaf6ff; text-shadow: 0 0 18px rgba(120,200,255,.45); pointer-events: none;
  }
  .sh-core-sub {
    position: absolute; left: 50%; top: calc(50% + 138px); transform: translateX(-50%);
    font-size: 10.5px; letter-spacing: .18em; white-space: nowrap;
    color: rgba(190,220,235,.62); pointer-events: none;
  }
  /* 节点本身就是那颗圆：SVG 连线画到圆心，被圆形底盖住尾段 = 视觉上「连到」节点 */
  .sh-node {
    position: absolute; transform: translate(-50%, -50%);
    width: 58px; height: 58px; padding: 0; border-radius: 50%; cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    background: radial-gradient(circle at 50% 40%, rgba(22,46,62,.86), rgba(3,9,16,.94) 75%);
    border: 1px solid rgba(156,255,223,.30);
    box-shadow: 0 0 22px rgba(90,180,215,.16), inset 0 0 16px rgba(120,220,255,.08);
    color: #cfe9f6; font-family: inherit; font-size: 12px; letter-spacing: .16em;
    transition: transform .26s cubic-bezier(.32,.72,0,1), border-color .26s, box-shadow .26s;
  }
  .sh-node svg { width: 27px; height: 27px; }
  .sh-node:hover, .sh-node:focus-visible {
    transform: translate(-50%, -50%) scale(1.12);
    border-color: rgba(156,255,223,.78);
    box-shadow: 0 0 34px rgba(120,220,255,.36), inset 0 0 20px rgba(120,220,255,.16);
    outline: none;
  }
  .sh-node-txt {
    position: absolute; top: 70px; left: 50%; transform: translateX(-50%);
    white-space: nowrap; pointer-events: none;
    text-shadow: 0 0 14px rgba(90,180,215,.5);
  }
  .sh-foot {
    position: absolute; left: 0; right: 0; bottom: 22px; text-align: center;
    font-size: 10px; letter-spacing: .14em; color: rgba(180,210,225,.34); pointer-events: none;
  }
  @media (max-width: 620px) {
    .sh-core { width: 112px; height: 112px; }
    .sh-core svg { width: 58px; height: 58px; }
    .sh-core-title { top: calc(50% + 78px); font-size: 17px; }
    .sh-core-sub { top: calc(50% + 108px); font-size: 9px; }
    .sh-node { width: 46px; height: 46px; font-size: 11px; }
    .sh-node svg { width: 22px; height: 22px; }
    .sh-node-txt { top: 56px; }
  }
"""

# --------------------------------------------------------------------------
# 2) header：加一个返回超级终端的入口（⌂）
# --------------------------------------------------------------------------
HEADER_BTN = (
    '      <button id="btnHome" class="btn" title="返回超级终端（启动页）">⌂</button>\n'
)

# --------------------------------------------------------------------------
# 3) 设置面板：新增「启动页」分组
# --------------------------------------------------------------------------
SET_GROUP = r"""      <div class="set-group">
        <div class="set-title">启动页</div>
        <div class="set-row set-links">
          <button class="btn set-link" data-start-page="super" id="setStartSuper">超级终端</button>
          <button class="btn set-link" data-start-page="terminal" id="setStartTerm">终端</button>
          <button class="btn set-link" id="setOpenHome" title="立即打开超级终端（不改变启动设置）">立即打开</button>
        </div>
        <div class="set-hint">默认「超级终端」：银河背景 + 终端标志，连向音乐 / 文件 / 组件 / 设置。改完下次启动生效；顶栏「⌂」或这里「立即打开」可随时回到该界面。</div>
      </div>

"""

# --------------------------------------------------------------------------
# 4) body：超级终端全屏层 + 独立脚本（放在所有其它 script 之前，先于 xterm 初始化）
# --------------------------------------------------------------------------
BODY_HTML_AND_JS = r"""  <!-- ================= 超级终端 · 启动页 =================
       独立全屏界面：银河星轨背景 + 中央终端标志 + 四个模块节点。
       银河粒子层照搬 Mineradio「星河」预设（原 Kali 版 galaxy-canvas 实现），
       零依赖 Canvas 2D；不写 Settings.json 位置、不进 panelMap。
       节点跳转一律走既有顶栏 / 设置按钮的 click，避免复制一份开窗逻辑。 -->
  <div id="superHome" aria-hidden="true">
    <canvas id="superGalaxy"></canvas>
    <div id="superVignette"></div>
    <div id="superStage">
      <svg id="superLinks" viewBox="0 0 1000 1000" preserveAspectRatio="none" aria-hidden="true">
        <line class="sh-link-glow" x1="500" y1="500" x2="240" y2="280"></line>
        <line class="sh-link-glow" x1="500" y1="500" x2="760" y2="280"></line>
        <line class="sh-link-glow" x1="500" y1="500" x2="240" y2="720"></line>
        <line class="sh-link-glow" x1="500" y1="500" x2="760" y2="720"></line>
        <line class="sh-link" x1="500" y1="500" x2="240" y2="280"></line>
        <line class="sh-link" x1="500" y1="500" x2="760" y2="280"></line>
        <line class="sh-link" x1="500" y1="500" x2="240" y2="720"></line>
        <line class="sh-link" x1="500" y1="500" x2="760" y2="720"></line>
      </svg>

      <button class="sh-node" data-sh-go="music" style="left:24%;top:28%" title="音乐 · 进入电台与播放队列">
        <svg viewBox="0 0 32 32" fill="none" stroke="#cfeeff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 22V8l14-3v14"></path>
          <ellipse cx="8.5" cy="23" rx="3.6" ry="2.7" fill="rgba(207,238,255,.16)"></ellipse>
          <ellipse cx="22.5" cy="20" rx="3.6" ry="2.7" fill="rgba(207,238,255,.16)"></ellipse>
        </svg>
        <span class="sh-node-txt">音乐</span>
      </button>

      <button class="sh-node" data-sh-go="file" style="left:76%;top:28%" title="文件 · 打开目录树">
        <svg viewBox="0 0 32 32" fill="none" stroke="#cfeeff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M4 9h9l3 3h12v15H4z"></path>
        </svg>
        <span class="sh-node-txt">文件</span>
      </button>

      <button class="sh-node" data-sh-go="comp" style="left:24%;top:72%" title="组件 · 打开组件面板">
        <svg viewBox="0 0 32 32" fill="none" stroke="#cfeeff" stroke-width="2" stroke-linejoin="round">
          <rect x="5" y="5" width="9" height="9" rx="2"></rect>
          <rect x="18" y="5" width="9" height="9" rx="2"></rect>
          <rect x="5" y="18" width="9" height="9" rx="2"></rect>
          <rect x="18" y="18" width="9" height="9" rx="2"></rect>
        </svg>
        <span class="sh-node-txt">组件</span>
      </button>

      <button class="sh-node" data-sh-go="settings" style="left:76%;top:72%" title="设置 · 打开设置面板">
        <svg viewBox="0 0 32 32" fill="none" stroke="#cfeeff" stroke-width="2" stroke-linecap="round">
          <circle cx="16" cy="16" r="4.2"></circle>
          <path d="M16 3v4M16 25v4M3 16h4M25 16h4M6.8 6.8l2.8 2.8M22.4 22.4l2.8 2.8M25.2 6.8l-2.8 2.8M9.6 22.4l-2.8 2.8"></path>
        </svg>
        <span class="sh-node-txt">设置</span>
      </button>

      <button id="shCore" class="sh-core" title="进入终端">
        <svg viewBox="0 0 64 64" fill="none" stroke="#9cffdf" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
          <rect x="6" y="10" width="52" height="44" rx="7"></rect>
          <path d="M6 22h52"></path>
          <circle cx="13" cy="16" r="1.6" fill="#9cffdf" stroke="none"></circle>
          <circle cx="20" cy="16" r="1.6" fill="#9cffdf" stroke="none"></circle>
          <circle cx="27" cy="16" r="1.6" fill="#9cffdf" stroke="none"></circle>
          <path d="M17 32l8 7-8 7"></path>
          <path d="M30 46h14"></path>
        </svg>
      </button>
      <div class="sh-core-title">超级终端</div>
      <div class="sh-core-sub">SUPER TERMINAL · 点击进入终端</div>
      <div class="sh-foot">CYRIL WEBTERM · 从终端进入任意模块，顶栏 ⌂ 可返回</div>
    </div>
  </div>
  <script>
  /* ==================================================================
   * 超级终端（启动页）
   * - 银河背景：照搬 Mineradio「星河」预设 / 原 Kali 版 galaxy-canvas 粒子层
   *   （Canvas 2D、零依赖；GPL-3.0 来源署名见 sonic-host.js / index.kali-legacy.html）
   * - 中央终端标志 + 四个节点；节点一律触发既有按钮的 click
   * - 启动页选择落 Settings.json 的 start_page；localStorage 只是「连上后端前」
   *   的即时缓存，避免先闪一帧终端再跳首页
   * ================================================================== */
  (function () {
    "use strict";
    var KEY = 'Win-webterm-start-page';
    var root = document.getElementById('superHome');
    var canvas = document.getElementById('superGalaxy');
    var ctx = canvas ? canvas.getContext('2d', { alpha: false }) : null;
    if (!root || !canvas || !ctx) return;

    /* ---- 银河粒子层：常量与公式逐项对齐原版，不二次调参 ---- */
    function rand(seed) { return Math.abs(Math.sin(seed * 3187.917) * 43758.5453) % 1; }
    var particles = [], running = false, handle = 0;
    var BAND_COLORS = ['#ffffff', '#eef4ff', '#dce8ff', '#c3d3ff', '#b9bcff', '#d8ccff'];
    var DUST_COLORS = ['#ffffff', '#e2ecff', '#cdd9ff'];
    var spriteCache = {};
    function hexRgb(hex) {
      var h = String(hex).replace('#', '');
      if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
      return { r: parseInt(h.substr(0, 2), 16), g: parseInt(h.substr(2, 2), 16), b: parseInt(h.substr(4, 2), 16) };
    }
    function spriteFor(col) {
      var s = spriteCache[col];
      if (s) return s;
      var size = 96, c = document.createElement('canvas');
      c.width = size; c.height = size;
      var g = c.getContext('2d');
      var rgb = hexRgb(col);
      var grad = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
      grad.addColorStop(0, '#ffffff');
      grad.addColorStop(0.16, col);
      grad.addColorStop(1, 'rgba(' + rgb.r + ',' + rgb.g + ',' + rgb.b + ',0)');
      g.fillStyle = grad; g.fillRect(0, 0, size, size);
      spriteCache[col] = c;
      return c;
    }
    function ensureParticles() {
      var target = Math.min(2200, Math.max(900, Math.round((innerWidth * innerHeight) / 1100)));
      while (particles.length < target) {
        var i = particles.length + 1;
        var lane = rand(i * 5.9);
        particles.push({
          seed: i * 11.37,
          x: rand(i) * 2 - 1,
          y: rand(i * 2.7),
          lane: lane,
          size: 0.5 + rand(i * 4.2) * 1.8,
          col: lane < 0.70 ? BAND_COLORS[Math.floor(rand(i * 7.7) * BAND_COLORS.length) % BAND_COLORS.length]
                           : DUST_COLORS[Math.floor(rand(i * 8.3) * DUST_COLORS.length) % DUST_COLORS.length]
        });
      }
      if (particles.length > target + 120) particles.length = target;
    }
    function resizeGalaxy() {
      var dpr = Math.min(1.35, Math.max(1, window.devicePixelRatio || 1));
      canvas.width = Math.max(1, Math.floor(innerWidth * dpr));
      canvas.height = Math.max(1, Math.floor(innerHeight * dpr));
      canvas.style.width = innerWidth + 'px';
      canvas.style.height = innerHeight + 'px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ensureParticles();
    }
    function drawGalaxy(nowMs) {
      if (!running) return;
      var now = nowMs * 0.001;
      ensureParticles();
      var bass = 0;                  // 启动页不接音频分析器，等价「未播放」态
      var W = innerWidth, H = innerHeight;
      ctx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 1;
      ctx.fillStyle = '#000';
      ctx.fillRect(0, 0, W, H);
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      var cx = W * 0.5, cy = H * 0.58;
      var bandW = W * 0.98, bandH = H * 0.30;
      var flow = now * 0.030;        // 未播放态的沿带漂移（原版同值）
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        var tw = Math.pow(0.5 + 0.5 * Math.sin(now * (0.7 + rand(p.seed) * 0.5) + p.seed * 6.283), 3);
        var x, y, alpha, r;
        if (p.lane < 0.70) {
          var u = ((p.x + flow * 0.35) % 2 + 2) % 2 - 1;
          var arc = Math.sin(u * 2.2 + flow * 2.0 + p.seed) * H * 0.038;
          var fine = Math.sin(u * Math.PI * 2 * (0.55 + rand(p.seed) * 0.24) + now * 1.4 + p.seed * 5.7) * H * 0.014;
          var bandT = (p.lane / 0.70);
          var edge = 1 - Math.pow(Math.abs(bandT - 0.5) * 2, 1.35);
          edge = Math.max(0, Math.min(1, edge));
          x = cx + u * bandW * 0.5 + Math.sin(now * 0.5 + p.seed * 8.0) * 8;
          y = cy + arc + fine + (bandT - 0.5) * bandH * 1.35 + Math.sin(now * 0.9 + p.seed * 2.1) * 3.2;
          alpha = (0.12 + edge * 0.42 + tw * 0.16) + bass * 0.22;
          r = Math.max(0.6, p.size * (0.85 + tw * 0.6) * (0.9 + edge * 0.35) * (1 + bass * 0.30));
        } else {
          var ud = ((p.x + flow * 0.18) % 2 + 2) % 2 - 1;
          x = (ud * 0.5 + 0.5) * W + Math.sin(now * 0.5 + p.seed * 3.3) * 5;
          y = p.y * H + Math.cos(now * 0.42 + p.seed) * 6 + Math.sin(now * 0.22 + p.seed * 2.0) * 3;
          alpha = 0.10 + tw * 0.32 + bass * 0.12;
          r = Math.max(0.5, p.size * (0.62 + tw * 0.55) * (1 + bass * 0.25));
        }
        var spr = spriteFor(p.col);
        var ds = Math.max(3, r * 7);
        ctx.globalAlpha = Math.min(1, alpha);
        ctx.drawImage(spr, x - ds / 2, y - ds / 2, ds, ds);
      }
      ctx.restore();
      handle = requestAnimationFrame(drawGalaxy);
    }
    function galaxyStart() {
      if (running) return;
      running = true; resizeGalaxy(); handle = requestAnimationFrame(drawGalaxy);
    }
    function galaxyStop() {
      running = false;
      if (handle) { cancelAnimationFrame(handle); handle = 0; }
    }
    window.addEventListener('resize', function () { if (running) resizeGalaxy(); });

    /* ---- 显示 / 隐藏：不动 xterm，只盖一层 ---- */
    var touched = false;
    function valid(v) { return v === 'super' || v === 'terminal'; }
    function readPage() {
      var v = localStorage.getItem(KEY);
      return valid(v) ? v : 'super';
    }
    function setPage(v) {
      if (!valid(v)) return;
      localStorage.setItem(KEY, v);
      if (window.__cyrilSuperHomeSyncUI) window.__cyrilSuperHomeSyncUI(v);
    }
    function show() {
      if (document.body.classList.contains('super-on')) return;
      document.body.classList.add('super-on');
      root.setAttribute('aria-hidden', 'false');
      galaxyStart();
      if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
    }
    function hide() {
      if (!document.body.classList.contains('super-on')) return;
      document.body.classList.remove('super-on');
      root.setAttribute('aria-hidden', 'true');
      galaxyStop();
      setTimeout(function () {
        var ta = document.querySelector('#terminal textarea');
        if (ta) { try { ta.focus(); } catch (e) {} }
      }, 30);
    }

    /* ---- 节点 / 中心 / 顶栏返回 ---- */
    var GO = { music: 'btnMusic', file: 'btnTree', comp: 'btnComp', settings: 'btnSettings' };
    Array.prototype.forEach.call(root.querySelectorAll('[data-sh-go]'), function (b) {
      b.addEventListener('click', function () {
        touched = true;
        hide();
        var id = GO[b.getAttribute('data-sh-go')], el = id && document.getElementById(id);
        if (el) setTimeout(function () { el.click(); }, 0);
      });
    });
    var core = document.getElementById('shCore');
    if (core) core.addEventListener('click', function () { touched = true; hide(); });
    var homeBtn = document.getElementById('btnHome');
    if (homeBtn) homeBtn.addEventListener('click', function () { touched = true; show(); });

    /* xterm 初始化会把焦点抢回终端；补一次，避免首页里打字打进 PTY */
    setTimeout(function () {
      if (!document.body.classList.contains('super-on')) return;
      var a = document.activeElement;
      if (a && a.blur && a.closest && a.closest('#terminal')) a.blur();
    }, 300);

    /* ---- 与配置系统桥接 ---- */
    window.__cyrilSuperHome = {
      show: show, hide: hide, getPage: readPage,
      setPage: function (v) { touched = true; setPage(v); },
      isOn: function () { return document.body.classList.contains('super-on'); }
    };
    window.__cyrilSuperHomeApply = function (v) {
      if (touched || !valid(v)) return;   // 用户已操作过就别再翻回去
      setPage(v);
      if (v === 'terminal') hide(); else show();
    };

    /* 启动即按缓存决定，不等 WebSocket；Settings.json 到了再校正一次。 */
    if (readPage() === 'super') show();
  })();
  </script>
"""

# --------------------------------------------------------------------------
# 前端补丁表：(说明, old, new)
# --------------------------------------------------------------------------
HTML_PATCHES = [
    ("CSS 超级终端层", "\n</style>\n</head>", CSS + "\n</style>\n</head>"),
    ("header 返回按钮", "      <button id=\"btnMusic\"", HEADER_BTN + "      <button id=\"btnMusic\""),
    ("设置面板启动页分组",
     "      <div class=\"set-group\">\n        <div class=\"set-title\">服务 · 开放端口</div>",
     SET_GROUP + "      <div class=\"set-group\">\n        <div class=\"set-title\">服务 · 开放端口</div>"),
    ("body 超级终端层 + 脚本",
     "<script type=\"module\" src=\"folia-adapter.js\"></script>",
     BODY_HTML_AND_JS + "<script type=\"module\" src=\"folia-adapter.js\"></script>"),
    ("cfgApply 启动页桥接",
     "      if (window.__cyrilApplySettings) window.__cyrilApplySettings(data);",
     "      if (window.__cyrilApplySettings) window.__cyrilApplySettings(data);\n"
     "      // 启动页以 Settings.json 为准；localStorage 只是连上后端前那一帧的缓存\n"
     "      if (window.__cyrilSuperHomeApply) window.__cyrilSuperHomeApply(data.start_page);"),
    ("settingsInit 启动页控件",
     "    var elSaved = $('setSaved');\n    if (!elPort || !elSaved) return;",
     "    var elSaved = $('setSaved');\n    if (!elPort || !elSaved) return;\n"
     "\n"
     "    /* ---- 启动页 ----\n"
     "       选择落在 Settings.json 的 start_page；界面本身由前面那段独立的\n"
     "       超级终端脚本实现（window.__cyrilSuperHome），这里只管控件与落盘。 */\n"
     "    var elStartBtns = document.querySelectorAll('[data-start-page]');\n"
     "    var elOpenHome = $('setOpenHome');\n"
     "    function syncStartUI(v) {\n"
     "      Array.prototype.forEach.call(elStartBtns, function (b) {\n"
     "        b.classList.toggle('active', b.getAttribute('data-start-page') === v);\n"
     "      });\n"
     "    }\n"
     "    window.__cyrilSuperHomeSyncUI = syncStartUI;\n"
     "    function startPageVal() {\n"
     "      var on = document.querySelector('[data-start-page].active');\n"
     "      return on ? on.getAttribute('data-start-page') : 'super';\n"
     "    }\n"
     "    syncStartUI('super');"),
    ("DEF 默认值",
     "      remote: false, link_bars: false,",
     "      remote: false, link_bars: false,\n      start_page: 'super',"),
    ("__cyrilApplySettings 回读",
     "      showVals();\n      applyAll();\n    };\n\n    /* ---- 控件：滑块即时预览",
     "      if (d.start_page === 'super' || d.start_page === 'terminal') syncStartUI(d.start_page);\n"
     "      showVals();\n      applyAll();\n    };\n\n    /* ---- 控件：滑块即时预览"),
    ("saveAll 整包",
     "      var c = { port: curPort(), remote: !!elRemote.checked,\n"
     "                link_bars: !!elLink.checked, cursor: cursorCfg(), terminal: termCfg() };",
     "      var c = { port: curPort(), remote: !!elRemote.checked,\n"
     "                link_bars: !!elLink.checked, start_page: startPageVal(),\n"
     "                cursor: cursorCfg(), terminal: termCfg() };"),
    ("启动页按钮事件",
     "    $('setSave').addEventListener('click', saveAll);",
     "    /* 启动页：点选即落盘，只在「下次启动」生效，不改动当前已开的界面。 */\n"
     "    Array.prototype.forEach.call(elStartBtns, function (b) {\n"
     "      b.addEventListener('click', function () {\n"
     "        var v = b.getAttribute('data-start-page');\n"
     "        syncStartUI(v);\n"
     "        if (window.__cyrilSuperHome) window.__cyrilSuperHome.setPage(v);\n"
     "        patch({ start_page: v });\n"
     "        flashSaved('已保存 · 下次启动生效');\n"
     "      });\n"
     "    });\n"
     "    if (elOpenHome) elOpenHome.addEventListener('click', function () {\n"
     "      if (window.__cyrilSuperHome) window.__cyrilSuperHome.show();\n"
     "    });\n"
     "    $('setSave').addEventListener('click', saveAll);"),
    ("恢复默认",
     "      elPort.value = String(DEF.port); elRemote.checked = DEF.remote;",
     "      elPort.value = String(DEF.port); elRemote.checked = DEF.remote;\n"
     "      syncStartUI('super');\n"
     "      if (window.__cyrilSuperHome) window.__cyrilSuperHome.setPage('super');"),
]

# --------------------------------------------------------------------------
# 后端补丁：_settings_default 加 start_page
# --------------------------------------------------------------------------
SRV_PATCHES = [
    ("start_page 默认值",
     "        \"theme\": \"dark\",          # dark | light",
     "        # 启动页：super（银河超级终端）| terminal（直接进终端）\n"
     "        \"start_page\": \"super\",\n"
     "        \"theme\": \"dark\",          # dark | light"),
]


def apply(path, patches, transform=None):
    with io.open(path, encoding="utf-8", newline="") as f:
        s = f.read()
    orig = s
    for label, old, new in patches:
        if transform:
            old, new = transform(old), transform(new)
        n = s.count(old)
        if n != 1:
            raise RuntimeError("%s: 锚点「%s」命中 %d 次（应为 1）" % (path, label, n))
        s = s.replace(old, new, 1)
    if s == orig:
        raise RuntimeError(path + ": 没有发生任何改动")
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(s)
    return len(s.encode("utf-8"))


def main():
    # 先看是否已被打过（幂等保护：重复执行会破坏文件）
    with io.open(WIN_HTML, encoding="utf-8") as f:
        if 'id="superHome"' in f.read():
            print("两版前端已包含超级终端，跳过（如需重打请先还原备份）")
            return 0

    total = 0
    for path in (WIN_HTML, LNX_HTML):
        def tr(t, linux=(path == LNX_HTML)):
            return t.replace(PFX_WIN, PFX_LNX) if linux else t
        size = apply(path, HTML_PATCHES, tr)
        total += size
        print("  %-44s %d 字节  %d 处补丁" % (path, size, len(HTML_PATCHES)))

    for path in (WIN_SRV, LNX_SRV):
        size = apply(path, SRV_PATCHES)
        print("  %-44s %d 字节  %d 处补丁" % (path, size, len(SRV_PATCHES)))

    print("完成：共 %d 个字符写入" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
