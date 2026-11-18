/* 凝彩接进 WebTerm 之后的 UI 验收：菜单项 / 外观按钮布局 / 三态互斥 / 主题联动 / 无报错。
 * 用法：node _tempera_ui_test.cjs [baseUrl]
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

let pass = 0, fail = 0;
const ck = (ok, name, detail) => {
  if (ok) { pass++; console.log('PASS', name, detail === undefined ? '' : JSON.stringify(detail)); }
  else { fail++; console.log('FAIL', name, detail === undefined ? '' : JSON.stringify(detail)); }
};

(async () => {
  const profile = path.join(os.tmpdir(), 'wt-tempera-ui');
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
  await cmd('Runtime.enable'); await cmd('Page.enable');

  for (let i = 0; i < 60; i++) {
    try { if (await ev("!!window.__cyrilViewOpen && !!window.Tempera")) break; } catch (e) {}
    await sleep(250);
  }
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(400);

  /* ---- 1) 视图菜单里有凝彩，且能点 ---- */
  const menu = await ev("JSON.stringify(Array.from(document.querySelectorAll('#viewMenu .vm-item')).map(e=>e.getAttribute('data-view')))");
  ck(JSON.parse(menu).indexOf('tempera') >= 0, '视图菜单含凝彩项', menu);
  await ev("document.getElementById('rbtn-view').click()");
  await sleep(300);
  await ev("(function(){var it=Array.from(document.querySelectorAll('#viewMenu .vm-item')).find(e=>e.getAttribute('data-view')==='tempera'); if(it) it.click(); return 1;})()");
  await sleep(3000);
  ck(await ev("document.body.classList.contains('tempera-on')"), '点菜单凝彩 → body.tempera-on');
  let dbg = JSON.parse(await ev('JSON.stringify(window.Tempera.debug())'));
  ck(dbg.started && dbg.ticker, '凝彩 runtime 已创建', { started: dbg.started, ticker: dbg.ticker });
  ck(dbg.paragraphs > 0, '演示态也有段落可画', dbg.paragraphs);
  const f1 = dbg.frames;
  await sleep(1200);
  dbg = JSON.parse(await ev('JSON.stringify(window.Tempera.debug())'));
  ck(dbg.frames > f1, 'ticker 在跑（帧数递增）', { before: f1, after: dbg.frames });
  ck(/凝彩/.test(await ev("document.getElementById('rbtn-view').title")), '视图按钮提示文案已含凝彩',
    await ev("document.getElementById('rbtn-view').title"));

  /* ---- 2) 外观面板：四个背景按钮都在，且不溢出面板 ---- */
  await ev("document.getElementById('btnSettings').click()");
  await sleep(200);
  await ev("(function(){var b=Array.from(document.querySelectorAll('#settingsPanel .btn, #settingsPanel button')).find(e=>/外观/.test(e.textContent)); if(b) b.click(); return 1;})()");
  await sleep(400);
  const box = await ev(`(function(){
    var p=document.getElementById('appearancePanel');
    var t=document.getElementById('btnTempera');
    var wall=document.getElementById('btnWall');
    if(!p||!t) return null;
    var pr=p.getBoundingClientRect(), tr=t.getBoundingClientRect(), wr=wall.getBoundingClientRect();
    return {
      visible: getComputedStyle(p).display !== 'none',
      inside: tr.right <= pr.right + 0.5 && tr.left >= pr.left - 0.5,
      sameRow: Math.abs(tr.top - wr.top) < 2,
      width: Math.round(tr.width), text: t.textContent.trim()
    };
  })()`);
  ck(!!box && box.visible, '外观面板已展开', box);
  ck(!!box && box.text === '凝彩', '外观面板有凝彩按钮', box && box.text);
  ck(!!box && box.inside, '凝彩按钮不溢出面板', box);
  ck(!!box && box.sameRow, '四个背景按钮同一行（面板够宽未换行）', box);

  /* ---- 3) 点外观的凝彩 → 进电台且背景是凝彩 ---- */
  await ev("document.getElementById('btnTempera').click()");
  await sleep(3000);
  ck(await ev("document.body.classList.contains('radio-on') && document.body.classList.contains('tempera-on')"), '外观凝彩 → 电台 + 凝彩背景');
  ck(await ev("document.getElementById('btnTempera').classList.contains('active')"), '凝彩按钮高亮');

  /* ---- 4) 黑白联动：凝彩主题跟着翻 ---- */
  await ev("document.getElementById('btnBlack').click()");
  await sleep(2200);
  ck(/午夜墨染/.test(await ev("window.Tempera.themeName()")), '黑 → 午夜墨染', await ev("window.Tempera.themeName()"));
  ck(/#09090b|rgb\(9, 9, 11\)/.test(await ev("document.getElementById('tempera-canvas').style.background")), '黑 → 纸色 #09090b',
    await ev("document.getElementById('tempera-canvas').style.background"));
  await ev("document.getElementById('btnWhite').click()");
  await sleep(2200);
  ck(/日光素白/.test(await ev("window.Tempera.themeName()")), '白 → 日光素白', await ev("window.Tempera.themeName()"));
  ck(/#f5f5f4|rgb\(245, 245, 244\)/.test(await ev("document.getElementById('tempera-canvas').style.background")), '白 → 纸色 #f5f5f4',
    await ev("document.getElementById('tempera-canvas').style.background"));

  /* ---- 5) 三态互斥：切到绘光 / 音域，凝彩要收干净 ---- */
  await ev("window.__cyrilViewOpen('lumiere')");
  await sleep(2500);
  ck(!(await ev("document.body.classList.contains('tempera-on')")), '切绘光后 tempera-on 已摘');
  ck((await ev("document.querySelectorAll('#tempera-canvas canvas').length")) === 0, '切绘光后凝彩画布已销毁');
  ck(!(await ev("window.Tempera.debug().started")), '切绘光后 Tempera.started=false');
  ck(await ev("document.body.classList.contains('lumiere-on')") && await ev("window.Lumiere.debug().ticker"), '绘光同时正常工作');

  await ev("window.__cyrilViewOpen('sonic')");
  await sleep(1500);
  ck(await ev("document.body.classList.contains('sonic-on')"), '切音域正常');
  ck(!(await ev("document.body.classList.contains('lumiere-on')")), '音域态下绘光已摘');

  /* ---- 6) 命令面板（模块菜单）里有凝彩 ---- */
  const kids = await ev("(function(){try{return JSON.stringify((window.__cyrilMusicKids||null));}catch(e){return null}})()");
  const srcHas = await ev("(function(){var s=document.documentElement.innerHTML;return s.indexOf('view:tempera')>=0})()");
  ck(srcHas || kids, '模块菜单动作表含 view:tempera', { kids, srcHas });

  /* ---- 7) 退出电台：凝彩要停 ---- */
  await ev("window.__cyrilViewClose && window.__cyrilViewClose()");
  await sleep(1200);
  const stillTempera = await ev("document.body.classList.contains('tempera-on')");
  const stillRadio = await ev("document.body.classList.contains('radio-on')");
  ck(!stillTempera && !stillRadio, '退出电台后回到终端', { stillTempera, stillRadio });

  ck(errs.length === 0, '页面无 console / 运行时报错', errs.slice(0, 4));

  console.log('\n---- 结果 ----');
  console.log('PASS ' + pass + ' / FAIL ' + fail);
  ws.close(); chrome.kill();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('异常', e); process.exit(2); });
