'use strict';

const TOKEN = document.querySelector('meta[name="token"]').content;
const APP_VERSION = '3.10.6';
const Q = (s) => document.querySelector(s);
const QA = (s) => Array.from(document.querySelectorAll(s));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fmtSize = (n) => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(1) + ' KB'
  : (n / 1048576).toFixed(2) + ' MB';
const fmtNum = (n) => (n || 0).toLocaleString('zh-CN');
const stem = (name) => name.replace(/\.[^.]+$/, '');
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

const PAGES = {
  icon: ['图标提取', '从 EXE / DLL 中拆出多尺寸图标，导出 ICO 与透明 PNG'],
  upx: ['UPX 加壳', '内置 UPX 引擎，压缩、脱壳、完整性校验一站完成'],
  vm: ['VM 虚拟壳', '把程序加密封装进随机生成的虚拟机外壳，每次构建都不一样'],
  elf: ['ELF 加固', 'Linux 可执行文件函数级虚拟化，产出 LD_PRELOAD 运行时 payload'],
  apk: ['APK 加固', '抽出 dex 加密后由壳加载，产出可直接安装的签名包'],
  about: ['关于', '本地运行，不上传任何文件'],
};

const S = {
  page: 'icon', files: { icon: null, upx: null, vm: null, elf: null, apk: null },
  icons: { vm: null, apk: null }, apkIcons: null, busy: false, outdir: '',
};

/* ------------------------------ 通用请求 ------------------------------ */
async function apiGet(path) {
  const url = path + (path.includes('?') ? '&' : '?') + 't=' + encodeURIComponent(TOKEN);
  const r = await fetch(url, { headers: { 'X-Token': TOKEN } });
  const j = await r.json().catch(() => ({ error: '响应解析失败' }));
  if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
  return j;
}
async function apiPost(path, body) {
  const r = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Token': TOKEN },
    body: JSON.stringify(body || {}),
  });
  const j = await r.json().catch(() => ({ error: '响应解析失败' }));
  if (!r.ok) throw new Error(j.error || 'HTTP ' + r.status);
  return j;
}
function upload(file, onPct) {
  return new Promise((res, rej) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/upload');
    xhr.setRequestHeader('X-Token', TOKEN);
    xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) onPct(e.loaded / e.total); };
    xhr.onload = () => {
      try {
        const j = JSON.parse(xhr.responseText);
        if (xhr.status === 200) res(j); else rej(new Error(j.error || xhr.status));
      } catch (e) { rej(new Error('上传响应异常')); }
    };
    xhr.onerror = () => rej(new Error('网络错误'));
    xhr.send(file);
  });
}

/* ------------------------------ 日志 ------------------------------ */
const LOG_BOX = () => Q('#log');
function log(text, tag) {
  const box = LOG_BOX();
  const line = document.createElement('span');
  line.className = 'l-' + (tag || 'INFO');
  line.textContent = text + '\n';
  box.appendChild(line);
  while (box.childElementCount > 1200) box.removeChild(box.firstChild);
  box.scrollTop = box.scrollHeight;
}
function stamp(text, tag) {
  const d = new Date();
  const t = [d.getHours(), d.getMinutes(), d.getSeconds()].map((x) => String(x).padStart(2, '0')).join(':');
  log(`[${t}] ${text}`, tag);
}
function focusResult(el) {
  if (!el) return;
  requestAnimationFrame(() => el.scrollIntoView({ block: 'nearest', behavior: 'smooth' }));
}

/* ------------------------------ 任务 ------------------------------ */
async function runJob(url, body, onResult) {
  const t0 = Date.now();
  setBusy(true);
  try {
    const j = await apiPost(url, body);
    let since = 0;
    for (;;) {
      const r = await apiGet(`/api/job?id=${j.job}&since=${since}`);
      (r.logs || []).forEach(([tag, text]) => log(text, tag));
      since = r.next;
      if (r.state !== 'running') {
        if (r.state === 'error') { stamp('失败：' + r.error, 'ERR'); throw new Error(r.error); }
        stamp(`完成，用时 ${((Date.now() - t0) / 1000).toFixed(1)} 秒`, 'OK');
        if (onResult) onResult(r.result);
        return r.result;
      }
      await sleep(320);
    }
  } finally {
    setBusy(false);
  }
}
function setBusy(on) {
  S.busy = on;
  QA('.btn').forEach((b) => { b.disabled = on; });
  document.body.style.cursor = on ? 'progress' : '';
}

/* ------------------------------ 页面切换 ------------------------------ */
function go(page) {
  S.page = page;
  QA('.nav-item').forEach((b) => b.classList.toggle('active', b.dataset.page === page));
  QA('.page').forEach((p) => p.classList.toggle('active', p.id === 'page-' + page));
  const [t, s] = PAGES[page];
  Q('#pageTitle').textContent = t;
  Q('#pageSub').textContent = s;
  refreshBadge();
  Q('.pages').scrollTop = 0;
}
QA('.nav-item').forEach((b) => b.addEventListener('click', () => go(b.dataset.page)));

function refreshBadge() {
  const f = S.files[S.page];
  const el = Q('#fileBadge');
  if (S.page === 'about') { el.textContent = '本地运行 · v' + APP_VERSION; el.classList.remove('on'); return; }
  if (f) { el.textContent = `${f.name} · ${fmtSize(f.size)}`; el.classList.add('on'); }
  else { el.textContent = '未选择文件'; el.classList.remove('on'); }
}

/* ------------------------------ 拖放 / 选择 ------------------------------ */
let dragDepth = 0;
const overlay = Q('#overlay');
window.addEventListener('dragenter', (e) => {
  if (!e.dataTransfer || !Array.from(e.dataTransfer.types).includes('Files')) return;
  e.preventDefault(); dragDepth++; overlay.classList.add('on');
});
window.addEventListener('dragover', (e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; });
window.addEventListener('dragleave', (e) => {
  e.preventDefault(); dragDepth = Math.max(0, dragDepth - 1);
  if (!dragDepth) overlay.classList.remove('on');
});
window.addEventListener('drop', (e) => {
  e.preventDefault(); dragDepth = 0; overlay.classList.remove('on');
  const page = S.page === 'about' ? 'icon' : S.page;
  const f = e.dataTransfer.files && e.dataTransfer.files[0];
  if (f) acceptFile(page, f);
});

QA('.drop').forEach((dz) => {
  const page = dz.dataset.page;
  dz.addEventListener('click', () => openPicker(page));
  dz.addEventListener('dragover', (e) => { e.preventDefault(); dz.classList.add('over'); });
  dz.addEventListener('dragleave', () => dz.classList.remove('over'));
  dz.addEventListener('drop', (e) => { e.preventDefault(); dz.classList.remove('over'); });
});
QA('[data-pick]').forEach((b) => b.addEventListener('click', (e) => {
  e.stopPropagation(); openPicker(b.dataset.pick);
}));

// Linux 可执行文件经常没有扩展名（./prog），这里不过滤，改用 ELF 魔数校验
const PICKER_ACCEPT = { elf: '' };
function openPicker(page) {
  const input = Q('#picker');
  input.value = '';
  input.accept = PICKER_ACCEPT[page] || '.exe,.dll,.scr,.cpl,.ocx,.sys';
  input.onchange = () => { if (input.files[0]) acceptFile(page, input.files[0]); };
  input.click();
}

function setBar(dz, p) {
  const bar = dz.querySelector('.drop-bar i');
  if (bar) bar.style.width = Math.min(100, Math.round(p * 100)) + '%';
}

async function looksElf(file) {
  try {
    const b = new Uint8Array(await file.slice(0, 6).arrayBuffer());
    if (b.length < 6 || b[0] !== 0x7f || b[1] !== 0x45 || b[2] !== 0x4c || b[3] !== 0x46) return 'not';
    if (b[4] !== 2 || b[5] !== 1) return 'unsupported';   // 32 位 / 大端
    return 'ok';
  } catch (e) { return 'not'; }
}

async function acceptFile(page, file) {
  const okPe = /\.(exe|dll|scr|cpl|ocx|sys)$/i.test(file.name);
  const okApk = /\.(apk|xapk|apks)$/i.test(file.name);
  if (page === 'elf') {
    const kind = await looksElf(file);
    if (kind === 'not') {
      stamp('这不是 ELF 文件：请选择 Linux 可执行文件或 .so', 'WARN');
      return;
    }
    if (kind === 'unsupported') {
      stamp('只支持 ELF64 小端；32 位（ELF32）与大端目标暂不支持', 'WARN');
      return;
    }
  } else if (page === 'apk' ? !okApk : !okPe) {
    stamp(page === 'apk' ? '只支持 APK 文件' : '只支持 EXE / DLL / SCR / CPL / OCX / SYS 文件', 'WARN');
    return;
  }
  const dz = Q('#drop-' + page);
  dz.classList.add('loading');
  setBar(dz, 0);
  stamp(`正在读取 ${file.name}（${fmtSize(file.size)}）…`, 'DIM');
  try {
    const r = await upload(file, (p) => setBar(dz, p));
    setBar(dz, 1);
    S.files[page] = { ...r, file };
    stamp(`已载入 ${r.name}`, 'OK');
    refreshBadge();
    if (page === 'icon') await loadIcon();
    if (page === 'upx') loadUpx();
    if (page === 'vm') loadVm();
    if (page === 'elf') loadElf();
    if (page === 'apk') await loadApk();
  } catch (e) {
    stamp('读取失败：' + e.message, 'ERR');
  } finally {
    dz.classList.remove('loading');
  }
}

function resetPage(page) {
  S.files[page] = null;
  Q('#drop-' + page).hidden = false;
  Q('#ws-' + page).hidden = true;
  refreshBadge();
}
Q('#btnIconRe').addEventListener('click', () => resetPage('icon'));
Q('#btnUpxRe').addEventListener('click', () => resetPage('upx'));
Q('#btnVmRe').addEventListener('click', () => resetPage('vm'));
Q('#btnElfRe').addEventListener('click', () => resetPage('elf'));
Q('#btnApkRe').addEventListener('click', () => resetPage('apk'));

/* ------------------------------ 图标选择（VM / APK） ------------------------------ */
const iconPicker = Q('#iconPicker');

function pickIcon(kind) {
  iconPicker.value = '';
  iconPicker.onchange = () => { if (iconPicker.files[0]) acceptIcon(kind, iconPicker.files[0]); };
  iconPicker.click();
}

async function acceptIcon(kind, file) {
  if (!/\.(ico|png|jpe?g|bmp|webp|exe|dll)$/i.test(file.name)) {
    stamp('图标只支持 ICO / PNG / JPG / BMP / WEBP，或 EXE / DLL', 'WARN');
    return;
  }
  stamp(`正在读取图标 ${file.name}（${fmtSize(file.size)}）…`, 'DIM');
  try {
    const r = await upload(file, () => {});
    const probe = await fetch(
      `/api/icon/preview?fid=${encodeURIComponent(r.fid)}&t=${encodeURIComponent(TOKEN)}`,
      { headers: { 'X-Token': TOKEN } });
    if (!probe.ok) {
      const j = await probe.json().catch(() => ({}));
      stamp('图标无法解码：' + (j.error || '请改用 ICO / PNG（JPG / BMP / WEBP 需 Pillow）'), 'ERR');
      return;
    }
    S.icons[kind] = { fid: r.fid, name: r.name };
    if (kind === 'vm') renderVmIcon(); else renderApkIcon();
    stamp(`图标已就绪：${r.name}`, 'OK');
  } catch (e) {
    stamp('图标读取失败：' + e.message, 'ERR');
  }
}

function iconPreviewHtml(ic) {
  return `<img src="/api/icon/preview?fid=${encodeURIComponent(ic.fid)}&t=${encodeURIComponent(TOKEN)}" alt="">`;
}

function renderVmIcon() {
  const ic = S.icons.vm;
  const pv = Q('#vmIconPreview');
  const keep = Q('#vmIcon');
  const keepLabel = keep.closest('.chk');
  if (!ic) {
    pv.innerHTML = '<span>—</span>';
    Q('#vmIconInfo').textContent = '未选择，默认沿用原程序图标';
    Q('#vmIconName').textContent = '支持 .ico .png .jpg .bmp .webp，也可选 .exe .dll';
    Q('#btnVmIconClear').hidden = true;
    keep.disabled = false;
    if (keepLabel) keepLabel.style.opacity = '';
    return;
  }
  pv.innerHTML = iconPreviewHtml(ic);
  Q('#vmIconInfo').textContent = ic.name;
  Q('#vmIconName').textContent = '已选中自定义图标，将覆盖「沿用原程序图标」';
  Q('#btnVmIconClear').hidden = false;
  keep.disabled = true;
  if (keepLabel) keepLabel.style.opacity = '.45';
}

function renderApkIcon() {
  const ic = S.icons.apk;
  const pv = Q('#apkIconPreview');
  if (!ic) {
    pv.innerHTML = '<span>—</span>';
    Q('#apkIconInfo').textContent = '未选择';
    Q('#apkIconName').textContent = '未选择图标，不替换';
    Q('#btnApkIconClear').hidden = true;
    Q('#apkIconCands').hidden = true;
    return;
  }
  pv.innerHTML = iconPreviewHtml(ic);
  Q('#apkIconInfo').textContent = ic.name;
  Q('#apkIconName').textContent = '勾选要替换的资源，默认全选可替换项';
  Q('#btnApkIconClear').hidden = false;
  renderApkCands();
}

function renderApkCands() {
  const box = Q('#apkIconCands');
  const wrap = Q('#apkIconCandList');
  if (!S.icons.apk) { box.hidden = true; return; }
  const data = S.apkIcons || {};
  const cands = data.candidates || [];
  const fid = S.files.apk ? S.files.apk.fid : '';
  box.hidden = false;
  if (!cands.length) {
    Q('#apkIconCandInfo').textContent = '';
    wrap.innerHTML = '<p class="para tiny">没有探测到图标资源。若安装包使用非常规命名，可跳过替换。</p>';
    return;
  }
  const repl = cands.filter((c) => c.replaceable).length;
  Q('#apkIconCandInfo').textContent =
    `${repl} 项可替换 / 共 ${cands.length} 项` +
    (data.adaptive ? ` · 自适应图层 ${data.adaptive}` : '') +
    (data.heuristic ? ` · 名称匹配 ${data.heuristic}` : '');
  wrap.innerHTML = '';
  cands.forEach((c) => {
    const sizeTxt = c.kind === 'vector' ? '矢量 24×24'
      : (c.width && c.height ? `${c.width}×${c.height}` : c.kind);
    const el = document.createElement('label');
    el.className = 'cand' + (c.replaceable ? '' : ' disabled');
    const thumb = c.replaceable
      ? `<img src="/api/apk/icon?fid=${encodeURIComponent(fid)}&path=${encodeURIComponent(c.path)}&t=${encodeURIComponent(TOKEN)}" alt="" loading="lazy">`
      : '<i>—</i>';
    el.innerHTML = `
      <input type="checkbox" data-path="${esc(c.path)}"${c.replaceable ? '' : ' disabled'}>
      <div class="cthumb">${thumb}</div>
      <div class="cmeta">
        <b title="${esc(c.path)}">${esc(c.path.split('/').pop())}</b>
        <span title="${esc(c.source)}">${esc(c.source)} · ${esc(sizeTxt)}${c.note ? ' · ' + esc(c.note) : (c.reason ? ' · ' + esc(c.reason) : '')}</span>
      </div>`;
    const cb = el.querySelector('input');
    if (c.replaceable) { cb.checked = true; el.classList.add('on'); }
    cb.addEventListener('change', () => el.classList.toggle('on', cb.checked));
    const img = el.querySelector('.cthumb img');
    if (img) img.addEventListener('error', () => { el.querySelector('.cthumb').innerHTML = '<i>图</i>'; });
    wrap.appendChild(el);
  });
}

function clearIcon(kind) {
  S.icons[kind] = null;
  if (kind === 'vm') renderVmIcon(); else renderApkIcon();
  stamp('已移除自定义图标', 'DIM');
}

function checkedApkEntries() {
  return QA('#apkIconCandList input[type="checkbox"]:checked')
    .map((x) => x.dataset.path).filter(Boolean);
}

Q('#btnVmIconPick').addEventListener('click', () => pickIcon('vm'));
Q('#btnVmIconClear').addEventListener('click', () => clearIcon('vm'));
Q('#btnApkIconPick').addEventListener('click', () => pickIcon('apk'));
Q('#btnApkIconClear').addEventListener('click', () => clearIcon('apk'));
Q('#btnApkIconAll').addEventListener('click', () => {
  QA('#apkIconCandList .cand').forEach((el) => {
    const b = el.querySelector('input');
    if (b && !b.disabled) { b.checked = true; el.classList.add('on'); }
  });
});
Q('#btnApkIconNone').addEventListener('click', () => {
  QA('#apkIconCandList .cand').forEach((el) => {
    const b = el.querySelector('input');
    if (b && !b.disabled) { b.checked = false; el.classList.remove('on'); }
  });
});


/* ------------------------------ 图标提取 ------------------------------ */
async function loadIcon() {
  const f = S.files.icon;
  Q('#drop-icon').hidden = true;
  Q('#ws-icon').hidden = false;
  Q('#iconResult').hidden = true;
  refreshBadge();
  try {
    const r = await apiPost('/api/probe', { fid: f.fid });
    Q('#iconOutdir').value = r.outdir;
    renderMeta(r.summary);
    renderIcons(r);
  } catch (e) {
    stamp('解析失败：' + e.message, 'ERR');
  }
}

function metaItem(label, value, cls) {
  return `<div class="meta-item"><i>${label}</i><b>${value}</b></div>`;
}
function renderMeta(s) {
  const tags = s.sections.map((x) => `<span class="tag">${esc(x)}</span>`).join(' ');
  const flags = [
    s.upx ? '<span class="tag warn">UPX 已加壳</span>' : '<span class="tag ok">未加壳</span>',
    s.dotnet ? '<span class="tag">.NET</span>' : '',
    s.resource ? '<span class="tag">含资源</span>' : '<span class="tag bad">无资源</span>',
  ].join(' ');
  Q('#metaGrid').innerHTML = [
    metaItem('文件名', esc(s.path.split(/[\\/]/).pop())),
    metaItem('大小', fmtNum(s.size) + ' 字节'),
    metaItem('架构', esc(s.machine)),
    metaItem('格式', esc(s.pe_type)),
    metaItem('入口点', esc(s.entry_point)),
    metaItem('映像基址', esc(s.image_base)),
    `<div class="meta-item" style="grid-column:1/-1"><i>节区</i><b style="white-space:normal">${tags}</b></div>`,
    `<div class="meta-item" style="grid-column:1/-1"><i>状态</i><b style="white-space:normal">${flags}</b></div>`,
  ].join('');
}

function renderIcons(r) {
  const grid = Q('#iconGrid');
  grid.innerHTML = '';
  const groups = r.groups || [];
  Q('#iconCount').textContent = groups.length
    ? `${groups.length} 组 · ${groups.reduce((a, g) => a + g.count, 0)} 个尺寸`
    : '没有找到图标资源';

  if (!groups.length) {
    grid.innerHTML = '<p class="para tiny">这个文件里没有 RT_GROUP_ICON 资源，通常意味着图标被剥离或被上层程序动态设置。</p>';
    Q('#bigPreview').innerHTML = '<span>无图标</span>';
    return;
  }

  const fid = r.fid;
  groups.forEach((g) => {
    const wrap = document.createElement('div');
    wrap.className = 'icon-group';
    let tag = g.name || '';
    tag = tag.startsWith('图标组 #') ? ' · ' + tag.slice(4) : (tag ? ' · ' + esc(tag) : '');
    wrap.innerHTML = `<h4>图标组 #${g.index + 1}${tag} · ${g.count} 个尺寸</h4>`;
    const list = document.createElement('div');
    list.className = 'icons-grid';
    g.entries.forEach((e, i) => {
      const src = `/api/icon?fid=${fid}&g=${g.index}&i=${i}&t=${encodeURIComponent(TOKEN)}`;
      const tile = document.createElement('div');
      tile.className = 'tile';
      tile.innerHTML = `<img src="${src}" alt=""><b>${esc(e.size)}</b><span>${e.bits} bit</span>`;
      tile.addEventListener('mouseenter', () => {
        Q('#bigPreview').innerHTML = `<img src="${src}" alt="">`;
      });
      list.appendChild(tile);
    });
    wrap.appendChild(list);
    grid.appendChild(wrap);
  });

  // 默认预览最大的一张
  const first = groups[0];
  let best = 0;
  first.entries.forEach((e, i) => {
    const w = parseInt(e.size.split('x')[0], 10) || 0;
    const bw = parseInt(first.entries[best].size.split('x')[0], 10) || 0;
    if (w > bw) best = i;
  });
  Q('#bigPreview').innerHTML =
    `<img src="/api/icon?fid=${fid}&g=0&i=${best}&t=${encodeURIComponent(TOKEN)}" alt="">`;
}

async function exportIcons(ico, png) {
  const f = S.files.icon;
  if (!f) return;
  try {
    const res = await runJob('/api/icons', {
      fid: f.fid, outdir: Q('#iconOutdir').value.trim(), ico, png,
    }, (result) => renderIconResult(result));
    if (res) stamp(`已导出 ${res.files.length} 个文件`, 'OK');
  } catch (e) { /* 已在 runJob 记录 */ }
}

function renderIconResult(res) {
  const box = Q('#iconResult');
  box.hidden = false;
  const chips = res.files.map((x) =>
    `<span class="fchip" title="${esc(x.path)}">${esc(x.name)}<i>${fmtSize(x.size)}</i></span>`).join('');
  box.innerHTML = `
    <div class="res-head"><b>导出完成 · ${res.files.length} 个文件</b>
      <span class="spacer" style="flex:1"></span>
      <button class="mini" id="btnIconFolder">打开文件夹</button>
    </div>
    <div class="res-path">${esc(res.dir)}</div>
    <div class="files">${chips}</div>`;
  Q('#btnIconFolder').addEventListener('click', () => openPath(res.dir));
  focusResult(box);
}
Q('#btnIconAll').addEventListener('click', () => exportIcons(true, true));
Q('#btnIconIco').addEventListener('click', () => exportIcons(true, false));
Q('#btnIconPng').addEventListener('click', () => exportIcons(false, true));
Q('#btnIconOpen').addEventListener('click', () => openPath(Q('#iconOutdir').value.trim()));

async function openPath(p) {
  if (!p) return;
  try { await apiPost('/api/open', { path: p }); }
  catch (e) { stamp('打开失败：' + e.message, 'WARN'); }
}

/* ------------------------------ UPX ------------------------------ */
let upxMode = 'pack';
function loadUpx() {
  const f = S.files.upx;
  Q('#drop-upx').hidden = true;
  Q('#ws-upx').hidden = false;
  Q('#upxResult').hidden = true;
  Q('#upxFileInfo').textContent = `${f.name} · ${fmtSize(f.size)}`;
  Q('#upxOutdir').value = S.outdir || '';
  syncUpxMode();
}
function syncUpxMode() {
  const seg = QA('#upxMode .seg').find((b) => b.dataset.mode === upxMode);
  QA('#upxMode .seg').forEach((b) => b.classList.toggle('active', b.dataset.mode === upxMode));
  Q('#upxOptions').hidden = upxMode !== 'pack';
  const nameEl = Q('#upxOutname');
  const f = S.files.upx;
  const base = f ? stem(f.name) : 'output';
  if (upxMode === 'pack') nameEl.value = base + '_upx.exe';
  else if (upxMode === 'unpack') nameEl.value = base + '_unpacked.exe';
  nameEl.closest('.field-row').hidden = !(upxMode === 'pack' || upxMode === 'unpack');
  Q('#btnUpxRun').textContent = {
    pack: '开始压缩', unpack: '脱壳还原', test: '执行完整性测试',
    list: '查看压缩信息', fileinfo: '查看壳参数',
  }[upxMode];
  // 只读模式下不显示图标/附加参数等压缩选项
  Q('#upxOptions').hidden = upxMode !== 'pack';
  if (seg) seg.classList.add('active');
}
QA('#upxMode .seg').forEach((b) => b.addEventListener('click', () => {
  upxMode = b.dataset.mode;
  syncUpxMode();
}));

const lvl = Q('#upxLevel');
function syncLevel() {
  Q('#upxLevelVal').textContent = lvl.value;
  lvl.style.setProperty('--p', ((lvl.value - 1) / 8 * 100) + '%');
}
lvl.addEventListener('input', syncLevel);
Q('#upxBest').addEventListener('change', () => { lvl.disabled = Q('#upxBest').checked || Q('#upxBrute').checked; });
Q('#upxBrute').addEventListener('change', () => { lvl.disabled = Q('#upxBest').checked || Q('#upxBrute').checked; });
Q('#btnUpxOpen').addEventListener('click', () => openPath(Q('#upxOutdir').value.trim()));

Q('#btnUpxRun').addEventListener('click', async () => {
  const f = S.files.upx;
  if (!f) return;
  const opts = {
    level: parseInt(lvl.value, 10),
    best: Q('#upxBest').checked,
    brute: Q('#upxBrute').checked,
    lzma: Q('#upxLzma').checked,
    force: Q('#upxForce').checked,
    icons: parseInt(Q('#upxIcons').value, 10),
    noRes: Q('#upxNoRes').checked,
    keepReloc: Q('#upxReloc').checked,
    extra: Q('#upxExtra').value.trim(),
  };
  stamp(`开始 ${upxMode}：${f.name}`, 'DIM');
  try {
    const res = await runJob('/api/upx', {
      fid: f.fid, mode: upxMode, opts,
      outdir: Q('#upxOutdir').value.trim(),
      outname: Q('#upxOutname').value.trim(),
    }, (r) => renderUpxResult(r));
    if (res) stamp('处理完成', 'OK');
  } catch (e) { /* ignore */ }
});

function renderUpxResult(r) {
  const box = Q('#upxResult');
  box.hidden = false;
  if (r.path) {
    box.innerHTML = `
      <div class="stat-row">
        <div class="stat"><i>原始大小</i><b>${fmtNum(r.size_before)}</b></div>
        <div class="stat"><i>处理后</i><b class="up">${fmtNum(r.size_after)}</b></div>
        <div class="stat"><i>体积变化</i><b class="up">${r.ratio > 0 ? '-' : '+'}${Math.abs(r.ratio)}%</b></div>
      </div>
      <div class="res-path">${esc(r.path)}</div>
      <div class="files"><span class="fchip">${esc(r.name)}<i>${fmtSize(r.size_after)}</i></span></div>
      <div class="btn-row">
        <button class="mini" id="btnUpxFolder">打开所在文件夹</button>
        <a class="mini" style="line-height:26px;text-decoration:none"
           href="/api/file?path=${encodeURIComponent(r.path)}&t=${encodeURIComponent(TOKEN)}">下载</a>
      </div>`;
    Q('#btnUpxFolder').addEventListener('click', () => openPath(r.path));
  } else {
    box.innerHTML = `<div class="res-head"><b>执行结果</b></div>
      <pre class="res-path" style="white-space:pre-wrap;line-height:1.6">${esc((r.lines || []).join('\n'))}</pre>`;
  }
  focusResult(box);
}

/* ------------------------------ VM ------------------------------ */
function loadVm() {
  const f = S.files.vm;
  Q('#drop-vm').hidden = true;
  Q('#ws-vm').hidden = false;
  Q('#vmResult').hidden = true;
  Q('#vmFileInfo').textContent = `${f.name} · ${fmtSize(f.size)}`;
  Q('#vmOutdir').value = S.outdir || '';
  Q('#vmOutname').value = stem(f.name) + '_vm.exe';
}
QA('#vmJunk .opt').forEach((l) => l.addEventListener('click', () => {
  QA('#vmJunk .opt').forEach((x) => x.classList.remove('active'));
  l.classList.add('active');
}));

/* ---- 一键预设（v3.2.4）：新手推荐 / 均衡分发 / 最强防护 ----
   只改下面列出的确定性选项；密码、输出目录、函数虚拟化等个性化项一律不动。 */
const VM_PRESETS = {
  novice: {
    hint: '当前预设：新手推荐 —— 标准强度 + 完整性校验 + 沿用图标，反调试 / 反沙箱等' +
      '容易误伤环境的选项全部关闭。直接点底部「开始构建」即可出成品。',
    set: { junk: 0, verify: true, cleanup: true, forward: true, icon: true,
           antiDebug: false, antiSandbox: false, secureTemp: true,
           vmpEnabled: true, vmpLicense: false, vmpAv: true },
  },
  balanced: {
    hint: '当前预设：均衡分发 —— 强混淆 + 完整性校验 + 版本资源（降低杀软误报），' +
      '守卫保持关闭。适合对外发布的正式版本。',
    set: { junk: 1, verify: true, cleanup: true, forward: true, icon: true,
           antiDebug: false, antiSandbox: false, secureTemp: true,
           vmpEnabled: true, vmpLicense: false, vmpAv: true },
  },
  hardened: {
    hint: '当前预设：最强防护 —— 极限混淆 + 反调试 + 反分析环境 + 加固临时释放。' +
      '注意：守卫类选项在云主机 / 虚拟桌面 / 企业终端上可能误判（v3.2.7 起命中时会有明确弹窗，' +
      '不再静默退出）。请先用自己的样本验证再分发。',
    set: { junk: 2, verify: true, cleanup: true, forward: true, icon: true,
           antiDebug: true, antiSandbox: true, secureTemp: true,
           vmpEnabled: true, vmpLicense: false, vmpAv: true },
  },
};

function applyVmPreset(name) {
  const p = VM_PRESETS[name];
  if (!p) return;
  QA('input[name="junk"]').forEach((x) => { x.checked = parseInt(x.value, 10) === p.set.junk; });
  QA('#vmJunk .opt').forEach((x) => x.classList.toggle(
    'active', x.querySelector('input').value === String(p.set.junk)));
  const chk = { verify: '#vmVerify', cleanup: '#vmCleanup', forward: '#vmForward',
                icon: '#vmIcon', antiDebug: '#vmAntiDebug', antiSandbox: '#vmAntiSandbox',
                secureTemp: '#vmSecureTemp', vmpEnabled: '#vmVmpEnabled',
                vmpLicense: '#vmVmpLicense', vmpAv: '#vmVmpAv' };
  for (const [k, sel] of Object.entries(chk)) Q(sel).checked = !!p.set[k];
  Q('#vmPresetHint').textContent = p.hint;
}
QA('#vmPreset .opt').forEach((l) => l.addEventListener('click', () => {
  QA('#vmPreset .opt').forEach((x) => x.classList.remove('active'));
  l.classList.add('active');
  applyVmPreset(l.querySelector('input').value);
  stamp(`已套用预设「${l.querySelector('b').textContent}」`, 'DIM');
}));
Q('#btnVmOpen').addEventListener('click', () => openPath(Q('#vmOutdir').value.trim()));

Q('#btnVmRun').addEventListener('click', async () => {
  const f = S.files.vm;
  if (!f) return;
  const junk = parseInt((QA('input[name="junk"]').find((x) => x.checked) || {}).value || '0', 10);
  const vmpLicense = Q('#vmVmpLicense').checked;
  if (vmpLicense && !Q('#vmVmpSecret').value.trim()) {
    stamp('启用离线授权时必须填写授权密钥', 'ERR');
    return;
  }
  const virtualize = Q('#vmVirtualize').checked;
  const vmFuncList = Q('#vmFuncList').value.trim();
  const vmAutoFunc = Q('#vmAutoFunc').checked;
  if (virtualize) {
    const rva = Q('#vmFuncRva').value.trim();
    const size = Q('#vmFuncSize').value.trim();
    if (vmFuncList) {
      // 批量清单格式：RVA(hex):长度(dec)，逗号分隔；容忍冒号/逗号两侧空白
      const norm = vmFuncList.replace(/\s+/g, '');
      const bad = norm.split(',').filter(Boolean)
        .filter((x) => !/^[0-9a-fA-F]+:[0-9]+$/.test(x) || parseInt(x.split(':')[1], 10) <= 0);
      if (bad.length) {
        stamp(`多函数清单格式有误：${bad.join('、')}（应为 RVA十六进制:长度十进制，如 1000:49,1010:18）`, 'ERR');
        Q('#vmFuncList').value = norm;
        return;
      }
    } else if (vmAutoFunc) {
      // 自动发现：由 C 内核逐窗口编译验证边界，无需手填 RVA / 长度
      const n = parseInt(Q('#vmAutoMax').value.trim(), 10);
      if (!(n >= 1 && n <= 64)) {
        stamp('自动发现上限需为 1~64 之间的整数', 'ERR');
        return;
      }
    } else if (!/^[0-9a-fA-F]+$/.test(rva) || !/^[0-9]+$/.test(size) || parseInt(size, 10) <= 0) {
      stamp('启用目标代码虚拟化时，需填多函数清单、勾选「自动发现函数」，或填函数 RVA（十六进制）+ 长度（正整数）', 'ERR');
      return;
    }
  }
  const vmpConfig = {
    mutation_level: 2,
    product_name: Q('#vmVmpProduct').value.trim() || 'CyrilShield Protected Application',
    company_name: Q('#vmVmpCompany').value.trim() || 'CyrilShield',
    file_version: Q('#vmVmpVersion').value.trim() || '1.0.0.0',
    av_compatibility: Q('#vmVmpAv').checked,
    licensing: vmpLicense,
    license_secret: Q('#vmVmpSecret').value,
    license_expires: Q('#vmVmpExpires').value.trim(),
    extras: { license_hwid: Q('#vmVmpHwid').value.trim() },
  };
  const opts = {
    junk,
    password: Q('#vmPass').value,
    extraArgs: Q('#vmArgs').value.trim(),
    verify: Q('#vmVerify').checked,
    cleanup: Q('#vmCleanup').checked,
    forwardArgs: Q('#vmForward').checked,
    keepIcon: Q('#vmIcon').checked,
    iconFid: S.icons.vm ? S.icons.vm.fid : '',
    antiDebug: Q('#vmAntiDebug').checked,
    antiSandbox: Q('#vmAntiSandbox').checked,
    secureTemp: Q('#vmSecureTemp').checked,
    vmpEnabled: Q('#vmVmpEnabled').checked,
    vmpConfig,
    virtualize,
    vmFuncRva: Q('#vmFuncRva').value.trim(),
    vmFuncSize: Q('#vmFuncSize').value.trim(),
    vmFuncList,
    vmAutoFunc,
    vmAutoMax: Q('#vmAutoMax').value.trim(),
  };
  stamp(`开始生成保护副本：${f.name}`, 'DIM');
  try {
    const res = await runJob('/api/vm', {
      fid: f.fid, opts,
      outdir: Q('#vmOutdir').value.trim(),
      outname: Q('#vmOutname').value.trim(),
    }, (r) => renderVmResult(r));
    if (res) stamp('生成完成', 'OK');
  } catch (e) { /* ignore */ }
});

function guardSummary(r) {
  const parts = [];
  const m = r.guard_mode || 0;
  if (m & 1) parts.push('反调试');
  if (m & 2) parts.push('反分析环境');
  if (!parts.length) parts.push('关闭');
  if (r.secure_temp) parts.push('加固临时释放');
  return esc(parts.join(' · '));
}

function renderVmResult(r) {
  const box = Q('#vmResult');
  box.hidden = false;
  const v = r.vmp || {};
  const vz = r.virtualization || {};
  const iconHtml = (r.icon && r.icon.applied) ? `
    <div class="hint" style="margin-top:8px">自定义图标：<b>${esc(r.icon.name)}</b> ·
      ${(r.icon.sizes || []).length} 个尺寸（16–256）</div>` : '';
  const auto = vz.auto_discover || null;
  const autoHtml = auto ? `
    <div class="hint" style="margin-top:6px">自动发现函数：<b>${fmtNum(auto.accepted)} 个</b> ·
      扫描 ${esc(String(auto.section))} 前 ${fmtNum(auto.scanned_bytes)}B ·
      候选 ${fmtNum(auto.candidates)} / 试编译 ${fmtNum(auto.tried)} ·
      <span class="muted">启发式边界，生产使用请用符号表复核</span></div>` : '';
  const unwindHtml = (vz.unwind && vz.unwind.enabled) ? `
    <div class="hint" style="margin-top:6px">异常展开元数据：<b>已生成</b> ·
      .pdata ${fmtNum(vz.unwind.pdata_entries)} 条（原表 ${fmtNum(vz.unwind.original_entries)} + stub ${fmtNum(vz.unwind.stub_entries)}）·
      <span class="muted">带 SEH/EH 处理器的函数会自动跳过虚拟化</span></div>` : '';
  const vzHtml = vz.skipped ? (vz.skipped === 'unwind' ? `
    <div class="hint" style="margin-top:8px">目标代码虚拟化：<b>已跳过</b> ·
      清单中的函数都带 SEH/EH 异常处理器或链式展开，虚拟化会丢失异常处理语义；
      VM 外壳保护已完整生效。${esc(String(vz.unwind_note || ''))}</div>
    ${autoHtml}` : vz.skipped === 'auto_no_candidates' ? `
    <div class="hint" style="margin-top:8px">目标代码虚拟化：<b>已跳过</b> ·
      自动函数发现未找到可编译的函数区间；VM 外壳保护已完整生效。</div>
    ${autoHtml}` : `
    <div class="hint" style="margin-top:8px">目标代码虚拟化：<b>已跳过</b> ·
      输入文件含压缩壳节区（${esc((vz.packed_sections || []).join(', '))}），文件里没有原始机器码可虚拟化；
      VM 外壳保护已完整生效。如需函数级虚拟化，请对未加壳的原程序操作。</div>
    `) : vz.enabled ? `
    <div class="hint" style="margin-top:8px">目标代码虚拟化：<b>已生效</b> ·
      新节区 ${esc(String(vz.section))} · stub ${esc(String(vz.stub_rva))} ·
      私有 ISA ${fmtNum(vz.bc_bytes)}B · 函数头 ${esc(String(vz.patched_head))} ·
      运行内核已内嵌（单文件成品）</div>${autoHtml}${unwindHtml}` : '';
  const ob = v.obfuscation || {};
  const cShellHtml = v.c_shell ? `
    <div class="hint" style="margin-top:8px">外壳类型：<b>C 引导器</b>（零 Python · 构建期无需 PyInstaller）·
      反调试/反分析/加固释放由 C 引导器原生实现 · 误报审计
      <b>${esc(String(v.av_score == null ? '-' : v.av_score))}/100</b>
      ${v.note ? `<br><span class="muted">${esc(v.note)}</span>` : ''}</div>` : '';
  const vmpHtml = v.enabled ? `
    <div class="hint" style="margin-top:8px">VMP 对标：${esc(String((v.capabilities || []).length))} 项能力 ·
      误报审计 <b>${esc(String(v.av_score == null ? '-' : v.av_score))}/100</b> ·
      常量加密 ${esc(String((ob.sealed || []).length))} 处 ·
      死块 ${esc(String(ob.dead_blocks || 0))} ·
      整数分裂 ${esc(String(ob.split_ints || 0))} ·
      离线授权 ${v.license ? '已签发' : '未启用'}${v.av_issues && v.av_issues.length ? ` · 待优化：${esc(v.av_issues.join('、'))}` : ''}</div>
    ${v.parity ? `<details style="margin-top:6px"><summary class="link">查看 VMP 对标矩阵</summary>
      <pre style="white-space:pre-wrap;font-size:11.5px;line-height:1.5">${esc(v.parity)}</pre></details>` : ''}` : '';
  box.innerHTML = `
    <div class="stat-row">
      <div class="stat"><i>原始大小</i><b>${fmtNum(r.size_before)}</b></div>
      <div class="stat"><i>加壳后</i><b>${fmtNum(r.size_after)}</b></div>
      <div class="stat"><i>虚拟指令</i><b>${fmtNum(r.program_size)}</b></div>
      <div class="stat"><i>构建指纹</i><b style="font-family:Consolas,monospace;font-size:12.5px">${esc(r.fingerprint || '')}</b></div>
    </div>
    <div class="res-path">${esc(r.path)}</div>
    <div class="hint" style="margin-top:8px">运行时守卫：${guardSummary(r)}</div>
    ${iconHtml}
    ${vmpHtml}
    ${vzHtml}
    ${cShellHtml}
    <div class="btn-row">
      <button class="mini" id="btnVmFolder">打开所在文件夹</button>
      <a class="mini" style="line-height:26px;text-decoration:none"
         href="/api/file?path=${encodeURIComponent(r.path)}&t=${encodeURIComponent(TOKEN)}">下载</a>
    </div>`;
  Q('#btnVmFolder').addEventListener('click', () => openPath(r.path));
  focusResult(box);
}

/* ------------------------------ ELF（Linux） ------------------------------ */
let ELF_FUNCS = [];

function loadElf() {
  const f = S.files.elf;
  Q('#drop-elf').hidden = true;
  Q('#ws-elf').hidden = false;
  Q('#elfResult').hidden = true;
  Q('#elfFileInfo').textContent = `${f.name} · ${fmtSize(f.size)}`;
  Q('#elfOutdir').value = S.outdir || '';
  Q('#elfOutname').value = stem(f.name) + '.csvmpayload';
  Q('#elfRanges').value = '';
  Q('#elfSearch').value = '';
  ELF_FUNCS = [];
  Q('#elfFuncs').innerHTML = '<p class="hint" style="margin:8px 10px">点右上角「扫描符号」读取函数清单</p>';
  Q('#elfFnInfo').textContent = '未扫描';
  Q('#elfMeta').innerHTML = '<div class="meta-item"><i>架构</i><b>解析中…</b></div>';
  scanElf();
}

function elfMetaHtml(d) {
  const items = [
    ['架构', d.arch],
    ['类型', d.type],
    ['入口', '0x' + (d.entry || 0).toString(16)],
    ['符号表', d.stripped ? '已 strip' : '可用'],
    ['可保护函数', d.protectable + ' 个'],
    ['节区头', d.sections + ' 个'],
  ];
  return items.map(([k, v]) => `<div class="meta-item"><i>${esc(k)}</i><b>${esc(String(v))}</b></div>`).join('');
}

async function scanElf() {
  const f = S.files.elf;
  if (!f) return;
  Q('#elfFnInfo').textContent = '扫描中…';
  try {
    const d = await runJob('/api/elf/inspect', { fid: f.fid }, null);
    Q('#elfMeta').innerHTML = elfMetaHtml(d);
    ELF_FUNCS = d.functions || [];
    renderElfFuncs();
    const ready = ELF_FUNCS.filter((x) => x.ok).length;
    Q('#elfFnInfo').textContent = d.stripped
      ? '无符号表，请用 RVA 指定'
      : `${ELF_FUNCS.length} 个符号 · ${ready} 个可直接编译`;
    Q('#elfBackend').textContent = '内核 ' + (d.kernel || 'CyrilShield.CodeVM');
  } catch (e) {
    Q('#elfFnInfo').textContent = '扫描失败';
    Q('#elfFuncs').innerHTML = `<p class="hint" style="margin:8px 10px;color:#b91c1c">${esc(e.message)}</p>`;
  }
}

function renderElfFuncs() {
  const kw = Q('#elfSearch').value.trim().toLowerCase();
  const list = ELF_FUNCS.filter((f) => !kw || f.name.toLowerCase().includes(kw));
  const box = Q('#elfFuncs');
  if (!list.length) {
    box.innerHTML = `<p class="hint" style="margin:8px 10px">${ELF_FUNCS.length
      ? '没有匹配的函数名' : '这个文件没有可读的函数符号（已 strip），请用下方「RVA:长度」指定'}</p>`;
    return;
  }
  box.innerHTML = list.map((f, i) => {
    const idx = ELF_FUNCS.indexOf(f);
    return `<label class="fn-row${f.ok ? '' : ' off'}" title="${esc(f.name)}">
      <input type="checkbox" data-idx="${idx}" ${f.ok ? '' : 'disabled'}>
      <span class="fn-name">${esc(f.name)}</span>
      <span class="fn-meta">0x${f.rva.toString(16)}</span>
      <span class="fn-meta">${f.size ? f.size + 'B' : '无大小'}</span>
      ${f.endbr64 ? '<span class="tag ok">endbr64</span>' : ''}
    </label>`;
  }).join('');
}

Q('#elfSearch').addEventListener('input', () => renderElfFuncs());
Q('#btnElfScan').addEventListener('click', () => scanElf());
Q('#btnElfAll').addEventListener('click', () => {
  QA('#elfFuncs input[type=checkbox]').forEach((c) => { if (!c.disabled) c.checked = true; });
});
Q('#btnElfNone').addEventListener('click', () => {
  QA('#elfFuncs input[type=checkbox]').forEach((c) => { c.checked = false; });
});
Q('#btnElfOpen').addEventListener('click', () => openPath(Q('#elfOutdir').value.trim()));

Q('#btnElfRun').addEventListener('click', async () => {
  const f = S.files.elf;
  if (!f) return;
  const symbols = QA('#elfFuncs input[type=checkbox]')
    .filter((c) => c.checked).map((c) => ELF_FUNCS[parseInt(c.dataset.idx, 10)].name);
  const raw = Q('#elfRanges').value.trim();
  const ranges = raw ? raw.split(',').map((x) => x.trim()).filter(Boolean) : [];
  const bad = ranges.filter((x) => !/^(0x)?[0-9a-fA-F]+:\s*(len=)?[0-9]+$/.test(x));
  if (bad.length) {
    stamp(`RVA 格式有误：${bad.join('、')}（应为 0x19f0:221）`, 'ERR');
    return;
  }
  if (!symbols.length && !ranges.length) {
    stamp('至少勾选一个函数，或填写一条 RVA:长度', 'ERR');
    return;
  }
  stamp(`开始虚拟化 ${symbols.length + ranges.length} 个函数…`, 'DIM');
  try {
    const r = await runJob('/api/elf/protect', {
      fid: f.fid,
      opts: { symbols, ranges },
      outdir: Q('#elfOutdir').value.trim(),
      outname: Q('#elfOutname').value.trim(),
    }, (res) => renderElfResult(res));
    if (r) stamp('payload 已生成', 'OK');
  } catch (e) { /* runJob 已记录 */ }
});

function renderElfResult(r) {
  const box = Q('#elfResult');
  box.hidden = false;
  const okList = (r.functions || []).map((f) =>
    `<div class="fn-row" style="padding-left:4px"><span class="fn-name">${esc(f.name)}</span>
       <span class="fn-meta">0x${f.rva.toString(16)}</span>
       <span class="fn-meta">${f.size}B → ${f.bc}B</span></div>`).join('');
  const skipped = (r.skipped || []).map((f) =>
    `<div class="fn-row off" style="padding-left:4px"><span class="fn-name">${esc(f.name)}</span>
       <span class="fn-meta">0x${f.rva.toString(16)}</span>
       <span class="fn-meta" style="flex:2">${esc(f.reason)}</span></div>`).join('');
  box.innerHTML = `
    <div class="res-head"><b>生成结果</b></div>
    <div class="stat-row">
      <div class="stat"><i>payload</i><b>${fmtSize(r.payload_size)}</b></div>
      <div class="stat"><i>已虚拟化</i><b>${r.count} 个函数</b></div>
      <div class="stat"><i>跳过</i><b>${(r.skipped || []).length} 个</b></div>
    </div>
    <pre class="res-path">${esc(r.path || '')}</pre>
    ${okList ? `<div style="margin-top:8px">${okList}</div>` : ''}
    ${skipped ? `<div class="hint warn" style="margin-top:10px">以下函数超出内核指令子集，未写入 payload：
      </div><div>${skipped}</div>` : ''}
    <div class="hint" style="margin-top:10px">在 Linux 目标机上运行（原文件不改一个字节）：</div>
    <pre class="res-path" style="white-space:pre-wrap;line-height:1.6;background:#f8fafd;padding:8px 10px;border-radius:8px">${esc(
      `LD_PRELOAD=/path/to/libcsvm_elfhook.so \\\n  CSVM_ELF_PAYLOAD=/path/to/${r.name || 'prog.csvmpayload'} \\\n  ./${r.target_name || 'your_program'}`)}</pre>
    <p class="hint">把 payload 与 <code>libcsvm_elfhook.so</code> 一起拷到 Linux 目标机，
      路径按实际位置替换（加 <code>CSVM_ELF_DEBUG=1</code> 可打印每次 VM 调用与退出统计）。</p>
    ${r.hook_found ? '' : '<p class="hint warn">本机没找到 libcsvm_elfhook.so，运行前需在 Linux 上用 native/build.sh 构建。</p>'}
    <div class="btn-row">
      <button class="mini" id="btnElfFolder">打开所在文件夹</button>
      <a class="mini" style="line-height:26px;text-decoration:none"
         href="/api/file?path=${encodeURIComponent(r.path)}&t=${encodeURIComponent(TOKEN)}">下载 payload</a>
    </div>`;
  Q('#btnElfFolder').addEventListener('click', () => openPath(r.path));
  focusResult(box);
}

/* ------------------------------ APK 加固 ------------------------------ */
async function loadApk() {
  const f = S.files.apk;
  Q('#drop-apk').hidden = true;
  Q('#ws-apk').hidden = false;
  Q('#apkResult').hidden = true;
  Q('#apkFileInfo').textContent = `${f.name} · ${fmtSize(f.size)}`;
  Q('#apkOutdir').value = S.outdir || '';
  Q('#apkOutname').value = stem(f.name) + '_protected.apk';
  Q('#apkMeta').innerHTML = '<div class="meta-item"><i>状态</i><b>解析中…</b></div>';
  Q('#apkWarn').hidden = true;
  try {
    await runJob('/api/apk/inspect', { fid: f.fid }, (r) => renderApkInfo(r));
  } catch (e) { /* 已在日志里提示 */ }
}

function renderApkInfo(r) {
  const sig = r.signature || {};
  const abis = Object.entries(r.abis || {});
  const items = [
    ['包名', r.package || '—'],
    ['版本', `${r.version_name || '—'}（${r.version_code || 0}）`],
    ['SDK', `min ${r.min_sdk == null ? '—' : r.min_sdk} / target ${r.target_sdk == null ? '—' : r.target_sdk}`],
    ['安装包大小', fmtSize(r.size)],
    ['dex', `${r.dexes.length} 个 · ${fmtSize(r.entry.dex_bytes)}`],
    ['条目总数', fmtNum(r.entry.count)],
    ['原生库 ABI', abis.length ? abis.map(([k, v]) => `${k}×${v}`).join('　') : '无'],
    ['签名方案', (sig.schemes || []).length ? sig.schemes.join(' + ') : '未签名'],
    ['签名证书', sig.dn || '—'],
    ['入口 Application', r.app_class || '系统默认'],
  ];
  Q('#apkMeta').innerHTML = items.map(([k, v]) =>
    `<div class="meta-item"><i>${esc(k)}</i><b title="${esc(v)}">${esc(v)}</b></div>`).join('');

  const warn = Q('#apkWarn');
  const list = (r.warnings || []).slice();
  if (list.length) {
    warn.hidden = false;
    warn.innerHTML = list.map((w) => `<span class="tag warn">${esc(w)}</span>`).join('');
  } else {
    warn.hidden = true;
  }
  const t = r.tools || {};
  Q('#apkTools').textContent = t.ok
    ? `工具链 ${t.build_tools} · ${t.platform}`
    : '工具链缺失：' + (t.missing || []).join('、');

  S.apkIcons = r.icons || { candidates: [] };
  if (S.icons.apk) renderApkCands();
}

QA('#apkLevel .opt').forEach((l) => l.addEventListener('click', () => {
  QA('#apkLevel .opt').forEach((x) => x.classList.remove('active'));
  l.classList.add('active');
}));

Q('#apkKeystore').addEventListener('input', () => {
  Q('#apkKsRow').hidden = !Q('#apkKeystore').value.trim();
});
Q('#btnApkOpen').addEventListener('click', () => openPath(Q('#apkOutdir').value.trim()));

Q('#btnApkRun').addEventListener('click', async () => {
  const f = S.files.apk;
  if (!f) return;
  const level = ((QA('input[name="apkLevel"]').find((x) => x.checked) || {}).value) || 'strong';
  let iconFid = '';
  let iconEntries = [];
  if (S.icons.apk) {
    iconEntries = checkedApkEntries();
    if (!iconEntries.length) {
      stamp('未勾选任何图标资源；请至少选一项，或点「移除」不替换图标', 'ERR');
      return;
    }
    iconFid = S.icons.apk.fid;
  }
  const opts = {
    level,
    stripDebug: Q('#apkStripDebug').checked,
    hardenBackup: Q('#apkBackup').checked,
    debug: Q('#apkDebug').checked,
    wipeMemory: Q('#apkWipeMemory').checked,
    checkSignature: Q('#apkCheckSignature').checked,
    antiDebug: Q('#apkAntiDebug').checked,
    antiEmulator: Q('#apkAntiEmulator').checked,
    shards: parseInt(Q('#apkShards').value, 10) || 0,
    keystore: Q('#apkKeystore').value.trim(),
    ksAlias: Q('#apkKsAlias').value.trim(),
    ksPass: Q('#apkKsPass').value,
    keyPass: Q('#apkKeyPass').value,
    iconFid,
    iconEntries,
    iconFit: Q('#apkIconFit').value,
  };
  stamp(`开始加固：${f.name}（${level}）`, 'DIM');
  try {
    const res = await runJob('/api/apk/protect', {
      fid: f.fid, opts,
      outdir: Q('#apkOutdir').value.trim(),
      outname: Q('#apkOutname').value.trim(),
    }, (r) => renderApkResult(r));
    if (res) stamp('加固完成，产物自检全部通过', 'OK');
  } catch (e) { /* ignore */ }
});

function renderApkResult(r) {
  const box = Q('#apkResult');
  box.hidden = false;
  const checks = Object.entries(r.checks || {})
    .map(([k, v]) => `<span class="tag ${v ? 'ok' : 'warn'}">${esc(k)}${v ? ' ✓' : ' ✗'}</span>`).join('');
  const caps = [];
  if (r.virtualized) caps.push(`<span class="tag ok">引导链 VM 虚拟化 · ${fmtNum(r.vm_instructions)} 条指令（含 ${fmtNum(r.vm_junk)} 垃圾）✓</span>`);
  if (r.shards) caps.push(`<span class="tag ok">乱序分片 ${fmtNum(r.shards)} 片 ✓</span>`);
  if (r.wipe_memory) caps.push('<span class="tag ok">明文擦除 ✓</span>');
  if (r.sig_bound) caps.push('<span class="tag ok">签名已绑定 ✓</span>');
  if (r.guard_mode & 1) caps.push('<span class="tag ok">反调试 ✓</span>');
  if (r.guard_mode & 4) caps.push('<span class="tag ok">反模拟器 ✓</span>');
  const iconList = r.icons || [];
  const iconHtml = iconList.length ? `
    <div class="tag-row"><span class="tag ok">图标替换 ${iconList.length} 项 ✓</span></div>
    <details style="margin-top:8px"><summary class="link">查看图标替换明细</summary>
      <pre style="white-space:pre-wrap;font-size:11.5px;line-height:1.5">${esc(
        iconList.map((x) => `${x.path}  ${x.width}×${x.height} · ${(x.format || '').toUpperCase()}  ${x.before} → ${x.after} 字节`).join('\n'))}</pre>
    </details>` : '';
  box.innerHTML = `
    <div class="stat-row">
      <div class="stat"><i>加固前</i><b>${fmtNum(r.size_before)}</b></div>
      <div class="stat"><i>加固后</i><b>${fmtNum(r.size_after)}</b></div>
      <div class="stat"><i>载荷分段</i><b>${fmtNum(r.payload_parts)}</b></div>
      <div class="stat"><i>耗时</i><b>${r.elapsed} 秒</b></div>
    </div>
    <div class="tag-row">${checks}</div>
    ${caps.length ? `<div class="tag-row">${caps.join('')}</div>` : ''}
    ${iconHtml}
    <div class="res-path">${esc(r.path)}</div>
    <div class="btn-row">
      <button class="mini" id="btnApkFolder">打开所在文件夹</button>
      <a class="mini" style="line-height:26px;text-decoration:none"
         href="/api/file?path=${encodeURIComponent(r.path)}&t=${encodeURIComponent(TOKEN)}">下载</a>
    </div>`;
  Q('#btnApkFolder').addEventListener('click', () => openPath(r.path));
  focusResult(box);
}

/* ------------------------------ 环境信息 ------------------------------ */
async function loadEnv() {
  try {
    const e = await apiGet('/api/env');
    S.outdir = e.outdir;
    const u = Q('#envUpx');
    if (e.upx.found) {
      u.innerHTML = `<i class="dot"></i>${esc(e.upx.version.replace(/^upx\s*/i, 'UPX '))}`;
      u.title = e.upx.path;
    } else {
      u.classList.add('bad');
      u.innerHTML = '<i class="dot"></i>内置 UPX 缺失';
    }
    const b = Q('#envBackend');
    if (e.backend.ok) {
      b.innerHTML = '<i class="dot"></i>编译后端就绪';
      b.title = e.backend.info;
    } else {
      b.classList.add('bad');
      b.innerHTML = '<i class="dot"></i>VM 编译后端不可用';
      b.title = e.backend.info;
    }
    Q('#vmBackend').textContent = e.backend.ok ? '外壳编译器就绪' : '需要 Python + PyInstaller';
    const a = Q('#envAndroid');
    const aBundled = !!(e.android && e.android.bundled);
    if (e.android && e.android.ok) {
      a.innerHTML = `<i class="dot"></i>Android ${esc(e.android.build_tools)}${aBundled ? ' · 内置' : ''}`;
      a.title = 'platform ' + e.android.platform + (aBundled ? '（随程序分发，无需安装 SDK）' : '');
    } else if (a) {
      a.classList.add('bad');
      a.innerHTML = '<i class="dot"></i>Android 工具链缺失';
      a.title = (e.android && e.android.missing || []).join('、');
    }
    Q('#apkTools').textContent = (e.android && e.android.ok)
      ? `${aBundled ? '内置工具链' : '本机工具链'} ${e.android.build_tools} · ${e.android.platform}`
      : '未检测到 Android SDK';
    Q('#aboutVer').textContent = 'v' + APP_VERSION + ' · Python ' + e.python;
    const cv = e.codevm || {};
    const ef = e.elf || {};
    const cvel = Q('#envCodevm');
    if (cvel) {
      if (cv.ok) {
        cvel.innerHTML = `<i class="dot"></i>虚拟化内核 · ABI 3${ef.hook_found ? ' · ELF 挂钩就绪' : ''}`;
        cvel.title = (cv.kernel || '') + (cv.runtime ? ' / ' + cv.runtime : '')
          + (ef.hook_found ? '' : '（缺 libcsvm_elfhook.so，ELF 产物只能在 Linux 上运行）');
      } else {
        cvel.classList.add('bad');
        cvel.innerHTML = '<i class="dot"></i>虚拟化内核缺失';
        cvel.title = cv.error || '';
      }
    }
    Q('#aboutGrid').innerHTML = [
      metaItem('内置 UPX', esc(e.upx.version.split(/[\r\n]/)[0] || '未检测到')),
      metaItem('编译后端', esc(e.backend.ok ? '可用' : '不可用')),
      metaItem('Android 工具链', esc(e.android && e.android.ok
        ? e.android.build_tools + (aBundled ? '（内置）' : '')
        : '不可用')),
      metaItem('虚拟化内核', esc(cv.ok
        ? (cv.runtime_release ? '就绪（发布版 8 导出）' : '就绪（构建版）')
        : '缺失')),
      metaItem('ELF 挂钩库', esc(ef.hook_found ? '已随包提供' : '缺失')),
      metaItem('运行方式', e.frozen ? '独立 EXE' : 'Python 源码'),
      metaItem('默认输出', esc(e.outdir)),
    ].join('');
    ['#iconOutdir', '#upxOutdir', '#vmOutdir', '#elfOutdir', '#apkOutdir'].forEach((sel) => {
      if (!Q(sel).value) Q(sel).value = e.outdir;
    });
  } catch (err) {
    stamp('环境信息读取失败：' + err.message, 'WARN');
  }
}

/* ------------------------------ 控制 ------------------------------ */
Q('#btnClear').addEventListener('click', () => { LOG_BOX().innerHTML = ''; });
Q('#btnFold').addEventListener('click', () => {
  const c = Q('#console');
  c.classList.toggle('folded');
  Q('#btnFold').textContent = c.classList.contains('folded') ? '展开' : '收起';
});
Q('#btnQuit').addEventListener('click', async () => {
  try { await apiPost('/api/quit', {}); } catch (e) { /* ignore */ }
  document.body.innerHTML =
    '<div style="height:100vh;display:flex;align-items:center;justify-content:center;' +
    'font-family:Segoe UI,sans-serif;color:#64748b;font-size:14px">' +
    '服务已停止，可以关闭这个窗口了。</div>';
  setTimeout(() => window.close(), 200);
});

setInterval(() => { fetch('/api/ping', { method: 'POST' }).catch(() => {}); }, 5000);

/* ------------------------------ 启动 ------------------------------ */
syncLevel();
syncUpxMode();
go('icon');
loadEnv();
stamp('CyrilShield v' + APP_VERSION + ' 就绪，把文件拖进窗口即可开始', 'OK');
