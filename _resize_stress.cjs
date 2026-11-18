/* 压测：进凝彩后连续抖动视口尺寸（模拟全屏切换时的多次 resize），抓异常 */
const { spawn } = require('child_process');
const http = require('http'); const net = require('net'); const crypto = require('crypto');
const fs = require('fs'); const path = require('path'); const os = require('os');
const BASE = process.argv[2] || 'http://127.0.0.1:18773/';
const PORT = 9433, CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const sleep = ms => new Promise(r => setTimeout(r, ms));
function enc(o){const d=Buffer.from(JSON.stringify(o));const m=crypto.randomBytes(4);let h;if(d.length<126){h=Buffer.alloc(2);h[1]=0x80|d.length}else{h=Buffer.alloc(4);h[1]=0x80|126;h.writeUInt16BE(d.length,2)}h[0]=0x81;const b=Buffer.from(d);for(let i=0;i<b.length;i++)b[i]^=m[i%4];return Buffer.concat([h,m,b])}
function ws(p){return new Promise((res,rej)=>{const k=crypto.randomBytes(16).toString('base64');const s=net.connect(PORT,'127.0.0.1',()=>s.write('GET '+p+' HTTP/1.1\r\nHost:127.0.0.1:'+PORT+'\r\nUpgrade:websocket\r\nConnection:Upgrade\r\nSec-WebSocket-Key:'+k+'\r\nSec-WebSocket-Version:13\r\n\r\n'));let buf=Buffer.alloc(0),open=false;const ls=[];const api={send(o){s.write(enc(o))},onText(f){ls.push(f)},close(){s.destroy()}};s.on('error',rej);s.on('data',d=>{buf=Buffer.concat([buf,d]);if(!open){const i=buf.indexOf('\r\n\r\n');if(i<0)return;open=true;buf=buf.slice(i+4);res(api)}for(;;){if(buf.length<2)return;const l0=buf[1]&0x7f;let len=l0,off=2;if(len===126){if(buf.length<4)return;len=buf.readUInt16BE(2);off=4}else if(len===127){if(buf.length<10)return;len=Number(buf.readBigUInt64BE(2));off=10}if(buf.length<off+len)return;const op=buf[0]&0x0f;const pl=buf.slice(off,off+len);buf=buf.slice(off+len);if(op===8){s.destroy();return}if(op===9)continue;if(op===1||op===0)ls.forEach(f=>f(pl.toString()))}})})}
const hj=p=>new Promise((res,rej)=>{http.get({host:'127.0.0.1',port:PORT,path:p},r=>{let s='';r.on('data',d=>s+=d);r.on('end',()=>res(JSON.parse(s)))}).on('error',rej)});
(async()=>{
  const prof=path.join(os.tmpdir(),'wt-stress');try{fs.rmSync(prof,{recursive:true,force:true})}catch(e){}
  const ch=spawn(CHROME,['--headless=new','--remote-debugging-port='+PORT,'--user-data-dir='+prof,'--no-first-run','--window-size=1414,800','--autoplay-policy=no-user-gesture-required',BASE],{stdio:'ignore'});
  let ts=null;for(let i=0;i<40;i++){try{ts=await hj('/json/list');if(ts&&ts.length)break}catch(e){}await sleep(250)}
  const page=(ts||[]).find(t=>t.type==='page'&&t.url.indexOf('127.0.0.1')>=0);
  const w=await ws(new URL(page.webSocketDebuggerUrl).pathname);
  let id=0;const pend=new Map();const errs=[];
  w.onText(t=>{let m;try{m=JSON.parse(t)}catch(e){return}if(m.id&&pend.has(m.id)){const p=pend.get(m.id);pend.delete(m.id);m.error?p.rej(new Error(JSON.stringify(m.error))):p.res(m.result);return}
    if(m.method==='Runtime.exceptionThrown'){const d=m.params.exceptionDetails;errs.push((d.exception&&d.exception.description||d.text||'').split('\n')[0])}});
  const cmd=(me,pa)=>new Promise((res,rej)=>{const i=++id;pend.set(i,{res,rej});w.send({id:i,method:me,params:pa||{}})});
  const ev=async e=>{const r=await cmd('Runtime.evaluate',{expression:e,returnByValue:true,awaitPromise:true,userGesture:true});return r.result&&r.result.value};
  await cmd('Runtime.enable');
  for(let i=0;i<60;i++){if(await ev('!!window.__cyrilViewOpen'))break;await sleep(250)}
  for(let i=0;i<6;i++){await ev('window.__cyrilSuperHome&&window.__cyrilSuperHome.hide()');await sleep(250)}
  await ev("document.getElementById('btnMode').click()");await sleep(800);
  await ev("window.__cyrilViewOpen('tempera')");await sleep(3000);
  const seq=[[1920,1080],[1920,1040],[1914,1030],[1920,1080],[1414,800],[1920,1080],[2560,1400],[1920,1080],[1360,760],[1920,1080],[3840,2160],[1920,1080]];
  for(let round=0;round<3;round++){
    for(const [wd,ht] of seq){
      await cmd('Emulation.setDeviceMetricsOverride',{width:wd,height:ht,deviceScaleFactor:1,mobile:false});
      await sleep(45);
    }
    await sleep(600);
  }
  console.log('errs',errs.length); errs.slice(0,6).forEach(e=>console.log(' -',e));
  console.log('still running', await ev('window.Tempera.isRunning()'), 'frames', await ev('window.Tempera.debug().frames'));
  try{ch.kill()}catch(e){}
  process.exit(0);
})();
