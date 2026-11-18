/* 在真实 Electron 壳里复现「转全屏报错」：进凝彩 → toggleMaximize → 抓异常与 #jserror 文本。
   前置：env -u ELECTRON_RUN_AS_NODE ./WebTerm-Electron.exe --remote-debugging-port=9444 */
const http = require('http'); const net = require('net'); const crypto = require('crypto');
const fs = require('fs'); const path = require('path');
const PORT = 9444;
const OUT = path.join(__dirname, '_maintenance', '20261118');
const sleep = ms => new Promise(r => setTimeout(r, ms));

function enc(o) {
  const d = Buffer.from(JSON.stringify(o)); const m = crypto.randomBytes(4); let h;
  if (d.length < 126) { h = Buffer.alloc(2); h[1] = 0x80 | d.length; }
  else { h = Buffer.alloc(4); h[1] = 0x80 | 126; h.writeUInt16BE(d.length, 2); }
  h[0] = 0x81; const b = Buffer.from(d);
  for (let i = 0; i < b.length; i++) b[i] ^= m[i % 4];
  return Buffer.concat([h, m, b]);
}
function ws(p) {
  return new Promise((res, rej) => {
    const k = crypto.randomBytes(16).toString('base64');
    const s = net.connect(PORT, '127.0.0.1', () => s.write('GET ' + p + ' HTTP/1.1\r\nHost:127.0.0.1:' + PORT + '\r\nUpgrade:websocket\r\nConnection:Upgrade\r\nSec-WebSocket-Key:' + k + '\r\nSec-WebSocket-Version:13\r\n\r\n'));
    let buf = Buffer.alloc(0), open = false; const ls = [];
    const api = { send(o) { s.write(enc(o)); }, onText(f) { ls.push(f); }, close() { s.destroy(); } };
    s.on('error', rej);
    s.on('data', d => {
      buf = Buffer.concat([buf, d]);
      if (!open) { const i = buf.indexOf('\r\n\r\n'); if (i < 0) return; open = true; buf = buf.slice(i + 4); res(api); }
      for (;;) {
        if (buf.length < 2) return;
        const l0 = buf[1] & 0x7f; let len = l0, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const op = buf[0] & 0x0f; const pl = buf.slice(off, off + len); buf = buf.slice(off + len);
        if (op === 8) { s.destroy(); return; }
        if (op === 9) continue;
        if (op === 1 || op === 0) ls.forEach(f => f(pl.toString()));
      }
    });
  });
}
const hj = p => new Promise((res, rej) => { http.get({ host: '127.0.0.1', port: PORT, path: p }, r => { let s = ''; r.on('data', d => s += d); r.on('end', () => res(JSON.parse(s))); }).on('error', rej); });

(async () => {
  const ts = await hj('/json/list');
  const page = (ts || []).find(t => t.type === 'page' && t.url.indexOf('127.0.0.1') >= 0);
  if (!page) { console.log('no page'); process.exit(1); }
  const w = await ws(new URL(page.webSocketDebuggerUrl).pathname);
  let id = 0; const pend = new Map(); const errs = [];
  w.onText(t => {
    let m; try { m = JSON.parse(t); } catch (e) { return; }
    if (m.id && pend.has(m.id)) { const p = pend.get(m.id); pend.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); return; }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params.exceptionDetails;
      errs.push({
        desc: ((d.exception && d.exception.description) || d.text || '').split('\n')[0],
        line: d.lineNumber, col: d.columnNumber, url: (d.url || '').split('/').pop(),
        stack: (d.stackTrace && d.stackTrace.callFrames || []).slice(0, 6).map(f => `${f.functionName || '(anon)'}:${f.lineNumber + 1}`)
      });
    }
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      const s = (m.params.args || []).map(a => a.value || a.description || '').join(' ');
      if (/resolution|folia|pixi/i.test(s)) errs.push({ desc: 'console.error: ' + s.slice(0, 300) });
    }
  });
  const cmd = (me, pa) => new Promise((res, rej) => { const i = ++id; pend.set(i, { res, rej }); w.send({ id: i, method: me, params: pa || {} }); });
  const ev = async e => {
    const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true, userGesture: true });
    if (r.exceptionDetails) return '__ERR__' + JSON.stringify(r.exceptionDetails).slice(0, 300);
    return r.result && r.result.value;
  };
  await cmd('Runtime.enable'); await cmd('Page.enable');

  for (let i = 0; i < 80; i++) { if (await ev('!!window.__cyrilViewOpen')) break; await sleep(250); }
  console.log('desktop api =', await ev('!!window.__cyrilDesktop'));
  for (let i = 0; i < 6; i++) { await ev('window.__cyrilSuperHome&&window.__cyrilSuperHome.hide()'); await sleep(200); }
  await ev("document.getElementById('btnMode').click()"); await sleep(800);
  await ev("window.__cyrilViewOpen('tempera')"); await sleep(3000);
  console.log('tempera =', await ev('window.Tempera.isRunning()'), 'layout =', JSON.stringify(await ev('window.Tempera.debug().layout')));
  console.log('fullscreen before =', await ev('window.__cyrilDesktop.isFullscreen()'));

  await ev('window.__cyrilDesktop.toggleMaximize()');
  await sleep(2500);
  console.log('fullscreen after =', await ev('window.__cyrilDesktop.isFullscreen()'), 'layout =', JSON.stringify(await ev('window.Tempera.debug().layout')));
  console.log('jserror =', JSON.stringify((await ev("(document.getElementById('jserror')||{}).textContent") || '').slice(0, 300)));

  /* 再来回切两次，抓偶发 */
  for (let k = 0; k < 2; k++) {
    await ev('window.__cyrilDesktop.toggleMaximize()'); await sleep(1800);
    await ev('window.__cyrilDesktop.toggleMaximize()'); await sleep(1800);
  }
  console.log('jserror(after 4 toggles) =', JSON.stringify((await ev("(document.getElementById('jserror')||{}).textContent") || '').slice(0, 400)));
  console.log('frames =', await ev('window.Tempera.debug().frames'), 'running =', await ev('window.Tempera.isRunning()'));

  const shot = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(OUT, 'probe-electron-fullscreen.png'), Buffer.from(shot.data, 'base64'));

  console.log('\n=== exceptions (' + errs.length + ') ===');
  errs.slice(0, 10).forEach((e, i) => console.log(i + 1 + '.', JSON.stringify(e)));
  w.close();
  process.exit(0);
})();
