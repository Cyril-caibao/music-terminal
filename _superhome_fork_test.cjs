/* 超级终端 启动页 · 真实浏览器（CDP）功能测试
 * 只验确定性行为：拖动是否位移、连线是否跟随、四个模块是否都分叉成扇形叶子。
 * 观感由用户自测，这里不评视觉。
 */
const BASE = process.argv[2] || 'http://127.0.0.1:8850/';
const DBG = process.argv[3] || 'http://127.0.0.1:9333';

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ok  ' + name); }
  else { fail++; console.log('  FAIL ' + name + (extra !== undefined ? '  → ' + JSON.stringify(extra) : '')); }
}

(async () => {
  /* 新开一个标签页直接落到目标地址：attach 到 about:blank 会因不透明源读不了 localStorage */
  const list0 = await (await fetch(DBG + '/json/list')).json();
  for (const t of list0) {
    if (t.type === 'page') await fetch(DBG + '/json/close/' + t.id).catch(() => {});
  }
  let created;
  try {
    created = await (await fetch(DBG + '/json/new?' + encodeURIComponent(BASE), { method: 'PUT' })).json();
  } catch (e) {
    created = await (await fetch(DBG + '/json/new?' + encodeURIComponent(BASE))).json();
  }
  const ws = new WebSocket(created.webSocketDebuggerUrl);
  let id = 0;
  const waiters = new Map();
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const errors = [];
  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.id && waiters.has(m.id)) { waiters.get(m.id)(m); waiters.delete(m.id); }
    if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
      const en = m.params.entry;
      /* 调试端口自己吐的 HTTP 层错误（/json/* 走错方法得到的 501）不是页面问题，
         只记页面同源（BASE）的 resource/console 错误 */
      if (en.url && en.url.indexOf(DBG) === 0) return;
      if (/Unsupported method/.test(en.text || '')) return;
      errors.push(en.text + (en.url ? ' @ ' + en.url : ''));
    }
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.text || 'exception');
  };
  function send(method, params) {
    return new Promise((res, rej) => {
      const mid = ++id;
      waiters.set(mid, m => { if (m.error) rej(new Error(method + ': ' + JSON.stringify(m.error))); else res(m.result); });
      ws.send(JSON.stringify({ id: mid, method, params: params || {} }));
    });
  }
  async function ev(expr) {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) {
      const d = r.exceptionDetails;
      throw new Error('eval: ' + ((d.exception && d.exception.description) || d.text));
    }
    return r.result.value;
  }
  const sleep = ms => new Promise(r => setTimeout(r, ms));

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  /* 拖拽会把坐标写回 Settings.json（源码版就在仓库根），跑测试前备份、跑完还原 */
  const cfgPath = process.env.CFG || require('path').join(__dirname, 'Settings.json');
  let cfgRaw = null;
  try { cfgRaw = require('fs').readFileSync(cfgPath, 'utf8'); } catch (e) { cfgRaw = null; }
  if (cfgRaw) {
    const d = JSON.parse(cfgRaw);
    const clean = Object.assign({}, d, { super_nodes: {} });
    require('fs').writeFileSync(cfgPath, JSON.stringify(clean, null, 2), 'utf8');
  }
  await send('Page.navigate', { url: BASE });
  await sleep(2600);
  await ev("localStorage.removeItem('Win-webterm-super-nodes'); localStorage.setItem('Win-webterm-start-page','super'); 1");
  await send('Page.reload', {});
  await sleep(2800);

  const onHome = () => ev("document.body.classList.contains('super-on')");
  const kidCount = () => ev("document.querySelectorAll('.sh-kid').length");
  const kidLabels = () => ev("Array.prototype.map.call(document.querySelectorAll('.sh-kid'), b=>b.textContent)");
  const lines = () => ev(`Array.prototype.map.call(document.querySelectorAll('#superLinkMain line'), l => ({
      x1:+l.getAttribute('x1'), y1:+l.getAttribute('y1'), x2:+l.getAttribute('x2'), y2:+l.getAttribute('y2') }))`);
  const nodePos = key => ev(`(function(){var n=document.querySelector('[data-sh-go="${key}"]');return [parseFloat(n.style.left), parseFloat(n.style.top)];})()`);
  const corePos = () => ev("(function(){var n=document.getElementById('shCoreWrap');return [parseFloat(n.style.left), parseFloat(n.style.top)];})()");

  async function centerOf(sel) {
    return ev(`(function(){var b=document.querySelector('${sel}');if(!b)return null;var r=b.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2};})()`);
  }
  async function dragBy(sel, dx, dy) {
    const c = await centerOf(sel);
    if (!c) throw new Error('no element ' + sel);
    await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: c.x, y: c.y, button: 'left', buttons: 1, clickCount: 1 });
    let mid = null;
    for (let i = 1; i <= 8; i++) {
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: c.x + dx * i / 8, y: c.y + dy * i / 8, button: 'left', buttons: 1 });
      await sleep(16);
      if (i === 5) mid = await ev("(function(){var l=document.querySelector('#superLinkMain line');return [+l.getAttribute('x2'), +l.getAttribute('y2')];})()");
    }
    await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: c.x + dx, y: c.y + dy, button: 'left', buttons: 0, clickCount: 1 });
    await sleep(140);
    return mid;
  }

  console.log('\n[1] 初始状态');
  ok('启动页已显示', await onHome());
  ok('四条芯线由脚本生成', (await lines()).length === 4, (await lines()).length);
  const l0 = await lines();
  const m0 = await nodePos('music');
  const cr0 = await corePos();
  ok('线端点 = 音乐节点坐标', Math.abs(l0[0].x2 - m0[0] * 10) < 0.2 && Math.abs(l0[0].y2 - m0[1] * 10) < 0.2, { line: l0[0], pos: m0 });
  ok('线起点 = 中央终端', Math.abs(l0[0].x1 - cr0[0] * 10) < 0.2 && Math.abs(l0[0].y1 - cr0[1] * 10) < 0.2, { line: l0[0], core: cr0 });

  console.log('\n[2] 拖动模块节点');
  const midLine = await dragBy('[data-sh-go="music"]', 120, 90);
  const m1 = await nodePos('music');
  ok('节点确实移动了', Math.abs(m1[0] - m0[0]) > 2 || Math.abs(m1[1] - m0[1]) > 2, { before: m0, after: m1 });
  const l1 = await lines();
  ok('连线跟到新圆心', Math.abs(l1[0].x2 - m1[0] * 10) < 0.3 && Math.abs(l1[0].y2 - m1[1] * 10) < 0.3, { line: l1[0], pos: m1 });
  ok('拖动过程中线就在动（不是抬手才动）', midLine && (Math.abs(midLine[0] - m0[0] * 10) > 1 || Math.abs(midLine[1] - m0[1] * 10) > 1), { mid: midLine, before: [m0[0] * 10, m0[1] * 10] });
  ok('其它三条线没被牵连', Math.abs(l1[1].x2 - l0[1].x2) < 0.01 && Math.abs(l1[2].x2 - l0[2].x2) < 0.01);

  console.log('\n[3] 拖动中央终端');
  const c0 = await corePos();
  await dragBy('#shCore', -70, 40);
  const c1 = await corePos();
  ok('中央终端移动了', Math.abs(c1[0] - c0[0]) > 1 || Math.abs(c1[1] - c0[1]) > 1, { before: c0, after: c1 });
  const l2 = await lines();
  ok('四条线起点全部跟着走', l2.every(l => Math.abs(l.x1 - c1[0] * 10) < 0.3 && Math.abs(l.y1 - c1[1] * 10) < 0.3), { lines: l2, core: c1 });
  const titleGap = await ev(`(function(){var w=document.getElementById('shCoreWrap').getBoundingClientRect();
      var t=document.querySelector('.sh-core-title').getBoundingClientRect();
      return {dx: Math.abs((t.left+t.width/2)-(w.left+w.width/2)), dy: t.top-w.bottom};})()`);
  ok('标题仍贴在圆心正下方', titleGap.dx < 2 && titleGap.dy > 18 && titleGap.dy < 60, titleGap);

  console.log('\n[4] 位置落盘');
  const store = await ev("JSON.parse(localStorage.getItem('Win-webterm-super-nodes')||'{}')");
  ok('localStorage 存了 core', Array.isArray(store.core) && Math.abs(store.core[0] - c1[0]) < 0.6, store.core);
  ok('localStorage 存了 music', Array.isArray(store.music) && Math.abs(store.music[0] - m1[0]) < 0.6, store.music);

  console.log('\n[5] 四个模块都分叉');
  async function forkTest(key, expectLabels) {
    await ev("window.__cyrilSuperHome.show()");
    await sleep(120);
    await ev(`document.querySelector('[data-sh-go="${key}"]').click()`);
    await sleep(220);
    const labels = await kidLabels();
    const n = labels.length;
    ok(`${key}: 分叉出 ${expectLabels ? expectLabels.length : '?'} 个叶子`, n >= 1, labels);
    if (expectLabels) ok(`${key}: 叶子标签正确`, expectLabels.every(x => labels.indexOf(x) >= 0), labels);
    const geo = await ev(`(function(){
      var kids = Array.prototype.map.call(document.querySelectorAll('.sh-kid'), function(b){
        var r=b.getBoundingClientRect(); return {x:r.left+r.width/2,y:r.top+r.height/2};});
      var paths = document.querySelectorAll('#superBranchLines path.sh-link-branch');
      var ds = Array.prototype.map.call(paths, function(p){return p.getAttribute('d');});
      var p = document.querySelector('[data-sh-go="${key}"]').getBoundingClientRect();
      return {kids:kids, paths:ds.length, parent:{x:p.left+p.width/2,y:p.top+p.height/2}};
    })()`);
    ok(`${key}: 每个叶子一根枝`, geo.paths === n * 1 && geo.paths > 0, { paths: geo.paths, kids: n });
    if (n > 1) {
      const xs = geo.kids.map(k => k.x), ys = geo.kids.map(k => k.y);
      const spreadX = Math.max(...xs) - Math.min(...xs);
      const spreadY = Math.max(...ys) - Math.min(...ys);
      ok(`${key}: 叶子扇形铺开（不是共线一列）`, Math.hypot(spreadX, spreadY) > 60 && spreadX > 8 && spreadY > 8,
        { spreadX: Math.round(spreadX), spreadY: Math.round(spreadY) });
      const inside = geo.kids.every(k => k.x > 10 && k.x < 1430 && k.y > 10 && k.y < 890);
      ok(`${key}: 叶子都在画面内`, inside, geo.kids.map(k => [Math.round(k.x), Math.round(k.y)]));
      ok(`${key}: 叶子离父节点有距离`, geo.kids.every(k => Math.hypot(k.x - geo.parent.x, k.y - geo.parent.y) > 60),
        geo.kids.map(k => Math.round(Math.hypot(k.x - geo.parent.x, k.y - geo.parent.y))));
      const boxes = await ev(`Array.prototype.map.call(document.querySelectorAll('.sh-kid'), function(b){
          var r=b.getBoundingClientRect(); return {l:r.left,t:r.top,r:r.right,b:r.bottom};})`);
      let overlap = null;
      for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
        const A = boxes[i], B = boxes[j];
        if (A.l < B.r - 2 && B.l < A.r - 2 && A.t < B.b - 2 && B.t < A.b - 2) overlap = [i, j];
      }
      ok(`${key}: 叶子之间不重叠`, !overlap, overlap);
      const coreC = await centerOf('#shCore');
      ok(`${key}: 叶子没压住中央终端`, geo.kids.every(k => Math.hypot(k.x - coreC.x, k.y - coreC.y) > 110),
        geo.kids.map(k => Math.round(Math.hypot(k.x - coreC.x, k.y - coreC.y))));
    }
    await ev(`document.querySelector('[data-sh-go="${key}"]').click()`);
    await sleep(120);
    ok(`${key}: 再点一次收起`, (await kidCount()) === 0);
  }
  await forkTest('music', ['音域回响', '封面拼贴', '绘光']);
  await forkTest('file', ['目录树', '刷新', '上一级']);
  await forkTest('settings', ['设置面板', '外观', '帮助']);
  await forkTest('comp', null);

  console.log('\n[5c] 组件分叉只列独立窗口型');
  await ev("window.__cyrilSuperHome.show()"); await sleep(120);
  await ev("document.querySelector('[data-sh-go=\"comp\"]').click()"); await sleep(250);
  const compLabels = await kidLabels();
  ok('只出现 panel 型组件', compLabels.length > 0 && !compLabels.includes('时钟') && !compLabels.includes('便签'), compLabels);
  ok('面板型组件在里面（CyrilShield / 音乐）',
    compLabels.includes('CyrilShield') || compLabels.includes('音乐'), compLabels);
  await ev("document.querySelector('[data-sh-go=\"comp\"]').click()"); await sleep(120);

  console.log('\n[5b] 多个分叉可同时打开');
  await ev("window.__cyrilSuperHome.show()"); await sleep(120);
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(200);
  const nMusic = await kidCount();
  await ev("document.querySelector('[data-sh-go=\"file\"]').click()"); await sleep(200);
  const nBoth = await kidCount();
  ok('音乐开着的状态下能再开文件', nBoth === nMusic + 3, { music: nMusic, both: nBoth });
  ok('两个节点同时高亮', await ev("document.querySelectorAll('.sh-node.open').length === 2"),
    await ev("document.querySelectorAll('.sh-node.open').length"));
  ok('两组枝线同时存在', (await ev("document.querySelectorAll('#superBranchLines path.sh-link-branch').length")) === nBoth,
    await ev("document.querySelectorAll('#superBranchLines path.sh-link-branch').length"));
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(200);
  ok('收起音乐后文件那支还在', (await kidCount()) === 3, await kidCount());
  await ev("document.querySelector('[data-sh-go=\"settings\"]').click()"); await sleep(200);
  const nThree = await kidCount();
  ok('再开第三个分叉：文件 + 设置并存', nThree === 6, nThree);
  await ev("document.body.dispatchEvent(new MouseEvent('click',{bubbles:true})); 1");
  await ev("document.getElementById('superStage').dispatchEvent(new MouseEvent('click',{bubbles:true})); 1");
  await sleep(150);
  ok('点空白处全部收起', (await kidCount()) === 0, await kidCount());

  console.log('\n[6] 叶子点了真能用');  await ev("window.__cyrilSuperHome.show()"); await sleep(120);
  await ev("document.querySelector('[data-sh-go=\"file\"]').click()"); await sleep(200);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='目录树')[0].click()");
  await sleep(500);
  ok('文件 → 目录树：启动页收起且文件树打开', !(await onHome()) && (await ev("document.body.classList.contains('tree-on')")), { home: await onHome(), tree: await ev("document.body.classList.contains('tree-on')") });

  await ev("window.__cyrilSuperHome.show()"); await sleep(150);
  await ev("document.querySelector('[data-sh-go=\"settings\"]').click()"); await sleep(200);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='设置面板')[0].click()");
  await sleep(500);
  ok('设置 → 设置面板：面板真的打开了', await ev("document.getElementById('settingsPanel').classList.contains('show')"));

  console.log('\n[7] 音乐视图直接显示在超级终端里（不切走）');
  const homeBg = await ev("getComputedStyle(document.getElementById('superHome')).backgroundImage.slice(0,6)");
  ok('默认背景是照片', homeBg !== 'none', homeBg);
  await ev("window.__cyrilSuperHome.show()"); await sleep(150);
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(200);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='音域回响')[0].click()");
  await sleep(1200);
  const v1 = await ev(`({on: document.body.classList.contains('super-on'),
      view: document.body.classList.contains('super-view'),
      radio: document.body.classList.contains('radio-on'),
      bg: getComputedStyle(document.getElementById('superHome')).backgroundImage,
      pe: getComputedStyle(document.querySelector('.sh-node')).pointerEvents})`);
  ok('点音域回响后启动页还在', v1.on === true, v1);
  ok('启动页背景让给视图（照片层撤掉）', v1.view === true && v1.bg === 'none', v1);
  ok('电台背景已起（#radio.show）', v1.radio === true, v1);
  ok('视图模式下节点仍可点', v1.pe === 'auto', v1.pe);

  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(220);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='音域回响')[0].click()");
  await sleep(700);
  const v2 = await ev(`({on: document.body.classList.contains('super-on'),
      view: document.body.classList.contains('super-view'),
      radio: document.body.classList.contains('radio-on'),
      bg: getComputedStyle(document.getElementById('superHome')).backgroundImage.slice(0,4)})`);
  ok('再点同一个叶子：回到照片背景且仍在启动页', v2.on === true && v2.view === false && v2.radio === false && v2.bg !== 'none', v2);

  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(220);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='封面拼贴')[0].click()");
  await sleep(900);
  const v3 = await ev(`({on: document.body.classList.contains('super-on'),
      lat: document.body.classList.contains('lattice-on'),
      view: document.body.classList.contains('super-view')})`);
  ok('封面拼贴也是叠在启动页上（lattice-on 且启动页还在）', v3.on === true && v3.lat === true && v3.view === true, v3);
  /* 命中测试要避开节点/叶子/核心（它们本来就该在上面），找一个空点看是不是落到拼贴里 */
  const hit = await ev(`(function(){
      var pts=[[720,450],[400,300],[1000,320],[300,620],[1100,600],[720,760]];
      for (var i=0;i<pts.length;i++){
        var st=document.elementsFromPoint(pts[i][0],pts[i][1]);
        var top=st[0];
        if (!top) continue;
        if (top.closest('.sh-node')||top.closest('#shCoreWrap')||top.closest('.sh-kid')) continue;
        return {x:pts[i][0], y:pts[i][1], tag:top.tagName,
                inLat: !!top.closest('#lattice'), inSuper: !!top.closest('#superHome')};
      }
      return {none:true};
    })()`);
  ok('拼贴海报没被启动页挡住（空点命中落在 #lattice 内）', hit.inLat === true && hit.inSuper === false, hit);
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(220);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='封面拼贴')[0].click()");
  await sleep(600);
  ok('再点封面拼贴收起', await ev("!document.body.classList.contains('lattice-on') && !document.body.classList.contains('super-view')"));

  console.log('\n[7b] 进终端再回首页：视图跟着走，被关掉则不残留空壳');
  await ev("window.__cyrilSuperHome.show()"); await sleep(150);
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(200);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='音域回响')[0].click()");
  await sleep(900);
  await ev("document.getElementById('shCore').click()"); await sleep(400);
  const t1 = await ev("({home: document.body.classList.contains('super-on'), radio: document.body.classList.contains('radio-on')})");
  ok('点中心进终端：启动页收起、视图继续当终端背景', t1.home === false && t1.radio === true, t1);
  await ev("document.getElementById('btnHome').click()"); await sleep(400);
  const t2 = await ev("({home: document.body.classList.contains('super-on'), view: document.body.classList.contains('super-view')})");
  ok('顶栏 ⌂ 回首页：视图背景仍在', t2.home === true && t2.view === true, t2);
  await ev("document.getElementById('shCore').click()"); await sleep(300);
  await ev("window.__cyrilClearRadio && window.__cyrilClearRadio()"); await sleep(500);
  await ev("document.getElementById('btnHome').click()"); await sleep(400);
  const t3 = await ev(`({home: document.body.classList.contains('super-on'),
      view: document.body.classList.contains('super-view'),
      radio: document.body.classList.contains('radio-on'),
      bg: getComputedStyle(document.getElementById('superHome')).backgroundImage.slice(0,4)})`);
  ok('终端里关掉视图后回首页：不再残留透明空壳', t3.home === true && t3.view === false && t3.radio === false && t3.bg !== 'none', t3);

  console.log('\n[8] 独立窗口组件直接浮在超级终端上');
  await ev("window.__cyrilSuperHome.show()"); await sleep(150);
  await ev("document.querySelector('[data-sh-go=\"comp\"]').click()"); await sleep(250);
  const panelLabel = await ev("(function(){var b=Array.prototype.filter.call(document.querySelectorAll('.sh-kid'),x=>x.textContent==='CyrilShield')[0];return b?b.textContent:null;})()");
  ok('找到独立窗口组件叶子 CyrilShield', panelLabel === 'CyrilShield', panelLabel);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='CyrilShield')[0].click()");
  await sleep(900);
  const cw = await ev(`(function(){var w=document.getElementById('cw-cyrilshield');
      if(!w) return {missing:true};
      var r=w.getBoundingClientRect(), cs=getComputedStyle(w);
      return {w:Math.round(r.width), h:Math.round(r.height), z:cs.zIndex, disp:cs.display,
              visible: r.width>100 && r.height>80,
              on: document.body.classList.contains('super-on')};})()`);
  ok('组件窗口真的开了（有尺寸）', !cw.missing && cw.visible === true, cw);
  ok('组件窗口浮在启动页之上（z=892）', cw.z === '892', cw.z);
  ok('打开组件没有退出启动页', cw.on === true, cw);

  await ev("document.querySelector('#cw-cyrilshield .cw-close').click()");
  await sleep(400);
  ok('关掉组件窗口后启动页仍在', await onHome());

  console.log('\n[10] 绘光：没载入音乐也要有舞台光');
  await ev("window.__cyrilSuperHome.show()"); await sleep(150);
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(250);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='绘光')[0].click()");
  await sleep(1800);
  const d1 = await ev("window.Lumiere && window.Lumiere.debug ? window.Lumiere.debug() : null");
  ok('绘光在跑（started + ticker 都在）', !!d1 && d1.started === true && d1.ticker === true, d1);
  ok('无曲目时走演示态（不是暂停态）', !!d1 && d1.demo === true, d1 && d1.demo);
  await sleep(1300);
  const d2 = await ev("window.Lumiere && window.Lumiere.debug ? window.Lumiere.debug() : null");
  ok('演示态每帧都在渲染（不是冻结一帧暗场）', !!d2 && (d2.frames - d1.frames) > 30, d2 && [d1.frames, d2.frames]);
  ok('演示态时间轴自己在走', !!d2 && d2.time > d1.time + 0.5, d2 && [d1.time, d2.time]);
  const lm = await ev(`(function(){
    var host = document.getElementById('lumiere-canvas');
    var cv = host && host.querySelector('canvas');
    var cs = getComputedStyle(host), r = host.getBoundingClientRect();
    return { disp: cs.display, bg: cs.backgroundColor,
             w: Math.round(r.width), h: Math.round(r.height),
             cw: cv && cv.width, ch: cv && cv.height,
             view: [innerWidth, innerHeight],
             on: document.body.classList.contains('lumiere-on') };
  })()`);
  ok('绘光层显示且铺满视口', lm.disp === 'block' && lm.on === true && lm.w >= lm.view[0] - 2 && lm.h >= lm.view[1] - 2, lm);
  ok('绘光自带舞台暗场（不让壁纸透出来）', lm.bg === 'rgb(5, 2, 8)', lm.bg);
  await ev("window.__cyrilViewClose && window.__cyrilViewClose()");
  await sleep(600);

  console.log('\n[11] 封面拼贴用终端字体');
  await ev("document.querySelector('[data-sh-go=\"music\"]').click()"); await sleep(250);
  await ev("Array.prototype.filter.call(document.querySelectorAll('.sh-kid'), b=>b.textContent==='封面拼贴')[0].click()");
  await sleep(1400);
  const ft = await ev(`(function(){
    var bodyFF = getComputedStyle(document.body).fontFamily;
    var nodes = [document.getElementById('lattice'),
                 document.getElementById('lattice-empty'),
                 document.getElementById('lattice-now'),
                 document.querySelector('#lattice-chrome .lbtn')];
    return { body: bodyFF, list: nodes.map(function(e){ return e ? getComputedStyle(e).fontFamily : null; }) };
  })()`);
  ok('拼贴根字体 = 终端等宽字体', !!ft && ft.list[0] === ft.body, ft && [ft.list[0], ft.body]);
  ok('拼贴内文字与按钮全部继承终端字体', !!ft && ft.list.every(f => f === ft.body), ft && ft.list);
  await ev("window.__cyrilViewClose && window.__cyrilViewClose()");
  await sleep(400);

  console.log('\n[9] 无脚本错误');
  ok('控制台无 error', errors.length === 0, errors.slice(0, 4));

  console.log('\n==== ' + pass + ' / ' + (pass + fail) + ' ====');
  /* 先关页面再还原配置：页面还开着时后端可能在下一次配置事件里把坐标重新写回磁盘 */
  await send('Page.close').catch(() => {});
  await sleep(300);
  if (cfgRaw !== null) require('fs').writeFileSync(cfgPath, cfgRaw, 'utf8');
  ws.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('TEST CRASH: ' + e.message); process.exit(2); });
