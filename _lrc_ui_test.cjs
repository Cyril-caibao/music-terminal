/* 英文歌歌词「只剩译文」修复的端到端验收：
 * 真导入一个 mp3 + 同名 merged LRC（原文块 + 翻译块，同时间戳），
 * 检查歌词面板里原文在正文、译文在副行，且当前高亮行是原文而不是译文。
 * 用法：node _lrc_ui_test.cjs [baseUrl] [mp3] [lrc]
 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18771/';
const MP3 = process.argv[3] || 'C:/Users/Cuisi/Music/Amazing - Ed Sheeran.mp3';
const LRC = process.argv[4] || 'C:/Users/Cuisi/Music/Amazing - Ed Sheeran.lrc';
const PORT = 9417;
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const SHOT_DIR = path.join(__dirname, '_maintenance', '20261118');
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

let pass = 0, fail = 0;
const ck = (ok, name, detail) => {
  if (ok) { pass++; console.log('PASS', name, detail === undefined ? '' : JSON.stringify(detail)); }
  else { fail++; console.log('FAIL', name, detail === undefined ? '' : JSON.stringify(detail)); }
};
const CJK = /[\u4e00-\u9fa5]/;

(async () => {
  const profile = path.join(os.tmpdir(), 'wt-lrc-ui');
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) {}
  const chrome = spawn(CHROME, [
    '--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required', BASE,
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
  await cmd('Runtime.enable'); await cmd('Page.enable'); await cmd('DOM.enable');

  for (let i = 0; i < 60; i++) {
    try { if (await ev("!!window.__cyrilViewOpen")) break; } catch (e) {}
    await sleep(250);
  }
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(400);
  /* 进电台 */
  await ev("(function(){var b=document.getElementById('btnMode'); if(b) b.click(); return 1;})()");
  await sleep(900);

  /* ---- 真导入 mp3 + lrc（走 <input type=file>，与用户点选同一条路） ---- */
  const doc = await cmd('DOM.getDocument', { depth: -1 });
  const q = await cmd('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#radio-files' });
  await cmd('DOM.setFileInputFiles', { nodeId: q.nodeId, files: [MP3, LRC] });
  await sleep(1500);
  /* Chrome 有时不替我们派发 change：补一次（导入后 handler 会清空 value） */
  const n0 = await ev("document.querySelectorAll('#radio-lyrics-body .l-line').length");
  if (!n0) {
    await ev("(function(){var el=document.getElementById('radio-files'); el.dispatchEvent(new Event('change',{bubbles:true})); return 1;})()");
  }
  /* 导入只进队列，歌词要选中曲目才渲染 —— 点第一条队列项（与用户点歌同一条路） */
  let qn = 0;
  for (let i = 0; i < 30; i++) {
    qn = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
    if (qn > 0) break;
    await sleep(400);
  }
  ck(qn > 0, '导入后队列有曲目', qn);
  await ev("(function(){var it=document.querySelector('#radio-queue-list .q-item'); if(it) it.click(); return 1;})()");
  let ok = false;
  for (let i = 0; i < 40; i++) {
    const n = await ev("document.querySelectorAll('#radio-lyrics-body .l-line').length");
    if (n > 0) { ok = true; break; }
    await sleep(500);
  }
  ck(ok, '选中曲目后歌词面板已渲染');

  const stat = JSON.parse(await ev(`JSON.stringify((function(){
    var lines = Array.from(document.querySelectorAll('#radio-lyrics-body .l-line'));
    var withTrans = lines.filter(function(l){ return !!l.querySelector('.l-trans'); });
    var swapped = lines.filter(function(l){
      var m=l.querySelector('.l-main'), s=l.querySelector('.l-trans');
      if(!m||!s) return false;
      var mt=m.textContent.trim(), st=s.textContent.trim();
      return /[\\u4e00-\\u9fa5]/.test(mt) && !/[\\u4e00-\\u9fa5]/.test(st);
    });
    return {
      total: lines.length, trans: withTrans.length, swapped: swapped.length,
      first: lines[0] ? { main: lines[0].querySelector('.l-main').textContent.trim(),
                          trans: (lines[0].querySelector('.l-trans')||{textContent:''}).textContent.trim() } : null,
      sample: lines.slice(6,9).map(function(l){
        var m=l.querySelector('.l-main'), s=l.querySelector('.l-trans');
        return { main: m?m.textContent.trim():'', trans: s?s.textContent.trim():'' };
      })
    };
  })())`));
  ck(stat.total > 30 && stat.total < 60, '同时间戳原文/译文已并成一条（行数不翻倍）', stat.total);
  ck(stat.trans > 30, '多数行带译文副行', stat.trans);
  ck(stat.swapped === 0, '没有「正文是中文、副行是英文」的错位行', stat.swapped);
  ck(stat.first && stat.first.main.indexOf('Amazing') === 0 && !stat.first.trans,
    '首行是曲名行、未被当成译文吞掉', stat.first);
  ck(stat.sample.every(s => !CJK.test(s.main)), '正文行全是英文原文', stat.sample);

  /* ---- 播放到有词处，检查高亮行是原文 ---- */
  await ev("(function(){var b=document.getElementById('rbtn-play'); if(b) b.click(); return 1;})()");
  await sleep(11000);
  const act = JSON.parse(await ev(`JSON.stringify((function(){
    var a = document.querySelector('#radio-lyrics-body .l-line.active');
    if(!a) return null;
    var m=a.querySelector('.l-main'), s=a.querySelector('.l-trans');
    return { main:m?m.textContent.trim():'', trans:s?s.textContent.trim():'' };
  })())`));
  ck(!!act, '存在当前高亮行');
  /* 该行有译文时，正文必须是英文原文 —— 这正是修复前会相反的那一条 */
  ck(!!act && (!act.trans || !CJK.test(act.main)), '当前高亮行的正文不是译文', act);

  /* ---- 截图：歌词面板 + 整屏 ---- */
  const clip = async (sel, file) => {
    const r = await ev(`(function(){var e=document.querySelector('${sel}'); if(!e) return null;
      var b=e.getBoundingClientRect(); return {x:b.x,y:b.y,w:b.width,h:b.height};})()`);
    if (!r) return null;
    const shot = await cmd('Page.captureScreenshot', {
      format: 'png',
      clip: { x: Math.max(0, r.x), y: Math.max(0, r.y), width: Math.max(1, r.w), height: Math.max(1, r.h), scale: 2 }
    });
    const p = path.join(SHOT_DIR, file);
    fs.writeFileSync(p, Buffer.from(shot.data, 'base64'));
    return p;
  };
  await ev("(function(){var p=document.getElementById('radio-lyrics'); if(p) p.classList.add('show'); return 1;})()");
  await sleep(500);
  const p1 = await clip('#radio-lyrics', 'probe-lrc-en-panel.png');
  ck(!!p1, '歌词面板截图已出', p1);
  const full = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(SHOT_DIR, 'probe-lrc-en-full.png'), Buffer.from(full.data, 'base64'));

  ck(errs.length === 0, '无 console / 运行时报错', errs.slice(0, 3));

  console.log('\n---- 结果 ----\nPASS ' + pass + ' / FAIL ' + fail);
  try { chrome.kill(); } catch (e) {}
  process.exit(fail ? 1 : 0);
})();
