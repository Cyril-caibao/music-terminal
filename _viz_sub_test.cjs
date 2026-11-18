/* 译文条验收：英文歌在绘光/凝彩下，大字是原文、下方补一行中文译文。
   用法：node _viz_sub_test.cjs [http://127.0.0.1:18773/] */
const { spawn } = require('child_process');
const http = require('http'); const net = require('net'); const crypto = require('crypto');
const fs = require('fs'); const path = require('path'); const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18773/';
const MP3 = path.join(__dirname, '_maintenance', '20261118', 'test-lyric-en.mp3');
const LRC = path.join(__dirname, '_maintenance', '20261118', 'test-lyric-en.lrc');
const PORT = 9437, CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = path.join(__dirname, '_maintenance', '20261118');
const sleep = ms => new Promise(r => setTimeout(r, ms));

let pass = 0, fail = 0;
function ck(ok, name, extra) {
  if (ok) { pass++; console.log('  PASS  ' + name); }
  else { fail++; console.log('  FAIL  ' + name + (extra !== undefined ? '  → ' + JSON.stringify(extra) : '')); }
}
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
  const prof = path.join(os.tmpdir(), 'wt-vizsub'); try { fs.rmSync(prof, { recursive: true, force: true }); } catch (e) {}
  const ch = spawn(CHROME, ['--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + prof,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required', BASE], { stdio: 'ignore' });
  let ts = null; for (let i = 0; i < 40; i++) { try { ts = await hj('/json/list'); if (ts && ts.length) break; } catch (e) {} await sleep(250); }
  const page = (ts || []).find(t => t.type === 'page' && t.url.indexOf('127.0.0.1') >= 0);
  const w = await ws(new URL(page.webSocketDebuggerUrl).pathname);
  let id = 0; const pend = new Map(); const errs = [];
  w.onText(t => {
    let m; try { m = JSON.parse(t); } catch (e) { return; }
    if (m.id && pend.has(m.id)) { const p = pend.get(m.id); pend.delete(m.id); m.error ? p.rej(new Error(JSON.stringify(m.error))) : p.res(m.result); return; }
    if (m.method === 'Runtime.exceptionThrown') { const d = m.params.exceptionDetails; errs.push(((d.exception && d.exception.description) || d.text || '').split('\n')[0]); }
  });
  const cmd = (me, pa) => new Promise((res, rej) => { const i = ++id; pend.set(i, { res, rej }); w.send({ id: i, method: me, params: pa || {} }); });
  const ev = async e => {
    const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true, userGesture: true });
    if (r.exceptionDetails) return '__ERR__' + JSON.stringify(r.exceptionDetails).slice(0, 200);
    return r.result && r.result.value;
  };
  const el = s => ev(`JSON.stringify((function(){var e=document.querySelector('${s}');if(!e)return null;var st=getComputedStyle(e),r=e.getBoundingClientRect();
    return {text:(e.textContent||'').trim(), show:e.classList.contains('show'), display:st.display, vis:st.visibility, op:st.opacity,
            color:st.color, bg:st.backgroundColor, z:st.zIndex, rect:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}};})())`);
  await cmd('Runtime.enable'); await cmd('Page.enable'); await cmd('DOM.enable');

  for (let i = 0; i < 60; i++) { if (await ev('!!window.__cyrilViewOpen')) break; await sleep(250); }
  for (let i = 0; i < 6; i++) { await ev('window.__cyrilSuperHome&&window.__cyrilSuperHome.hide()'); await sleep(200); }
  await ev("document.getElementById('btnMode').click()"); await sleep(800);

  const doc = await cmd('DOM.getDocument', { depth: -1 });
  const q = await cmd('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#radio-files' });
  await cmd('DOM.setFileInputFiles', { nodeId: q.nodeId, files: [MP3, LRC] });
  let qn = 0;
  for (let i = 0; i < 30; i++) { qn = await ev("document.querySelectorAll('#radio-queue-list .q-item').length"); if (qn > 0) break; await sleep(400); }
  ck(qn > 0, '导入后队列有曲目', qn);
  await ev("(function(){var it=document.querySelector('#radio-queue-list .q-item'); if(it) it.click(); return 1;})()");
  await sleep(1500);

  const lines = await ev("document.querySelectorAll('#radio-lyrics-body .l-line').length");
  ck(lines === 3, '3 句歌词未被译文撑成 6 行', lines);

  /* 无可视化器时不应该显示 */
  const off = JSON.parse(await el('#viz-sub'));
  ck(!off || !off.show, '终端模式下译文条不显示', off);

  /* 进凝彩 */
  await ev("window.__cyrilViewOpen('tempera')");
  await sleep(2600);
  const sub1 = JSON.parse(await el('#viz-sub'));
  ck(!!sub1 && sub1.show, '凝彩下译文条已显示', sub1);
  ck(!!sub1 && /[\u4e00-\u9fa5]/.test(sub1.text), '译文条内容是中文', sub1 && sub1.text);
  ck(!!sub1 && sub1.text.indexOf('醒来') === 0, '译文条取的是当前句译文', sub1 && sub1.text);
  const bar = JSON.parse(await el('#radio-controls'));
  ck(!!sub1 && sub1.rect.y > 400, '译文条落在画面下半部（不压终端提示符）', sub1 && sub1.rect);
  ck(!!sub1 && !!bar && (sub1.rect.y + sub1.rect.h) <= bar.rect.y + 1,
    '译文条在播放条上方、不被它盖住',
    { subBottom: sub1 && sub1.rect.y + sub1.rect.h, barTop: bar && bar.rect.y });
  const shot1 = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(OUT, 'probe-vizsub-tempera-dark.png'), Buffer.from(shot1.data, 'base64'));

  /* 大字必须仍是英文原文，不能被译文顶掉 */
  const dbg = JSON.parse(await ev('JSON.stringify(window.Tempera.debug())'));
  const shownTex = (dbg.scene && dbg.scene.shown || []).map(s => s.t).join('');
  ck(/[A-Za-z]/.test(shownTex), '凝彩大字仍是英文原文', shownTex.slice(0, 40));

  /* 切绘光：译文条要在 */
  await ev("window.__cyrilViewOpen('lumiere')");
  await sleep(2200);
  const sub2 = JSON.parse(await el('#viz-sub'));
  ck(!!sub2 && sub2.show && /[\u4e00-\u9fa5]/.test(sub2.text), '绘光下译文条同样显示', sub2 && sub2.text);
  const shot2 = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(OUT, 'probe-vizsub-lumiere.png'), Buffer.from(shot2.data, 'base64'));

  /* 黑白两端各取一次：深色主题浅字、浅色主题深字 */
  await ev("window.__cyrilApplyTheme('dark')"); await sleep(800);
  const subDark = JSON.parse(await el('#viz-sub'));
  /* 浅纸深字只属于凝彩（绘光的白主题仍是压暗舞台，见 CSS 注释） */
  await ev("window.__cyrilViewOpen('tempera')"); await sleep(1800);
  await ev("window.__cyrilApplyTheme('light')"); await sleep(800);
  const sub3 = JSON.parse(await el('#viz-sub'));
  const lum = c => { const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c || ''); return m ? (+m[1] + +m[2] + +m[3]) / 3 : -1; };
  ck(!!subDark && subDark.show && lum(subDark.color) > 150, '深色主题下译文条是浅字', subDark && subDark.color);
  ck(!!sub3 && sub3.show && lum(sub3.color) > 0 && lum(sub3.color) < 110, '浅色主题下译文条是深字', sub3 && sub3.color);
  const shot3 = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(OUT, 'probe-vizsub-light.png'), Buffer.from(shot3.data, 'base64'));

  /* ===== 第六轮新增：去白框 / 译文开关 / 终端指令 ===== */

  /* 去白框：两种主题下译文条背景都必须全透明（sub1 深色、sub3 浅色凝彩） */
  ck(!!sub1 && /rgba\(0, 0, 0, 0\)|transparent/.test(sub1.bg), '深色主题译文条无背景框', sub1 && sub1.bg);
  ck(!!sub3 && /rgba\(0, 0, 0, 0\)|transparent/.test(sub3.bg), '浅色凝彩译文条无背景框', sub3 && sub3.bg);

  /* 设置面板里要有开关、默认开、与 localStorage 同步 */
  const boxState = JSON.parse(await ev(`JSON.stringify((function(){
    var b = document.getElementById('setVizSub');
    if (!b) return null;
    return { exists: true, checked: b.checked, ls: localStorage.getItem('Win-webterm-viz-sub') };
  })())`));
  ck(!!boxState && boxState.exists && boxState.checked, '设置面板有「电台译文」开关且默认开', boxState);

  /* 终端 trans off / on：走真实 PTY（server.py 预置的 trans 别名 → 标记 → feedTerm 改写） */
  await ev("window.__cyrilInsertTerm('trans off\\r')");
  let lsOff = '';
  for (let i = 0; i < 20; i++) {
    lsOff = await ev("localStorage.getItem('Win-webterm-viz-sub')");
    if (lsOff === '0') break;
    await sleep(400);
  }
  ck(lsOff === '0', '终端 trans off 已关闭译文（localStorage=0）', lsOff);
  await ev("window.__cyrilInsertTerm('trans on\\r')");
  let lsOn = '';
  for (let i = 0; i < 20; i++) {
    lsOn = await ev("localStorage.getItem('Win-webterm-viz-sub')");
    if (lsOn === '1') break;
    await sleep(400);
  }
  ck(lsOn === '1', '终端 trans on 已重新开启（localStorage=1）', lsOn);
  const boxAfter = await ev("document.getElementById('setVizSub').checked");
  ck(boxAfter === true, '设置面板开关跟随 trans 指令同步', boxAfter);

  /* 设置 / 插件：英文命令走 PTY 打开对应面板 */
  await ev("window.__cyrilInsertTerm('settings\\r')");
  let setShown = false;
  for (let i = 0; i < 20; i++) {
    setShown = await ev("document.getElementById('settingsPanel').classList.contains('show')");
    if (setShown) break;
    await sleep(400);
  }
  ck(setShown, '终端 settings → 设置面板已打开', setShown);
  await ev("document.querySelector('#settingsPanel .panel-x').click()"); await sleep(400);

  await ev("window.__cyrilInsertTerm('plugin\\r')");
  let compShown = false;
  for (let i = 0; i < 20; i++) {
    compShown = await ev("document.getElementById('compPanel').classList.contains('show')");
    if (compShown) break;
    await sleep(400);
  }
  ck(compShown, '终端 plugin → 组件面板已打开', compShown);
  await ev("document.querySelector('#compPanel .panel-x').click()"); await sleep(400);

  /* 退出电台：收起 */
  await ev("window.__cyrilViewClose()");
  await sleep(900);
  const sub4 = JSON.parse(await el('#viz-sub'));
  ck(!sub4 || !sub4.show, '退出电台后译文条收起', sub4);

  ck(errs.length === 0, '无 console 异常', errs.slice(0, 3));

  console.log('\n结果：' + pass + ' PASS / ' + fail + ' FAIL');
  w.close(); try { ch.kill(); } catch (e) {}
  process.exit(fail ? 1 : 0);
})();
