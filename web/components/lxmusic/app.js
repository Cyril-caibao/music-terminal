/* 音乐插件主逻辑：音源管理 / 搜索 / 播放 / 下载 */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var audio = $('audio');
  var sb = $('sb');

  var S = {
    cfg: {},
    sources: [],
    sourceFile: '',
    caps: {},          // 音源声明的平台能力
    platform: '',
    list: [],
    page: 1,
    allPage: 1,
    keyword: '',
    current: null,
    curIdx: -1,
    loading: false,
  };

  /* ---------------- 基础工具 ---------------- */
  var toastTimer = 0;
  function toast(msg, isErr) {
    var el = $('toast');
    el.textContent = msg;
    el.className = 'toast on' + (isErr ? ' err' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.className = 'toast'; }, isErr ? 4200 : 2200);
  }

  function fmtTime(sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    var m = Math.floor(sec / 60);
    var s = Math.floor(sec % 60);
    return m + ':' + (s < 10 ? '0' + s : s);
  }

  /* 音源文件编码：常见 UTF-8，也有人存成 GBK，按 UTF-8 硬读会把中文读花 */
  function decodeSource(buf) {
    var u8 = new Uint8Array(buf);
    if (u8.length >= 3 && u8[0] === 0xEF && u8[1] === 0xBB && u8[2] === 0xBF) {
      return new TextDecoder('utf-8').decode(u8.subarray(3));
    }
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(u8);
    } catch (e) { /* 不是合法 UTF-8，往下试 GBK */ }
    try {
      return new TextDecoder('gbk').decode(u8);
    } catch (e) {
      return new TextDecoder('utf-8').decode(u8);
    }
  }

  function api(url, opt) {
    return fetch(url, opt).then(function (r) {
      return r.json().catch(function () { return { ok: false, error: 'HTTP ' + r.status }; });
    });
  }

  function post(url, body) {
    return api(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
    });
  }

  /* ---------------- 沙箱 ---------------- */
  function waitSandbox() {
    return new Promise(function (resolve, reject) {
      var n = 0;
      (function tick() {
        if (sb.contentWindow && typeof sb.contentWindow.loadSource === 'function') {
          resolve(sb.contentWindow);
          return;
        }
        if (++n > 150) { reject(new Error('音源沙箱未就绪')); return; }
        setTimeout(tick, 40);
      })();
    });
  }

  var sandbox = null;

  async function loadSourceFile(file) {
    try {
      var r = await api('/lx/source?f=' + encodeURIComponent(file));
      if (!r.ok) throw new Error(r.error || '读取音源失败');
      if (!sandbox) sandbox = await waitSandbox();
      var res = sandbox.loadSource(r.code, { name: file });
      if (!res || !res.ok) throw new Error('音源执行失败：' + ((res && res.error) || '未知'));
      var st = sandbox.getState();
      if (!st.hasRequest) throw new Error('这个音源没有注册 request 处理器');
      S.sourceFile = file;
      S.caps = (st.inited && st.inited.sources) || {};
      renderPlatforms();
      renderSources();
      $('srcNow').textContent = '音源：' + file;
      $('srcNow').className = 'on';
      if (st.alert && st.alert.log) toast(String(st.alert.log).slice(0, 120));
      post('/lx/config', { patch: { last_source: file, sources_auto_load: true } });
      // 音源声明的平台若为空，退回到我们内置搜索支持的平台
      if (!Object.keys(S.caps).length) toast('音源未声明平台，已用内置搜索平台', true);
      return true;
    } catch (e) {
      toast(String(e.message || e), true);
      return false;
    }
  }

  function renderPlatforms() {
    var sel = $('platform');
    var caps = Object.keys(S.caps);
    var all = ['kw', 'kg', 'tx', 'wy', 'mg'];
    var names = { kw: '酷我', kg: '酷狗', tx: 'QQ', wy: '网易云', mg: '咪咕' };
    var list = caps.length ? caps : all;
    sel.innerHTML = '';
    list.forEach(function (k) {
      var o = document.createElement('option');
      o.value = k;
      o.textContent = names[k] || k;
      sel.appendChild(o);
    });
    if (list.indexOf(S.platform) < 0) S.platform = list[0] || '';
    sel.value = S.platform;
  }

  /* ---------------- 音源列表 ---------------- */
  async function refreshSources() {
    var r = await api('/lx/sources');
    if (r.ok) {
      S.sources = r.items || [];
      renderSources();
      $('srcDir').textContent = r.dir ? '音源目录：' + r.dir : '';
    }
  }

  function renderSources() {
    var box = $('srcList');
    box.innerHTML = '';
    if (!S.sources.length) {
      var e = document.createElement('div');
      e.className = 'muted';
      e.textContent = '还没有音源。下面选一个 .js 文件导入。';
      box.appendChild(e);
      return;
    }
    S.sources.forEach(function (it) {
      var row = document.createElement('div');
      row.className = 'src-item' + (it.file === S.sourceFile ? ' on' : '');

      var nm = document.createElement('span');
      nm.className = 'nm';
      nm.textContent = it.name || it.file;
      nm.title = it.file;

      var ver = document.createElement('span');
      ver.className = 'ver';
      ver.textContent = it.version ? 'v' + it.version.replace(/^v/i, '') : '';

      var del = document.createElement('button');
      del.className = 'del';
      del.textContent = '删除';
      del.addEventListener('click', function (ev) {
        ev.stopPropagation();
        if (!confirm('删除音源「' + (it.name || it.file) + '」？')) return;
        post('/lx/source/delete', { file: it.file }).then(function (res) {
          if (res.ok) {
            if (S.sourceFile === it.file) { S.sourceFile = ''; S.caps = {}; $('srcNow').textContent = '未加载音源'; $('srcNow').className = ''; }
            S.sources = res.items || [];
            renderSources();
            toast('已删除');
          } else toast(res.error || '删除失败', true);
        });
      });

      row.appendChild(nm);
      row.appendChild(ver);
      row.appendChild(del);
      row.addEventListener('click', function () { loadSourceFile(it.file); });
      box.appendChild(row);
    });
  }

  /* ---------------- 搜索 ---------------- */
  function itemSource(it) {
    return it.source || S.platform;
  }

  async function doSearch(page) {
    var kw = $('kw').value.trim();
    if (!kw) { toast('先输入关键词'); return; }
    if (!S.platform) { toast('没有可用平台，先导入音源', true); return; }
    if (!window.LXSDK) { toast('搜索模块没加载（sdk.js）', true); return; }
    S.keyword = kw;
    S.page = page || 1;
    S.loading = true;
    $('empty').textContent = '搜索中…';
    $('empty').style.display = 'flex';
    $('list').innerHTML = '';
    try {
      var res = await window.LXSDK.search(S.platform, kw, S.page);
      S.list = (res && res.list) || [];
      S.allPage = (res && res.allPage) || 1;
      renderList();
      $('pageInfo').textContent = S.list.length
        ? '第 ' + S.page + ' / ' + S.allPage + ' 页 · 共 ' + (res.total || S.list.length) + ' 首'
        : '';
      $('btnPrevPage').disabled = S.page <= 1;
      $('btnNextPage').disabled = S.page >= S.allPage;
    } catch (e) {
      S.list = [];
      $('pageInfo').textContent = '';
      $('empty').textContent = '搜索失败：' + (e.message || e);
      toast('搜索失败：' + (e.message || e), true);
    } finally {
      S.loading = false;
      if (S.list.length) $('empty').style.display = 'none';
    }
  }

  function renderList() {
    var box = $('list');
    box.innerHTML = '';
    if (!S.list.length) {
      $('empty').style.display = 'flex';
      $('empty').textContent = '没有结果，换个关键词试试';
      return;
    }
    $('empty').style.display = 'none';

    S.list.forEach(function (it, i) {
      var row = document.createElement('div');
      row.className = 'row-item' + (S.current && S.current.idx === i ? ' playing' : '');
      row.setAttribute('data-idx', i);

      var idx = document.createElement('span');
      idx.className = 'c-idx';
      idx.textContent = i + 1;

      var nm = document.createElement('span');
      nm.className = 'c-name';
      nm.textContent = it.name || '';
      nm.title = it.name || '';

      var sg = document.createElement('span');
      sg.className = 'c-singer';
      sg.textContent = it.singer || '';
      sg.title = it.singer || '';

      var al = document.createElement('span');
      al.className = 'c-album';
      al.textContent = it.albumName || '';
      al.title = it.albumName || '';

      var tm = document.createElement('span');
      tm.className = 'c-time';
      tm.textContent = it.interval || '';

      var act = document.createElement('span');
      act.className = 'c-act';
      var bPlay = document.createElement('button');
      bPlay.textContent = '播放';
      bPlay.addEventListener('click', function (ev) {
        ev.stopPropagation();
        playIndex(i);
      });
      var bDl = document.createElement('button');
      bDl.textContent = '下载';
      bDl.addEventListener('click', function (ev) {
        ev.stopPropagation();
        downloadItem(it, i, act);
      });
      act.appendChild(bPlay);
      act.appendChild(bDl);

      row.appendChild(idx);
      row.appendChild(nm);
      row.appendChild(sg);
      row.appendChild(al);
      row.appendChild(tm);
      row.appendChild(act);
      row.addEventListener('dblclick', function () { playIndex(i); });
      box.appendChild(row);
    });
  }

  function markPlaying() {
    Array.prototype.forEach.call(document.querySelectorAll('#list .row-item'), function (el) {
      var i = parseInt(el.getAttribute('data-idx'), 10);
      el.classList.toggle('playing', S.curIdx === i);
    });
  }

  /* ---------------- 音质 ---------------- */
  var QORDER = ['flac24bit', 'flac', '320k', '192k', '128k'];
  function pickQuality(it) {
    var want = S.cfg.quality || '320k';
    var has = (it && it._types) || {};
    if (has[want]) return want;
    var i = QORDER.indexOf(want);
    if (i < 0) i = 2;
    for (var k = i + 1; k < QORDER.length; k++) if (has[QORDER[k]]) return QORDER[k];
    for (var j = i - 1; j >= 0; j--) if (has[QORDER[j]]) return QORDER[j];
    return want;
  }

  function extOf(quality) {
    if (quality === 'flac' || quality === 'flac24bit') return 'flac';
    if (quality === 'm4a') return 'm4a';
    return 'mp3';
  }

  /* ---------------- 调音源 ---------------- */
  function musicInfoOf(it) {
    // 音源普遍用 songmid / hash / id；同时塞一份 meta，兼容写 meta.xxx 的音源
    var mi = Object.assign({}, it);
    mi.source = itemSource(it);
    mi.meta = Object.assign({}, it, { source: mi.source });
    return mi;
  }

  async function callSource(source, action, info) {
    if (!sandbox) throw new Error('音源未加载');
    return sandbox.callSource({ source: source, action: action, info: info });
  }

  /* 超时兜底：音源/第三方接口偶尔会一直不返回，不能让它把整个下载卡死 */
  function withTimeout(p, ms, fallback) {
    return new Promise(function (resolve) {
      var done = false;
      var t = setTimeout(function () {
        if (done) return;
        done = true;
        resolve(fallback);
      }, ms);
      Promise.resolve(p).then(function (v) {
        if (done) return;
        done = true;
        clearTimeout(t);
        resolve(v);
      }, function () {
        if (done) return;
        done = true;
        clearTimeout(t);
        resolve(fallback);
      });
    });
  }

  /* 音源在 init 里声明自己支持哪些动作（多数只有 musicUrl） */
  function srcSupports(source, action) {
    try {
      var st = sb.contentWindow.getState();
      var s = st && st.inited && st.inited.sources && st.inited.sources[source];
      return !!(s && s.actions && s.actions.indexOf(action) > -1);
    } catch (e) { return false; }
  }

  /* 歌词：音源支持就用音源，否则用内置 SDK（LX 原版就是这个分工） */
  async function lyricOf(it) {
    var src = itemSource(it);
    if (srcSupports(src, 'lyric')) {
      try {
        var ly = await callSource(src, 'lyric', { musicInfo: musicInfoOf(it) });
        var lrc = typeof ly === 'string' ? ly : ((ly && (ly.lyric || ly.lrc)) || '');
        if (lrc) return { lyric: lrc, tlyric: (ly && ly.tlyric) || '' };
      } catch (e) { /* 落到内置 */ }
    }
    if (window.LXSDK && window.LXSDK.getLyric) {
      try {
        var r = await window.LXSDK.getLyric(src, musicInfoOf(it));
        if (r && (r.lyric || r.tlyric)) return { lyric: r.lyric || '', tlyric: r.tlyric || '' };
      } catch (e) { /* 换平台再试 */ }
      /* 本平台没歌词：用酷狗按歌名找一份（酷狗曲库最全） */
      try {
        var fb = await kgFallback(it);
        if (fb) {
          var r2 = await window.LXSDK.getLyric('kg', musicInfoOf(fb));
          if (r2 && (r2.lyric || r2.tlyric)) return { lyric: r2.lyric || '', tlyric: r2.tlyric || '' };
        }
      } catch (e) { /* 没歌词就算了 */ }
    }
    return { lyric: '', tlyric: '' };
  }

  /* 封面直链：同上 */
  async function picOf(it) {
    var src = itemSource(it);
    if (srcSupports(src, 'pic')) {
      try {
        var p = await callSource(src, 'pic', { musicInfo: musicInfoOf(it) });
        var u = typeof p === 'string' ? p : ((p && (p.pic || p.url)) || '');
        if (u) return u;
      } catch (e) { /* 落到内置 */ }
    }
    if (window.LXSDK && window.LXSDK.getPic) {
      try {
        var r = await window.LXSDK.getPic(src, musicInfoOf(it));
        if (r) return r;
      } catch (e) { /* 换平台再试 */ }
      try {
        var fb = await kgFallback(it);
        if (fb) {
          var r2 = await window.LXSDK.getPic('kg', musicInfoOf(fb));
          if (r2) return r2;
        }
      } catch (e) { /* 没封面不影响播放 */ }
    }
    return '';
  }

  /* 在酷狗里按「歌名 + 歌手」找同一首，供其它平台补歌词/封面 */
  var _kgCache = {};
  function normTitle(s) {
    return String(s || '').toLowerCase()
      .replace(/[\s\-_()（）\[\]【】·。、,，!！?？'"“”‘’~]/g, '');
  }
  async function kgFallback(it) {
    if (itemSource(it) === 'kg' || !window.LXSDK) return null;
    var key = (it.name || '') + '\u0001' + (it.singer || '');
    if (key in _kgCache) return _kgCache[key];
    var hit = null;
    try {
      var who = String(it.singer || '').split(/[、,&/]/)[0].trim();
      var res = await window.LXSDK.search('kg', ((it.name || '') + ' ' + who).trim(), 1);
      var list = (res && res.list) || [];
      var want = normTitle(it.name);
      for (var i = 0; i < list.length; i++) {
        if (normTitle(list[i].name) === want) { hit = list[i]; break; }
      }
      if (!hit && list.length) hit = list[0];
      if (hit) hit.source = 'kg';
    } catch (e) { hit = null; }
    _kgCache[key] = hit;
    return hit;
  }

  async function getUrl(it, quality) {
    var q = quality || pickQuality(it);
    if (!S.sourceFile) throw new Error('还没加载音源（右上「音源」里选一个再试）');
    var url = await callSource(itemSource(it), 'musicUrl', {
      musicInfo: musicInfoOf(it),
      type: q,
    });
    if (!url || typeof url !== 'string') throw new Error('音源没有返回播放地址');
    return { url: url, quality: q };
  }

  /* ---------------- 播放 ---------------- */
  async function playIndex(i) {
    var it = S.list[i];
    if (!it) return;
    S.curIdx = i;
    S.current = Object.assign({ idx: i }, it);
    markPlaying();
    $('tTitle').textContent = it.name || '';
    $('tSub').textContent = [it.singer, it.albumName].filter(Boolean).join(' · ');
    $('cover').className = '';
    $('coverFallback').style.display = '';
    toast('正在获取播放地址…');

    try {
      var r = await withTimeout(getUrl(it), 25000, null);
      if (!r) throw new Error('音源响应超时，换一首或换个音源试试');
      audio.src = '/lx/stream?u=' + encodeURIComponent(r.url);
      await audio.play();
      toast('播放中 · ' + r.quality);
      loadLyricAndCover(it);
    } catch (e) {
      toast('播放失败：' + (e.message || e), true);
    }
  }

  async function loadLyricAndCover(it) {
    var pic = await withTimeout(picOf(it), 15000, '');
    if (pic) {
      var img = $('cover');
      img.src = '/lx/stream?u=' + encodeURIComponent(pic);
      img.onload = function () {
        img.className = 'on';
        $('coverFallback').style.display = 'none';
      };
      img.onerror = function () { img.className = ''; };
    }
  }

  function togglePlay() {
    if (!audio.src) { if (S.list.length) playIndex(0); return; }
    if (audio.paused) audio.play().catch(function () {});
    else audio.pause();
  }

  function step(dir) {
    if (!S.list.length) return;
    var n = S.curIdx + dir;
    if (n < 0) n = S.list.length - 1;
    if (n >= S.list.length) n = 0;
    playIndex(n);
  }

  /* ---------------- 歌词合并 ---------------- */
  var timeFieldExp = /^(?:\[[\d:.]+\])+/g;
  var timeExp = /\d{1,3}(:\d{1,3}){0,2}(?:\.\d{1,3})/g;

  function normTime(t) {
    return t.replace(/^0+(\d+)/, '$1').replace(/:0+(\d+)/g, ':$1').replace(/\.0+(\d+)/, '.$1');
  }

  function lrcLabels(lrc) {
    var set = {};
    String(lrc || '').split(/\r\n|\n|\r/).forEach(function (line) {
      line = line.trim();
      var r = timeFieldExp.exec(line);
      timeFieldExp.lastIndex = 0;
      if (!r) return;
      var text = line.replace(timeFieldExp, '').trim();
      if (!text) return;
      var times = r[0].match(timeExp) || [];
      times.forEach(function (t) { set[normTime(t)] = 1; });
    });
    return set;
  }

  function mergeTrans(main, trans) {
    if (!trans) return main;
    var labels = lrcLabels(main);
    var out = [];
    String(trans).split(/\r\n|\n|\r/).forEach(function (line) {
      line = line.trim();
      if (!line) return;
      var r = line.match(timeFieldExp);
      if (!r) return;
      var text = line.replace(timeFieldExp, '').trim();
      if (!text) return;
      var times = (r[0].match(timeExp) || []).filter(function (t) { return labels[normTime(t)]; });
      if (!times.length) return;
      if (times.length !== (r[0].match(timeExp) || []).length) {
        line = '[' + times.join('][') + ']' + text;
      }
      out.push(line);
    });
    return out.length ? main.trim() + '\n\n' + out.join('\n') + '\n' : main;
  }

  /* ---------------- 下载 ---------------- */
  var dlBusy = {};

  /* ---- 行内下载进度条 ---- */
  function dlBarMount(actEl) {
    if (!actEl) return null;
    actEl.classList.add('busy');
    var bar = document.createElement('span');
    bar.className = 'dl-bar';
    var fill = document.createElement('i');
    var txt = document.createElement('em');
    txt.textContent = '准备';
    bar.appendChild(fill);
    bar.appendChild(txt);
    actEl.appendChild(bar);
    return { actEl: actEl, bar: bar, fill: fill, txt: txt };
  }
  function dlBarSet(ctl, pct, text) {
    if (!ctl) return;
    var pending = Number(pct) < 0;                 // 负数 = 还在等，走扫动动画
    ctl.bar.classList.toggle('pending', pending);
    if (!pending) ctl.fill.style.width = Math.max(0, Math.min(100, Number(pct) || 0)) + '%';
    if (text != null) ctl.txt.textContent = text;
  }
  function dlBarDrop(ctl) {
    if (!ctl) return;
    ctl.actEl.classList.remove('busy');
    if (ctl.bar.parentNode) ctl.bar.parentNode.removeChild(ctl.bar);
  }
  function fmtMB(n) {
    n = Number(n) || 0;
    return n >= 1048576 ? (n / 1048576).toFixed(1) + 'M' : Math.round(n / 1024) + 'K';
  }
  /* 轮询后端进度；返回停止函数 */
  function watchProgress(tid, ctl) {
    var alive = true;
    (function loop() {
      if (!alive) return;
      api('/lx/progress?tid=' + encodeURIComponent(tid)).then(function (j) {
        if (!alive || !j || !j.ok) return;
        if (j.phase === 'fetch') {
          if (j.total) {
            dlBarSet(ctl, j.pct, (j.pct || 0) + '%');
            if (ctl.bar) ctl.bar.title = fmtMB(j.done) + ' / ' + fmtMB(j.total);
          } else {
            dlBarSet(ctl, 0, fmtMB(j.done));
          }
        } else if (j.phase === 'tag') {
          dlBarSet(ctl, 100, '写标签');
        } else if (j.phase === 'done') {
          dlBarSet(ctl, 100, '完成');
        }
      }).catch(function () {}).then(function () {
        if (alive) setTimeout(loop, 220);
      });
    })();
    return function () { alive = false; };
  }


  async function downloadItem(it, idx, actEl) {
    var key = (it.songmid || it.hash || it.id || '') + '_' + (idx != null ? idx : '');
    if (dlBusy[key]) { toast('这首正在下载'); return; }
    dlBusy[key] = true;

    var tid = 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    var ctl = dlBarMount(actEl);
    var stopWatch = null;
    var keepBar = 0;
    dlBarSet(ctl, -1, '取地址');
    try {
      var r = await withTimeout(getUrl(it), 25000, null);
      if (!r) throw new Error('音源响应超时，换一首或换个音源试试');

      dlBarSet(ctl, -1, '取歌词');
      var lyr = await withTimeout(lyricOf(it), 15000, { lyric: '', tlyric: '' });
      var lyric = lyr.lyric || '';
      if (lyr.tlyric) lyric = mergeTrans(lyric, lyr.tlyric);
      var pic = await withTimeout(picOf(it), 15000, '');

      dlBarSet(ctl, 0, '连接');
      stopWatch = watchProgress(tid, ctl);

      var res = await post('/lx/download', {
        tid: tid,
        url: r.url,
        name: it.name || '',
        singer: it.singer || '',
        album: it.albumName || '',
        ext: extOf(r.quality),
        cover: pic,
        lyric: lyric || '',
        quality: r.quality,
      });
      if (stopWatch) { stopWatch(); stopWatch = null; }

      if (res.ok) {
        dlBarSet(ctl, 100, '完成');
        keepBar = 900;
        var bits = ['已保存：' + res.path.split(/[\\/]/).pop()];
        if (res.lrc) bits.push('歌词');
        if (res.tagged) bits.push('标签');
        toast(bits.join(' · '));
      } else {
        toast('下载失败：' + (res.error || ''), true);
      }
    } catch (e) {
      toast('下载失败：' + (e.message || e), true);
    } finally {
      if (stopWatch) stopWatch();
      dlBusy[key] = false;
      setTimeout(function () { dlBarDrop(ctl); }, keepBar);
    }
  }

  /* ---------------- 设置 ---------------- */
  async function loadConfig() {
    var r = await api('/lx/config');
    if (!r.ok) return;
    S.cfg = r.config || {};
    $('cfgQuality').value = S.cfg.quality || '320k';
    $('cfgNameFmt').value = S.cfg.name_format || '{name} - {singer}';
    $('cfgSaveLrc').checked = !!S.cfg.save_lrc;
    $('cfgEmbedLrc').checked = !!S.cfg.embed_lyric;
    $('cfgEmbedCover').checked = !!S.cfg.embed_cover;
    $('cfgLrcFmt').value = S.cfg.lrc_format || 'utf8';
    $('cfgDlDir').value = S.cfg.download_dir || '';
    $('cfgDlDir').placeholder = '留空 = ' + (S.cfg._dir || '音乐目录/CyrilMusic');
  }

  async function saveConfig() {
    var patch = {
      quality: $('cfgQuality').value,
      name_format: $('cfgNameFmt').value || '{name} - {singer}',
      save_lrc: $('cfgSaveLrc').checked,
      embed_lyric: $('cfgEmbedLrc').checked,
      embed_cover: $('cfgEmbedCover').checked,
      lrc_format: $('cfgLrcFmt').value,
      download_dir: $('cfgDlDir').value.trim(),
    };
    var r = await post('/lx/config', { patch: patch });
    if (r.ok) {
      S.cfg = r.config || S.cfg;
      toast('设置已保存');
      $('setMsg').textContent = '';
    } else {
      toast(r.error || '保存失败', true);
    }
  }

  /* ---------------- 事件绑定 ---------------- */
  function bind() {
    $('btnSearch').addEventListener('click', function () { doSearch(1); });
    $('kw').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') doSearch(1);
    });
    $('platform').addEventListener('change', function () {
      S.platform = this.value;
    });
    $('btnPrevPage').addEventListener('click', function () { if (S.page > 1) doSearch(S.page - 1); });
    $('btnNextPage').addEventListener('click', function () { if (S.page < S.allPage) doSearch(S.page + 1); });

    $('btnPlay').addEventListener('click', togglePlay);
    $('btnPrev').addEventListener('click', function () { step(-1); });
    $('btnNext').addEventListener('click', function () { step(1); });
    $('btnDl').addEventListener('click', function () {
      if (!S.current) { toast('还没有播放中的歌曲'); return; }
      downloadItem(S.current, S.current.idx);
    });

    audio.addEventListener('play', function () { $('btnPlay').textContent = '⏸'; });
    audio.addEventListener('pause', function () { $('btnPlay').textContent = '▶'; });
    audio.addEventListener('timeupdate', function () {
      var d = audio.duration || 0;
      $('cur').textContent = fmtTime(audio.currentTime);
      $('dur').textContent = fmtTime(d);
      if (d > 0) $('seek').value = Math.round((audio.currentTime / d) * 1000);
    });
    audio.addEventListener('ended', function () { step(1); });
    audio.addEventListener('error', function () {
      if (audio.src) toast('音频加载失败（可能是链接过期，重新点一次播放）', true);
    });
    $('seek').addEventListener('input', function () {
      var d = audio.duration || 0;
      if (d > 0) audio.currentTime = (this.value / 1000) * d;
    });
    $('vol').addEventListener('input', function () { audio.volume = this.value / 100; });
    audio.volume = 0.8;

    $('btnSource').addEventListener('click', function () {
      $('srcPanel').classList.add('on');
      refreshSources();
    });
    $('btnSetting').addEventListener('click', function () {
      $('setPanel').classList.add('on');
      loadConfig();
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-close]'), function (b) {
      b.addEventListener('click', function () {
        $(b.getAttribute('data-close')).classList.remove('on');
      });
    });

    $('srcFile').addEventListener('change', function () {
      var f = this.files && this.files[0];
      if (!f) return;
      var fr = new FileReader();
      fr.onload = function () {
        var code = decodeSource(fr.result);
        post('/lx/source', { file: f.name, code: code }).then(function (r) {
          if (!r.ok) { toast(r.error || '导入失败', true); return; }
          S.sources = r.items || [];
          renderSources();
          $('srcMsg').textContent = '已导入 ' + r.file;
          loadSourceFile(r.file);
          toast('音源已导入并启用');
        });
      };
      fr.readAsArrayBuffer(f);
      this.value = '';
    });

    /* 打开目录：音源目录（抽屉底部） / 下载目录（设置里，优先用输入框当前写的路径） */
    function openDir(which, msgEl, path) {
      var body = { which: which };
      if (path) body.path = path;
      post('/lx/open-dir', body).then(function (r) {
        if (r.ok) {
          if (msgEl) msgEl.textContent = '已打开：' + r.dir;
        } else {
          toast(r.error || '打不开目录', true);
          if (msgEl) msgEl.textContent = r.error || '打不开目录';
        }
      }).catch(function () { toast('打不开目录', true); });
    }
    $('btnOpenSrcDir').addEventListener('click', function () { openDir('sources', null); });
    $('btnOpenDir').addEventListener('click', function () {
      openDir('music', $('setMsg'), $('cfgDlDir').value.trim());
    });

    $('btnSrcSave').addEventListener('click', function () {
      var code = $('srcCode').value.trim();
      if (code.length < 40) { $('srcMsg').textContent = '代码太短'; return; }
      /* 名字原样交给后端清洗：中文、空格、括号都保留，这里只管兜个底 */
      var m = code.match(/@name\s+(.+)/);
      var base = (m ? m[1].trim().split(/\s{2,}|[|,]/)[0].trim() : '') || '粘贴的音源';
      var fname = base.slice(0, 60) + '.js';
      post('/lx/source', { file: fname, code: code }).then(function (r) {
        if (!r.ok) { toast(r.error || '导入失败', true); return; }
        S.sources = r.items || [];
        renderSources();
        $('srcCode').value = '';
        $('srcMsg').textContent = '已导入 ' + r.file;
        loadSourceFile(r.file);
        toast('音源已导入并启用');
      });
    });

    $('btnSrcUrl').addEventListener('click', async function () {
      var u = $('srcUrl').value.trim();
      if (!/^https?:\/\//i.test(u)) { $('srcMsg').textContent = '填一个 http(s) 直链'; return; }
      $('srcMsg').textContent = '下载中…';
      try {
        var r = await post('/lx/proxy', { url: u, method: 'GET' });
        if (!r.ok) throw new Error(r.error || '下载失败');
        var code = r.body || '';
        if (code.length < 40) throw new Error('内容太短，可能不是音源');
        var name = decodeURIComponent(u.split('/').pop().split('?')[0] || 'source.js');
        if (!/\.js$/i.test(name)) name += '.js';
        var res = await post('/lx/source', { file: name, code: code });
        if (!res.ok) throw new Error(res.error || '导入失败');
        S.sources = res.items || [];
        renderSources();
        $('srcUrl').value = '';
        $('srcMsg').textContent = '已导入 ' + name;
        loadSourceFile(res.file);
        toast('音源已导入并启用');
      } catch (e) {
        $('srcMsg').textContent = String(e.message || e);
        toast('导入失败：' + (e.message || e), true);
      }
    });

    $('btnSetSave').addEventListener('click', saveConfig);

    // 列表行单击也播放
    $('list').addEventListener('click', function (e) {
      var row = e.target.closest ? e.target.closest('.row-item') : null;
      if (!row || e.target.tagName === 'BUTTON') return;
      playIndex(parseInt(row.getAttribute('data-idx'), 10));
    });

    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') {
        $('srcPanel').classList.remove('on');
        $('setPanel').classList.remove('on');
      }
      var typing = /INPUT|TEXTAREA|SELECT/.test((e.target.tagName || '').toUpperCase());
      if (typing) return;
      if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
    });

    window.addEventListener('message', function (e) {
      if (e.data && e.data.type === 'lx-sandbox-ready') {
        waitSandbox().then(function (w) { sandbox = w; });
      }
    });
  }

  /* ---------------- 主题 ---------------- */
  function syncTheme() {
    try {
      var cls = (parent.document.body.className || '');
      var light = /(^|\s)(light|on-light)(\s|$)/.test(cls);
      document.body.classList.toggle('light', light);
    } catch (e) {}
  }

  /* ---------------- 启动 ---------------- */
  async function boot() {
    bind();
    syncTheme();
    setInterval(syncTheme, 1200);

    await loadConfig();
    await refreshSources();

    try {
      sandbox = await waitSandbox();
    } catch (e) {
      toast('音源沙箱加载失败', true);
    }

    if (S.cfg.sources_auto_load && S.cfg.last_source) {
      var exists = S.sources.some(function (s) { return s.file === S.cfg.last_source; });
      if (exists) await loadSourceFile(S.cfg.last_source);
    }
    /* 上次用的音源被删/改名了：有音源就自动挑一个，别让用户对着「未加载音源」发愣 */
    if (!S.sourceFile && S.cfg.sources_auto_load && S.sources.length) {
      var first = S.sources[0];
      await loadSourceFile(first.file);
    }
    if (!S.sourceFile) {
      renderPlatforms();
      $('empty').textContent = '点右上「音源」导入一个音源 js，然后就能搜歌了';
    }
  }

  boot();
})();
