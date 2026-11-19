#!/usr/bin/env node
/* 播放模式四态按钮 + 歌曲详情面板（封面/标签/歌词编辑）端到端测试。
 * 用法：node _details_test.cjs [baseUrl]
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
const PORT = 9433;
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const MUSIC = 'C:/Users/Cuisi/Music/';
const FILES = [
  MUSIC + 'Amazing - Ed Sheeran.mp3',
  MUSIC + '1_告白气球 - 周杰伦_(Instrumental).flac',
  MUSIC + '1_Westlife - My Love(Radio Edit)_(Instrumental).flac',
];
/* 1x1 红色 PNG */
const TEST_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const TMP_PNG = path.join(os.tmpdir(), 'wt-cover-test.png');

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
  const profile = path.join(os.tmpdir(), 'wt-details-ui');
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

  /* ============ 播放模式四态按钮 ============ */
  const shuffleGone = await ev("!document.getElementById('rbtn-shuffle') && !document.getElementById('rbtn-loop') && !!document.getElementById('rbtn-mode')");
  ck(shuffleGone, '旧两按钮已移除、rbtn-mode 存在');
  const t0 = await ev("document.getElementById('rbtn-mode').title");
  ck(/顺序播放/.test(t0), '初始态=顺序播放', t0);
  await ev("document.getElementById('rbtn-mode').click()");
  const t1 = await ev("document.getElementById('rbtn-mode').title");
  ck(/列表循环/.test(t1), '点击1→列表循环', t1);
  await ev("document.getElementById('rbtn-mode').click()");
  const t2 = await ev("document.getElementById('rbtn-mode').title");
  ck(/单曲循环/.test(t2), '点击2→单曲循环', t2);
  await ev("document.getElementById('rbtn-mode').click()");
  const t3 = await ev("document.getElementById('rbtn-mode').title");
  ck(/随机播放/.test(t3), '点击3→随机播放', t3);
  const onCls = await ev("document.getElementById('rbtn-mode').classList.contains('on')");
  ck(onCls, '非顺序态按钮高亮');
  await ev("document.getElementById('rbtn-mode').click()");
  const t4 = await ev("document.getElementById('rbtn-mode').title");
  ck(/顺序播放/.test(t4), '点击4→回到顺序播放', t4);
  /* AI 指令兼容：shuffle on → 按钮显示随机 */
  const aiRes = await ev("(function(){var r=window.__cyrilPlayerCmd({action:'shuffle',on:true}); return document.getElementById('rbtn-mode').title;})()");
  ck(/随机播放/.test(aiRes), 'AI 指令 shuffle → 按钮同步随机态', aiRes);
  await ev("window.__cyrilPlayerCmd({action:'loop',mode:'one'})");
  const aiRes2 = await ev("document.getElementById('rbtn-mode').title");
  ck(/单曲循环/.test(aiRes2), 'AI 指令 loop one → 按钮同步单曲', aiRes2);
  await ev("window.__cyrilPlayerCmd({action:'loop',mode:'off'})");

  /* ============ 导入 + 播放 ============ */
  await ev("document.getElementById('rbtn-queue').click()");
  await sleep(300);
  const doc = await cmd('DOM.getDocument', { depth: -1 });
  const q = await cmd('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#radio-files' });
  await cmd('DOM.setFileInputFiles', { nodeId: q.nodeId, files: FILES });
  await sleep(1500);
  let qn = 0;
  for (let i = 0; i < 40; i++) {
    qn = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
    if (qn >= 3) break;
    await sleep(500);
  }
  ck(qn >= 3, '导入 3 首进队列', qn);
  await sleep(2000);
  await ev("(function(){var it=document.querySelectorAll('#radio-queue-list .q-item')[0]; if(it) it.click(); return 1;})()");
  await sleep(1500);

  /* ============ 详情面板：打开 + 填充 ============ */
  await ev("document.getElementById('rbtn-info').click()");
  await sleep(400);
  const shown = await ev("document.getElementById('radio-details').classList.contains('show')");
  ck(shown, 'ℹ 打开详情面板');
  const who = await ev("document.getElementById('dt-who').textContent");
  ck(/1 \/ 3/.test(who), 'who 显示 1 / 3', who);
  const titleVal = await ev("document.getElementById('dt-title').value");
  ck(titleVal.length > 0, '标题已填充', titleVal);

  /* ============ 改标签 ============ */
  await ev("(function(){var t=document.getElementById('dt-title'); t.value='测试改题'; return 1;})()");
  await ev("(function(){var a=document.getElementById('dt-artist'); a.value='测试歌手'; return 1;})()");
  await ev("document.getElementById('dt-save').click()");
  await sleep(500);
  const rowTxt = await ev("document.querySelector('#radio-queue-list .q-item .q-name').textContent");
  ck(/测试改题/.test(rowTxt) && /测试歌手/.test(rowTxt), '保存后队列行更新', rowTxt);
  const radioTitle = await ev("document.getElementById('radio-title').textContent");
  ck(/测试改题/.test(radioTitle), '播放条标题同步', radioTitle);

  /* ============ 改歌词 ============ */
  const LRC = '[00:01.00]详情测试第一行\n[00:02.00]详情测试第二行\n[00:03.00]详情测试第三行';
  await ev("(function(){var l=document.getElementById('dt-lrc'); l.value=" + JSON.stringify(LRC) + "; return 1;})()");
  await ev("document.getElementById('dt-save').click()");
  await sleep(500);
  await ev("document.getElementById('rbtn-lyrics').click()");
  await sleep(600);
  const lrcLines = await ev("document.querySelectorAll('#radio-lyrics-body .l-line').length");
  ck(lrcLines >= 3, '歌词保存后面板渲染 3 行', lrcLines);

  /* ============ 改封面 ============ */
  const doc2 = await cmd('DOM.getDocument', { depth: -1 });
  await ev("document.getElementById('rbtn-info').click()");
  await sleep(300);
  const qc = await cmd('DOM.querySelector', { nodeId: doc2.root.nodeId, selector: '#dt-cover-file' });
  await cmd('DOM.setFileInputFiles', { nodeId: qc.nodeId, files: [TMP_PNG] });
  await sleep(600);
  const pendingShown = await ev("document.getElementById('dt-cover-pending').style.display !== 'none'");
  ck(pendingShown, '选图后出现「新封面待保存」标记');
  const coverSrc = await ev("document.getElementById('dt-cover').src");
  ck(/^blob:/.test(coverSrc), '封面预览已切换', coverSrc.slice(0, 20));
  await ev("document.getElementById('dt-save').click()");
  await sleep(500);
  const pendingGone = await ev("document.getElementById('dt-cover-pending').style.display === 'none'");
  ck(pendingGone, '保存后待保存标记消失');
  /* IndexedDB 持久化核对 */
  const dbRec = await ev("(async function(){\
    var db = await new Promise(function(r){var q=indexedDB.open('cyril-radio',1); q.onsuccess=function(){r(q.result);};});\
    var all = await new Promise(function(r){var tx=db.transaction('tracks','readonly'); var rq=tx.objectStore('tracks').getAll(); rq.onsuccess=function(){r(rq.result||[]);};});\
    var t = all.filter(function(x){return x && x.title==='测试改题';})[0];\
    return t ? { artist: t.artist, hasCover: !!t.coverBlob, hasLrc: !!(t.lrc && t.lrc.indexOf('详情测试') >= 0) } : null;\
  })()");
  ck(dbRec && dbRec.artist === '测试歌手' && dbRec.hasCover && dbRec.hasLrc,
    'IndexedDB 已持久化 标签+封面+歌词', dbRec);

  /* ============ 队列行 ✎ 打开指定曲目 ============ */
  await ev("(function(){var rows=document.querySelectorAll('#radio-queue-list .q-item'); rows[1].querySelector('.q-edit').click(); return 1;})()");
  await sleep(400);
  const who2 = await ev("document.getElementById('dt-who').textContent");
  ck(/2 \/ 3/.test(who2), '行 ✎ 打开第 2 首详情', who2);

  ck(errs.length === 0, '全程无 JS 异常/错误', errs.slice(0, 3));
  try { ws.close(); } catch (e) {}
  chrome.kill();
  console.log('----');
  console.log('RESULT: ' + pass + ' pass, ' + fail + ' fail');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('PROBE ERROR:', e.message); process.exit(2); });
