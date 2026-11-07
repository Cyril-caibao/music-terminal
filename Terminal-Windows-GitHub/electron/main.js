'use strict';
/* WebTerm · Electron 桌面壳
 * ------------------------------------------------------------------
 * 不是把服务端用 Node 重写一遍：仍然是原封不动的 Python/PyInstaller 后端
 * （dist/WebTerm.exe），Electron 只负责：
 *   1. 起一个隐藏窗口的后端进程（--port <空闲端口> --no-browser）；
 *   2. 等它 /api/me 通了，把 BrowserWindow 指到 127.0.0.1:<port>/；
 *   3. 关窗时用 taskkill /T 把 onefile 解出来的子进程一起收干净。
 * 这样 Web 版 / Electron 版共用同一份 web/index.html 和同一套后端逻辑，
 * 功能、登录（回环免登录管理员）、Settings.json、PTY 行为完全一致。
 * 打包时 dist/WebTerm.exe 通过 --extra-resource 放进 resources/ 根目录。
 *
 * 无边框（Mineradio 同款）：frame:false + transparent:true + backgroundColor
 * '#00000000'，窗口四角由渲染页面的 body 用 clip-path 裁圆；原生标题栏换成
 * preload.js 注入的自绘标题栏（启动页 loading.html 自带一套）。
 */
const { app, BrowserWindow, Menu, dialog, ipcMain, screen } = require('electron');
const { spawn, execFile } = require('child_process');
const http = require('http');
const net = require('net');
const path = require('path');
const fs = require('fs');

const DEV_BACKEND = path.join(__dirname, '..', 'dist', 'WebTerm.exe');
const PACKED_BACKEND = path.join(process.resourcesPath || '', 'WebTerm.exe');
const BACKEND = (process.resourcesPath && fs.existsSync(PACKED_BACKEND))
  ? PACKED_BACKEND : DEV_BACKEND;
const PRELOAD = path.join(__dirname, 'preload.js');

let backend = null;
let win = null;
let stopped = false;
let LOG_FILE = '';
/* Windows 的透明无边框窗口可能已经盖满屏幕、isFullScreen() 却仍返回 false，
   所以自己维护一份全屏状态（Mineradio desktop/main.js 同款做法）。 */
let fullscreenActive = false;

/* 借鉴 Mineradio 的 getWindowedBounds：初始尺寸也必须留在工作区内。
   之前写死 1440×900，在 1280×800 的屏上被系统裁成 1280×800 —— 看着像全屏，
   其实只是普通窗口铺满屏幕，任务栏照样盖在上面，isFullScreen() 也跟着乱报。 */
function windowedBounds() {
  try {
    const a = screen.getPrimaryDisplay().workArea;
    const w = Math.min(1440, Math.max(940, a.width - 80));
    const h = Math.min(900, Math.max(560, a.height - 80));
    return {
      x: a.x + Math.round((a.width - w) / 2),
      y: a.y + Math.round((a.height - h) / 2),
      width: w, height: h
    };
  } catch (e) { return { width: 1440, height: 900 }; }
}
/* 借鉴 Mineradio 的 applyWindowedBounds / setMainWindowFullscreenResizeGuard：
   还原窗口尺寸这一步必须同时把「全屏期间锁住的 resizable」放回来，否则用户
   退出全屏后就再也拖不动窗口边缘（这正是「窗口时无法调整大小」的根因之一）。 */
function applyWindowedBounds() {
  if (!win || win.isDestroyed()) return;
  try {
    if (typeof win.isResizable === 'function') {
      if (!win.isResizable()) win.setResizable(true);
    } else win.setResizable(true);
  } catch (e) { try { win.setResizable(true); } catch (_) {} }
  if (win.isMaximized()) win.unmaximize();
  win.setMinimumSize(940, 560);
  win.setBounds(windowedBounds(), false);
}

/* GUI 进程没有控制台，日志落到 userData/main.log，排查启动失败时看它 */
function log() {
  const line = '[WebTerm/Electron] ' + [].slice.call(arguments).join(' ') + '\n';
  if (!LOG_FILE) {
    /* 用系统 TEMP 而不是 app.getPath：后者在 asar 里可能落到不可写的路径，
       日志静默丢失就完全没法排查了 */
    LOG_FILE = path.join(process.env.TEMP || process.env.TMP || 'C:\\Windows\\Temp',
                         'webterm-electron-main.log');
  }
  try { fs.appendFileSync(LOG_FILE, line); } catch (e) {}
  console.log(line.trim());
}
log('module loaded');

/* 优先用固定 8765（localStorage 的 origin 才稳定，外观/树宽不会因换端口丢）；
   被占用时让系统给一个空闲端口。 */
function pickPort(cb) {
  const s = net.createServer();
  s.once('error', function () {
    try { s.close(); } catch (e) {}
    const t = net.createServer();
    t.listen(0, '127.0.0.1', function () {
      const p = t.address().port;
      t.close(function () { cb(p); });
    });
  });
  s.listen(8765, '127.0.0.1', function () {
    const p = s.address().port;
    s.close(function () { cb(p); });
  });
}

/* ===== 【设置】里的端口 / 局域网开关 =====
   后端 exe 和 Settings.json 同目录（打包后是 resources/）。网页【设置】写进去的
   port / remote 会由后端自己读，但 Electron 壳必须先知道要用哪个端口去 loadURL，
   所以这里也读一份。命令行 --port / --remote 优先级最高（方便快捷方式临时覆盖）。
   之前壳只传 --port X --no-browser，用户给 Electron.exe 加 --remote 完全没用：
   参数被 Electron 自己吃掉了，从没转发给 resources/WebTerm.exe。 */
function readSvcCfg() {
  const out = { port: null, remote: false };
  try {
    const p = path.join(path.dirname(BACKEND), 'Settings.json');
    if (fs.existsSync(p)) {
      const d = JSON.parse(fs.readFileSync(p, 'utf8'));
      if (d && typeof d === 'object') {
        if (Number.isInteger(d.port) && d.port >= 1 && d.port <= 65535) out.port = d.port;
        out.remote = !!d.remote;
      }
    }
  } catch (e) { log('readSvcCfg failed:', e.message); }
  return out;
}

function cliOpts() {
  const a = process.argv.slice(1);
  const out = { port: null, remote: false };
  for (let i = 0; i < a.length; i++) {
    if (a[i] === '--remote') out.remote = true;
    else if (a[i] === '--port' && a[i + 1]) { out.port = parseInt(a[i + 1], 10); i++; }
    else if (/^--port=\d+$/.test(a[i])) out.port = parseInt(a[i].slice(7), 10);
  }
  if (!(Number.isInteger(out.port) && out.port >= 1 && out.port <= 65535)) out.port = null;
  return out;
}

/* 选服务：① 配置端口上已经有 WebTerm 在跑 → 直接复用，别再起第二个（用户
   常常先开了 Web 版 exe）；② 配置端口空闲 → 就用它；③ 被别的程序占了 →
   退回原来的 8765/随机端口逻辑，绝不因为端口冲突就启动失败。 */
function chooseService(preferred, cb) {
  if (!preferred) {
    return pickPort(function (p) { cb({ port: p, reuse: false }); });
  }
  probe(preferred, function (exists) {
    if (exists) { log('reuse existing backend on', preferred); cb({ port: preferred, reuse: true }); return; }
    const s = net.createServer();
    s.once('error', function () {
      try { s.close(); } catch (e) {}
      log('configured port', preferred, 'busy, fallback');
      pickPort(function (p) { cb({ port: p, reuse: false }); });
    });
    s.listen(preferred, '127.0.0.1', function () {
      s.close(function () { cb({ port: preferred, reuse: false }); });
    });
  });
}

function probe(p, cb) {
  const req = http.get({ host: '127.0.0.1', port: p, path: '/api/me', timeout: 1500 }, function (res) {
    res.resume();
    cb(res.statusCode === 200);
  });
  req.on('error', function () { cb(false); });
  req.on('timeout', function () { req.destroy(); cb(false); });
}

function waitReady(p, cb) {
  let tries = 0;
  (function tick() {
    if (stopped) return;
    if (backend && backend.exitCode !== null) { log('backend exited early, code =', backend.exitCode); cb(false); return; }
    probe(p, function (ok) {
      if (ok) { log('probe ok on port', p); cb(true); return; }
      if (++tries > 180) { cb(false); return; }   /* onefile 解压慢，最多等 90s */
      setTimeout(tick, 500);
    });
  })();
}

function startBackend(p, remote) {
  if (!fs.existsSync(BACKEND)) {
    dialog.showErrorBox('WebTerm',
      '找不到后端服务：\n' + BACKEND + '\n\n请先构建 dist/WebTerm.exe（Web 版）再打 Electron 包。');
    app.quit();
    return false;
  }
  /* --remote 必须显式转发：Electron 自己收到的 --remote 不会自动传给子进程。
     后端收到后监听 0.0.0.0 并放行防火墙，局域网设备才连得上（本机回环仍免登录）。 */
  const args = ['--port', String(p), '--no-browser'];
  if (remote) args.push('--remote');
  log('spawn', BACKEND, args.join(' '));
  backend = spawn(BACKEND, args, {
    cwd: path.dirname(BACKEND),
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe']
  });
  /* 必须持续消费 stdout/stderr，否则管道写满会把后端卡死 */
  backend.stdout.on('data', function (d) { process.stdout.write('[backend] ' + d); });
  backend.stderr.on('data', function (d) { process.stderr.write('[backend] ' + d); });
  backend.on('error', function (e) { log('backend error:', e.message); });
  backend.on('exit', function (code) { log('backend exit:', code); });
  return true;
}

function killBackend() {
  if (stopped) return;
  stopped = true;
  if (!backend) return;
  const pid = backend.pid;
  try { backend.kill(); } catch (e) {}
  /* PyInstaller onefile 会再拉一个子进程，只 kill 父进程会留孤儿占着端口 */
  if (process.platform === 'win32' && pid) {
    try { execFile('taskkill', ['/PID', String(pid), '/T', '/F'], function () {}); } catch (e) {}
  }
}

/* ===== 无边框窗口控制 =====
   frame:false 之后系统标题栏没了，最小化/最大化/关闭只能由渲染页的自绘按钮
   通过 IPC 调回来。两个状态必须分开下发：
     - maxed：只用来切按钮图标，允许 isMaximized()（透明窗口上会误报，无伤大雅）；
     - fullscreen：只认 isFullScreen() / fullscreenActive，专管「摘圆角 + 隐藏缩放
       手柄」。绝不能拿 isMaximized() 判全屏 —— 普通透明窗口启动时常被判成
       maximized，一摘就把窗口态的圆角也弄没了（Mineradio 的 desktop-fullscreen
       与 desktop-maximized 也是两个独立 class）。
   Mineradio public/app.css：body.desktop-shell.desktop-fullscreen
   #desktop-window-shell { border-radius:0; clip-path:none; box-shadow:none }。 */
function sendWindowState() {
  if (!win || win.isDestroyed()) return;
  const full = !!(win.isFullScreen() || fullscreenActive);
  const maxed = full || win.isMaximized();
  try {
    win.webContents.send('cyril-window-state', maxed);
    win.webContents.send('cyril-fullscreen-state', full);
  } catch (e) {}
}

/* 退出全屏兜底：透明无边框窗口在 Windows 上偶尔不发 leave-full-screen。
   解锁 resizable 无论如何都要做；边界还原只做一次（事件来了就取消兜底，
   免得和 leave-full-screen 形成 move/resize 风暴 —— Mineradio 的注释也这么说）。 */
let fsRestorePending = false;
function requestWindowedBounds() {
  if (!win || win.isDestroyed()) return;
  try {
    if (typeof win.isResizable === 'function' && !win.isResizable()) win.setResizable(true);
  } catch (e) { try { win.setResizable(true); } catch (_) {} }
  fsRestorePending = true;
  setTimeout(function () {
    if (!fsRestorePending || !win || win.isDestroyed()) return;
    fsRestorePending = false;
    log('fullscreen exit fallback: leave-full-screen did not fire');
    applyWindowedBounds();
    sendWindowState();
  }, 320);
}

function registerWindowIpc() {
  ipcMain.on('cyril-win-min', function () {
    if (win && !win.isDestroyed()) win.minimize();
  });
  /* 「最大化」= 真正全屏：普通 maximize() 只占工作区，Windows 任务栏照样露着。
     借鉴 Mineradio：先 setBounds 到 display.bounds（整块屏幕，含任务栏区域），
     锁 resizable，再 setFullScreen(true)；退出时由 leave-full-screen 还原居中窗口。
     兜底 requestWindowedBounds() 保证即使那个事件不来，resizable 也一定解锁。 */
  ipcMain.on('cyril-win-max', function () {
    if (!win || win.isDestroyed()) return;
    log('toggle fullscreen, current =', !!(win.isFullScreen() || fullscreenActive));
    if (win.isFullScreen() || fullscreenActive) {
      fullscreenActive = false;
      win.setFullScreen(false);
      requestWindowedBounds();
      sendWindowState();
      return;
    }
    if (win.isMaximized()) win.unmaximize();
    const d = screen.getDisplayMatching(win.getBounds()) || screen.getPrimaryDisplay();
    const b = d.bounds;
    fsRestorePending = false;
    win.setBounds({ x: b.x, y: b.y, width: b.width, height: b.height }, false);
    try { win.setResizable(false); } catch (e) {}
    fullscreenActive = true;
    win.setFullScreen(true);
    sendWindowState();
  });
  ipcMain.on('cyril-win-close', function () {
    if (win && !win.isDestroyed()) win.close();
  });
  ipcMain.handle('cyril-win-state', function () {
    return !!(win && !win.isDestroyed() &&
      (win.isMaximized() || win.isFullScreen() || fullscreenActive));
  });
  ipcMain.handle('cyril-win-fullscreen', function () {
    return !!(win && !win.isDestroyed() && (win.isFullScreen() || fullscreenActive));
  });

  /* ===== 手动拖动窗口 =====
     transparent + frameless 窗口下 -webkit-app-region:drag 实测不生效，
     改由渲染进程发 pointerdown / pointerup，主进程按屏幕光标增量移动窗口。
     用 screen.getCursorScreenPoint() 轮询而不是 DOM 的 mousemove：鼠标拖出
     窗口边界后页面收不到事件，轮询不会丢。 */
  let dragTimer = null;
  /* ===== 手动缩放窗口 =====
     frame:false 没有系统边框，原生边缘缩放在透明窗口上并不总是生效；而且
     渲染页原先把四周 28px 都当成「拖动区」，用户拖边缘只会把窗口挪走、
     尺寸永远不变。现在由 preload 注入 5 条自绘手柄（左/右/下/左下/右下），
     主进程按同一套屏幕光标增量算 setBounds，并夹在 minWidth/minHeight 内。 */
  let resizeTimer = null;
  const MIN_W = 940, MIN_H = 560;
  const RESIZE_DIRS = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'];

  ipcMain.on('cyril-drag-start', function () {
    if (dragTimer || resizeTimer || !win || win.isDestroyed() ||
        win.isFullScreen() || fullscreenActive) return;
    log('drag start');
    if (win.isMaximized()) win.unmaximize();        // 拖最大化窗口 = 先还原（Windows 习惯）
    const origin = screen.getCursorScreenPoint();
    const pos = win.getPosition();
    dragTimer = setInterval(function () {
      if (!win || win.isDestroyed()) {
        clearInterval(dragTimer); dragTimer = null; return;
      }
      const cur = screen.getCursorScreenPoint();
      win.setPosition(pos[0] + cur.x - origin.x, pos[1] + cur.y - origin.y);
    }, 16);
  });
  ipcMain.on('cyril-drag-end', function () {
    if (dragTimer) { clearInterval(dragTimer); dragTimer = null; log('drag end'); }
  });

  ipcMain.on('cyril-resize-start', function (_ev, dir) {
    if (resizeTimer || dragTimer || !win || win.isDestroyed()) return;
    if (win.isFullScreen() || fullscreenActive || win.isMaximized()) return;
    const d = RESIZE_DIRS.indexOf(dir) >= 0 ? dir : 'se';
    const origin = screen.getCursorScreenPoint();
    const b0 = win.getBounds();
    let lastKey = '';
    log('resize start', d);
    resizeTimer = setInterval(function () {
      if (!win || win.isDestroyed()) {
        clearInterval(resizeTimer); resizeTimer = null; return;
      }
      const cur = screen.getCursorScreenPoint();
      const dx = cur.x - origin.x, dy = cur.y - origin.y;
      let x = b0.x, y = b0.y, w = b0.width, h = b0.height;
      if (d.indexOf('e') >= 0) w = b0.width + dx;
      if (d.indexOf('s') >= 0) h = b0.height + dy;
      if (d.indexOf('w') >= 0) { w = b0.width - dx; x = b0.x + dx; }
      if (d.indexOf('n') >= 0) { h = b0.height - dy; y = b0.y + dy; }
      /* 从左边/上边缩到最小时，把 x/y 反向补回去，右下角才不会跟着跑 */
      if (w < MIN_W) { if (d.indexOf('w') >= 0) x -= (MIN_W - w); w = MIN_W; }
      if (h < MIN_H) { if (d.indexOf('n') >= 0) y -= (MIN_H - h); h = MIN_H; }
      const nx = Math.round(x), ny = Math.round(y), nw = Math.round(w), nh = Math.round(h);
      const key = nx + '|' + ny + '|' + nw + '|' + nh;
      if (key === lastKey) return;                 // 光标没动就别触发 resize 事件风暴
      lastKey = key;
      win.setBounds({ x: nx, y: ny, width: nw, height: nh }, false);
    }, 16);
  });
  ipcMain.on('cyril-resize-end', function () {
    if (resizeTimer) { clearInterval(resizeTimer); resizeTimer = null; log('resize end'); }
  });
}

app.whenReady().then(function () {
  Menu.setApplicationMenu(null);
  registerWindowIpc();
  log('app ready, backend =', BACKEND, 'exists =', fs.existsSync(BACKEND));
  const cli = cliOpts();
  const cfg = readSvcCfg();
  const servicePort = cli.port || cfg.port;      // 命令行 > Settings.json
  const remote = cli.remote || cfg.remote;
  log('service config: port =', servicePort, 'remote =', remote,
      'cli =', JSON.stringify(cli), 'cfg =', JSON.stringify(cfg));
  chooseService(servicePort, function (svc) {
    const p = svc.port;
    log('picked port', p, 'reuse =', svc.reuse);
    win = new BrowserWindow({
      ...windowedBounds(),
      minWidth: 940, minHeight: 560,
      title: 'WebTerm',
      /* 无边框 + 透明：四角由页面 body 的 clip-path 裁圆，圆外透出桌面。
         resizable 显式打开：frame:false 下系统不再画缩放边框，但原生 resize
         仍要留着（自绘手柄走 IPC setBounds，两条路都可用）。全屏期间才锁它。 */
      resizable: true,
      show: false,
      frame: false,
      transparent: true,
      backgroundColor: '#00000000',
      hasShadow: true,
      roundedCorners: true,
      autoHideMenuBar: true,
      webPreferences: {
        preload: PRELOAD,
        nodeIntegration: false,
        contextIsolation: true,
        webSecurity: true
      }
    });
    win.once('ready-to-show', function () {
      if (!win || win.isDestroyed()) return;
      try {
        if (typeof win.isResizable === 'function' && !win.isResizable()) win.setResizable(true);
      } catch (e) {}
      win.show();
      sendWindowState();
    });
    ['maximize', 'unmaximize', 'enter-full-screen', 'leave-full-screen'].forEach(function (ev) {
      win.on(ev, sendWindowState);
    });
    /* 全屏期间锁住窗口尺寸，退出时按权威的 leave-full-screen 事件还原一次，
       不在 IPC 里补延迟 setBounds（会和 move/resize 事件打架，Mineradio 的坑）。
       初始窗口现在留在工作区内，不会再被误判成全屏，enter-full-screen 可以
       安心同步状态（isFullScreen() 在事件触发瞬间可能还是 false）。 */
    win.on('enter-full-screen', function () {
      fsRestorePending = false;
      fullscreenActive = true;
      sendWindowState();
      log('enter-full-screen');
    });
    win.on('leave-full-screen', function () {
      fullscreenActive = false;
      fsRestorePending = false;       // 权威事件来了就取消兜底，避免重复 setBounds
      applyWindowedBounds();
      sendWindowState();
      log('leave-full-screen');
    });
    win.loadFile('loading.html');
    win.on('closed', function () { win = null; });

    function onReady(ok) {
      if (!win) return;
      if (!ok) {
        log('backend NOT ready on port', p);
        dialog.showErrorBox('WebTerm', '后端服务启动超时或提前退出。\n端口：' + p +
          '\n可先直接运行 dist/WebTerm.exe 检查报错。');
        return;
      }
      log('backend ready, loadURL', p);
      win.loadURL('http://127.0.0.1:' + p + '/');
    }
    if (svc.reuse) {
      /* 端口上已有 WebTerm（多半是用户先开了 Web 版 exe）：直接连它，
         别 spawn 第二个后端，关窗时也就不该去 kill 别人的进程。 */
      waitReady(p, onReady);
    } else {
      if (!startBackend(p, remote)) return;
      log('backend spawned, pid =', backend.pid);
      waitReady(p, onReady);
    }
  });
});

app.on('window-all-closed', function () { killBackend(); app.quit(); });
app.on('before-quit', killBackend);
app.on('will-quit', killBackend);
