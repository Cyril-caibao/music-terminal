/* 复现 viz-sub 浅色主题断言状态 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18767/';
const PORT = 9416;
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
    const api = { send(obj) { sock.write(encodeFrame(obj)); }, onText(fn) { listeners.push(fn); }, close() { try { sock.destroy(); } catch (e) {} } };
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
  const profile = path.join(os.tmpdir(), 'wt-vizsub-repro');
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) {}
  const chrome = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', [
    '--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--no-first-run', '--window-size=1440,900', BASE,
  ], { stdio: 'ignore' });
  let targets = null;
  for (let i = 0; i < 40; i++) {
    try { targets = await httpJSON('/json/list'); if (targets && targets.length) break; } catch (e) {}
    await sleep(250);
  }
  const page = (targets || []).find(t => t.type === 'page' && t.url.indexOf('127.0.0.1') >= 0);
  const ws = await connectWS(new URL(page.webSocketDebuggerUrl).pathname);
  let id = 0; const pending = new Map();
  ws.onText(text => {
    let m; try { m = JSON.parse(text); } catch (e) { return; }
    if (m.id && pending.has(m.id)) { const { resolve } = pending.get(m.id); pending.delete(m.id); resolve(m.result); }
  });
  const cmd = (method, params) => new Promise(resolve => { const mid = ++id; pending.set(mid, { resolve }); ws.send({ id: mid, method, params: params || {} }); });
  const ev = async expr => { const r = await cmd('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true }); return r.result ? r.result.value : undefined; };
  await cmd('Runtime.enable'); await cmd('Page.enable');
  for (let i = 0; i < 60; i++) { try { if (await ev('!!window.__cyrilViewOpen')) break; } catch (e) {} await sleep(250); }

  const lrc = [{ t: 0, text: '测试行', trans: 'test line' }, { t: 30, text: '第二行', trans: 'line two' }];
  await ev("window.Tempera.setLyrics(" + JSON.stringify(lrc) + ", {title:'t',artist:'t'})");
  await ev("window.__cyrilViewOpen('tempera')"); await sleep(1800);
  await ev("window.__cyrilApplyTheme('light')"); await sleep(800);
  console.log('classes:', await ev("document.body.className"));
  console.log('sub color:', await ev("getComputedStyle(document.getElementById('viz-sub')).color"));
  console.log('vizsub pref:', await ev("localStorage.getItem('Win-webterm-viz-sub')"));
  ws.close(); chrome.kill(); process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });
