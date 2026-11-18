/* 绘光主题联动 + 拼贴终端最小化：真实浏览器（headless Chrome + CDP）验证。
 *
 * 为什么要真浏览器：主题是否真的换到 runtime、最小化后 xterm 是否还活着、
 * 收起再展开有没有把 PTY 行列打歪，这几件事在 Node DOM mock 里都验不出来。
 *
 * 用法：node _lumiere_theme_test.cjs [baseUrl]
 *   前提：web 服务已在 baseUrl 上跑起来（python server.py --port 18765 --no-browser）
 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18765/';
const PORT = 9337;
const OUT = path.join(__dirname, '_maintenance', '20261118');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const pass = [];
const fail = [];
function ck(cond, label, extra) {
  (cond ? pass : fail).push(label);
  console.log((cond ? 'PASS ' : 'FAIL ') + label + (extra !== undefined ? '  ' + JSON.stringify(extra) : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

/* ------------------ 极简 CDP 客户端（手写 WS 帧，不装任何依赖） ------------------ */
function encodeFrame(obj) {
  const data = Buffer.from(JSON.stringify(obj), 'utf8');
  const mask = crypto.randomBytes(4);
  let hdr;
  if (data.length < 126) { hdr = Buffer.alloc(2); hdr[1] = 0x80 | data.length; }
  else if (data.length < 65536) { hdr = Buffer.alloc(4); hdr[1] = 0x80 | 126; hdr.writeUInt16BE(data.length, 2); }
  else { hdr = Buffer.alloc(10); hdr[1] = 0x80 | 127; hdr.writeBigUInt64BE(BigInt(data.length), 2); }
  hdr[0] = 0x81;
  const body = Buffer.from(data);
  for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
  return Buffer.concat([hdr, mask, body]);
}

function connectWS(pathname) {
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const sock = net.connect(PORT, '127.0.0.1', () => {
      sock.write('GET ' + pathname + ' HTTP/1.1\r\nHost: 127.0.0.1:' + PORT +
        '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ' + key +
        '\r\nSec-WebSocket-Version: 13\r\n\r\n');
    });
    let buf = Buffer.alloc(0), open = false, acc = '', accOp = -1;
    const listeners = [];
    const api = {
      send(obj) { sock.write(encodeFrame(obj)); },
      onText(fn) { listeners.push(fn); },
      close() { try { sock.destroy(); } catch (e) {} },
    };
    sock.on('error', reject);
    sock.on('data', d => {
      buf = Buffer.concat([buf, d]);
      if (!open) {
        const i = buf.indexOf('\r\n\r\n');
        if (i < 0) return;
        const head = buf.slice(0, i).toString();
        buf = buf.slice(i + 4);
        if (!/ 101 /.test(head)) return reject(new Error('握手失败: ' + head.split('\r\n')[0]));
        open = true;
        resolve(api);
      }
      for (;;) {
        if (buf.length < 2) return;
        const b0 = buf[0], b1 = buf[1];
        let len = b1 & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const op = b0 & 0x0f;
        const payload = buf.slice(off, off + len);
        buf = buf.slice(off + len);
        if (op === 8) { sock.destroy(); return; }
        if (op === 9) continue;                       // ping
        if (op === 1) { acc = payload.toString('utf8'); accOp = 1; }
        else if (op === 0) acc += payload.toString('utf8');
        else continue;
        if ((b0 & 0x80) || accOp === 1) {
          const text = acc; acc = ''; accOp = -1;
          listeners.forEach(fn => fn(text));
        }
      }
    });
  });
}

function httpJSON(p) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path: p }, res => {
      let s = '';
      res.on('data', d => s += d);
      res.on('end', () => { try { resolve(JSON.parse(s)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const profile = path.join(os.tmpdir(), 'wt-cdp-9337');
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) {}

  const chrome = spawn(CHROME, [
    '--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
    BASE,
  ], { stdio: 'ignore' });

  let targets = null;
  for (let i = 0; i < 40; i++) {
    try { targets = await httpJSON('/json/list'); if (targets && targets.length) break; } catch (e) {}
    await sleep(250);
  }
  const page = (targets || []).find(t => t.type === 'page' && t.url.indexOf('127.0.0.1') >= 0);
  if (!page) { console.log('FAIL 拿不到页面 target'); chrome.kill(); process.exit(1); }

  const ws = await connectWS(new URL(page.webSocketDebuggerUrl).pathname);
  let id = 0;
  const pending = new Map();
  const consoleErrors = [];
  ws.onText(text => {
    let msg;
    try { msg = JSON.parse(text); } catch (e) { return; }
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      return;
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails || {};
      consoleErrors.push('exception: ' + (d.exception && d.exception.description || d.text));
    }
    if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
      consoleErrors.push('console.error: ' + (msg.params.args || []).map(a => a.value || a.description || '').join(' '));
    }
    if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
      consoleErrors.push('log: ' + msg.params.entry.text);
    }
  });
  function cmd(method, params) {
    const mid = ++id;
    return new Promise((resolve, reject) => {
      pending.set(mid, { resolve, reject });
      ws.send({ id: mid, method, params: params || {} });
    });
  }
  async function ev(expr) {
    const r = await cmd('Runtime.evaluate', {
      expression: expr, returnByValue: true, awaitPromise: true, userGesture: true,
    });
    if (r.exceptionDetails) throw new Error('页面求值异常: ' + JSON.stringify(r.exceptionDetails).slice(0, 400));
    return r.result ? r.result.value : undefined;
  }
  async function shot(name) {
    const r = await cmd('Page.captureScreenshot', { format: 'png' });
    const f = path.join(OUT, name);
    fs.writeFileSync(f, Buffer.from(r.data, 'base64'));
    return f;
  }

  await cmd('Runtime.enable');
  await cmd('Log.enable');
  await cmd('Page.enable');

  /* 等页面脚本就绪 */
  let ready = false;
  for (let i = 0; i < 60; i++) {
    try {
      ready = await ev("!!(window.__cyrilViewOpen && document.readyState === 'complete')");
      if (ready) break;
    } catch (e) {}
    await sleep(250);
  }
  ck(ready, '页面脚本就绪（__cyrilViewOpen 可用）');
  if (!ready) {
    const diag = await ev("JSON.stringify({href:location.href, rs:document.readyState, len:(document.body&&document.body.innerHTML||'').length, hasView:typeof window.__cyrilViewOpen, hasLum:typeof window.Lumiere})").catch(e => 'eval失败:' + e.message);
    console.log('DIAG', diag);
    console.log('错误', consoleErrors.slice(0, 6));
  }

  const boot = await ev("JSON.stringify({adapter: !!window.LumiereFolia, dual: !!(window.LumiereFolia && window.LumiereFolia.BASE_DUAL_THEME), theme: document.body.classList.contains('light') ? 'light' : 'dark'})");
  const bootObj = JSON.parse(boot);
  ck(bootObj.adapter, 'adapter 已加载 window.LumiereFolia');
  ck(bootObj.dual, '原版 baseThemes 已进构建产物（BASE_DUAL_THEME）');

  /* ---- 1) 起绘光：主题按当前黑 / 白走（本机 Settings.json 存的是 light） ---- */
  const startLight = await ev("document.body.classList.contains('light')");
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(400);
  await ev("window.__cyrilViewOpen('lumiere')");
  await sleep(3000);
  let dbg = JSON.parse(await ev('JSON.stringify(window.Lumiere.debug())'));
  ck(dbg.started && dbg.ticker, '绘光 runtime 已创建', dbg);
  ck(new RegExp(startLight ? '日光素白' : '午夜墨染').test(dbg.theme),
    '启动主题 = ' + (startLight ? '白 → 日光素白' : '黑 → 午夜墨染'), dbg.theme);
  const f1 = dbg.frames;
  await shot(startLight ? 'lumiere-daylight.png' : 'lumiere-midnight.png');

  /* ---- 2) 点【外观】另一个主题 → 绘光跟着换，且 ticker 没被换主题打断 ---- */
  await ev("document.getElementById('" + (startLight ? 'btnBlack' : 'btnWhite') + "').click()");
  await sleep(1200);
  dbg = JSON.parse(await ev('JSON.stringify(window.Lumiere.debug())'));
  ck(new RegExp(startLight ? '午夜墨染' : '日光素白').test(dbg.theme),
    '切到' + (startLight ? '黑 → 午夜墨染' : '白 → 日光素白'), dbg.theme);
  ck(dbg.light === !startLight, '桥接层 isLight 与主题一致', dbg.light);
  const f2 = dbg.frames;
  ck(f2 > f1, '换主题后 ticker 仍在跑（帧数递增）', { before: f1, after: f2 });
  await shot(startLight ? 'lumiere-midnight.png' : 'lumiere-daylight.png');

  /* ---- 3) 切回原主题 ---- */
  await ev("document.getElementById('" + (startLight ? 'btnWhite' : 'btnBlack') + "').click()");
  await sleep(900);
  dbg = JSON.parse(await ev('JSON.stringify(window.Lumiere.debug())'));
  ck(new RegExp(startLight ? '日光素白' : '午夜墨染').test(dbg.theme), '切回原主题', dbg.theme);

  /* ---- 4) 拼贴里的终端浮窗：最小化 / 展开 ---- */
  /* 启动页默认盖在上面（body.super-on 时 #termFloat 是 display:none），先退回终端 */
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(400);
  await ev("window.__cyrilViewOpen('lattice')");
  await sleep(2500);
  const mounted = await ev("!!document.querySelector('#tfBody #terminal')");
  ck(mounted, '拼贴态：终端已搬进浮窗 #tfBody');

  const h0 = await ev("Math.round(document.getElementById('termFloat').getBoundingClientRect().height)");
  ck(h0 > 150, '展开态高度正常', h0);

  await ev("document.getElementById('tfMin').click()");
  await sleep(500);
  const h1 = await ev("Math.round(document.getElementById('termFloat').getBoundingClientRect().height)");
  const cls1 = await ev("document.getElementById('termFloat').className");
  const stillThere = await ev("!!document.querySelector('#tfBody #terminal')");
  ck(h1 <= 36, '最小化后只剩标题条', { height: h1, cls: cls1 });
  ck(/min/.test(cls1), '浮窗挂上 .min 类', cls1);
  ck(stillThere, '最小化不卸载终端（xterm 实例保活）');
  const ls1 = await ev("JSON.parse(localStorage.getItem('Win-webterm-termfloat')||'{}').min");
  ck(ls1 === true, '最小化状态已落盘 localStorage', ls1);
  await shot('lattice-term-min.png');

  await ev("document.getElementById('tfMin').click()");
  await sleep(900);
  const h2 = await ev("Math.round(document.getElementById('termFloat').getBoundingClientRect().height)");
  const cls2 = await ev("document.getElementById('termFloat').className");
  ck(h2 > 150 && !/\bmin\b/.test(cls2), '再点一次恢复原高度', { height: h2, cls: cls2 });

  /* 真终端还活着：往 PTY 里打一条命令，从 xterm 缓冲区读回显
     （默认渲染器是 canvas，.xterm-rows 的 innerText 读不到，必须走 buffer API） */
  const readBuf = "(function(){var t=window.__cyrilTerm; if(!t) return ''; var b=t.buffer.active, out=[]; for(var i=0;i<b.length;i++){ var ln=b.getLine(i); if(ln) out.push(ln.translateToString(true)); } return out.join('\\n');})()";
  const before = await ev(readBuf);
  ck(before.length > 50, '展开后终端仍有内容（横幅 / 提示符在）', before.length);
  await ev("(function(){var ta=document.querySelector('#terminal .xterm-helper-textarea'); if(ta) ta.focus(); return !!ta;})()");
  await cmd('Input.insertText', { text: 'echo CYRIL-MIN-OK' });
  for (const type of ['keyDown', 'keyUp']) {
    await cmd('Input.dispatchKeyEvent', {
      type, key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13,
    });
  }
  await sleep(2000);
  const screen = await ev(readBuf);
  ck(/CYRIL-MIN-OK/.test(screen), '展开后终端仍可输入执行（回显命中）', screen.slice(-160));

  /* ---- 5) console 错误 ---- */
  ck(consoleErrors.length === 0, '页面无 console / 运行时报错', consoleErrors.slice(0, 4));

  console.log('\n---- 结果 ----');
  console.log('PASS ' + pass.length + ' / FAIL ' + fail.length);
  if (fail.length) { console.log('失败项：'); fail.forEach(f => console.log('  · ' + f)); }

  ws.close(); chrome.kill();
  process.exit(fail.length ? 1 : 0);
})().catch(async e => {
  console.error('测试异常：', e && e.stack || e);
  process.exit(2);
});
