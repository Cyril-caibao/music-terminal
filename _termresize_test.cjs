/* 浮窗拖拽改尺寸 + 光标对齐回归（真实 Chrome / CDP）
   - 刚打开拼贴那一刻，屏幕上的「提示符文本行」和「光标视觉行」必须同一行（用户报的 bug）
   - 右下角抓手拖完之后：面板像素变了、xterm 行列跟着变、提示符仍在光标那一行
   - 尺寸落盘、刷新后还在；⤢ 能回到预设档；双击抓手恢复默认大小
   - 不超出视口、不小于最小尺寸、拖尺寸不误触面板按钮、无脚本错误
*/
const DBG = process.argv[3] || 'http://127.0.0.1:9334';
const BASE = process.argv[2] || 'http://127.0.0.1:8861/';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  ' + JSON.stringify(extra) : '')); }
}

(async () => {
  const list = await (await fetch(DBG + '/json/list')).json();
  for (const t of list) { if (t.type === 'page') await fetch(DBG + '/json/close/' + t.id).catch(() => {}); }
  const target = await (await fetch(DBG + '/json/new?' + encodeURIComponent('about:blank'), { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  let id = 0; const waiters = new Map();
  await new Promise(r => ws.onopen = r);
  const errors = [];
  ws.onmessage = e => {
    const m = JSON.parse(e.data);
    if (m.id && waiters.has(m.id)) { waiters.get(m.id)(m); waiters.delete(m.id); return; }
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.text);
    if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
      const src = (m.params.entry.url || '');
      if (!/\/json\//.test(src)) errors.push(m.params.entry.text);
    }
  };
  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++id; waiters.set(i, x => x.error ? rej(new Error(method + ': ' + JSON.stringify(x.error))) : res(x.result));
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  const ev = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' :: ' + expr.slice(0, 100));
    return r.result.value;
  };
  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: BASE });
  await sleep(2800);
  await ev(`(function(){ try { localStorage.removeItem('Win-webterm-termfloat'); } catch(e){} return 1; })()`);
  await send('Page.reload', { ignoreCache: true });
  await sleep(2800);
  await ev(`(function(){
    if (document.body.classList.contains('super-on') && window.__cyrilSuperHome) window.__cyrilSuperHome.hide();
    return 1;
  })()`);
  await sleep(600);

  /* 一次采样：面板像素 + xterm 行列 + 提示符视觉行 + 光标视觉行 */
  const SNAP = `(function(){
    var t = window.__cyrilTerm, b = t.buffer.active;
    var p = document.getElementById('termFloat').getBoundingClientRect();
    var scr = document.querySelector('.xterm-screen').getBoundingClientRect();
    var ch = scr.height / t.rows;
    var rowsEl = document.querySelector('.xterm-rows');
    var dom = rowsEl ? Array.prototype.map.call(rowsEl.children, function(d){ return d.textContent || ''; }) : [];
    var promptRow = -1;
    for (var i = 0; i < dom.length; i++) if (dom[i].indexOf('PS ') === 0) promptRow = i;
    var cur = document.querySelector('.xterm-cursor');
    var cr = cur ? cur.getBoundingClientRect() : null;
    return { w: Math.round(p.width), h: Math.round(p.height),
             left: Math.round(p.left), top: Math.round(p.top),
             rows: t.rows, cols: t.cols,
             modelRow: b.cursorY - (b.baseY - b.viewportY),
             curRow: cr ? Math.round((cr.top - scr.top) / ch) : null,
             promptRow: promptRow, domRows: dom.length,
             big: document.getElementById('termFloat').classList.contains('big') };
  })()`;
  const aligned = s => s.promptRow >= 0 && s.curRow === s.promptRow && s.modelRow === s.promptRow;

  console.log('[1] 刚进拼贴：光标必须和提示符同一行');
  await ev(`(function(){ if (window.__cyrilViewOpen) window.__cyrilViewOpen('lattice'); return 1; })()`);
  let firstBad = null;
  for (const ms of [30, 60, 120, 250, 500, 900]) {
    await sleep(ms);
    const s = await ev(SNAP);
    if (!firstBad && !aligned(s)) firstBad = { ms: ms, s: s };
  }
  const s0 = await ev(SNAP);
  ok('稳定后光标行 == 提示符行 == 模型行', aligned(s0), s0);
  ok('搬进浮窗后 xterm 行列跟着面板走（不是全屏 1440）', s0.cols < 100 && s0.rows < 40, s0);

  console.log('\n[2] 右下角抓手拖拽改尺寸');
  const g0 = await ev(SNAP);
  const gripBox = await ev(`(function(){
    var g = document.getElementById('tfGrip'); var r = g.getBoundingClientRect();
    return { l: Math.round(r.left), t: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height),
             vis: getComputedStyle(g).display, cursor: getComputedStyle(g).cursor };
  })()`);
  ok('抓手存在且可拖（nwse-resize）', gripBox.vis !== 'none' && gripBox.w >= 12 && /resize/.test(gripBox.cursor), gripBox);
  ok('抓手贴在面板右下角', Math.abs((gripBox.l + gripBox.w) - (g0.left + g0.w)) <= 6 &&
                          Math.abs((gripBox.t + gripBox.h) - (g0.top + g0.h)) <= 6, { gripBox, panel: g0 });
  await ev(`(function(){
    var g = document.getElementById('tfGrip'), r = g.getBoundingClientRect();
    var sx = r.left + r.width / 2, sy = r.top + r.height / 2, id = 11;
    g.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: sx, clientY: sy, pointerId: id, pointerType: 'mouse', button: 0 }));
    for (var i = 1; i <= 6; i++) {
      window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientX: sx + i * 30, clientY: sy + i * 18, pointerId: id, pointerType: 'mouse' }));
    }
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, clientX: sx + 180, clientY: sy + 108, pointerId: id, pointerType: 'mouse' }));
    return 1;
  })()`);
  await sleep(320);
  const g1 = await ev(SNAP);
  ok('面板变宽了（+~180px）', g1.w - g0.w >= 150, { before: g0.w, after: g1.w });
  ok('面板变高了（+~108px）', g1.h - g0.h >= 80, { before: g0.h, after: g1.h });
  ok('面板左上角没动（朝右下长，不往上顶）', Math.abs(g1.left - g0.left) <= 2 && Math.abs(g1.top - g0.top) <= 2, { g0: g0, g1: g1 });
  ok('xterm 行列跟着变大', g1.cols >= g0.cols && g1.rows > g0.rows, { c0: g0.cols + 'x' + g0.rows, c1: g1.cols + 'x' + g1.rows });
  ok('拖完光标仍在提示符那一行', aligned(g1), g1);
  ok('拖完终端不越出面板', await ev(`(function(){
    var s = document.querySelector('.xterm-screen').getBoundingClientRect();
    var b = document.getElementById('tfBody').getBoundingClientRect();
    return s.right - b.right <= 2 && s.bottom - b.bottom <= 2;
  })()`));

  console.log('\n[3] 最小尺寸 / 不越出视口');
  await ev(`(function(){
    var g = document.getElementById('tfGrip'), r = g.getBoundingClientRect();
    var sx = r.left + 8, sy = r.top + 8, id = 12;
    g.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: sx, clientY: sy, pointerId: id, pointerType: 'mouse', button: 0 }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientX: sx - 2000, clientY: sy - 2000, pointerId: id, pointerType: 'mouse' }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, clientX: sx - 2000, clientY: sy - 2000, pointerId: id, pointerType: 'mouse' }));
    return 1;
  })()`);
  await sleep(300);
  const g2 = await ev(SNAP);
  ok('缩到最小仍是 320×180 起（不会缩成一条线）', g2.w >= 320 && g2.h >= 180, g2);
  ok('缩到最小后光标仍对齐', aligned(g2), g2);

  await ev(`(function(){
    var g = document.getElementById('tfGrip'), r = g.getBoundingClientRect();
    var sx = r.left + 8, sy = r.top + 8, id = 13;
    g.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientX: sx, clientY: sy, pointerId: id, pointerType: 'mouse', button: 0 }));
    window.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientX: sx + 5000, clientY: sy + 5000, pointerId: id, pointerType: 'mouse' }));
    window.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, clientX: sx + 5000, clientY: sy + 5000, pointerId: id, pointerType: 'mouse' }));
    return 1;
  })()`);
  await sleep(300);
  const g3 = await ev(SNAP);
  ok('拖到屏幕外也不会超出视口', g3.left + g3.w <= 1440 && g3.top + g3.h <= 900, g3);

  console.log('\n[4] 尺寸落盘 + 刷新还在');
  const saved = await ev(`JSON.parse(localStorage.getItem('Win-webterm-termfloat')||'null')`);
  ok('localStorage 里存了 w/h', saved && typeof saved.w === 'number' && typeof saved.h === 'number', saved);
  await send('Page.reload', { ignoreCache: true });
  await sleep(2800);
  await ev(`(function(){
    if (document.body.classList.contains('super-on') && window.__cyrilSuperHome) window.__cyrilSuperHome.hide();
    if (window.__cyrilViewOpen) window.__cyrilViewOpen('lattice');
    return 1;
  })()`);
  await sleep(700);
  const r1 = await ev(SNAP);
  ok('刷新后尺寸与刷新前一致（±4px）', Math.abs(r1.w - g3.w) <= 4 && Math.abs(r1.h - g3.h) <= 4, { before: g3, after: r1 });
  ok('刷新后光标仍对齐', aligned(r1), r1);

  console.log('\n[5] ⤢ 换档：手动尺寸作废、回到预设');
  await ev(`document.getElementById('tfSize').click()`);
  await sleep(350);
  const b1 = await ev(SNAP);
  ok('点 ⤢ 后回到预设尺寸（大窗 = min(94vw,1120)）', b1.big === true && Math.abs(b1.w - 1120) <= 2, { r1: r1.w, b1: b1.w, big: b1.big });
  ok('换档后光标仍对齐', aligned(b1), b1);
  ok('换档后左上角不动（朝右下长）', Math.abs(b1.left - r1.left) <= 2, { before: r1.left, after: b1.left });
  await ev(`document.getElementById('tfSize').click()`);
  await sleep(350);
  const b2 = await ev(SNAP);
  ok('再点一次回到小窗', b2.big === false && b2.w < b1.w, { w: b2.w });

  console.log('\n[6] 双击抓手恢复默认大小');
  await ev(`(function(){
    document.getElementById('tfGrip').dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    return 1;
  })()`);
  await sleep(350);
  const d1 = await ev(SNAP);
  ok('双击抓手后尺寸回到默认档', d1.big === false && d1.w <= 700 && d1.w >= 320, d1);
  ok('双击抓手后光标仍对齐', aligned(d1), d1);

  console.log('\n[7] 拖尺寸不会误触标题按钮（面板没被拖走）');
  ok('面板位置由抓手操作决定，标题栏点击未被触发（关闭/换档按钮仍可用）',
     await ev(`!!document.getElementById('tfSize') && document.getElementById('termFloat').getBoundingClientRect().width > 0`));

  console.log('\n[8] 字号晚到（升级后第一次打开）→ 必须很快归位、不裁行尾');
  /* 真实时序：首屏按默认 15px 画完，settings_get 到货才改成保存的 21px。
     字号一变字宽行高全变 —— 这条曾经就是用户报的「刚打开光标没对准 + 提示符末尾被切」。 */
  const fitToBody = `(function(){
    var s = document.querySelector('.xterm-screen').getBoundingClientRect();
    var b = document.getElementById('tfBody').getBoundingClientRect();
    return { over: Math.round(s.right - b.right), sw: Math.round(s.width), bw: Math.round(b.width) };
  })()`;
  await send('Page.reload', { ignoreCache: true });
  await sleep(2600);
  await ev(`(function(){
    localStorage.removeItem('Win-webterm-font');   // 当作升级后第一次打开：没有缓存
    if (document.body.classList.contains('super-on') && window.__cyrilSuperHome) window.__cyrilSuperHome.hide();
    if (window.__cyrilViewOpen) window.__cyrilViewOpen('lattice');
    return 1;
  })()`);
  await sleep(500);
  await ev(`window.__cyrilSetTermFont({ font_size: 21, line_height: 1.2 })`);
  let lateBad = null;
  for (const ms of [220, 400, 800, 1400]) {
    await sleep(ms);
    const s = await ev(SNAP);
    const f = await ev(fitToBody);
    if ((!aligned(s) || f.over > 2) && !lateBad) lateBad = { ms: ms, s: s, f: f };
  }
  const sl = await ev(SNAP);
  const fl = await ev(fitToBody);
  ok('改字号后光标与提示符同一行', aligned(sl), sl);
  ok('改字号后 xterm 没被撑出面板（行尾不被裁）', fl.over <= 2, fl);
  ok('改字号后行列真的跟着变了（21px 比 15px 少列）', sl.cols < s0.cols, { before: s0.cols, after: sl.cols });
  ok('250ms 之后没有任何一次错位/溢出', lateBad === null, lateBad);

  console.log('\n[9] 字体缓存：下次启动首屏就用保存的字号（没有过渡帧）');
  ok('应用字号后本地缓存已写入 21', (await ev(`JSON.parse(localStorage.getItem('Win-webterm-font')||'{}').font_size`)) === 21);
  /* 用「文档加载前就植入的探针」记录 fontSize 的变化序列：第一个值如果是 21，
     就说明构造终端时用的是缓存（默认值 15 会在 applyCfg 之后才出现）。 */
  const probe = await send('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__fsLog = [];
    var __iv = setInterval(function () {
      try {
        if (window.__cyrilTerm && window.__cyrilTerm.options) {
          var v = window.__cyrilTerm.options.fontSize;
          if (window.__fsLog[window.__fsLog.length - 1] !== v) window.__fsLog.push(v);
          if (window.__fsLog.length > 20) clearInterval(__iv);
        }
      } catch (e) {}
    }, 10);
  ` });
  await send('Page.reload', { ignoreCache: true });
  await sleep(2200);
  const fsLog = await ev('window.__fsLog');
  ok('构造时先按缓存的 21px 起屏（首个观测值 = 21）', fsLog[0] === 21, fsLog);
  await ev(`(function(){
    if (document.body.classList.contains('super-on') && window.__cyrilSuperHome) window.__cyrilSuperHome.hide();
    if (window.__cyrilViewOpen) window.__cyrilViewOpen('lattice');
    return 1;
  })()`);
  let coldBad = null;
  for (const ms of [40, 90, 200, 600, 1200]) {
    await sleep(ms);
    const s = await ev(SNAP);
    const f = await ev(fitToBody);
    if ((!aligned(s) || f.over > 2) && !coldBad) coldBad = { ms: ms, s: s, f: f };
  }
  const sc = await ev(SNAP);
  ok('有缓存时进拼贴全过程都没有错位/溢出（含首帧）', coldBad === null, coldBad);
  ok('有缓存时光标与提示符同一行', aligned(sc), sc);
  await send('Page.removeScriptToEvaluateOnNewDocument', { identifier: probe.identifier });
  await ev(`window.__cyrilSetTermFont({ font_size: 15, line_height: 1.2 })`);
  await sleep(400);

  console.log('\n[10] 无脚本错误');
  ok('控制台无 error / 无未捕获异常', errors.length === 0, errors.slice(0, 3));

  console.log('\n==== ' + pass + ' / ' + (pass + fail) + ' ====');
  if (firstBad) console.log('（提示）进拼贴瞬间有一次未对齐快照：' + JSON.stringify(firstBad));
  ws.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
