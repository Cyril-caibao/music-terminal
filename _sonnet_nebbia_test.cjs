/* 商籁(Sonnet)+雾笺(Nebbia)冒烟探针：加载页 → 五态切换 → 渲染断言 → 主题 → resize。
 * 用法：node _sonnet_nebbia_test.cjs [baseUrl]
 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18767/';
const PORT = 9415;
const OUT = path.join(__dirname, '_maintenance', '20261118');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let PASS = 0, FAIL = 0;
function ok(name, cond, extra) {
  if (cond) { PASS++; console.log('PASS  ' + name); }
  else { FAIL++; console.log('FAIL  ' + name + (extra ? '  -- ' + extra : '')); }
}

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
    let buf = Buffer.alloc(0), open = false;
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
        const head = buf.slice(0, i).toString(); buf = buf.slice(i + 4);
        if (!/ 101 /.test(head)) return reject(new Error('握手失败'));
        open = true; resolve(api);
      }
      for (;;) {
        if (buf.length < 2) return;
        const b0 = buf[0], b1 = buf[1];
        let len = b1 & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const op = b0 & 0x0f;
        const payload = buf.slice(off, off + len); buf = buf.slice(off + len);
        if (op === 8) { sock.destroy(); return; }
        if (op === 9) continue;
        if (op === 1 || op === 0) listeners.forEach(fn => fn(payload.toString('utf8')));
      }
    });
  });
}
function httpJSON(p) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path: p }, res => {
      let s = ''; res.on('data', d => s += d);
      res.on('end', () => { try { resolve(JSON.parse(s)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const profile = path.join(os.tmpdir(), 'wt-sn-probe');
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) {}
  const chrome = spawn(CHROME, [
    '--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    BASE,
  ], { stdio: 'ignore' });

  let targets = null;
  for (let i = 0; i < 40; i++) {
    try { targets = await httpJSON('/json/list'); if (targets && targets.length) break; } catch (e) {}
    await sleep(250);
  }
  const page = (targets || []).find(t => t.type === 'page' && t.url.indexOf('127.0.0.1') >= 0);
  if (!page) { console.log('FAIL 拿不到页面'); chrome.kill(); process.exit(1); }
  const ws = await connectWS(new URL(page.webSocketDebuggerUrl).pathname);
  let id = 0; const pending = new Map(); const errs = [];
  ws.onText(text => {
    let m; try { m = JSON.parse(text); } catch (e) { return; }
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id); pending.delete(m.id);
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
      return;
    }
    if (m.method === 'Runtime.exceptionThrown') errs.push('exception: ' + ((m.params.exceptionDetails.exception || {}).description || m.params.exceptionDetails.text));
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errs.push('console: ' + (m.params.args || []).map(a => a.value || a.description || '').join(' '));
  });
  const cmd = (method, params) => new Promise((resolve, reject) => {
    const mid = ++id; pending.set(mid, { resolve, reject }); ws.send({ id: mid, method, params: params || {} });
  });
  const ev = async expr => {
    const r = await cmd('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true });
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 300));
    return r.result ? r.result.value : undefined;
  };
  const shot = async name => {
    const r = await cmd('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT, name), Buffer.from(r.data, 'base64'));
    return name;
  };
  await cmd('Runtime.enable'); await cmd('Page.enable');

  for (let i = 0; i < 60; i++) {
    try { if (await ev("!!window.__cyrilViewOpen && !!window.Sonnet && !!window.Nebbia")) break; } catch (e) {}
    await sleep(250);
  }
  ok('页面无 JS 异常（载入）', errs.length === 0, errs.slice(0, 2).join(' | '));
  ok('SonnetFolia 挂载', await ev('!!window.SonnetFolia'));
  ok('NebbiaFolia 挂载', await ev('!!window.NebbiaFolia'));
  ok('Sonnet 桥接 API', await ev('typeof Sonnet.start === "function" && typeof Sonnet.setLyrics === "function"'));
  ok('Nebbia 桥接 API', await ev('typeof Nebbia.start === "function" && typeof Nebbia.setLyrics === "function"'));

  const lrc = [
    { t: 0, text: '雾从纸上漫过来', trans: 'mist creeps over the page' },
    { t: 5, text: '一行诗被雨水洇开', trans: 'a line of verse bleeds in rain' },
    { t: 10, text: '玻璃上的水珠在滑', trans: 'droplets slide on glass' },
    { t: 15, text: '字迹沉进了雾里', trans: 'letters sink into fog' },
    { t: 20, text: '远处有一盏灯', trans: 'a lamp far away' },
    { t: 25, text: '把雾照成金色', trans: 'turning mist to gold' },
  ];
  await ev("window.Sonnet.setLyrics(" + JSON.stringify(lrc) + ", {title:'商籁探针',artist:'Cyril'})");
  await ev("window.Nebbia.setLyrics(" + JSON.stringify(lrc) + ", {title:'雾笺探针',artist:'Cyril'})");

  /* ---- 商籁 ---- */
  await ev("window.__cyrilViewOpen('sonnet')");
  await sleep(2500);
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide(); document.body.classList.remove('super-on','super-view')");
  await sleep(3000);
  ok('商籁 body.sonnet-on', await ev("document.body.classList.contains('sonnet-on')"));
  ok('商籁 isRunning', await ev('!!Sonnet.isRunning()'));
  ok('商籁画布存在', await ev("document.querySelectorAll('#sonnet-canvas canvas').length > 0"));
  await shot('probe-sonnet.png');
  ok('商籁运行无 JS 异常', errs.length === 0, errs.slice(0, 3).join(' | '));

  /* ---- 雾笺 ---- */
  await ev("window.__cyrilViewOpen('nebbia')");
  await sleep(2500);
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide(); document.body.classList.remove('super-on','super-view')");
  await sleep(3000);
  ok('雾笺 body.nebbia-on', await ev("document.body.classList.contains('nebbia-on')"));
  ok('雾笺 isRunning', await ev('!!Nebbia.isRunning()'));
  ok('雾笺画布存在', await ev("document.querySelectorAll('#nebbia-canvas canvas').length > 0"));
  await shot('probe-nebbia.png');
  ok('雾笺运行无 JS 异常', errs.length === 0, errs.slice(0, 3).join(' | '));

  /* ---- 主题黑白切换（商籁态）---- */
  await ev("window.__cyrilViewOpen('sonnet')");
  await sleep(1500);
  await ev("document.getElementById('btnBlack').click()");
  await sleep(1200);
  ok('商籁黑主题切换无异常', errs.length === 0, errs.slice(0, 2).join(' | '));
  await ev("document.getElementById('btnWhite').click()");
  await sleep(1200);
  ok('商籁白主题切换无异常', errs.length === 0, errs.slice(0, 2).join(' | '));

  /* ---- resize 压测（雾笺态，最重 WebGL）---- */
  await ev("window.__cyrilViewOpen('nebbia')");
  await sleep(2000);
  for (const [w, h] of [[900, 650], [1400, 900], [1000, 700], [1440, 900]]) {
    await cmd('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: false });
    await sleep(700);
  }
  await cmd('Emulation.clearDeviceMetricsOverride');
  await sleep(1500);
  ok('雾笺 resize 压测无异常', errs.length === 0, errs.slice(0, 3).join(' | '));
  await shot('probe-nebbia-after-resize.png');

  /* ---- 互斥：切回音域全停 ---- */
  await ev("window.__cyrilViewOpen('sonic')");
  await sleep(1500);
  ok('切回音域商籁已停', await ev('!Sonnet.isRunning()'));
  ok('切回音域雾笺已停', await ev('!Nebbia.isRunning()'));
  ok('全程 JS 异常总数', errs.length === 0, errs.slice(0, 3).join(' | '));

  console.log(`\n== ${PASS} pass, ${FAIL} fail ==`);
  ws.close();
  chrome.kill();
  process.exit(FAIL ? 1 : 0);
})().catch(e => { console.error('PROBE ERROR', e); process.exit(2); });
