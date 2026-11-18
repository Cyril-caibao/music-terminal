/* 顶栏【音乐】双态开关回归
   用法: node _musicbtn_test.cjs [pageUrl] [dbgUrl] */
const PAGE = process.argv[2] || 'http://127.0.0.1:8861/';
const DBG = process.argv[3] || 'http://127.0.0.1:9334';
const sleep = ms => new Promise(r => setTimeout(r, ms));

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; console.log('  ✗ ' + name + (extra !== undefined ? '  ' + JSON.stringify(extra) : '')); }
}

(async () => {
  const list = await (await fetch(DBG + '/json/list')).json();
  for (const t of list) if (t.type === 'page') await fetch(DBG + '/json/close/' + t.id).catch(() => {});
  const tgt = await (await fetch(DBG + '/json/new?' + encodeURIComponent('about:blank'), { method: 'PUT' })).json();
  const ws = new WebSocket(tgt.webSocketDebuggerUrl);
  let id = 0; const waits = new Map();
  await new Promise(r => (ws.onopen = r));
  ws.onmessage = e => { const m = JSON.parse(e.data); if (m.id && waits.has(m.id)) { waits.get(m.id)(m); waits.delete(m.id); } };
  const send = (method, params = {}) => new Promise((res, rej) => {
    const i = ++id; waits.set(i, x => (x.error ? rej(new Error(JSON.stringify(x.error))) : res(x.result)));
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  const ev = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception || {}).description);
    return r.result.value;
  };

  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
  const errors = [];
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.text);
    if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') errors.push(m.params.entry.text);
  });

  await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: PAGE });
  await sleep(2600);

  const BTN = `(function(){
    var b = document.getElementById('btnMusic');
    var r = b.getBoundingClientRect();
    return { txt: (b.textContent||'').trim(), title: b.title, pressed: b.getAttribute('aria-pressed'),
             w: Math.round(r.width), h: Math.round(r.height),
             radio: document.body.classList.contains('radio-on'),
             lat: document.body.classList.contains('lattice-on'),
             queue: !!(document.getElementById('radio')||{}).className &&
                    /show/.test(document.getElementById('radio').className) };
  })()`;
  const click = `document.getElementById('btnMusic').click(); 1`;

  console.log('\n[1] 初始：终端界面 → 按钮写「音乐」');
  // 先确保在终端（超级终端启动页会挡住顶栏，先退出）
  await ev(`(function(){ if(document.body.classList.contains('super-on')&&window.__cyrilSuperHome) window.__cyrilSuperHome.hide(); return 1; })()`);
  await sleep(400);
  const s0 = await ev(BTN);
  ok('文案 = 音乐', s0.txt === '音乐', s0);
  ok('aria-pressed = false', s0.pressed === 'false', s0);
  ok('不在音乐界面（无 radio-on）', s0.radio === false, s0);
  ok('标题提示「进入」', /进入音乐/.test(s0.title), s0.title);

  console.log('\n[2] 点一下 → 进音乐，按钮改写成「终端」');
  await ev(click);
  await sleep(700);
  const s1 = await ev(BTN);
  ok('进了音乐界面（radio-on）', s1.radio === true, s1);
  ok('按钮文案 = 终端', s1.txt === '终端', s1);
  ok('aria-pressed = true', s1.pressed === 'true', s1);
  ok('标题提示「返回终端」', /返回终端/.test(s1.title), s1.title);
  ok('顺带打开了播放队列面板', s1.queue === true, s1);
  ok('按钮尺寸没因换字跳动（两字等宽）', s1.w === s0.w && s1.h === s0.h, { a: s0.w, b: s1.w, ah: s0.h, bh: s1.h });

  console.log('\n[3] 再点一下 → 回终端，按钮改回「音乐」');
  await ev(click);
  await sleep(700);
  const s2 = await ev(BTN);
  ok('退出音乐界面（radio-on 移除）', s2.radio === false, s2);
  ok('按钮文案 = 音乐', s2.txt === '音乐', s2);
  ok('标题提示「进入」', /进入音乐/.test(s2.title), s2.title);
  ok('队列浮层跟着收起', s2.queue === false, s2);

  console.log('\n[4] 从别的入口进音乐（封面拼贴）→ 按钮也要变成「终端」');
  await ev(`window.__cyrilViewOpen('lattice'); 1`);
  await sleep(900);
  const s3 = await ev(BTN);
  ok('拼贴打开了（radio-on + lattice-on）', s3.radio === true && s3.lat === true, s3);
  ok('按钮文案 = 终端', s3.txt === '终端', s3);

  console.log('\n[5] 拼贴里点「终端」→ 拼贴关掉、回终端');
  await ev(click);
  await sleep(900);
  const s4 = await ev(BTN);
  ok('拼贴已关闭', s4.lat === false, s4);
  ok('电台模式已退出', s4.radio === false, s4);
  ok('按钮文案 = 音乐', s4.txt === '音乐', s4);
  ok('终端回到 main 里（不在浮窗）', await ev(`(function(){
    var t = document.getElementById('terminal');
    return !!(t && t.parentNode && t.parentNode.id !== 'tfBody');
  })()`), null);

  console.log('\n[6] 从别的入口退音乐（外观面板 / ⌂ 走的 exitRadio）→ 文案跟着回来');
  await ev(`window.__cyrilViewOpen('sonic'); 1`);
  await sleep(500);
  ok('音域回响打开后文案 = 终端', (await ev(BTN)).txt === '终端', null);
  await ev(`window.__cyrilViewClose(); 1`);
  await sleep(500);
  ok('__cyrilViewClose 之后文案 = 音乐', (await ev(BTN)).txt === '音乐', null);

  console.log('\n[7] 快速连点 5 次不串状态');
  for (let i = 0; i < 5; i++) { await ev(click); await sleep(160); }
  await sleep(900);
  const s5 = await ev(BTN);
  ok('连点后状态自洽（radio 与文案一致）', (s5.radio && s5.txt === '终端') || (!s5.radio && s5.txt === '音乐'), s5);

  console.log('\n[8] 无脚本错误');
  ok('控制台无 error', errors.length === 0, errors.slice(0, 4));

  console.log('\n---------------------------------------');
  console.log(`结果: ${pass}/${pass + fail} 通过, ${fail} 失败`);
  ws.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
