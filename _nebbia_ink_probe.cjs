/* 雾笺暗景字迹自适应验证：黑主题 + 暗雾，看擦开的字有没有被抬亮 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18767/';
const PORT = 9417;
const OUT = path.join(__dirname, '_maintenance', '20261118');
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
  const profile = path.join(os.tmpdir(), 'wt-nebbia-ink');
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
  let id = 0; const pending = new Map(); const errs = [];
  ws.onText(text => {
    let m; try { m = JSON.parse(text); } catch (e) { return; }
    if (m.id && pending.has(m.id)) { const { resolve } = pending.get(m.id); pending.delete(m.id); resolve(m.result); }
    if (m.method === 'Runtime.exceptionThrown') errs.push((m.params.exceptionDetails.exception || {}).description || '');
  });
  const cmd = (method, params) => new Promise(resolve => { const mid = ++id; pending.set(mid, { resolve }); ws.send({ id: mid, method, params: params || {} }); });
  const ev = async expr => { const r = await cmd('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true }); return r.result ? r.result.value : undefined; };
  const shot = async name => { const r = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, name), Buffer.from(r.data, 'base64')); };
  await cmd('Runtime.enable'); await cmd('Page.enable');
  for (let i = 0; i < 60; i++) { try { if (await ev('!!window.__cyrilViewOpen && !!window.Nebbia')) break; } catch (e) {} await sleep(250); }

  /* 黑主题（用户截图的场景） */
  await ev("window.__cyrilApplyTheme('dark')"); await sleep(600);
  const lrc = [];
  const lines = ['不要在我知道的时候离开', '萤火不让夜幕落下来', '微微光火照小径'];
  let t = 0;
  for (const s of lines) { lrc.push({ t, text: s, trans: ' ' }); t += 9; }
  await ev("window.__cyrilViewOpen('nebbia')"); await sleep(2500);
  await ev("window.Nebbia.setLyrics(" + JSON.stringify(lrc) + ", {title:'雾笺自适应',artist:'Cyril'})");
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide(); document.body.classList.remove('super-on','super-view')");
  await sleep(3000);
  await ev("window.Nebbia.seek(10.5)"); await sleep(2600);
  await shot('probe-nebbia-ink-dark.png');
  console.log('debug:', JSON.stringify(await ev('window.Nebbia.debug()')).slice(0, 600));
  console.log('runtime dbg:', JSON.stringify(await ev('(function(){var r=Nebbia.debug();return {time:r.time, demo:r.demo, frames:r.frames, running:r.running};})()')));
  console.log('lines:', await ev('JSON.stringify((window.__cyrilNebbiaLines||[]).length)'));
  console.log('hasChars:', await ev("(function(){var c=document.querySelector('#nebbia-canvas');return !!c;})()"));
  await ev("window.__cyrilApplyTheme('light')"); await sleep(2500);
  await ev("window.Nebbia.seek(10.5)"); await sleep(2600);
  await shot('probe-nebbia-ink-light.png');
  console.log('errors:', errs.length ? errs.slice(0, 3) : 'none');
  ws.close(); chrome.kill(); process.exit(0);
})().catch(e => { console.error(e); process.exit(2); });
