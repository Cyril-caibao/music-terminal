/* CyrilShield · WebTerm 插件前端
 * 与宿主同源，后端接口见 bridge.py（前缀 /cs/）。
 */
(function () {
  'use strict';

  var Q = function (s, r) { return (r || document).querySelector(s); };
  var state = {
    file: null, fid: '', tool: 'icon', job: '', timer: 0,
    running: false, rendered: 0, env: null, apk: null, dir: '', files: 0
  };

  var TIP = '先选一个文件';

  // ---------------------------------------------------------------- 工具
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function fmtSize(n) {
    n = Number(n) || 0;
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }

  function ext(name) {
    var i = String(name || '').lastIndexOf('.');
    return i < 0 ? '' : String(name).slice(i).toLowerCase();
  }

  function val(id) { var el = Q(id); return el ? String(el.value || '').trim() : ''; }
  function chk(id) { var el = Q(id); return !!(el && el.checked); }

  function log(msg, tag) {
    var el = Q('#log');
    var empty = Q('.empty', el);
    if (empty) empty.remove();
    el.appendChild(line(0, tag || 'INFO', msg));
    el.scrollTop = el.scrollHeight;
  }

  function line(t, tag, msg) {
    var s = document.createElement('span');
    s.className = 'l';
    s.innerHTML = '<span class="t">' + (Number(t) || 0).toFixed(2) + 's</span>' +
      '<span class="g ' + esc(tag) + '">' + esc(msg) + '</span>';
    return s;
  }

  function hint(s) {
    var el = Q('#hint');
    el.textContent = s;
    el.title = s;
  }

  function setBar(p) {
    var bar = Q('#bar'), inner = Q('#barIn');
    if (!bar || !inner) return;
    if (p == null) { bar.hidden = true; inner.style.width = '0'; return; }
    bar.hidden = false;
    inner.style.width = Math.max(0, Math.min(1, p)) * 100 + '%';
  }

  function resetResult() {
    var r = Q('#result');
    r.hidden = true;
    r.classList.remove('bad');
    state.dir = '';
    state.files = 0;
  }

  // ---------------------------------------------------------------- 主题
  function syncTheme() {
    var light = false;
    try {
      var b = parent.document.body;
      light = !!(b && /(^|\s)light(\s|$)/.test(b.className || ''));
      if (!light && parent.document.documentElement) {
        light = parent.document.documentElement.getAttribute('data-theme') === 'light';
      }
    } catch (e) { /* 非同源就保持默认深色 */ }
    document.body.classList.toggle('light', light);
  }

  // ---------------------------------------------------------------- 环境
  function loadEnv() {
    fetch('/cs/env').then(function (r) { return r.json(); }).then(function (d) {
      state.env = d;
      Q('#ver').textContent = d.version ? 'v' + d.version : '—';
      var t = Q('#tagTools');
      if (!d.ok) {
        t.textContent = '未找到项目';
        t.className = 'tag bad';
        t.title = d.error || '';
        hint(d.error || '');
        log(d.error || '未找到 CyrilShield 项目', 'ERR');
        return;
      }
      var tools = d.tools || {};
      if (tools.ok) {
        t.textContent = 'Android ' + (tools.build_tools || '就绪');
        t.className = 'tag ok';
        t.title = (tools.bundled ? '内置工具链（随插件分发）' : '系统工具链')
          + '\nandroid.jar：' + (tools.platform || '')
          + '\nd8 / apksigner / zipalign 已就位';
      } else {
        t.textContent = 'Android 缺工具链';
        t.className = 'tag warn';
        t.title = '缺少：' + ((tools.missing || []).join('、') || '未知');
      }
      var u = Q('#tagUpx');
      u.textContent = (d.upx && d.upx.ok) ? ('UPX ' + (d.upx.version || '就绪')) : 'UPX 缺失';
      u.className = 'tag ' + (d.upx && d.upx.ok ? 'ok' : 'bad');
      u.title = (d.upx && d.upx.path) || '';
      var v = Q('#tagVm');
      v.textContent = (d.vm && d.vm.ok) ? 'VM 就绪' : 'VM 缺 Python';
      v.className = 'tag ' + (d.vm && d.vm.ok ? 'ok' : 'warn');
      v.title = (d.vm && d.vm.msg) || '';
      log('插件：' + (d.plugin || '—'), 'DIM');
      log('项目：' + d.home, 'DIM');
      log('输出：' + d.outdir, 'DIM');
    }).catch(function (e) {
      Q('#tagTools').textContent = '后端未加载';
      Q('#tagTools').className = 'tag bad';
      Q('#tagTools').title = '需要含 /cs/ 插件路由的 server.py';
      hint('后端未加载：请用新版 server.py 重启 WebTerm');
      log('连接后端失败：' + e, 'ERR');
      log('确认宿主 server.py 已包含 components/*/bridge.py 的加载逻辑，然后重启 WebTerm。', 'WARN');
    });
  }

  // ---------------------------------------------------------------- 参数面板
  function renderOpts() {
    var t = state.tool;
    var html = '';
    if (t === 'icon') {
      html =
        '<div class="note">直接解析 PE 资源段，按尺寸与色深分组导出。PNG 需要 Pillow，未装时只能导出 ICO。</div>' +
        '<div class="chks">' +
        '<label class="chk"><input type="checkbox" id="icoIco" checked>导出 ICO（多尺寸）</label>' +
        '<label class="chk"><input type="checkbox" id="icoPng" checked>导出 PNG</label>' +
        '</div>';
    } else if (t === 'upx') {
      var lv = '';
      for (var i = 1; i <= 9; i++) lv += '<option value="' + i + '"' + (i === 7 ? ' selected' : '') + '>' + i + '</option>';
      html =
        '<div class="note">内置 UPX 5.3.0，进程调用。压缩会改变文件字节，签名失效；UPX 特征本身也容易被启发式误报。</div>' +
        '<div class="row"><label class="k">压缩级别</label><select id="upxLevel">' + lv + '</select></div>' +
        '<div class="chks">' +
        '<label class="chk"><input type="checkbox" id="upxBest">--best</label>' +
        '<label class="chk"><input type="checkbox" id="upxBrute">--brute</label>' +
        '<label class="chk"><input type="checkbox" id="upxLzma">--lzma</label>' +
        '</div>' +
        '<div class="chks">' +
        '<label class="chk"><input type="checkbox" id="upxNoRes">不压缩资源段</label>' +
        '<label class="chk"><input type="checkbox" id="upxKeepReloc">保留重定位</label>' +
        '</div>' +
        '<div class="row"><label class="k">图标资源</label><select id="upxIcons">' +
        '<option value="2" selected>默认</option><option value="1">压缩</option><option value="0">不压缩</option>' +
        '</select></div>' +
        '<div class="row"><label class="k">附加参数</label>' +
        '<input class="input" id="upxExtra" placeholder="如 --backup" spellcheck="false"></div>';
    } else if (t === 'vm') {
      var vmOk = !!(state.env && state.env.vm && state.env.vm.ok);
      html =
        '<div class="note">把 x86 代码翻译成自定义字节码，由内置虚拟机解释执行。' +
        '每次构建随机化 magic、salt、指令编号与符号名。' +
        (vmOk ? '' : '<br><b>当前缺少 PyInstaller，无法构建。</b>') + '</div>' +
        '<div class="row"><label class="k">口令</label>' +
        '<input class="input" type="password" id="vmPass" placeholder="留空 = 每次随机密钥" spellcheck="false"></div>' +
        '<div class="row"><label class="k">混淆强度</label><select id="vmJunk">' +
        '<option value="1" selected>标准</option><option value="2">更强</option><option value="0">不混淆</option>' +
        '</select></div>' +
        '<div class="chks">' +
        '<label class="chk"><input type="checkbox" id="vmVerify" checked>构建后自检</label>' +
        '<label class="chk"><input type="checkbox" id="vmArgsFwd" checked>转发启动参数</label>' +
        '<label class="chk"><input type="checkbox" id="vmIcon" checked>保留图标</label>' +
        '</div>' +
        '<div class="row"><label class="k">运行参数</label>' +
        '<input class="input" id="vmArgs" placeholder="附加给原程序的参数，可留空" spellcheck="false"></div>';
    } else {
      html =
        '<div class="info" id="apkInfo">拖入 APK 后自动解析包名、SDK、dex 与签名。</div>' +
        '<div class="row"><label class="k">强度</label><select id="apkLevel">' +
        '<option value="standard" selected>standard（推荐）</option>' +
        '<option value="strong">strong</option>' +
        '<option value="compat">compat</option>' +
        '</select></div>' +
        '<div class="chks">' +
        '<label class="chk"><input type="checkbox" id="apkStrip" checked>移除 debuggable</label>' +
        '<label class="chk"><input type="checkbox" id="apkBackup" checked>allowBackup=false</label>' +
        '<label class="chk"><input type="checkbox" id="apkDebug">保留壳诊断日志</label>' +
        '</div>' +
        '<div class="sep">签名</div>' +
        '<div class="row"><label class="k">密钥库</label>' +
        '<input class="input" id="apkKs" placeholder="留空 = 内置默认密钥库" spellcheck="false"></div>' +
        '<div class="row"><label class="k">别名</label>' +
        '<input class="input" id="apkAlias" placeholder="留空 = 随机别名" spellcheck="false"></div>' +
        '<div class="row"><label class="k">口令</label>' +
        '<input class="input" type="password" id="apkPass" placeholder="密钥库口令" spellcheck="false"></div>';
    }

    var box = Q('#opts');
    box.innerHTML = '<div id="toolOpts">' + html + '</div>' +
      '<div class="sep">输出</div>' +
      '<div class="row"><label class="k">目录</label>' +
      '<input class="input" id="outdir" placeholder="留空 = 桌面\\CyrilShield-Output" spellcheck="false"></div>' +
      '<div class="row" id="outnameRow"><label class="k">文件名</label>' +
      '<input class="input" id="outname" placeholder="留空 = 自动命名" spellcheck="false"></div>';

    Q('#outdir').value = state.outdir || '';
    Q('#outname').value = state.outname || '';
    Q('#outnameRow').hidden = (t === 'icon');
    Q('#outdir').addEventListener('input', function () { state.outdir = this.value; });
    Q('#outname').addEventListener('input', function () { state.outname = this.value; });

    if (t === 'apk' && state.apk) showApk(state.apk);
    updateGo();
  }

  function switchTool(t) {
    state.tool = t;
    Array.prototype.forEach.call(Q('#tabs').children, function (b) {
      b.classList.toggle('on', b.dataset.tool === t);
    });
    renderOpts();
  }

  // ---------------------------------------------------------------- APK 信息
  function showApk(d) {
    var el = Q('#apkInfo');
    if (!el) return;
    if (!d || !d.package) { el.textContent = '未能解析该 APK'; return; }
    var sig = d.signature || {};
    var lines = [
      ['包名', d.package || '—'],
      ['版本', (d.version_name || '?') + '  (' + (d.version_code || '?') + ')'],
      ['SDK', (d.min_sdk || '?') + ' → ' + (d.target_sdk || '?')],
      ['dex', (d.dexes || []).length + ' 个 · ' + fmtSize((d.entry || {}).dex_bytes)],
      ['签名', (sig.dn || '未签名')],
      ['入口类', d.app_class || '（无自定义 Application）']
    ];
    el.innerHTML = lines.map(function (kv) {
      return '<span class="k">' + esc(kv[0]) + '  </span>' + esc(kv[1]);
    }).join('\n');
    if (d.warnings && d.warnings.length) {
      el.innerHTML += '\n<span class="k">提示  </span>' + esc(d.warnings.join('；'));
    }
  }

  // ---------------------------------------------------------------- 上传
  function upload(file) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', '/cs/upload?name=' + encodeURIComponent(file.name));
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable) setBar(e.loaded / e.total);
      };
      xhr.onload = function () {
        var r = null;
        try { r = JSON.parse(xhr.responseText); } catch (e) { /* ignore */ }
        if (r && r.ok) { setBar(1); resolve(r); }
        else reject(new Error((r && r.error) || ('上传失败 HTTP ' + xhr.status)));
      };
      xhr.onerror = function () { reject(new Error('上传失败（网络错误）')); };
      xhr.send(file);
    });
  }

  function pickFile(file) {
    if (!file) return;
    stopPoll();
    state.job = '';
    state.running = false;
    Q('#go').textContent = '开始';
    state.file = file;
    state.fid = '';
    state.apk = null;
    state.job = '';
    resetResult();
    Q('#dropin').hidden = true;
    Q('#filecard').hidden = false;
    Q('#fname').textContent = file.name;
    Q('#fmeta').textContent = fmtSize(file.size) + ' · ' + (ext(file.name).slice(1) || '未知').toUpperCase();
    Q('#fico').textContent = ext(file.name) === '.apk' ? '▤' : '▣';
    Q('#log').innerHTML = '';
    hint('上传中…');
    updateGo();

    var e = ext(file.name);
    if (e === '.apk' && state.tool !== 'apk') switchTool('apk');
    if (e !== '.apk' && state.tool === 'apk') switchTool('icon');

    upload(file).then(function (r) {
      state.fid = r.fid;
      setBar(null);
      log('已载入 ' + r.name + '（' + fmtSize(r.size) + '）', 'OK');
      hint('已就绪，Ctrl+Enter 开始');
      updateGo();
      if (ext(file.name) === '.apk') {
        var box = Q('#apkInfo');
        if (box) box.textContent = '解析包结构…';
        inspect();
      }
    }).catch(function (err) {
      setBar(null);
      hint(err.message);
      log(err.message, 'ERR');
      updateGo();
    });
  }

  function inspect() {
    if (!state.fid) return;
    fetch('/cs/run', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fid: state.fid, tool: 'inspect', opts: {} })
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (!d.ok) { log(d.error || '解析失败', 'ERR'); return; }
      watch(d.id, true);
    }).catch(function (e) { log('解析失败：' + e, 'ERR'); });
  }

  // ---------------------------------------------------------------- 执行
  function collect() {
    var t = state.tool, opts = {}, outdir = val('#outdir'), outname = val('#outname');
    state.outdir = outdir;
    state.outname = outname;
    if (t === 'icon') {
      opts = { ico: chk('#icoIco'), png: chk('#icoPng') };
      if (!opts.ico && !opts.png) throw new Error('至少选一种导出格式');
    } else if (t === 'upx') {
      opts = {
        level: Number(val('#upxLevel') || 7),
        best: chk('#upxBest'), brute: chk('#upxBrute'), lzma: chk('#upxLzma'),
        noRes: chk('#upxNoRes'), keepReloc: chk('#upxKeepReloc'),
        icons: Number(val('#upxIcons') || 2),
        extra: val('#upxExtra')
      };
    } else if (t === 'vm') {
      opts = {
        password: val('#vmPass'), junk: Number(val('#vmJunk') || 1),
        verify: chk('#vmVerify'), forwardArgs: chk('#vmArgsFwd'),
        keepIcon: chk('#vmIcon'), extraArgs: val('#vmArgs')
      };
    } else {
      opts = {
        level: val('#apkLevel') || 'standard',
        stripDebug: chk('#apkStrip'), hardenBackup: chk('#apkBackup'),
        debug: chk('#apkDebug'),
        keystore: val('#apkKs'), ksAlias: val('#apkAlias'), ksPass: val('#apkPass')
      };
    }
    return { fid: state.fid, tool: t, opts: opts, outdir: outdir, outname: outname };
  }

  function start() {
    if (state.running || !state.fid) return;
    var body;
    try { body = collect(); } catch (e) { hint(e.message); return; }
    resetResult();
    state.running = true;
    state.rendered = 0;
    Q('#log').innerHTML = '';
    Q('#go').disabled = true;
    Q('#go').textContent = '执行中…';
    Q('#clock').textContent = '';

    var toolName = { icon: '图标提取', upx: 'UPX 压缩', vm: 'VM 虚拟壳', apk: 'APK 加固' }[state.tool];
    log('开始：' + toolName, 'STEP');

    fetch('/cs/run', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (!d.ok) throw new Error(d.error || '任务启动失败');
      watch(d.id, false);
    }).catch(function (e) {
      state.running = false;
      Q('#go').disabled = false;
      Q('#go').textContent = '开始';
      hint(e.message);
      log(e.message, 'ERR');
    });
  }

  function stopPoll() {
    if (state.timer) { clearTimeout(state.timer); state.timer = 0; }
  }

  function watch(id, silent) {
    state.job = id;
    state.rendered = 0;
    var tick = function () {
      if (state.job !== id) return;
      fetch('/cs/job?id=' + encodeURIComponent(id)).then(function (r) { return r.json(); }).then(function (d) {
        if (state.job !== id) return;
        if (!d.ok) throw new Error(d.error || '任务丢失');
        var j = d.job;
        renderLogs(j.logs);
        Q('#clock').textContent = (j.elapsed || 0).toFixed(1) + 's';
        if (j.state === 'running') {
          state.timer = setTimeout(tick, 400);
        } else {
          finish(j, silent);
        }
      }).catch(function (e) {
        if (silent) return;
        log(e.message, 'ERR');
        state.running = false;
        Q('#go').disabled = false;
        Q('#go').textContent = '开始';
      });
    };
    tick();
  }

  function renderLogs(logs) {
    var el = Q('#log');
    logs = logs || [];
    for (var i = state.rendered; i < logs.length; i++) {
      var row = logs[i];
      el.appendChild(line(row[0], row[1], row[2]));
    }
    state.rendered = logs.length;
    el.scrollTop = el.scrollHeight;
  }

  function finish(j, silent) {
    stopPoll();
    state.running = false;
    Q('#go').disabled = false;
    Q('#go').textContent = '开始';
    var r = Q('#result');
    if (j.state === 'error') {
      r.hidden = false;
      r.classList.add('bad');
      Q('#rlabel').textContent = '失败';
      Q('#rpath').textContent = j.error || '未知错误';
      hint(j.error || '失败');
      log(j.error || '失败', 'ERR');
      return;
    }
    var res = j.result || {};
    if (silent) {                       // inspect：只更新信息卡
      state.apk = res;
      showApk(res);
      return;
    }
    state.dir = res.dir || (res.path ? res.path.replace(/[\\/][^\\/]+$/, '') : '');
    state.files = (j.files || []).length;
    r.hidden = false;
    r.classList.remove('bad');
    Q('#rlabel').textContent = '完成';
    Q('#rpath').textContent = res.path || res.dir || (j.files || [])[0] || '';
    Q('#download').hidden = state.files === 0;
    Q('#openDir').hidden = !state.dir;
    hint('用时 ' + (j.elapsed || 0).toFixed(1) + ' 秒');
  }

  // ---------------------------------------------------------------- 绑定
  function updateGo() {
    var ok = !!(state.fid && state.file && !state.running);
    var e = state.file ? ext(state.file.name) : '';
    if (state.fid) {
      if (state.tool === 'apk' && e !== '.apk') { ok = false; hint('APK 加固需要 .apk 文件'); }
      if (state.tool === 'upx' && e !== '.exe') { ok = false; hint('UPX 只能处理 .exe'); }
      if (state.tool === 'vm' && ['.exe', '.dll'].indexOf(e) < 0) { ok = false; hint('VM 壳需要 .exe'); }
    }
    Q('#go').disabled = !ok;
  }

  function bind() {
    var drop = Q('#drop');
    Q('#pick').addEventListener('click', function (ev) { ev.stopPropagation(); Q('#file').click(); });
    Q('#file').addEventListener('change', function () { pickFile(this.files && this.files[0]); });
    drop.addEventListener('click', function (ev) {
      if (ev.target.closest('button')) return;
      Q('#file').click();
    });

    ['dragenter', 'dragover'].forEach(function (n) {
      drop.addEventListener(n, function (e) {
        e.preventDefault(); e.stopPropagation();
        drop.classList.add('over');
      });
    });
    ['dragleave', 'drop'].forEach(function (n) {
      drop.addEventListener(n, function (e) {
        e.preventDefault(); e.stopPropagation();
        if (n === 'dragleave' && drop.contains(e.relatedTarget)) return;
        drop.classList.remove('over');
      });
    });
    drop.addEventListener('drop', function (e) {
      var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      pickFile(f);
    });
    window.addEventListener('dragover', function (e) { e.preventDefault(); });
    window.addEventListener('drop', function (e) { e.preventDefault(); });

    Q('#tabs').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-tool]');
      if (b) { switchTool(b.dataset.tool); state.dir = ''; }
    });

    Q('#go').addEventListener('click', start);
    Q('#clearLog').addEventListener('click', function () {
      Q('#log').innerHTML = '<span class="empty">等待执行…</span>';
      state.rendered = 0;
    });
    Q('#openDir').addEventListener('click', function () {
      if (!state.dir) return;
      fetch('/cs/open', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dir: state.dir })
      }).then(function (r) { return r.json(); }).then(function (d) {
        if (!d.ok) log(d.error || '打开失败', 'WARN');
      });
    });
    Q('#download').addEventListener('click', function () {
      if (!state.job || !state.files) return;
      window.location.href = '/cs/get?job=' + encodeURIComponent(state.job) + '&i=0';
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); start(); }
    });
  }

  // ---------------------------------------------------------------- 启动
  function boot() {
    syncTheme();
    setInterval(syncTheme, 900);
    Q('#log').innerHTML = '<span class="empty">等待执行…</span>';
    Q('#openDir').hidden = true;
    Q('#download').hidden = true;
    bind();
    renderOpts();
    loadEnv();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
