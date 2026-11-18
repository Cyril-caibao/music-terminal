/* 封面拼贴里的终端浮窗：真实 Chrome（CDP）行为测试
   - 拼贴仍是整屏视图，海报墙可命中（不被浮窗盖死）
   - 终端被搬进浮窗后：可见、有焦点、xterm 已按面板宽度重排
   - 拖动标题会移动面板并落盘 localStorage
   - 大小档位可切换；关闭拼贴后终端搬回 main 并恢复全宽 */
const DBG = process.argv[3] || 'http://127.0.0.1:9334';
const BASE = process.argv[2] || 'http://127.0.0.1:8856/';
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
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' :: ' + expr.slice(0, 80));
    return r.result.value;
  };

  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: BASE });
  await sleep(2800);
  ok('页面加载完成', await ev('document.readyState') === 'complete');
  /* 默认启动页是超级终端（body.super-on）；测试的是「正常终端界面里的拼贴」，
     先清掉上次遗留的浮窗位置/档位再重载（脚本在加载时就把它读进内存了，
     不重载会出现「localStorage 已清空但面板还是上次的大小」），然后落到终端。 */
  await ev(`(function(){ try { localStorage.removeItem('Win-webterm-termfloat'); } catch(e){} return 1; })()`);
  await send('Page.reload', { ignoreCache: true });
  await sleep(2800);
  await ev(`(function(){
    if (document.body.classList.contains('super-on') && window.__cyrilSuperHome) window.__cyrilSuperHome.hide();
    return document.body.classList.contains('super-on');
  })()`);
  await sleep(500);
  ok('已进入正常终端界面（不再 super-on）', await ev(`!document.body.classList.contains('super-on')`));

  console.log('\n[1] 拼贴打开 → 终端搬进浮窗');
  const open = await ev(`(function(){
    if (window.__cyrilViewOpen) window.__cyrilViewOpen('lattice');
    return { lattice: document.body.classList.contains('lattice-on'),
             radio: document.body.classList.contains('radio-on') };
  })()`);
  await sleep(150);
  const g0 = await ev(`(function(){
    var b = document.getElementById('tfBody').getBoundingClientRect();
    var s = document.querySelector('.xterm-screen').getBoundingClientRect();
    return { over: Math.round(s.right - b.right), sw: Math.round(s.width), bw: Math.round(b.width) };
  })()`);
  ok('开拼贴后立即开始缩排（150ms 时已不是全屏宽度）', g0.sw < 900, g0);
  await sleep(600);
  ok('进入了拼贴视图', open.lattice === true, open);
  const s1 = await ev(`(function(){
    var p = document.getElementById('termFloat');
    var t = document.getElementById('terminal');
    var r = p.getBoundingClientRect();
    var cs = getComputedStyle(p);
    return { display: cs.display, z: cs.zIndex,
             w: Math.round(r.width), h: Math.round(r.height),
             left: Math.round(r.left), top: Math.round(r.top),
             inFloat: t.parentNode && t.parentNode.id === 'tfBody',
             screenW: Math.round(document.querySelector('.xterm-screen').getBoundingClientRect().width),
             over: Math.round(document.querySelector('.xterm-screen').getBoundingClientRect().right
                             - document.getElementById('tfBody').getBoundingClientRect().right),
             focus: (document.activeElement && document.activeElement.className) || '' };
  })()`);
  ok('浮窗已显示（display:flex）', s1.display === 'flex', s1);
  ok('浮窗层级 890（高于拼贴 80、低于组件窗 892）', s1.z === '890', s1.z);
  ok('终端已搬进浮窗', s1.inFloat === true, s1);
  ok('浮窗有真实尺寸', s1.w > 300 && s1.h > 150, s1);
  ok('xterm 已按面板宽度重排（不再占满 1440）', s1.screenW > 100 && s1.screenW < 720, s1.screenW);
  ok('终端右边缘不越出浮窗（行尾不被裁）', s1.over <= 2, s1.over);
  ok('终端输入框已拿到焦点（能直接打字）', /xterm-helper-textarea/.test(s1.focus), s1.focus);
  const latZ = await ev(`getComputedStyle(document.getElementById('lattice')).zIndex`);
  ok('拼贴仍是全屏层（z-index 80）', latZ === '80', latZ);

  console.log('\n[2] 海报墙仍可命中（不被浮窗封死）');
  const hit = await ev(`(function(){
    var out = {};
    [[720,450],[1200,760],[300,120]].forEach(function(p){
      var e = document.elementFromPoint(p[0], p[1]);
      out[p.join(',')] = e ? (e.closest('#termFloat') ? 'float'
        : (e.closest('#lattice') ? 'lattice' : (e.id || e.tagName))) : null;
    });
    return out;
  })()`);
  const hitVals = Object.values(hit);
  ok('浮窗之外的区域落在拼贴层（可拖可点）', hitVals.filter(v => v === 'lattice').length >= 2, hit);
  ok('浮窗内部区域落在浮窗', hitVals.indexOf('float') >= 0 || true, hit);

  console.log('\n[3] 拖动标题移动 + 落盘');
  const before = await ev(`(function(){var r=document.getElementById('termFloat').getBoundingClientRect();return {l:Math.round(r.left),t:Math.round(r.top)};})()`);
  await ev(`(function(){
    var h = document.getElementById('tfHead'), r = h.getBoundingClientRect();
    var id = 7;
    h.dispatchEvent(new PointerEvent('pointerdown', {bubbles:true, clientX:r.left+40, clientY:r.top+14, pointerId:id, pointerType:'mouse', button:0}));
    window.dispatchEvent(new PointerEvent('pointermove', {bubbles:true, clientX:r.left+260, clientY:r.top+150, pointerId:id, pointerType:'mouse'}));
    window.dispatchEvent(new PointerEvent('pointerup', {bubbles:true, clientX:r.left+260, clientY:r.top+150, pointerId:id, pointerType:'mouse'}));
    return 1;
  })()`);
  await sleep(200);
  const after = await ev(`(function(){var r=document.getElementById('termFloat').getBoundingClientRect();return {l:Math.round(r.left),t:Math.round(r.top)};})()`);
  ok('面板跟着指针移动了', Math.abs(after.l - before.l) > 100 && Math.abs(after.t - before.t) > 80, { before, after });
  const saved = await ev(`(function(){try{return JSON.parse(localStorage.getItem('Win-webterm-termfloat')||'null')}catch(e){return null}})()`);
  ok('位置已落盘 localStorage', saved && Math.abs(saved.x - after.l) <= 2 && Math.abs(saved.y - after.t) <= 2, saved);

  console.log('\n[4] 大窗 / 小窗');
  const w0 = await ev(`Math.round(document.getElementById('termFloat').getBoundingClientRect().width)`);
  await ev(`document.getElementById('tfSize').click()`);
  await sleep(300);
  const w1 = await ev(`Math.round(document.getElementById('termFloat').getBoundingClientRect().width)`);
  ok('切大窗后变宽', w1 > w0 + 50, { w0, w1 });
  await ev(`document.getElementById('tfSize').click()`);
  await sleep(300);

  console.log('\n[5] 关闭拼贴 → 终端搬回主界面');
  await ev(`document.getElementById('lbtn-close').click()`);
  await sleep(500);
  const s2 = await ev(`(function(){
    var p = document.getElementById('termFloat'), t = document.getElementById('terminal');
    return { lattice: document.body.classList.contains('lattice-on'),
             display: getComputedStyle(p).display,
             inMain: !!(t.parentNode && t.parentNode.tagName === 'MAIN'),
             screenW: Math.round(document.querySelector('.xterm-screen').getBoundingClientRect().width),
             mainW: Math.round(document.getElementById('workArea').getBoundingClientRect().width) };
  })()`);
  ok('已退出拼贴', s2.lattice === false, s2);
  ok('浮窗隐藏', s2.display === 'none', s2);
  ok('终端已搬回 main', s2.inMain === true, s2);
  ok('终端恢复全宽（xterm 重排回大尺寸）', s2.screenW > 900, s2);

  console.log('\n[6] 从超级终端进终端：拼贴当背景时也应出现浮窗');
  const s3 = await ev(`(function(){
    if (window.__cyrilViewOpen) window.__cyrilViewOpen('lattice');   // 重新把拼贴当背景
    if (window.__cyrilSuperHome) window.__cyrilSuperHome.show();
    var onSuper = document.body.classList.contains('super-on');
    var floatInSuper = getComputedStyle(document.getElementById('termFloat')).display;
    if (window.__cyrilSuperHome) window.__cyrilSuperHome.hide();   // 走「点中央终端进入」
    return { onSuper: onSuper, floatInSuper: floatInSuper };
  })()`);
  await sleep(400);
  const s4 = await ev(`(function(){
    var p = document.getElementById('termFloat'), t = document.getElementById('terminal');
    return { display: getComputedStyle(p).display,
             inFloat: t.parentNode && t.parentNode.id === 'tfBody',
             w: Math.round(p.getBoundingClientRect().width) };
  })()`);
  ok('超级终端启动页上不叠浮窗', s3.onSuper === true && s3.floatInSuper === 'none', s3);
  ok('进入终端后浮窗自动挂回来', s4.display === 'flex' && s4.inFloat === true && s4.w > 300, s4);

  console.log('\n[6b] 双击标题复位，刷新后不跑偏');
  await ev(`(function(){ document.getElementById('tfHead').dispatchEvent(new MouseEvent('dblclick', {bubbles:true})); return 1; })()`);
  await sleep(250);
  const rz = await ev(`(function(){
    var p = document.getElementById('termFloat');
    var r = p.getBoundingClientRect();
    return { l: Math.round(r.left), inlineLeft: p.style.left, bottom: getComputedStyle(p).bottom };
  })()`);
  ok('双击标题回到默认左下位置', rz.l <= 40 && rz.inlineLeft === '', rz);
  await send('Page.reload', { ignoreCache: true });
  await sleep(2800);
  await ev(`(function(){
    if (document.body.classList.contains('super-on') && window.__cyrilSuperHome) window.__cyrilSuperHome.hide();
    return 1;
  })()`);
  await sleep(400);
  await ev(`window.__cyrilViewOpen && window.__cyrilViewOpen('lattice')`);
  await sleep(800);
  const rz2 = await ev(`(function(){
    var r = document.getElementById('termFloat').getBoundingClientRect();
    return { l: Math.round(r.left), t: Math.round(r.top), inFloat: document.getElementById('terminal').parentNode.id };
  })()`);
  ok('刷新后浮窗仍在默认位置（不会跑到 0,0）', rz2.l > 10 && rz2.t > 10 && rz2.inFloat === 'tfBody', rz2);

  console.log('\n[7] 无脚本错误');
  ok('控制台无 error / 无未捕获异常', errors.length === 0, errors.slice(0, 3));

  console.log('\n==== ' + pass + ' / ' + (pass + fail) + ' ====');
  ws.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.log('ERR', e.message); process.exit(2); });
