'use strict';
/* WebTerm · 无边框桌面壳（渲染进程侧）
 * ------------------------------------------------------------------
 * 主进程用 frame:false + transparent:true 起窗口（Mineradio 同款做法）。
 *   1. 给 html/body 挂桌面壳 class + **常驻**圆角裁剪，再叠一圈可见的
 *      #cyrilFrame 圆角描边，让「边缘」实体化；
 *   2. 注入自绘标题栏（左上拖动区 + 右上最小化 / 最大化 / 关闭）；
 *   3. contextBridge 暴露最小 API，启动页和主界面都能用。
 *
 * 为什么不用 -webkit-app-region: drag：
 *   transparent + frameless 窗口在 Windows 上命中测试不走常规路径，CSS 的 drag
 *   区域实测完全拖不动（顶栏计算值明明是 drag，窗口却纹丝不动）。改成
 *   pointerdown → IPC → 主进程按 screen.getCursorScreenPoint() 轮询 setPosition()
 *   的手动拖动，100% 可控，且能顺带把「窗口边缘」也变成拖动区。
 *
 * 圆角两态（Mineradio 同款）：
 *   窗口态保留 34px 圆角；**只有真全屏**（isFullScreen / fullscreenActive）
 *   才挂 .cyril-fullscreen 摘掉圆角与描边，铺满整块屏幕。
 *   绝不能用 isMaximized() 判全屏：透明无边框窗口启动时常被误判 maximized，
 *   早期版本因此要么圆角从头没出现，要么普通窗口被摘成直角。
 */
const { contextBridge, ipcRenderer } = require('electron');

const CH = {
  min: 'cyril-win-min',
  max: 'cyril-win-max',
  close: 'cyril-win-close',
  state: 'cyril-win-state',
  stateChanged: 'cyril-window-state',
  fullscreenState: 'cyril-win-fullscreen',
  fullscreenChanged: 'cyril-fullscreen-state',
  dragStart: 'cyril-drag-start',
  dragEnd: 'cyril-drag-end',
  resizeStart: 'cyril-resize-start',
  resizeEnd: 'cyril-resize-end'
};

const api = {
  desktop: true,
  minimize: function () { ipcRenderer.send(CH.min); },
  toggleMaximize: function () { ipcRenderer.send(CH.max); },
  close: function () { ipcRenderer.send(CH.close); },
  isMaximized: function () { return ipcRenderer.invoke(CH.state); },
  isFullscreen: function () { return ipcRenderer.invoke(CH.fullscreenState); },
  onWindowState: function (cb) {
    const listener = function (_event, maxed) { cb(!!maxed); };
    ipcRenderer.on(CH.stateChanged, listener);
    return function () { ipcRenderer.removeListener(CH.stateChanged, listener); };
  },
  onFullscreenState: function (cb) {
    const listener = function (_event, full) { cb(!!full); };
    ipcRenderer.on(CH.fullscreenChanged, listener);
    return function () { ipcRenderer.removeListener(CH.fullscreenChanged, listener); };
  },
  dragStart: function () { ipcRenderer.send(CH.dragStart); },
  dragEnd: function () { ipcRenderer.send(CH.dragEnd); },
  resizeStart: function (dir) { ipcRenderer.send(CH.resizeStart, dir); },
  resizeEnd: function () { ipcRenderer.send(CH.resizeEnd); }
};
contextBridge.exposeInMainWorld('__cyrilDesktop', api);

/* 只有后端提供的 http 页面需要注入：loading.html 自己写了壳样式和按钮。 */
const SHELL_CSS = [
  /* 圆角一直不出现的真正原因：CSS 2.1 §14.2 规定，html 的 background-color
     为 transparent 且 background-image 为 none 时，浏览器会把 **body 的**
     background-color / background-image（包括用户设的壁纸）提升成 canvas 背景，
     平铺整个视口 —— 它绘制在根元素层，body 上的 clip-path 根本裁不到，于是
     四角永远是不透明的方角。Mineradio public/app.css 里那句
     `body.desktop-shell{background:transparent}` 就是同一个规避。
     这里不改成 transparent 背景色，而是给 html 一个「非 none 但完全透明」的
     background-image：按规范这就不满足传播条件，body 背景会正常画在 body 盒内
     并被圆角裁掉；同时窗口四角仍是 100% 透明，没有任何额外底色。 */
  'html.cyril-desktop-root {',
  '  background-color: transparent !important;',
  '  background-image: linear-gradient(rgba(0,0,0,0), rgba(0,0,0,0)) !important;',
  '  background-repeat: no-repeat !important;',
  '  border-radius: 34px !important;',
  '  clip-path: inset(0 round 34px) !important;',
  '  -webkit-clip-path: inset(0 round 34px) !important;',
  '}',
  /* 借鉴 Mineradio 的 #desktop-window-shell：圆角层要自己开一个合成层
     （transform:translateZ(0)），否则 fixed 定位的子层可能逃逸 clip-path，
     四角就会被方形深色背景补满，看起来仍是直角。 */
  'html.cyril-desktop-root body {',
  '  border-radius: 34px !important; overflow: hidden !important;',
  '  clip-path: inset(0 round 34px) !important;',
  '  -webkit-clip-path: inset(0 round 34px) !important;',
  '  transform: translateZ(0) !important;',
  '}',
  /* Mineradio 给 #wallpaper-board / #sonic-workshop-layer 单独裁圆角，
     同理这里给每一层铺满全屏的 fixed 背景层各自补一刀。 */
  'html.cyril-desktop-root #bgVideo,',
  'html.cyril-desktop-root #bgOverlay,',
  'html.cyril-desktop-root #radio,',
  'html.cyril-desktop-root #lumiere-canvas,',
  'html.cyril-desktop-root #sonic-canvas,',
  'html.cyril-desktop-root #loginScreen {',
  '  border-radius: 34px !important;',
  '  clip-path: inset(0 round 34px) !important;',
  '  -webkit-clip-path: inset(0 round 34px) !important;',
  '}',
  /* 一圈看得见的圆角边缘：既是装饰，也是「靠近边缘唤出 UI」的那条边。
     pointer-events:none —— 它绝不能吃掉终端 / 顶栏的任何点击。 */
  '#cyrilFrame { position: fixed; inset: 0; z-index: 2147482999;',
  '  border-radius: 34px; pointer-events: none;',
  '  border: 1px solid rgba(156,255,223,.16);',
  '  box-shadow: inset 0 0 0 1px rgba(255,255,255,.05),',
  '              inset 0 0 26px rgba(0,0,0,.28),',
  '              0 0 0 1px rgba(0,0,0,.35);',
  '}',
  /* 顶栏整条可拖动（含按钮之间的空隙、标题、状态标签）。bars-ready 隐藏态的
     pointer-events:none 会让按钮一起失效，桌面模式下强制顶栏仍可命中，
     但隐藏时单独关掉按钮点击，避免「看不见却能点」。拖动本身走下面的 IPC。 */
  'html.cyril-desktop-root body.cyril-desktop header { padding-right: 140px !important; }',
  'html.cyril-desktop-root body.cyril-desktop.bars-ready header {',
  '  pointer-events: auto !important;',
  '}',
  'html.cyril-desktop-root body.cyril-desktop.bars-ready:not(.head-on) header .btn,',
  'html.cyril-desktop-root body.cyril-desktop.bars-ready:not(.head-on) header #status {',
  '  pointer-events: none !important;',
  '}',
  '#cyrilWinBar { position: fixed; left: 0; right: 0; top: 0; height: 44px;',
  '  z-index: 2147483000; pointer-events: none; }',
  '#cyrilWinBar .cy-drag-top { position: absolute; left: 0; right: 0; top: 0; height: 9px;',
  '  pointer-events: auto; }',
  '#cyrilWinBar .cy-drag-left { position: absolute; left: 0; top: 0; width: 168px; height: 44px;',
  '  pointer-events: auto; }',
  '#cyrilWinBar .cy-btns { position: absolute; right: 14px; top: 8px; display: flex; gap: 6px;',
  '  pointer-events: auto; }',
  '#cyrilWinBar button { width: 38px; height: 30px; padding: 0; border: 0; border-radius: 10px;',
  '  background: rgba(4,8,10,.42); color: rgba(224,250,255,.72); cursor: pointer;',
  '  display: flex; align-items: center; justify-content: center;',
  '  transition: background .18s, color .18s, transform .18s; }',
  '#cyrilWinBar button svg { width: 14px; height: 14px; stroke: currentColor; stroke-width: 1.7;',
  '  fill: none; stroke-linecap: round; stroke-linejoin: round; }',
  '#cyrilWinBar button:hover { background: rgba(244,210,138,.16); color: #fff1bd; transform: translateY(-1px); }',
  '#cyrilWinBar button.cy-close:hover { background: rgba(255,86,100,.86); color: #fff; }',
  /* ===== 真全屏：四角必须铺满整块屏幕 =====
     Mineradio public/app.css 里对 .desktop-fullscreen 的 #desktop-window-shell /
     #wallpaper-board / #sonic-workshop-layer 都是 border-radius:0 + clip-path:none
     + box-shadow:none。这里同样只在 isFullScreen()/fullscreenActive 为真时挂
     .cyril-fullscreen；普通窗口（哪怕 isMaximized() 误报）继续保留 34px 圆角。 */
  'html.cyril-desktop-root.cyril-fullscreen,',
  'html.cyril-desktop-root.cyril-fullscreen body {',
  '  border-radius: 0 !important;',
  '  clip-path: none !important;',
  '  -webkit-clip-path: none !important;',
  '  box-shadow: none !important;',
  '}',
  'html.cyril-desktop-root.cyril-fullscreen #bgVideo,',
  'html.cyril-desktop-root.cyril-fullscreen #bgOverlay,',
  'html.cyril-desktop-root.cyril-fullscreen #radio,',
  'html.cyril-desktop-root.cyril-fullscreen #lumiere-canvas,',
  'html.cyril-desktop-root.cyril-fullscreen #sonic-canvas,',
  'html.cyril-desktop-root.cyril-fullscreen #loginScreen {',
  '  border-radius: 0 !important;',
  '  clip-path: none !important;',
  '  -webkit-clip-path: none !important;',
  '  box-shadow: none !important;',
  '}',
  'html.cyril-desktop-root.cyril-fullscreen #cyrilFrame { display: none !important; }',
  /* 缩放手柄的光标提示：只在鼠标落在边缘时挂类，避免常驻覆盖终端的 cursor */
  'html.cyril-resizing, html.cyril-resizing * {',
  '  cursor: var(--cy-resize-cursor, ew-resize) !important;',
  '}'
].join('\n');

const ICON_MAX = '<svg viewBox="0 0 14 14"><rect x="2.5" y="2.5" width="9" height="9" rx="1.2"/></svg>';
const ICON_RESTORE = '<svg viewBox="0 0 14 14"><path d="M4.5 4.5h7v7"/><rect x="2.5" y="2.5" width="7" height="7" rx="1.2"/></svg>';
const ICON_MIN = '<svg viewBox="0 0 14 14"><path d="M2.5 7h9"/></svg>';
const ICON_CLOSE = '<svg viewBox="0 0 14 14"><path d="M3.2 3.2l7.6 7.6M10.8 3.2l-7.6 7.6"/></svg>';

function makeButton(act, label, icon, extra) {
  const b = document.createElement('button');
  b.type = 'button';
  b.title = label;
  b.setAttribute('aria-label', label);
  b.setAttribute('data-act', act);
  if (extra) b.className = extra;
  b.innerHTML = icon;
  return b;
}

function applyState(maxed) {
  /* 只切按钮图标 / 保留 class 供调试，不再拿它摘圆角 */
  document.body.classList.toggle('cyril-maximized', !!maxed);
  const maxBtn = document.querySelector('#cyrilWinBar button[data-act="max"]');
  if (maxBtn) {
    maxBtn.innerHTML = maxed ? ICON_RESTORE : ICON_MAX;
    maxBtn.title = maxed ? '还原' : '最大化';
    maxBtn.setAttribute('aria-label', maxBtn.title);
  }
}

/* 全屏 / 窗口是两条独立状态：只有真全屏才摘圆角、关掉缩放手柄。 */
let isFullscreen = false;
function applyFullscreen(full) {
  isFullscreen = !!full;
  document.documentElement.classList.toggle('cyril-fullscreen', isFullscreen);
  document.body.classList.toggle('cyril-fullscreen', isFullscreen);
  if (isFullscreen) document.documentElement.classList.remove('cyril-resizing');
}

/* ===== 手动拖动 + 手动缩放 =====
   CSS 的 -webkit-app-region:drag 在这个透明窗口上不生效，改由主进程按屏幕
   光标增量移动窗口。顶栏 + 四周边缘一圈都是拖动区；按钮、输入框、浮层、
   文件树、终端正文一律不触发，免得选文字 / 点按钮时把窗口拖跑。

   frame:false 没有系统边框，透明窗口的原生边缘缩放也不稳定；更糟的是原来
   四周 28px 全被判成「拖动区」，用户拖边缘只会把窗口挪走，尺寸永远不变。
   现在边缘 9px / 底部两角 26px 优先判为缩放，走 IPC 让主进程 setBounds。 */
const DRAG_EDGE = 28;
const RESIZE_EDGE = 9, RESIZE_CORNER = 26, TITLE_H = 44;
const RESIZE_CURSOR = {
  w: 'ew-resize', e: 'ew-resize', s: 'ns-resize',
  sw: 'nesw-resize', se: 'nwse-resize'
};
const NO_DRAG = 'button, input, select, textarea, a, [contenteditable="true"],' +
  ' .float-panel, #loginScreen, #fileTree, #leftRail, #railHint, .panel-x';

function isInteractive(t) { return !!(t && t.closest && t.closest(NO_DRAG)); }
function isDragArea(t, x, y) {
  if (!t || !t.closest || isInteractive(t)) return false;
  if (t.closest('header') || t.closest('#cyrilWinBar')) return true;
  return x <= DRAG_EDGE || x >= window.innerWidth - DRAG_EDGE ||
         y <= DRAG_EDGE || y >= window.innerHeight - DRAG_EDGE;
}
/* 顶栏整条是拖动区（用户明确要求），所以顶部不做缩放。底部两角的命中区放宽
   到 26px，让落点处在 34px 圆角的圆内 —— 角尖被 clip-path 裁掉的那几像素
   本来也收不到 pointerdown（Mineradio 用独立 shell 层规避，我们用坐标兜底）。 */
function hitResize(t, x, y) {
  if (isFullscreen || isInteractive(t)) return null;
  const w = window.innerWidth, h = window.innerHeight;
  if (y <= TITLE_H || y >= h) return null;
  if (y >= h - RESIZE_CORNER && x >= w - RESIZE_CORNER) return 'se';
  if (y >= h - RESIZE_CORNER && x <= RESIZE_CORNER) return 'sw';
  if (y >= h - RESIZE_EDGE) return 's';
  if (x >= w - RESIZE_EDGE) return 'e';
  if (x <= RESIZE_EDGE) return 'w';
  return null;
}
function bindManualDrag() {
  let dragging = false, resizing = false;
  function setCursor(dir) {
    const root = document.documentElement;
    root.classList.toggle('cyril-resizing', !!dir);
    if (dir) root.style.setProperty('--cy-resize-cursor', RESIZE_CURSOR[dir] || 'ew-resize');
  }
  document.addEventListener('pointermove', function (ev) {
    if (dragging || resizing) return;
    setCursor(hitResize(ev.target, ev.clientX, ev.clientY));
  }, { capture: true, passive: true });
  document.addEventListener('pointerdown', function (ev) {
    if (ev.button !== 0 || dragging || resizing) return;
    const dir = hitResize(ev.target, ev.clientX, ev.clientY);
    if (dir) { resizing = true; setCursor(null); api.resizeStart(dir); return; }
    if (!isDragArea(ev.target, ev.clientX, ev.clientY)) return;
    dragging = true;
    api.dragStart();
  }, { capture: true, passive: true });
  function end() {
    if (dragging) { dragging = false; api.dragEnd(); }
    if (resizing) { resizing = false; api.resizeEnd(); }
  }
  window.addEventListener('pointerup', end);
  window.addEventListener('pointercancel', end);
  window.addEventListener('blur', end);
}

function injectShell() {
  if (document.getElementById('cyrilWinBar')) return;   // 已经有壳（或重复导航）就别叠加
  document.documentElement.classList.add('cyril-desktop-root');
  document.body.classList.add('cyril-desktop');

  const style = document.createElement('style');
  style.id = 'cyril-desktop-css';
  style.textContent = SHELL_CSS;
  (document.head || document.documentElement).appendChild(style);

  const frame = document.createElement('div');
  frame.id = 'cyrilFrame';
  document.body.appendChild(frame);

  const bar = document.createElement('div');
  bar.id = 'cyrilWinBar';
  const dragTop = document.createElement('div');
  dragTop.className = 'cy-drag-top';
  const dragLeft = document.createElement('div');
  dragLeft.className = 'cy-drag-left';
  const btns = document.createElement('div');
  btns.className = 'cy-btns';
  btns.appendChild(makeButton('min', '最小化', ICON_MIN));
  btns.appendChild(makeButton('max', '最大化', ICON_MAX));
  btns.appendChild(makeButton('close', '关闭', ICON_CLOSE, 'cy-close'));
  bar.appendChild(dragTop);
  bar.appendChild(dragLeft);
  bar.appendChild(btns);
  document.body.appendChild(bar);

  btns.addEventListener('click', function (ev) {
    const btn = ev.target.closest ? ev.target.closest('button[data-act]') : null;
    if (!btn) return;
    const act = btn.getAttribute('data-act');
    if (act === 'min') api.minimize();
    else if (act === 'max') api.toggleMaximize();
    else if (act === 'close') api.close();
  });
  /* 自绘拖动区没有系统标题栏的「双击最大化」，补上：顶栏空白处双击等同
     双击原生标题栏；按钮上双击不触发，避免误操作。 */
  [dragTop, dragLeft].forEach(function (el) {
    el.addEventListener('dblclick', function () { api.toggleMaximize(); });
  });
  document.addEventListener('dblclick', function (ev) {
    const t = ev.target;
    if (!t || !t.closest) return;
    if (!t.closest('header')) return;
    if (t.closest('button, input, select, textarea, a')) return;
    api.toggleMaximize();
  });

  bindManualDrag();
  api.onWindowState(applyState);
  api.isMaximized().then(applyState).catch(function () {});
  api.onFullscreenState(applyFullscreen);
  api.isFullscreen().then(applyFullscreen).catch(function () {});
}

/* file:// 的启动页自带按钮，只在 http(s) 的主界面注入。 */
if (/^https?:$/.test(window.location.protocol)) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectShell, { once: true });
  } else {
    injectShell();
  }
}
