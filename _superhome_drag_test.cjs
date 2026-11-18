/* 超级终端：拖动 + 分支展开 的 Node DOM mock 行为测试（确定性） */
const fs = require('fs');
const vm = require('vm');

const HTML = 'D:/Terminal-Windows/web/index.html';
const html = fs.readFileSync(HTML, 'utf8');
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
const code = scripts[0];
if (!/__cyrilSuperNodes/.test(code)) { console.error('第一段脚本不是超级终端脚本'); process.exit(1); }

let PASS = 0, FAIL = [];
function ck(c, label, extra) {
  if (c) PASS++; else FAIL.push(label);
  console.log((c ? 'PASS ' : 'FAIL ') + label + (extra !== undefined ? '  ' + extra : ''));
}

function El(tag) {
  const s = new Set();
  return {
    tagName: tag, id: '', style: {}, attrs: {}, handlers: {}, children: [], parentNode: null,
    textContent: '', title: '',
    get className() { return [...s].join(' '); },
    set className(v) { s.clear(); String(v).split(/\s+/).filter(Boolean).forEach(c => s.add(c)); },
    classList: {
      add: c => s.add(c), remove: c => s.delete(c),
      contains: c => s.has(c), toggle: (c, on) => (on ? s.add(c) : s.delete(c)), _set: s
    },
    setAttribute(k, v) { this.attrs[k] = v; },
    getAttribute(k) { return this.attrs[k] !== undefined ? this.attrs[k] : null; },
    addEventListener(ev, fn) { (this.handlers[ev] = this.handlers[ev] || []).push(fn); },
    removeEventListener(ev, fn) {
      const a = this.handlers[ev] || [], i = a.indexOf(fn); if (i >= 0) a.splice(i, 1);
    },
    fire(ev, extra, evt) {
      /* 冒泡时沿用同一个事件对象：target 始终是最初被点的元素（与真实 DOM 一致） */
      const base = evt || { target: this, pointerId: 1, pointerType: 'mouse', button: 0,
                            clientX: 0, clientY: 0, _stopped: false,
                            stopPropagation() { this._stopped = true; }, preventDefault() {} };
      base.currentTarget = this;
      Object.assign(base, extra || {});
      (this.handlers[ev] || []).slice().forEach(f => f(base));
      if (!base._stopped && this.parentNode) this.parentNode.fire(ev, extra, base);
    },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    removeChild(c) { const i = this.children.indexOf(c); if (i >= 0) this.children.splice(i, 1); c.parentNode = null; return c; },
    closest(sel) {
      let n = this;
      while (n) {
        if (sel === '[data-sh-go]' && n.attrs['data-sh-go']) return n;
        if (sel === '.sh-kid' && n.classList.contains('sh-kid')) return n;
        n = n.parentNode;
      }
      return null;
    },
    querySelectorAll(sel) { return this._nodes && sel === '[data-sh-go]' ? this._nodes : []; },
    getBoundingClientRect() {
      const l = parseFloat(this.style.left || '50'), t = parseFloat(this.style.top || '50');
      const w = 58, h = 58, cx = l / 100 * 1920, cy = t / 100 * 1080;
      return { left: cx - w / 2, top: cy - h / 2, width: w, height: h, right: cx + w / 2, bottom: cy + h / 2 };
    }
  };
}

function run(opts) {
  opts = opts || {};
  const store = new Map();
  if (opts.pos) store.set('Win-webterm-super-nodes', JSON.stringify(opts.pos));
  const btns = {}, viewOpens = [], compOpens = [], cfgPatches = [], syncCalls = [];
  let snapshot = opts.comps || null;      // 组件清单快照（null = 后端还没报）
  let waiter = null;

  const root = El('div'); root.id = 'superHome';
  const stage = El('div'); stage.id = 'superStage';
  const links = El('svg'); links.id = 'superLinks';
  const gLines = El('g'); gLines.id = 'superBranchLines';
  root.appendChild(stage); stage.appendChild(links); links.appendChild(gLines);

  const nodes = ['music', 'file', 'comp', 'settings'].map(k => {
    const e = El('button'); e.attrs['data-sh-go'] = k; stage.appendChild(e); return e;
  });
  root._nodes = nodes;

  const ids = { superHome: root, superStage: stage, superLinks: links, superBranchLines: gLines };
  ids.shCore = El('button'); stage.appendChild(ids.shCore);
  ids.shCore.fire = function (ev) { (this.handlers[ev] || []).forEach(f => f({ target: this })); };
  ids.superGalaxy = El('canvas');
  ids.superGalaxy.getContext = () => ({
    globalAlpha: 1, globalCompositeOperation: '', fillStyle: '',
    createRadialGradient() { return { addColorStop() {} }; },
    fillRect() {}, clearRect() {}, save() {}, restore() {}, setTransform() {}, drawImage() {}
  });
  ids.btnHome = El('button');
  ['btnMusic', 'btnTree', 'btnComp', 'btnSettings'].forEach(k => { ids[k] = El('button'); });
  Object.keys(ids).forEach(k => { if (!ids[k].id) ids[k].id = k; });

  const winHandlers = {};
  const classes = new Set();
  const document = {
    body: { classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c) } },
    activeElement: null,
    getElementById: id => ids[id] || null,
    querySelector: sel => (sel === '#terminal textarea' ? { focus() {} } : null),
    querySelectorAll: () => [nodes[0]],
    createElement: tag => El(tag),
    createElementNS: (ns, tag) => El(tag)
  };

  const sandbox = {
    document, console, innerWidth: 1920, innerHeight: 1080, devicePixelRatio: 1,
    localStorage: {
      getItem: k => (store.has(k) ? store.get(k) : null),
      setItem: (k, v) => store.set(k, String(v)),
      removeItem: k => store.delete(k)
    },
    requestAnimationFrame: () => 1,
    cancelAnimationFrame: () => {},
    addEventListener(ev, fn) { (winHandlers[ev] = winHandlers[ev] || []).push(fn); },
    removeEventListener(ev, fn) {
      const a = winHandlers[ev] || [], i = a.indexOf(fn); if (i >= 0) a.splice(i, 1);
    },
    setTimeout: fn => { fn(); return 1; },
    clearTimeout: () => {},
    Math, Object, Array, String, Number, JSON, parseInt, parseFloat, isFinite
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.__cyrilCfgSend = p => cfgPatches.push(p);
  sandbox.__cyrilSuperHomeSyncUI = v => syncCalls.push(v);
  sandbox.__cyrilViewOpen = v => { viewOpens.push(v); return true; };
  sandbox.__cyrilCompOpen = id => { compOpens.push(id); return true; };
  sandbox.__cyrilCompSnapshot = () => snapshot || [];
  sandbox.__cyrilCompWhenReady = cb => { if (snapshot && snapshot.length) cb(); else waiter = cb; };

  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);

  const fireWin = (ev, obj) => (winHandlers[ev] || []).slice().forEach(f => f(obj));

  return {
    sandbox, store, classes, ids, nodes, root, stage, gLines, btns,
    viewOpens, compOpens, cfgPatches, syncCalls,
    win: () => sandbox,
    node: k => nodes.find(n => n.attrs['data-sh-go'] === k),
    kids: () => stage.children.filter(c => c.classList.contains('sh-kid')),
    fireWin,
    setComps(list) { snapshot = list; },
    readyWaiters() { if (waiter) { const w = waiter; waiter = null; w(); } },
    pos: k => { const n = nodes.find(x => x.attrs['data-sh-go'] === k); return [parseFloat(n.style.left), parseFloat(n.style.top)]; }
  };
}

console.log('=== 1. 默认位置与持久化读取 ===');
let a = run();
ck(a.pos('music')[0] === 24 && a.pos('music')[1] === 28, '默认位置 music 24/28', a.pos('music').join('/'));
ck(a.pos('comp')[0] === 24 && a.pos('comp')[1] === 72, '默认位置 comp 24/72', a.pos('comp').join('/'));
let b = run({ pos: { file: [60, 20], settings: [40, 80] } });
ck(b.pos('file')[0] === 60 && b.pos('file')[1] === 20, 'localStorage 位置生效 file 60/20', b.pos('file').join('/'));
ck(b.pos('music')[0] === 24, '未存过的节点保持默认');

console.log('\n=== 2. 后端配置覆盖位置 ===');
let c = run();
vm.runInContext("window.__cyrilSuperNodes({ comp: [30, 62], settings: [70, 30] });", c.sandbox);
ck(c.pos('comp')[0] === 30 && c.pos('comp')[1] === 62, 'Settings.json 的 super_nodes 覆盖 comp', c.pos('comp').join('/'));
ck(c.pos('music')[0] === 24, '配置里没给的节点不动');

console.log('\n=== 3. 拖动：阈值与落盘 ===');
let d = run();
const mn = d.node('music');
mn.fire('pointerdown', { clientX: 460, clientY: 302 });
d.fireWin('pointermove', { pointerId: 1, clientX: 461, clientY: 303 });   // 位移 2px < 4px
ck(d.pos('music')[0] === 24, '位移 <4px 不动（防手抖）', d.pos('music').join('/'));
d.fireWin('pointerup', { pointerId: 1 });
mn.fire('click');
ck(d.kids().length === 3, '阈值内抬起仍按点击处理（展开分支）');

let e2 = run();
const mn2 = e2.node('music');
mn2.fire('pointerdown', { clientX: 460, clientY: 302 });
e2.fireWin('pointermove', { pointerId: 1, clientX: 700, clientY: 500 });  // 真实拖动
const moved = e2.pos('music');
ck(moved[0] > 24 && moved[1] > 28, '拖动后位置跟随指针', moved.map(v => v.toFixed(1)).join('/'));
e2.fireWin('pointerup', { pointerId: 1 });
ck(e2.store.has('Win-webterm-super-nodes'), '抬手后 localStorage 已写位置');
ck(e2.cfgPatches.length === 1 && e2.cfgPatches[0].super_nodes,
   '抬起后 settings_set 收到 super_nodes', JSON.stringify(e2.cfgPatches[0] || {}).slice(0, 60));
ck(Math.abs(e2.cfgPatches[0].super_nodes.music[0] - moved[0]) < 0.01, '落盘值与界面一致');
mn2.fire('click');
ck(e2.kids().length === 0, '拖动后的那次 click 不再展开分支');

console.log('\n=== 4. 分支：音乐三视图 ===');
let f = run();
f.node('music').fire('click');
const kids = f.kids();
ck(kids.length === 3, '音乐展开 3 个子项', kids.length);
ck(kids.map(k => k.textContent).join(',') === '音域回响,封面拼贴,绘光',
   '子项名称与顺序', kids.map(k => k.textContent).join(','));
ck(f.gLines.children.length === 3, '三条虚线连接线已画', f.gLines.children.length);
ck(f.node('music').classList.contains('open'), '父节点标记为展开态');
f.node('music').fire('click');
ck(f.kids().length === 0 && f.gLines.children.length === 0, '再点父节点收起分支');

console.log('\n=== 5. 子项跳转 ===');
for (const [label, want] of [['音域回响', 'sonic'], ['封面拼贴', 'lattice'], ['绘光', 'lumiere']]) {
  const g = run();
  g.node('music').fire('click');
  const kid = g.kids().find(k => k.textContent === label);
  kid.fire('click');
  ck(g.viewOpens.length === 1 && g.viewOpens[0] === want, label + ' → __cyrilViewOpen(' + want + ')', g.viewOpens.join(','));
  ck(!g.classes.has('super-on'), label + ' 点击后退出启动页');
}

console.log('\n=== 6. 组件分支：清单未到货 / 到货后重建 ===');
let h = run();                    // snapshot = null
h.node('comp').fire('click');
ck(h.kids().length === 1 && h.kids()[0].textContent === '读取中…', '清单未到货时占位提示');
h.setComps([{ id: 'cyrilshield', name: 'CyrilShield' }, { id: 'nmap', name: 'Nmap 扫描' }]);
h.readyWaiters();
ck(h.kids().map(k => k.textContent).join(',') === 'CyrilShield,Nmap 扫描',
   '清单到货后自动换成真名字', h.kids().map(k => k.textContent).join(','));

let i = run({ comps: [{ id: 'cyrilshield', name: 'CyrilShield' }, { id: 'cyriltrack', name: 'CyrilTrack' },
                       { id: 'nmap', name: 'Nmap 扫描' }, { id: 'sqlmap', name: 'Sqlmap 注入' }] });
i.node('comp').fire('click');
ck(i.kids().length === 4, '四个组件全部列出', i.kids().length);
const y = i.kids().map(k => parseFloat(k.style.top));
ck(y.every((v, idx) => idx === 0 || v < y[idx - 1]), '下半屏节点向上依次排开（不打到屏幕底部）', y.join('/'));
i.kids().find(k => k.textContent === 'Nmap 扫描').fire('click');
ck(i.compOpens[0] === 'nmap', '点子项 → __cyrilCompOpen(nmap)', i.compOpens.join(','));

console.log('\n=== 7. 无下级的节点仍直接跳转 ===');
for (const [k, target] of [['file', 'btnTree'], ['settings', 'btnSettings']]) {
  const z = run();
  let clicked = null;
  z.ids[target].click = function () { clicked = target; };
  z.node(k).fire('click');
  ck(clicked === target, k + ' → ' + target + '.click()', String(clicked));
  ck(!z.classes.has('super-on'), k + ' 点击后退出启动页');
}

console.log('\n=== 8. 空白处点击收起分支 / 拖走父节点收起分支 ===');
let j = run();
j.node('music').fire('click');
ck(j.kids().length === 3, '先展开');
j.stage.fire('click');
ck(j.kids().length === 0, '点空白处收起');

let k2 = run();
k2.node('music').fire('click');
const m2 = k2.node('music');
m2.fire('pointerdown', { clientX: 460, clientY: 302 });
k2.fireWin('pointermove', { pointerId: 1, clientX: 600, clientY: 400 });
ck(k2.kids().length === 0, '开始拖动即收起分支');

console.log('\n[拖动+分支行为测试] ' + PASS + ' passed, ' + FAIL.length + ' failed');
if (FAIL.length) { console.log('失败: ' + FAIL.join('; ')); process.exit(1); }
