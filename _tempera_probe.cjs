/* 凝彩在 WebTerm 里到底画出了什么：载入假 LRC，截图看色块 / 大字 / 镜头是否真的出现，
 * 以及黑（午夜墨染）/ 白（日光素白）是否整屏翻纸色。
 * 用法：node _tempera_probe.cjs [baseUrl]
 */
const { spawn } = require('child_process');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');

const BASE = process.argv[2] || 'http://127.0.0.1:18767/';
const PORT = 9414;
const OUT = path.join(__dirname, '_maintenance', '20261118');
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const sleep = ms => new Promise(r => setTimeout(r, ms));

function encodeFrame(obj) {
  const data = Buffer.from(JSON.stringify(obj), 'utf8');
  const mask = crypto.randomBytes(4);
  let hdr;
  if (data.length < 126) { hdr = Buffer.alloc(2); hdr[1] = 0x80 | data.length; }
  else if (data.length < 65536) { hdr = Buffer.alloc(4); hdr[1] = 0x80 | 126; hdr.writeUInt16BE(data.length, 2); }
  else { hdr = Buffer.alloc(10); hdr[1] = 0x80 | 127; hdr.writeBigUInt64BE(BigInt(data.length), 2); }
  hdr[0] = 0x81;
  const body = Buffer.from(data);
  for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
  return Buffer.concat([hdr, mask, body]);
}
function connectWS(pathname) {
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const sock = net.connect(PORT, '127.0.0.1', () => {
      sock.write('GET ' + pathname + ' HTTP/1.1\r\nHost: 127.0.0.1:' + PORT +
        '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ' + key +
        '\r\nSec-WebSocket-Version: 13\r\n\r\n');
    });
    let buf = Buffer.alloc(0), open = false;
    const listeners = [];
    const api = {
      send(obj) { sock.write(encodeFrame(obj)); },
      onText(fn) { listeners.push(fn); },
      close() { try { sock.destroy(); } catch (e) {} },
    };
    sock.on('error', reject);
    sock.on('data', d => {
      buf = Buffer.concat([buf, d]);
      if (!open) {
        const i = buf.indexOf('\r\n\r\n');
        if (i < 0) return;
        const head = buf.slice(0, i).toString(); buf = buf.slice(i + 4);
        if (!/ 101 /.test(head)) return reject(new Error('握手失败'));
        open = true; resolve(api);
      }
      for (;;) {
        if (buf.length < 2) return;
        const b0 = buf[0], b1 = buf[1];
        let len = b1 & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const op = b0 & 0x0f;
        const payload = buf.slice(off, off + len); buf = buf.slice(off + len);
        if (op === 8) { sock.destroy(); return; }
        if (op === 9) continue;
        if (op === 1 || op === 0) listeners.forEach(fn => fn(payload.toString('utf8')));
      }
    });
  });
}
function httpJSON(p) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path: p }, res => {
      let s = ''; res.on('data', d => s += d);
      res.on('end', () => { try { resolve(JSON.parse(s)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const profile = path.join(os.tmpdir(), 'wt-tempera-probe');
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch (e) {}
  const chrome = spawn(CHROME, [
    '--headless=new', '--remote-debugging-port=' + PORT, '--user-data-dir=' + profile,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    /* WT_SW=1 时走 SwiftShader 软件渲染。凝彩依赖 difference filter + 四个全屏后处理 pass，
       软件渲染下 back texture 采样会崩（整屏中灰、主歌词消失），所以默认用真实 GPU，
       只有在没有显卡的机器上才需要这个开关。 */
    ...(process.env.WT_SW ? ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] : []),
    BASE,
  ], { stdio: 'ignore' });

  let targets = null;
  for (let i = 0; i < 40; i++) {
    try { targets = await httpJSON('/json/list'); if (targets && targets.length) break; } catch (e) {}
    await sleep(250);
  }
  const page = (targets || []).find(t => t.type === 'page' && t.url.indexOf('127.0.0.1') >= 0);
  if (!page) { console.log('FAIL 拿不到页面'); chrome.kill(); process.exit(1); }
  const ws = await connectWS(new URL(page.webSocketDebuggerUrl).pathname);
  let id = 0; const pending = new Map(); const errs = [];
  ws.onText(text => {
    let m; try { m = JSON.parse(text); } catch (e) { return; }
    if (m.id && pending.has(m.id)) {
      const { resolve, reject } = pending.get(m.id); pending.delete(m.id);
      m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
      return;
    }
    if (m.method === 'Runtime.exceptionThrown') errs.push('exception: ' + ((m.params.exceptionDetails.exception || {}).description || m.params.exceptionDetails.text));
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errs.push('console: ' + (m.params.args || []).map(a => a.value || a.description || '').join(' '));
  });
  const cmd = (method, params) => new Promise((resolve, reject) => {
    const mid = ++id; pending.set(mid, { resolve, reject }); ws.send({ id: mid, method, params: params || {} });
  });
  const ev = async expr => {
    const r = await cmd('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true, userGesture: true });
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 300));
    return r.result ? r.result.value : undefined;
  };
  const shot = async name => {
    const r = await cmd('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(OUT, name), Buffer.from(r.data, 'base64'));
    return name;
  };
  await cmd('Runtime.enable'); await cmd('Page.enable');

  for (let i = 0; i < 60; i++) {
    try { if (await ev("!!window.__cyrilViewOpen && !!window.Tempera")) break; } catch (e) {}
    await sleep(250);
  }
  console.log('桥接就位:', await ev("typeof window.Tempera + '/' + (window.TemperaFolia ? 'adapter OK' : 'adapter 缺失')"));
  console.log('默认 tuning:', await ev("JSON.stringify(window.Tempera.debug().tuning)"));

  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await sleep(300);
  await ev("window.__cyrilViewOpen('tempera')");
  await sleep(2000);
  await ev("window.__cyrilSuperHome && window.__cyrilSuperHome.hide()");
  await ev("document.body.classList.remove('super-on','super-view')");
  await sleep(1500);
  console.log('tempera-on:', await ev("document.body.classList.contains('tempera-on')"));
  console.log('canvas 子元素:', await ev("document.querySelectorAll('#tempera-canvas canvas').length"));
  console.log('host 尺寸:', await ev("(function(){var h=document.getElementById('tempera-canvas');return h.clientWidth+'x'+h.clientHeight;})()"));
  console.log('adapter 预设:', await ev("JSON.stringify(window.TemperaFolia.BASE_DUAL_THEME.light)"));
  console.log('runtime layout:', await ev('JSON.stringify(window.Tempera.debug().layout)'));
  console.log('theme colors:', await ev('JSON.stringify(window.Tempera.debug().colors)'));

  const lrc = [
    { t: 0, text: '纸上的字被切成两色' },
    { t: 5, text: '色块从画外推了进来' },
    { t: 10, text: '网点压过整片留白' },
    { t: 15, text: '镜头沿着一条线走' },
    { t: 20, text: '把句子拼成一张海报' },
    { t: 25, text: '然后撕开它' }
  ];
  await ev("window.Tempera.setLyrics(" + JSON.stringify(lrc) + ", {title:'凝彩探针',artist:'Cyril'})");
  await sleep(6000);
  console.log('roots:', await ev('JSON.stringify(window.Tempera.debug().scene.roots)'));
  console.log('shown:', await ev('JSON.stringify(window.Tempera.debug().scene.shown)'));

  /* 只留画布，把终端与面板藏掉，便于看凝彩本体 */
  await ev("document.body.classList.add('probe-clean')");
  await cmd('Runtime.evaluate', { expression: `
    var st=document.createElement('style');
    st.textContent='body.probe-clean #workArea,body.probe-clean #radio-controls,body.probe-clean #radio-queue,body.probe-clean header,body.probe-clean footer{opacity:0 !important}';
    document.head.appendChild(st);
  ` });
  await sleep(800);

  /* 逐点 seek 截图：每个时刻停 1.2s 让场景建好，再拍。
     只看一个时刻很容易撞上入场/交接的低透明度帧，会误判成「没画出来」。 */
  await ev("document.getElementById('btnBlack').click()");
  await sleep(2500);
  for (const t of [3, 7, 11, 15, 19, 23, 27]) {
    await ev("window.Tempera.seek(" + t + ")");
    await sleep(1200);
    const d = JSON.parse(await ev('JSON.stringify(window.Tempera.debug())'));
    const sc = d.scene || {};
    console.log('seek' + t, 'txt=' + sc.textsTotal, 'gfx=' + sc.graphics,
      'shown=' + JSON.stringify((sc.shown || []).filter(x => x.a >= 0.9).slice(0, 3)));
    await shot('probe-tempera-t' + t + '.png');
  }

  await ev("document.getElementById('btnWhite').click()");
  await sleep(2500);
  for (const t of [15, 19]) {
    await ev("window.Tempera.seek(" + t + ")");
    await sleep(1200);
    await shot('probe-tempera-light-t' + t + '.png');
  }

  console.log('errs:', errs.slice(0, 6));
  ws.close(); chrome.kill(); process.exit(0);
})().catch(e => { console.error('异常', e); process.exit(2); });
