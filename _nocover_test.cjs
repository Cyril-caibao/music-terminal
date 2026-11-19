#!/usr/bin/env node
/* 无封面曲目更换封面修复探针。
 * 场景：详情面板里曲目无封面（img.nocover）时，占位框可见可点、
 *       点击触发选图、选图→预览→保存→IndexedDB 全链路。
 * 用法：node _nocover_test.cjs [baseUrl]
 */
'use strict';
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18767/';
const PORT = 9434;
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const MUSIC = 'C:/Users/Cuisi/Music/';
const FILES = [
  MUSIC + 'Amazing - Ed Sheeran.mp3',
  MUSIC + '1_告白气球 - 周杰伦_(Instrumental).flac',
];
/* 1x1 蓝色 PNG */
const TEST_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');
const TMP_PNG = path.join(os.tmpdir(), 'wt-nocover-test.png');

let pass = 0, fail = 0;
const ck = (ok, name, detail) => {
  if (ok) { pass++; console.log('PASS', name, detail === undefined ? '' : JSON.stringify(detail)); }
  else { fail++; console.log('FAIL', name, detail === undefined ? '' : JSON.stringify(detail)); }
};
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
    sock.on('error', reject);
    sock.on('data', d => {
      buf = Buffer.concat([buf, d]);
      if (!open) {
        const i = buf.indexOf('\r\n\r\n');
        if (i < 0) return;
        const head = buf.slice(0, i).toString(); buf = buf.slice(i + 4);
        if (!/ 101 /.test(head)) return reject(new Error('握手失败'));
        open = true; resolve({
          send(obj) { sock.write(encodeFrame(obj)); },
          onText(fn) { listeners.push(fn); },
          close() { try { sock.destroy(); } catch (e) {} },
        });
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
  fs.writeFileSync(TMP_PNG, TEST_PNG);
  const profile = path.join(os.tmpdir(), 'wt-nocover-ui');
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
    try { if (await ev('!!window.__cyrilViewOpen')) break; } catch (e) {}
    await sleep(250);
  }
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide(); document.body.classList.remove('super-on','super-view')");
  await sleep(400);
  await ev("(function(){var b=document.getElementById('btnMode'); if(b) b.click(); return 1;})()");
  await sleep(900);

  /* ============ 导入曲目 ============ */
  await ev("document.getElementById('rbtn-queue').click()");
  await sleep(300);
  const doc = await cmd('DOM.getDocument', { depth: -1 });
  const q = await cmd('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#radio-files' });
  await cmd('DOM.setFileInputFiles', { nodeId: q.nodeId, files: FILES });
  await sleep(1500);
  let qn = 0;
  for (let i = 0; i < 40; i++) {
    qn = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
    if (qn >= 2) break;
    await sleep(500);
  }
  ck(qn >= 2, '导入 2 首进队列', qn);
  await sleep(1500);
  await ev("(function(){var it=document.querySelectorAll('#radio-queue-list .q-item')[0]; if(it) it.click(); return 1;})()");
  await sleep(1200);

  /* ============ 打开详情面板，探测第 1 首真实封面状态 ============ */
  await ev("document.getElementById('rbtn-info').click()");
  await sleep(400);
  const shown = await ev("document.getElementById('radio-details').classList.contains('show')");
  ck(shown, '打开详情面板');
  const naturalNocover = await ev("document.getElementById('dt-cover').classList.contains('nocover')");
  console.log('INFO 第 1 首天然无封面 =', naturalNocover);

  /* ============ 复现无封面状态（与 populateDetails 渲染一致）============ */
  await ev("(function(){\
    var c = document.getElementById('dt-cover');\
    c.classList.add('nocover'); c.removeAttribute('src');\
    return 1;\
  })()");
  const imgHidden = await ev("(function(){\
    var c = document.getElementById('dt-cover');\
    return c.classList.contains('nocover') && getComputedStyle(c).display === 'none';\
  })()");
  ck(imgHidden, '无封面时 img 隐藏 (nocover)');
  const phVisible = await ev("(function(){\
    var p = document.getElementById('dt-cover-ph');\
    if (!p) return 'missing';\
    return getComputedStyle(p).display !== 'none' && p.offsetWidth > 0 && p.offsetHeight > 0;\
  })()");
  ck(phVisible === true, '无封面占位框可见可点', phVisible);
  const shotPh = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync('_probe-nocover-ph.png', Buffer.from(shotPh.data, 'base64'));

  /* ============ 点击占位框 → 触发文件选择器 ============ */
  await ev("(function(){\
    window.__fc = 0;\
    var inp = document.getElementById('dt-cover-file');\
    inp.click = function(){ window.__fc++; };\
    return 1;\
  })()");
  await ev("document.getElementById('dt-cover-ph').click()");
  await sleep(200);
  let fc = await ev('window.__fc');
  ck(fc === 1, '点击占位框触发选图', fc);
  await ev("document.getElementById('dt-cover-tip').click()");
  await sleep(200);
  fc = await ev('window.__fc');
  ck(fc === 2, '点击提示文字同样触发选图', fc);
  await ev("(function(){ delete document.getElementById('dt-cover-file').click; delete window.__fc; return 1; })()");

  /* ============ 选图 → 预览 → 保存（真实 change 处理器）============ */
  const doc2 = await cmd('DOM.getDocument', { depth: -1 });
  const qc = await cmd('DOM.querySelector', { nodeId: doc2.root.nodeId, selector: '#dt-cover-file' });
  await cmd('DOM.setFileInputFiles', { nodeId: qc.nodeId, files: [TMP_PNG] });
  await sleep(600);
  const after = await ev("(function(){\
    var c = document.getElementById('dt-cover'), p = document.getElementById('dt-cover-ph');\
    return {\
      nocoverGone: !c.classList.contains('nocover'),\
      imgShown: getComputedStyle(c).display !== 'none' && /^blob:/.test(c.src || ''),\
      phGone: getComputedStyle(p).display === 'none',\
      pending: document.getElementById('dt-cover-pending').style.display !== 'none',\
    };\
  })()");
  ck(after.nocoverGone && after.imgShown && after.phGone,
    '选图后：img 显示 blob 预览、占位框消失', after);
  ck(after.pending, '「新封面待保存」标记出现');

  await ev("document.getElementById('dt-save').click()");
  await sleep(600);
  const dbRec = await ev("(async function(){\
    var db = await new Promise(function(r){var q=indexedDB.open('cyril-radio',1); q.onsuccess=function(){r(q.result);};});\
    var all = await new Promise(function(r){var tx=db.transaction('tracks','readonly'); var rq=tx.objectStore('tracks').getAll(); rq.onsuccess=function(){r(rq.result||[]);};});\
    var t = all.filter(function(x){return x && x.coverBlob;});\
    return { withCover: t.length, total: all.length };\
  })()");
  ck(dbRec && dbRec.withCover >= 1, '保存后 IndexedDB 写入 coverBlob', dbRec);

  const stillBlob = await ev("(function(){\
    var c = document.getElementById('dt-cover');\
    return !c.classList.contains('nocover') && /^blob:/.test(c.src || '');\
  })()");
  ck(stillBlob, '保存后面板封面保持显示');
  /* 队列行设计上无封面位（序号/歌名/✎/×），不在此断言 */

  /* ============ 回归：有封面的曲目点击 img 本体仍可换图 ============ */
  await ev("(function(){\
    window.__fc2 = 0;\
    var inp = document.getElementById('dt-cover-file');\
    inp.click = function(){ window.__fc2++; };\
    return 1;\
  })()");
  await ev("document.getElementById('dt-cover').click()");
  await sleep(200);
  const fc2 = await ev('window.__fc2');
  ck(fc2 === 1, '有封面时点击 img 仍触发选图', fc2);
  await ev("(function(){ delete document.getElementById('dt-cover-file').click; delete window.__fc2; return 1; })()");

  /* 截图留档 */
  await ev("(function(){\
    window.__cyrilSuperHome && window.__cyrilSuperHome.hide();\
    document.body.classList.remove('super-on','super-view');\
    var s = document.querySelector('.super-home');\
    if (s) s.style.display = 'none';\
    return 1;\
  })()");
  await sleep(500);
  const shot = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync('_probe-nocover.png', Buffer.from(shot.data, 'base64'));

  ck(errs.length === 0, '全程无 JS 异常/错误', errs.slice(0, 3));
  try { ws.close(); } catch (e) {}
  chrome.kill();
  console.log('----');
  console.log('RESULT: ' + pass + ' pass, ' + fail + ' fail');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('PROBE ERROR:', e.message); process.exit(2); });
