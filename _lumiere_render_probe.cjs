/* 绘光在 WebTerm 里到底画出了什么：载入一段假 LRC，截图看歌词/画框/词色是否真的出现。
 * 用法：node _lumiere_render_probe.cjs [baseUrl]
 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18766/';
const PORT = 9413;
const OUT = path.join(__dirname, '_maintenance', '20261118');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const sleep = ms => new Promise(r => setTimeout(r, ms));

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
  const profile = path.join(os.tmpdir(), 'wt-render-probe');
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
    if (m.method === 'Runtime.exceptionThrown') errs.push('exception: ' + (m.params.exceptionDetails.exception || {}).description);
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
    try { if (await ev("!!window.__cyrilViewOpen")) break; } catch (e) {}
    await sleep(250);
  }
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(300);
  await ev("window.__cyrilViewOpen('lumiere')");
  await sleep(2500);
  /* 后端配置到货后会把 start_page=super 再推回来，这里强制收起一次，保证看到的是绘光本体 */
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await ev("document.body.classList.remove('super-on','super-view')");
  await sleep(800);
  console.log('lumiere-on:', await ev("document.body.classList.contains('lumiere-on')"));

  const lrc = [
    { t: 0, text: '光落在烟里 才看得见形状' },
    { t: 6, text: '夜色把台词都吞掉' },
    { t: 12, text: '我数着尘埃慢慢上升' },
    { t: 18, text: '像被谁轻轻托住' },
    { t: 24, text: '若这就是所谓的明亮' },
    { t: 30, text: '我愿在暗处多站一会儿' }
  ];
  await ev("window.Lumiere.setLyrics(" + JSON.stringify(lrc) + ", {title:'探针曲',artist:'Cyril'})");
  await sleep(4000);
  console.log('debug:', await ev('JSON.stringify(window.Lumiere.debug())'));

  /* 只留画布，把终端与面板藏掉，便于看绘光本体（画框 / 光束 / 歌词） */
  await ev("document.body.classList.add('probe-clean')");
  await cmd('Runtime.evaluate', { expression: `
    var st=document.createElement('style');
    st.textContent='body.probe-clean #workArea,body.probe-clean #radio-controls,body.probe-clean #radio-queue,body.probe-clean header,body.probe-clean footer{opacity:0 !important}';
    document.head.appendChild(st);
  ` });
  await sleep(500);

  /* 先黑（午夜墨染），再白（日光素白），同一画面同一时刻各一张，用来量化差异 */
  await ev("document.getElementById('btnBlack').click()");
  await sleep(2200);
  console.log('dark:', await ev('JSON.stringify(window.Lumiere.debug())'));
  console.log('panel dark:', await ev("(function(){var p=document.getElementById('radio-lyrics');var s=getComputedStyle(p);return JSON.stringify({bg:s.backgroundColor,border:s.borderTopColor});})()"));
  console.log('shot dark:', await shot('probe-lumiere-midnight.png'));

  await ev("document.getElementById('btnWhite').click()");
  await sleep(2200);
  console.log('light:', await ev('JSON.stringify(window.Lumiere.debug())'));
  console.log('panel light:', await ev("(function(){var p=document.getElementById('radio-lyrics');var s=getComputedStyle(p);return JSON.stringify({bg:s.backgroundColor,border:s.borderTopColor});})()"));
  console.log('shot light:', await shot('probe-lumiere-daylight.png'));
  console.log('canvas bg light:', await ev("getComputedStyle(document.getElementById('lumiere-canvas')).backgroundColor"));

  console.log('errs:', errs.slice(0, 5));
  ws.close(); chrome.kill(); process.exit(0);
})().catch(e => { console.error('异常', e); process.exit(2); });
