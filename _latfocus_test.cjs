/* 封面拼贴：点哪张哪张放大 + 换歌跟着最近的一份走（照抄 Folia 源码）
   用法: node _latfocus_test.cjs [pageUrl] [dbgUrl] */
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
  const send = (m, p = {}) => new Promise((res, rej) => {
    const i = ++id; waits.set(i, x => (x.error ? rej(new Error(JSON.stringify(x.error))) : res(x.result)));
    ws.send(JSON.stringify({ id: i, method: m, params: p }));
  });
  const ev = async x => {
    const r = await send('Runtime.evaluate', { expression: x, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + ((r.exceptionDetails.exception || {}).description || ''));
    return r.result.value;
  };
  const errors = [];
  ws.addEventListener('message', e => {
    const m = JSON.parse(e.data);
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.text);
    if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
      const t = m.params.entry.text;
      if (!/media|MEDIA|decode|blob:/i.test(t)) errors.push(t);
    }
  });
  await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url: PAGE });
  await sleep(600);
  // 种 40 首测试曲（IndexedDB），刷新后由 loadLibrary() 恢复
  await ev(`(function(){return new Promise(function(res){
    var r=indexedDB.open('cyril-radio',1);
    r.onupgradeneeded=function(e){var d=e.target.result;if(!d.objectStoreNames.contains('tracks'))d.createObjectStore('tracks',{keyPath:'key'});};
    r.onsuccess=function(){var db=r.result,tx=db.transaction('tracks','readwrite'),st=tx.objectStore('tracks');
      var n=8000, buf=new ArrayBuffer(44+n), v=new DataView(buf);
      function str(o,t){for(var k=0;k<t.length;k++)v.setUint8(o+k,t.charCodeAt(k));}
      str(0,'RIFF');v.setUint32(4,36+n,true);str(8,'WAVE');str(12,'fmt ');
      v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);
      v.setUint32(24,8000,true);v.setUint32(28,8000,true);v.setUint16(32,1,true);v.setUint16(34,8,true);
      str(36,'data');v.setUint32(40,n,true);
      for(var k=0;k<n;k++)v.setUint8(44+k,128);
      var wav=new Blob([buf],{type:'audio/wav'});
      for(var i=0;i<40;i++)st.put({key:'seed-'+i,name:'seed'+i+'.wav',title:'Song '+i,artist:'A'+i,
        blob:wav,coverBlob:null,lrc:null});
      tx.oncomplete=function(){res(1)};tx.onerror=function(){res(0)};};
    r.onerror=function(){res(0)};});})()`);
  await send('Page.reload', { ignoreCache: true });
  await sleep(2600);
  await ev(`(function(){if(document.body.classList.contains('super-on')&&window.__cyrilSuperHome)window.__cyrilSuperHome.hide();return 1;})()`);
  await sleep(350);

  const SNAP = `(function(){
    var all=Array.prototype.slice.call(document.querySelectorAll('.lattice-poster'));
    var cur=document.querySelector('.lattice-poster.is-current');
    function info(d){ if(!d)return null; var L=d.__lat||{}, r=d.getBoundingClientRect();
      return {tx:L.tx,ty:L.ty,slot:L.slot,idx:L.idx,c:L.c,r:L.r,x:L.x,y:L.y,
        w:Math.round(r.width),h:Math.round(r.height),pw:Math.round(parseFloat(d.style.width||0)),
        lyr:!!d.querySelector('.lp-lyric'),z:d.style.zIndex||'',cur:d.classList.contains('is-current')}; }
    var w=document.getElementById('lattice-world'), m=new DOMMatrix(getComputedStyle(w).transform);
    var st=window.__cyrilPlayerState?window.__cyrilPlayerState():{};
    return {count:all.length,current:info(cur),big:all.filter(function(d){return parseFloat(d.style.width||0)>795&&parseFloat(d.style.height||0)>795;}).map(info),
      cam:{x:m.m41,y:m.m42,s:m.m11,vw:innerWidth,vh:innerHeight}, idx:st.index, qcount:st.count};
  })()`;
  const pickClick = idx => `(function(){
    var a=document.querySelector('.lattice-poster.is-current'); var A=a&&a.__lat;
    var cands=Array.prototype.filter.call(document.querySelectorAll('.lattice-poster'),function(d){
      var L=d.__lat; return d && L && A && (L.tx!==A.tx||L.ty!==A.ty) && Math.abs(L.idx-${idx})>4;
    });
    if(!cands.length) return null;
    var d=cands[0], L=d.__lat, before={tx:A.tx,ty:A.ty,slot:A.slot,idx:A.idx};
    d.click();
    return {before:before,target:{tx:L.tx,ty:L.ty,slot:L.slot,idx:L.idx}};
  })()`;

  console.log('\n[1] 开播后进拼贴：展开的是「离中心最近的那一份」，且只有一个 6×6');
  await ev(`window.__cyrilPlayerCmd({action:'play',index:1}); 1`);
  await sleep(700);
  await ev(`window.__cyrilViewOpen('lattice'); 1`);
  await sleep(1250);
  const s0 = await ev(SNAP);
  ok('曲库有 40 首且第 1 首在播', s0.qcount === 40 && s0.idx === 0, { q: s0.qcount, idx: s0.idx });
  ok('拼贴已渲染海报', s0.count > 40, s0.count);
  ok('有且只有一张展开的海报', !!s0.current && s0.big.length === 1, { cur: s0.current, big: s0.big.length });
  ok('展开的是当前曲目（idx=0）', s0.current && s0.current.idx === 0, s0.current);
  ok('展开的尺寸是 6×6 = 808px', s0.current && s0.current.pw === 808, s0.current);
  ok('展开海报带歌词容器与 z=20', s0.current && s0.current.lyr && s0.current.z === '20', s0.current);
  const nearEntry = (() => {
    const c = s0.current, cam = s0.cam, wx = (cam.vw / 2 - cam.x) / cam.s, wy = (cam.vh / 2 - cam.y) / cam.s;
    return Math.abs(wx - (c.x + (c.c * 136 - 8) / 2)) < 8 && Math.abs(wy - (c.y + (c.r * 136 - 8) / 2)) < 8;
  })();
  ok('入场时相机对准展开的那张', nearEntry, { cam: s0.cam, cur: s0.current });

  console.log('\n[2] 点另一个区块的海报 → 那一张就地放大，旧的退回小图');
  const old = s0.current;
  const clicked = await ev(pickClick(s0.idx));
  ok('找到了另一区块、另一首歌的海报', !!clicked, clicked);
  await sleep(900);
  const s1 = await ev(SNAP);
  ok('点过的那张成为当前展开项', s1.current && s1.current.tx === clicked.target.tx && s1.current.ty === clicked.target.ty && s1.current.slot === clicked.target.slot, { cur: s1.current, target: clicked.target });
  ok('换到被点那首歌（原版点开后再播；这里点即切歌）', s1.idx === clicked.target.idx, { idx: s1.idx, target: clicked.target.idx });
  ok('旧的展开海报退回基础尺寸', s1.big.length === 1 && !(s1.big[0].tx === old.tx && s1.big[0].ty === old.ty && s1.big[0].slot === old.slot), { big: s1.big.map(b => [b.tx, b.ty, b.slot]), old: [old.tx, old.ty, old.slot] });
  ok('全场仍然只有一张 6×6', s1.big.length === 1, s1.big.length);
  ok('歌词容器只跟着新展开项', s1.current && s1.current.lyr && s1.big.filter(b => b.lyr).length === 1, { cur: s1.current.lyr, big: s1.big.map(b => b.lyr) });
  const nearClick = (() => {
    const c = s1.current, cam = s1.cam, wx = (cam.vw / 2 - cam.x) / cam.s, wy = (cam.vh / 2 - cam.y) / cam.s;
    return Math.abs(wx - (c.x + (c.c * 136 - 8) / 2)) < 8 && Math.abs(wy - (c.y + (c.r * 136 - 8) / 2)) < 8;
  })();
  ok('相机飞到被点开的那张（不是飞回原点）', nearClick, { cam: s1.cam, cur: s1.current });

  console.log('\n[3] 再点第三个区块：放大位置继续跟随，不固定');
  const clicked2 = await ev(pickClick(s1.idx));
  ok('又找到第三个区块的海报', !!clicked2, clicked2);
  await sleep(900);
  const s2 = await ev(SNAP);
  ok('第三张成为唯一展开项', s2.current && s2.current.tx === clicked2.target.tx && s2.current.ty === clicked2.target.ty && s2.current.slot === clicked2.target.slot, { cur: s2.current, target: clicked2.target });
  ok('三张互不重合（每次只有一个大封面）', s2.big.length === 1, s2.big.length);

  console.log('\n[4] 从别处点「下一首」→ 展开项跳到新歌最近的一份');
  const beforeNext = s2.current;
  await ev(`window.__cyrilPlayerCmd({action:'next'}); 1`);
  await sleep(1000);
  const s3 = await ev(SNAP);
  ok('曲目确实前进了一首', s3.idx === (s2.idx + 1) % 40, { before: s2.idx, after: s3.idx });
  ok('展开项显示的是新歌', s3.current && s3.current.idx === s3.idx, s3.current);
  ok('展开项换了位置（不再钉在上一张）', s3.current && (s3.current.tx !== beforeNext.tx || s3.current.ty !== beforeNext.ty || s3.current.slot !== beforeNext.slot), { before: beforeNext, after: s3.current });
  ok('全场仍只有一张 6×6', s3.big.length === 1, s3.big.length);
  const nearest = await ev(`(function(){
    var c=document.querySelector('.lattice-poster.is-current'), L=c.__lat;
    var w=document.getElementById('lattice-world'), m=new DOMMatrix(getComputedStyle(w).transform);
    var cx=(innerWidth/2-m.m41)/m.m11, cy=(innerHeight/2-m.m42)/m.m11;
    var best=null, bd=Infinity;
    Array.prototype.forEach.call(document.querySelectorAll('.lattice-poster'),function(d){
      var x=d.__lat; if(!x||x.idx!==${s3.idx}) return;
      var px=x.x+(x.c*136-8)/2, py=x.y+(x.r*136-8)/2, dd=(px-cx)*(px-cx)+(py-cy)*(py-cy);
      if(dd<bd){bd=dd;best={tx:x.tx,ty:x.ty,slot:x.slot};}
    });
    return {best:best,cur:{tx:L.tx,ty:L.ty,slot:L.slot}};
  })()`);
  ok('落在所有同名海报中离视口中心最近的一份', !!nearest.best && nearest.best.tx === nearest.cur.tx && nearest.best.ty === nearest.cur.ty && nearest.best.slot === nearest.cur.slot, nearest);

  console.log('\n[5] 点已经展开的那张 = 播放/暂停，不会把展开态弄丢');
  await ev(`document.querySelector('.lattice-poster.is-current').click(); 1`);
  await sleep(400);
  const s4 = await ev(SNAP);
  ok('展开态还在，且仍是同一张', !!s4.current && s4.current.tx === s3.current.tx && s4.current.ty === s3.current.ty && s4.current.slot === s3.current.slot, s4.current);
  ok('全场仍只有一张 6×6', s4.big.length === 1, s4.big.length);

  console.log('\n[6] 退出拼贴再进：重新落到当前曲目，不残留两张大图');
  await ev(`window.__cyrilViewClose(); 1`);
  await sleep(350);
  await ev(`window.__cyrilViewOpen('lattice'); 1`);
  await sleep(1200);
  const s5 = await ev(SNAP);
  ok('只有一张 6×6', s5.big.length === 1 && !!s5.current, { big: s5.big.length });
  ok('展开的是当前曲目', s5.current && s5.current.idx === s5.idx, { cur: s5.current, idx: s5.idx });

  console.log('\n[7] 无脚本错误');
  ok('控制台无 error', errors.length === 0, errors.slice(0, 4));

  console.log('\n---------------------------------------');
  console.log(`结果: ${pass}/${pass + fail} 通过, ${fail} 失败`);
  ws.close();
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
