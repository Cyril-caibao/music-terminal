/* 超级终端启动页：Node DOM mock 行为测试（确定性，不依赖浏览器/截图） */
const fs = require('fs');
const vm = require('vm');

const HTML = 'D:/Terminal-Windows/web/index.html';
const html = fs.readFileSync(HTML, 'utf8');
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const code = scripts[0];
if (!/superHome/.test(code) || !/__cyrilSuperHomeApply/.test(code)) {
  console.error('提取到的第一段内联脚本不是超级终端脚本'); process.exit(1);
}

let PASS = 0, FAIL = [];
function ck(c, label, extra) {
  if (c) PASS++; else FAIL.push(label);
  console.log((c ? 'PASS ' : 'FAIL ') + label + (extra !== undefined ? '  ' + extra : ''));
}

function run(initPage) {
  const store = new Map();
  if (initPage !== undefined) store.set('Win-webterm-start-page', initPage);
  const timers = [];
  const rafs = new Map();
  let rafSeq = 0, rafCancelled = 0, drawCalls = 0;
  const btnClicks = [];
  const syncCalls = [];

  function mkEl(id) {
    return {
      id, attrs: {}, handlers: {}, style: {},
      setAttribute(k, v) { this.attrs[k] = v; },
      getAttribute(k) { return this.attrs[k] !== undefined ? this.attrs[k] : null; },
      addEventListener(ev, fn) { (this.handlers[ev] = this.handlers[ev] || []).push(fn); },
      fire(ev) { (this.handlers[ev] || []).forEach(f => f({ target: this })); },
      click() { btnClicks.push(this.id); this.fire('click'); },
      focus() {}, blur() {},
      closest() { return null; },
      querySelectorAll(sel) { return sel === '[data-sh-go]' ? nodes : []; },
      classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } }
    };
  }

  const nodes = ['music', 'file', 'comp', 'settings'].map(k => {
    const e = mkEl('node-' + k);
    e.attrs['data-sh-go'] = k;
    return e;
  });
  const ids = {};
  ['superHome', 'superGalaxy', 'shCore', 'btnHome',
   'btnMusic', 'btnTree', 'btnComp', 'btnSettings'].forEach(k => { ids[k] = mkEl(k); });

  const grad = { addColorStop() {} };
  function mkCtx() {
    return {
      globalAlpha: 1, globalCompositeOperation: '', fillStyle: '',
      createRadialGradient() { return grad; },
      fillRect() {}, clearRect() {}, save() {}, restore() {}, setTransform() {},
      drawImage() { drawCalls++; }
    };
  }
  const canvasCtx = mkCtx();
  ids.superGalaxy.getContext = () => canvasCtx;
  ids.superGalaxy.width = 0; ids.superGalaxy.height = 0;
  ids.superGalaxy.style = {};

  const classes = new Set();
  const body = {
    classList: {
      add(c) { classes.add(c); }, remove(c) { classes.delete(c); },
      contains(c) { return classes.has(c); }, toggle(c, on) { on ? classes.add(c) : classes.delete(c); }
    }
  };
  const document = {
    body, activeElement: null,
    getElementById(id) { return ids[id] || null; },
    querySelector(sel) { return sel === '#terminal textarea' ? { focus() {} } : null; },
    querySelectorAll(sel) { return []; },
    createElement(tag) {
      return { width: 0, height: 0, getContext: () => mkCtx() };
    }
  };

  const sandbox = {
    document, console,
    innerWidth: 1920, innerHeight: 1080,
    devicePixelRatio: 1,
    localStorage: {
      getItem(k) { return store.has(k) ? store.get(k) : null; },
      setItem(k, v) { store.set(k, String(v)); },
      removeItem(k) { store.delete(k); }
    },
    requestAnimationFrame(fn) { const id = ++rafSeq; rafs.set(id, fn); return id; },
    cancelAnimationFrame(id) { if (rafs.has(id)) { rafs.delete(id); rafCancelled++; } },
    addEventListener() {},
    setTimeout(fn, ms) { timers.push({ fn, ms }); return timers.length; },
    clearTimeout() {},
    Math, Object, Array, String, Number, JSON, parseInt, isFinite,
    __syncUI(v) { syncCalls.push(v); }
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext('window.__cyrilSuperHomeSyncUI = function (v) { window.__syncUI(v); };', sandbox);
  vm.runInContext(code, sandbox);

  return {
    sandbox, store, classes, ids, nodes, timers, rafs,
    btnClicks, syncCalls,
    get cancelled() { return rafCancelled; },
    get draws() { return drawCalls; },
    home: () => sandbox.window.__cyrilSuperHome,
    flushTimers() { const t = timers.splice(0); t.forEach(x => { try { x.fn(); } catch (e) {} }); },
    stepFrame() {
      const pending = [...rafs.entries()];
      rafs.clear();
      pending.forEach(([, fn]) => { fn(1000); });
    }
  };
}

console.log('=== 1. 默认启动 = 超级终端 ===');
let a = run();
ck(a.classes.has('super-on'), 'localStorage 空时启动即显示 super-on');
ck(a.ids.superHome.attrs['aria-hidden'] === 'false', 'aria-hidden 同步为 false');
ck(a.rafs.size === 1, '银河 rAF 启动', 'rAF 队列 ' + a.rafs.size);
ck(a.home().getPage() === 'super', 'getPage() 默认 super');
ck(a.home().isOn() === true, 'isOn() = true');
ck(typeof a.home().show === 'function' && typeof a.home().hide === 'function', 'show/hide 已暴露');

console.log('\n=== 2. 银河粒子层真的在绘制 ===');
a.stepFrame();
const expected = Math.min(2200, Math.max(900, Math.round((1920 * 1080) / 1100)));
ck(a.draws === expected, 'drawImage 次数 = 粒子数公式', a.draws + ' / ' + expected);
ck(a.rafs.size === 1, '绘制后继续请求下一帧（持续动画）');

console.log('\n=== 3. 配置回包：terminal 关闭首页 ===');
let b = run();
vm.runInContext("window.__cyrilSuperHomeApply('terminal');", b.sandbox);
ck(!b.classes.has('super-on'), 'cfgApply(terminal) → 隐藏首页');
ck(b.store.get('Win-webterm-start-page') === 'terminal', 'localStorage 缓存更新为 terminal');
ck(b.syncCalls.includes('terminal'), '设置面板控件同步为 terminal');
ck(b.cancelled === 1, '银河 rAF 已取消（不留后台绘制）');
ck(b.ids.superHome.attrs['aria-hidden'] === 'true', 'aria-hidden 同步为 true');

console.log('\n=== 4. 用户操作过后，配置回包不再翻页 ===');
let c = run();
c.home().setPage('terminal');
ck(c.syncCalls.includes('terminal'), 'setPage 同步 UI');
vm.runInContext("window.__cyrilSuperHomeApply('super');", c.sandbox);
ck(c.classes.has('super-on'), 'touched 后 applyCfg 不覆盖用户选择');

console.log('\n=== 5. cfgApply(super) 从 terminal 缓存恢复首页 ===');
let d = run('terminal');
ck(!d.classes.has('super-on'), '缓存 terminal 时启动不显示首页');
vm.runInContext("window.__cyrilSuperHomeApply('super');", d.sandbox);
ck(d.classes.has('super-on'), '配置说 super 时补显示首页');

console.log('\n=== 6. 文件 / 设置节点直接跳转走既有按钮（音乐/组件改为展开分支，见 _superhome_drag_test.cjs） ===');
const GO = { file: 'btnTree', settings: 'btnSettings' };
for (const key of Object.keys(GO)) {
  const e = run();
  const node = e.nodes.find(n => n.attrs['data-sh-go'] === key);
  node.fire('click');
  ck(!e.classes.has('super-on'), key + ' 节点点击 → 退出首页');
  e.flushTimers();
  ck(e.btnClicks.includes(GO[key]), key + ' 节点 → ' + GO[key] + '.click()');
}

console.log('\n=== 7. 中心标志 / 顶栏返回 ===');
let f = run();
f.ids.shCore.fire('click');
ck(!f.classes.has('super-on'), '点中心终端标志 → 进入终端');
let g = run('terminal');
g.ids.btnHome.fire('click');
ck(g.classes.has('super-on'), '顶栏 ⌂ → 回到超级终端');
ck(g.rafs.size === 1, '返回时银河重新启动');

console.log('\n[超级终端行为测试] ' + PASS + ' passed, ' + FAIL.length + ' failed');
if (FAIL.length) { console.log('失败: ' + FAIL.join('; ')); process.exit(1); }
