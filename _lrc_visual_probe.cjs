/* 英文歌歌词的可视证据：导入 mp3 + merged LRC → 开歌词面板截图；再切凝彩看大字是否为英文原文。 */
const { spawn } = require('child_process');
const http = require('http'); const net = require('net'); const crypto = require('crypto');
const fs = require('fs'); const path = require('path'); const os = require('os');
const BASE = process.argv[2] || 'http://127.0.0.1:18771/';
const MP3 = 'C:/Users/Cuisi/Music/Amazing - Ed Sheeran.mp3';
const LRC = 'C:/Users/Cuisi/Music/Amazing - Ed Sheeran.lrc';
const PORT = 9421, CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const OUT = path.join(__dirname, '_maintenance', '20261118');
const sleep = ms => new Promise(r => setTimeout(r, ms));
function enc(o){const d=Buffer.from(JSON.stringify(o));const m=crypto.randomBytes(4);let h;if(d.length<126){h=Buffer.alloc(2);h[1]=0x80|d.length}else{h=Buffer.alloc(4);h[1]=0x80|126;h.writeUInt16BE(d.length,2)}h[0]=0x81;const b=Buffer.from(d);for(let i=0;i<b.length;i++)b[i]^=m[i%4];return Buffer.concat([h,m,b])}
function ws(p){return new Promise((res,rej)=>{const k=crypto.randomBytes(16).toString('base64');const s=net.connect(PORT,'127.0.0.1',()=>s.write('GET '+p+' HTTP/1.1\r\nHost:127.0.0.1:'+PORT+'\r\nUpgrade:websocket\r\nConnection:Upgrade\r\nSec-WebSocket-Key:'+k+'\r\nSec-WebSocket-Version:13\r\n\r\n'));let buf=Buffer.alloc(0),open=false;const ls=[];const api={send(o){s.write(enc(o))},onText(f){ls.push(f)},close(){s.destroy()}};s.on('error',rej);s.on('data',d=>{buf=Buffer.concat([buf,d]);if(!open){const i=buf.indexOf('\r\n\r\n');if(i<0)return;open=true;buf=buf.slice(i+4);res(api)}for(;;){if(buf.length<2)return;const len0=buf[1]&0x7f;let len=len0,off=2;if(len===126){if(buf.length<4)return;len=buf.readUInt16BE(2);off=4}else if(len===127){if(buf.length<10)return;len=Number(buf.readBigUInt64BE(2));off=10}if(buf.length<off+len)return;const op=buf[0]&0x0f;const pl=buf.slice(off,off+len);buf=buf.slice(off+len);if(op===8){s.destroy();return}if(op===9)continue;if(op===1||op===0)ls.forEach(f=>f(pl.toString()))}})})}
const hj=p=>new Promise((res,rej)=>{http.get({host:'127.0.0.1',port:PORT,path:p},r=>{let s='';r.on('data',d=>s+=d);r.on('end',()=>res(JSON.parse(s)))}).on('error',rej)});
(async()=>{
  const prof=path.join(os.tmpdir(),'wt-lrc-vis');try{fs.rmSync(prof,{recursive:true,force:true})}catch(e){}
  const ch=spawn(CHROME,['--headless=new','--remote-debugging-port='+PORT,'--user-data-dir='+prof,'--no-first-run','--no-default-browser-check','--window-size=1440,900','--autoplay-policy=no-user-gesture-required',BASE],{stdio:'ignore'});
  let ts=null;for(let i=0;i<40;i++){try{ts=await hj('/json/list');if(ts&&ts.length)break}catch(e){}await sleep(250)}
  const page=(ts||[]).find(t=>t.type==='page'&&t.url.indexOf('127.0.0.1')>=0);
  const w=await ws(new URL(page.webSocketDebuggerUrl).pathname);
  let id=0;const pend=new Map();
  w.onText(t=>{let m;try{m=JSON.parse(t)}catch(e){return}if(m.id&&pend.has(m.id)){const p=pend.get(m.id);pend.delete(m.id);m.error?p.rej(new Error(JSON.stringify(m.error))):p.res(m.result)}});
  const cmd=(me,pa)=>new Promise((res,rej)=>{const i=++id;pend.set(i,{res,rej});w.send({id:i,method:me,params:pa||{}})});
  const ev=async e=>{const r=await cmd('Runtime.evaluate',{expression:e,returnByValue:true,awaitPromise:true,userGesture:true});if(r.exceptionDetails)throw new Error(JSON.stringify(r.exceptionDetails).slice(0,200));return r.result&&r.result.value};
  await cmd('Runtime.enable');await cmd('Page.enable');await cmd('DOM.enable');
  for(let i=0;i<60;i++){try{if(await ev("!!window.__cyrilViewOpen"))break}catch(e){}await sleep(250)}
  for(let i=0;i<6;i++){await ev("window.__cyrilSuperHome&&window.__cyrilSuperHome.hide()");await sleep(300)}
  await ev("document.getElementById('btnMode').click()");await sleep(1000);
  const doc=await cmd('DOM.getDocument',{depth:-1});
  const q=await cmd('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'#radio-files'});
  await cmd('DOM.setFileInputFiles',{nodeId:q.nodeId,files:[MP3,LRC]});
  await sleep(2500);
  await ev("(function(){var it=document.querySelector('#radio-queue-list .q-item'); if(it) it.click(); return 1;})()");
  await sleep(1500);
  for(let i=0;i<6;i++){await ev("window.__cyrilSuperHome&&window.__cyrilSuperHome.hide()");await sleep(200)}
  await ev("(function(){var b=document.getElementById('rbtn-lyrics'); if(b && !b.classList.contains('on')) b.click(); return 1;})()");
  await sleep(1200);
  const info=JSON.parse(await ev(`JSON.stringify((function(){var p=document.getElementById('radio-lyrics');
    var r=p.getBoundingClientRect(), st=getComputedStyle(p);
    return {rect:{x:r.x,y:r.y,w:r.width,h:r.height}, display:st.display, z:st.zIndex, show:p.classList.contains('show')};})())`));
  console.log('panel', JSON.stringify(info));
  const full=await cmd('Page.captureScreenshot',{format:'png'});
  fs.writeFileSync(path.join(OUT,'probe-lrc-en-full.png'),Buffer.from(full.data,'base64'));
  if(info.rect.w>10){
    const shot=await cmd('Page.captureScreenshot',{format:'png',clip:{x:Math.round(info.rect.x),y:Math.round(info.rect.y),width:Math.round(info.rect.w),height:Math.round(info.rect.h),scale:2}});
    fs.writeFileSync(path.join(OUT,'probe-lrc-en-panel.png'),Buffer.from(shot.data,'base64'));
    console.log('panel shot ok');
  }
  /* 切凝彩：看大字用的是不是英文原文 */
  await ev("document.getElementById('rbtn-view').click()");await sleep(300);
  await ev("(function(){var it=Array.from(document.querySelectorAll('#viewMenu .vm-item')).find(e=>e.getAttribute('data-view')==='tempera'); if(it) it.click(); return 1;})()");
  await sleep(3500);
  await ev("(function(){if(window.Tempera&&window.Tempera.seek) window.Tempera.seek(30); return 1;})()");
  await sleep(1500);
  const dbg=JSON.parse(await ev("JSON.stringify(window.Tempera.debug())"));
  console.log('tempera scene', JSON.stringify((dbg.scene&&dbg.scene.shown||[]).slice(0,6)));
  const t2=await cmd('Page.captureScreenshot',{format:'png'});
  fs.writeFileSync(path.join(OUT,'probe-lrc-en-tempera.png'),Buffer.from(t2.data,'base64'));
  try{ch.kill()}catch(e){}
  process.exit(0);
})();
