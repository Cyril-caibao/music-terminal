#!/usr/bin/env node
/* 播放队列窗口测试：搜索 / 歌手分组 / 专辑分组 / 折叠展开 / 过滤态播放。
 * 用法：node _queue_test.cjs [baseUrl]
 * 走真实导入（<input type=file>），用 C:/Users/Cuisi/Music 下的多歌手曲目。
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
const PORT = 9431;
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const MUSIC = 'C:/Users/Cuisi/Music/';
const FILES = [
  MUSIC + 'Amazing - Ed Sheeran.mp3',
  MUSIC + '1_告白气球 - 周杰伦_(Instrumental).flac',
  MUSIC + '1_稻香 - 周杰伦_(Instrumental).flac',
  MUSIC + '1_Westlife - My Love(Radio Edit)_(Instrumental).flac',
  MUSIC + '1_Run - OneRepublic_(Instrumental).flac',
];

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
  const profile = path.join(os.tmpdir(), 'wt-queue-ui');
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
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(400);
  await ev("(function(){var b=document.getElementById('btnMode'); if(b) b.click(); return 1;})()");
  await sleep(900);

  /* 打开队列面板 */
  await ev("document.getElementById('rbtn-queue').click()");
  await sleep(400);

  /* ---- 真导入 5 首多歌手曲目 ---- */
  const doc = await cmd('DOM.getDocument', { depth: -1 });
  const q = await cmd('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#radio-files' });
  await cmd('DOM.setFileInputFiles', { nodeId: q.nodeId, files: FILES });
  await sleep(1500);
  const n0 = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
  if (!n0) await ev("(function(){var el=document.getElementById('radio-files'); el.dispatchEvent(new Event('change',{bubbles:true})); return 1;})()");
  let qn = 0;
  for (let i = 0; i < 40; i++) {
    qn = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
    if (qn >= 5) break;
    await sleep(500);
  }
  ck(qn >= 5, '导入 5 首进队列', qn);
  await sleep(2500);   /* 等元数据(标题/歌手)解析完 */

  /* ---- 搜索：命中歌手 ---- */
  await ev("(function(){var s=document.getElementById('q-search'); s.value='周杰伦'; s.dispatchEvent(new Event('input',{bubbles:true})); return 1;})()");
  await sleep(300);
  const hitRows = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
  ck(hitRows === 2, '搜索「周杰伦」命中 2 首', hitRows);
  const cntTxt = await ev("document.getElementById('radio-qcount').textContent");
  ck(/2 \/ 5/.test(cntTxt), '计数显示 2 / 5 首', cntTxt);

  /* ---- 搜索：无结果提示 ---- */
  await ev("(function(){var s=document.getElementById('q-search'); s.value='不存在的歌xyz'; s.dispatchEvent(new Event('input',{bubbles:true})); return 1;})()");
  await sleep(250);
  const hint = await ev("(function(){var h=document.querySelector('#radio-queue-list .q-empty-hint'); return h?h.textContent:'';})()");
  ck(/没有匹配/.test(hint), '无结果显示提示', hint);

  /* ---- Esc 清空搜索 ---- */
  await ev("(function(){var s=document.getElementById('q-search'); s.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true})); return 1;})()");
  await sleep(250);
  const afterEsc = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
  ck(afterEsc >= 5, 'Esc 清空搜索恢复全列', afterEsc);

  /* ---- 歌手分组 ---- */
  await ev("(function(){var b=document.querySelector('#q-group-btns button[data-g=artist]'); b.click(); return 1;})()");
  await sleep(300);
  const heads = await ev("(function(){return Array.prototype.map.call(document.querySelectorAll('#radio-queue-list .q-group-head .q-gname'),function(e){return e.textContent;});})()");
  ck(heads.length >= 4, '歌手分组头数 ≥ 4', heads);
  ck(heads.indexOf('周杰伦') >= 0, '分组头含「周杰伦」', heads);
  const zhouRows = await ev("(function(){var h=Array.prototype.find.call(document.querySelectorAll('.q-group-head'),function(e){return e.querySelector('.q-gname').textContent==='周杰伦';}); if(!h) return -1; var n=0,el=h.nextElementSibling; while(el&&el.classList.contains('q-item')){n++;el=el.nextElementSibling;} return n;})()");
  ck(zhouRows === 2, '周杰伦组下 2 首', zhouRows);

  /* ---- 折叠/展开 ---- */
  const foldRes = await ev("(function(){var h=Array.prototype.find.call(document.querySelectorAll('.q-group-head'),function(e){return e.querySelector('.q-gname').textContent==='周杰伦';}); if(!h) return 'no-head'; h.click(); return h.nextElementSibling.style.display;})()");
  ck(foldRes === 'none', '点击组头折叠（首行 display none）', foldRes);
  const visCount = await ev("(function(){var rows=document.querySelectorAll('#radio-queue-list .q-item'),n=0; for(var i=0;i<rows.length;i++){if(rows[i].style.display!=='none')n++;} return n;})()");
  ck(visCount === 3, '折叠后可见 3 首', visCount);
  await ev("(function(){var h=Array.prototype.find.call(document.querySelectorAll('.q-group-head'),function(e){return e.querySelector('.q-gname').textContent==='周杰伦';}); if(h) h.click(); return 1;})()");
  const visCount2 = await ev("(function(){var rows=document.querySelectorAll('#radio-queue-list .q-item'),n=0; for(var i=0;i<rows.length;i++){if(rows[i].style.display!=='none')n++;} return n;})()");
  ck(visCount2 === 5, '再点展开恢复 5 首', visCount2);

  /* ---- 专辑分组 ---- */
  await ev("(function(){var b=document.querySelector('#q-group-btns button[data-g=album]'); b.click(); return 1;})()");
  await sleep(300);
  const aheads = await ev("document.querySelectorAll('#radio-queue-list .q-group-head').length");
  ck(aheads >= 1, '专辑分组有组头', aheads);
  const unknownOk = await ev("(function(){var t=document.getElementById('radio-queue-list').textContent; return t.indexOf('未知专辑')>=0 || document.querySelectorAll('#radio-queue-list .q-group-head').length>=3;})()");
  ck(unknownOk, '无专辑标签曲目归入「未知专辑」或多专辑分组', unknownOk);

  /* ---- 过滤态下播放：搜 Westlife 后点行，active 落对 ---- */
  await ev("(function(){var b=document.querySelector('#q-group-btns button[data-g=none]'); b.click(); var s=document.getElementById('q-search'); s.value='Westlife'; s.dispatchEvent(new Event('input',{bubbles:true})); return 1;})()");
  await sleep(300);
  const wRows = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
  ck(wRows === 1, '过滤后只剩 Westlife 1 首', wRows);
  await ev("(function(){var it=document.querySelector('#radio-queue-list .q-item'); it.click(); return 1;})()");
  await sleep(1200);
  const activeTxt = await ev("(function(){var a=document.querySelector('#radio-queue-list .q-item.active .q-name'); return a?a.textContent:'';})()");
  ck(/Westlife|My Love/i.test(activeTxt), '过滤态点行播放成功（active 行）', activeTxt);

  /* ---- 切回全部：active 行仍在、无 JS 报错 ---- */
  await ev("(function(){var s=document.getElementById('q-search'); s.value=''; s.dispatchEvent(new Event('input',{bubbles:true})); return 1;})()");
  await sleep(250);
  const allRows = await ev("document.querySelectorAll('#radio-queue-list .q-item').length");
  ck(allRows >= 5, '切回全部恢复 5+ 首', allRows);
  const activeStill = await ev("!!document.querySelector('#radio-queue-list .q-item.active')");
  ck(activeStill, '切回全部后 active 高亮保留');
  ck(errs.length === 0, '全程无 JS 异常/错误', errs.slice(0, 3));

  try { ws.close(); } catch (e) {}
  chrome.kill();
  console.log('----');
  console.log('RESULT: ' + pass + ' pass, ' + fail + ' fail');
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('PROBE ERROR:', e.message); process.exit(2); });
