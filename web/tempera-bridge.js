/* ==================================================================
 * tempera-bridge.js — WebTerm「凝彩」接入桥（Folia Tempera 运行时）
 * ------------------------------------------------------------------
 * 依赖：web/folia-adapter.js（module，加载后暴露 window.TemperaFolia）
 * 职责：LRC → Line[] → compileTemperaProgram → TemperaPixiRuntime
 *       凝彩是**绝对时间驱动**的色块 PV（网点图形 / 大字拼贴 / 121 种镜头），
 *       不吃 audioPower / audioBands —— 这一点和绘光不同，桥接层不喂能量。
 * 主题：同样用原版 services/baseThemes.ts 的两套预设，跟着终端黑 / 白走：
 *       午夜墨染（深纸 + 冷白字） / 日光素白（浅纸 + 墨字 + 橙强调）。
 *       凝彩的调色板整个由 theme.backgroundColor / primaryColor 派生
 *       （temperaPalette.ts），所以黑白切换是「整屏纸色翻转」，比绘光明显。
 * 接口：window.Tempera = { init, setLyrics, start, stop, resize, isRunning,
 *                           setTheme, themeName, debug }
 * ================================================================== */
(function () {
  "use strict";

  var host = null;
  var hooks = { getTime: null, playing: null, hasTrack: null };
  var runtime = null;              // TemperaPixiRuntime
  var currentTime = null;          // MotionValue<number>（秒）
  var lrcLines = [], meta = {};
  var seedN = 0;
  var curSeed = 'tempera-demo';    // 演示态固定 seed：每次启动构图一致，不会一进来就换花样
  var _lastProgram = null;
  var TUNING = null;               // 懒取 DEFAULT_TEMPERA_TUNING（adapter 是 module，后加载）
  var raf = 0;
  var started = false;
  var lastPaused = false;          // 存的是「上一帧是否在动」，与 setPaused 取反
  var demoClock = 0, demoPrev = 0; // 演示态时间轴（没载入曲目时自走）
  var isDrift = false, frameCount = 0, lastTime = 0;

  /* WebTerm 只覆盖字体：凝彩的字形要与终端一致，别退回 Folia 自带的西文 sans。
     颜色一个字不改 —— 纸色、墨色、色块全部由 theme 派生。

     ⚠ 字体必须走 `fontFamilyStack` 数组，**不能**把整条 CSS 字体栈塞进 `fontFamily`：
     fontStacks.ts 的 buildCustomFontFamilyStack 把 fontFamily 当成「一个字体名」，
     会用 quoteFontFamily 给整串再加一层引号、并把里面的双引号转义，拼出来的
     canvas font 串（`700 211px "Microsoft YaHei\",\"PingFang SC\",sans-serif"`）
     是非法的 → ctx.font 赋值被静默忽略 → 全部文字按 canvas 默认的 10px 画。
     实测表现就是「场景里字号是 211，画面上只有 12 像素的几个字」。 */
  var WEBTERM_FONT = {
    fontStyle: 'sans',
    fontFamily: 'Microsoft YaHei',
    fontFamilyStack: ['Microsoft YaHei', 'PingFang SC', 'Noto Sans SC'],
    fontWeight: 700,
    animationIntensity: 'normal'
  };

  /* adapter 没重建时的兜底主题（= 原版午夜墨染的原值），保证不至于无主题可渲染 */
  var LEGACY_DARK = {
    name: 'Midnight Default',
    backgroundColor: '#09090b',
    primaryColor: '#f4f4f5',
    accentColor: '#f4f4f5',
    secondaryColor: '#71717a'
  };
  var LEGACY_LIGHT = {
    name: 'Daylight Default',
    backgroundColor: '#f5f5f4',
    primaryColor: '#1c1917',
    accentColor: '#ea580c',
    secondaryColor: '#44403c'
  };

  var isLight = false;
  var currentTheme = null;

  function F() { return window.TemperaFolia || null; }

  function activeTheme() {
    if (!currentTheme) currentTheme = themeFor(isLight);
    return currentTheme;
  }

  /* 画布底色 = 主题纸色。Pixi 画布是透明底（backgroundAlpha:0，镂空族要靠它透出壳背景），
     构图的色块不是每帧都满出血，所以宿主元素必须自己铺一层同色，否则会漏出桌面壁纸。 */
  function paintHostPaper() {
    if (!host || !currentTheme) return;
    try { host.style.background = currentTheme.backgroundColor || ''; } catch (e) {}
  }

  function themeFor(light) {
    var lib = F();
    var dual = lib && lib.BASE_DUAL_THEME;
    var base = dual ? (light ? dual.light : dual.dark) : (light ? LEGACY_LIGHT : LEGACY_DARK);
    var t = {};
    for (var k in base) if (Object.prototype.hasOwnProperty.call(base, k)) t[k] = base[k];
    for (var f in WEBTERM_FONT) if (Object.prototype.hasOwnProperty.call(WEBTERM_FONT, f)) t[f] = WEBTERM_FONT[f];
    t.name = (light ? '日光素白' : '午夜墨染') + ' · WebTerm';
    return t;
  }

  /* 渲染参数 = 原版 DEFAULT_TEMPERA_TUNING（vendor-src/folia/src/types.ts:674）。
     不在这里写死一份：凝彩的默认值有 21 个字段，抄一遍就等于埋一个「以后原版改了
     我们不跟」的坑。只在 adapter 还没到位时用下面这份兜底（同样是原版值）。 */
  var FALLBACK_TUNING = {
    cameraIntensity: 1, glyphMotion: 1, wholeLineLyrics: false, glyphSettleStretch: 0.5,
    colorMode: 'duo', showBlocks: true, showDecor: true, showCornerMarks: true,
    textInversion: true, layerImages: [], layerImageDepth: 'back', layerImageFrequency: 0.6,
    enableTransitions: true, textureResolution: 1.5, postProcessEnabled: true,
    postProcessTextureCompression: false, postProcessGrain: 0.2, postProcessContrast: 0,
    postProcessRgbShift: 0, postProcessVignette: 0.85, postProcessLensDistortion: 0.3
  };

  function tuning() {
    if (TUNING) return TUNING;
    var lib = F();
    var base = (lib && lib.DEFAULT_TEMPERA_TUNING) || FALLBACK_TUNING;
    try {
      TUNING = JSON.parse(JSON.stringify(base));
    } catch (e) {
      TUNING = base;
    }
    return TUNING;
  }

  /* LRC [{t,text}] → Folia Line[]（词时间按字符长度比例分摊到行区间）。
     与 folia-bridge.js 同一套换算：两套可视化器吃同一个 Line 结构，
     分词只在有 segmentTextWords 时做，拿不到就整行当一个词。 */
  function buildLines() {
    var segment = null;
    var lib = F();
    if (lib && lib.segmentTextWords) segment = lib.segmentTextWords;
    var out = [];
    for (var i = 0; i < lrcLines.length; i++) {
      var ln = lrcLines[i];
      var text = String(ln.text || '').trim();
      if (!text) continue;
      var start = Number(ln.t) || 0;
      var end = (i + 1 < lrcLines.length) ? (Number(lrcLines[i + 1].t) || start + 4) : (start + 4);
      if (end <= start) end = start + 4;
      var segs = null;
      try { segs = segment ? segment(text) : null; } catch (e) {}
      var words;
      if (segs && segs.length) {
        var total = 0, k;
        for (k = 0; k < segs.length; k++) total += String(segs[k].segment || '').length;
        if (total <= 0) total = segs.length;
        var acc = 0;
        words = [];
        for (k = 0; k < segs.length; k++) {
          var wlen = String(segs[k].segment || '').length;
          var ws = start + (end - start) * acc / total;
          acc += wlen;
          var we = start + (end - start) * acc / total;
          if (we <= ws) we = ws + 0.05;
          words.push({ text: segs[k].segment, startTime: ws, endTime: we });
        }
      } else {
        words = [{ text: text, startTime: start, endTime: end }];
      }
      out.push({ words: words, startTime: start, endTime: end, fullText: text });
    }
    return out;
  }

  /* 没载入曲目时的演示歌词。原版 VisualizerTempera 对纯音乐（无 LRC）就是灌
     60 个 8 秒一格的「♪」虚拟行，让色块与镜头照常走 —— 这里照原版的做法，
     不自己编文案。凝彩必须至少有一个 paragraph 才画东西（renderFrame 首行就
     `if (program.paragraphs.length === 0) return`），所以演示行不是装饰。 */
  function demoLines() {
    var out = [];
    for (var i = 0; i < 60; i++) {
      out.push({
        id: 'tempera-demo-' + i,
        startTime: i * 8,
        endTime: i * 8 + 6,
        fullText: '♪',
        words: [],
        isChorus: false
      });
    }
    return out;
  }

  function songLines() {
    var real = buildLines();
    return real.length ? real : demoLines();
  }

  function makeSeed() { seedN += 1; return 'webterm-tempera-' + seedN; }

  /* 节目总时长。TemperaProgram 里没有 duration 字段（只有 paragraphs），
     所以这里按歌词推算：真实曲目取末行时间 + 12 秒；演示态是 60 格 × 8 秒 = 480。
     演示态时间轴要在这个范围内回绕，否则会冲过最后一格、画面停在片尾卡上。 */
  function timelineDuration() {
    if (lrcLines.length) {
      return Math.max(30, (Number(lrcLines[lrcLines.length - 1].t) || 0) + 12);
    }
    return 480;
  }

  function program(seed) {
    var lib = F();
    if (!lib) return null;
    _lastProgram = lib.compileTemperaProgram(songLines(), seed || curSeed, {
      wholeLineLyrics: !!tuning().wholeLineLyrics
    });
    return _lastProgram;
  }

  function hasTrack() {
    if (!hooks.hasTrack) return true;
    try { return !!hooks.hasTrack(); } catch (e) { return true; }
  }

  function drive() {
    if (!runtime) return;
    frameCount++;
    /* 演示态：没载入曲目时不能让时间钉在 0 —— 凝彩是绝对时间驱动，
       0 秒永远停在第一个 shot 的第一帧，整屏看着就是一张静止色块。
       这里让时间轴自己走，按节目总时长回绕，构图一直换。 */
    var drift = !hasTrack();
    isDrift = drift;
    var t;
    if (drift) {
      var now = performance.now();
      if (!demoPrev) demoPrev = now;
      demoClock += Math.min(0.25, Math.max(0, (now - demoPrev) / 1000));   // 切后台回来不跳帧
      demoPrev = now;
      var dur = timelineDuration();
      t = demoClock % Math.max(30, dur - 2);
    } else {
      demoPrev = 0;
      /* Folia 的 currentTime 是「秒」：audio.currentTime 直接喂，别传毫秒 */
      t = hooks.getTime ? (hooks.getTime() || 0) : 0;
    }
    currentTime.set(t);
    lastTime = t;

    var playing = drift ? true : (hooks.playing ? hooks.playing() : true);
    if (playing !== lastPaused) {
      lastPaused = playing;
      runtime.setPaused(!playing);
    }
    raf = requestAnimationFrame(drive);
  }

  /* 场景树摘要（自检用）：字有没有建、字号多少、真正会画出来几个。
     凝彩的构图里混着大片细线与装饰碎字，光看截图分不清「主歌词没编进 shot」
     还是「编进去了但被 filter/可见性吞掉」，这里直接读 Pixi 节点给答案。 */
  function sceneDigest() {
    if (!runtime || !runtime.sceneContainer) return null;
    var out = { scenes: 0, graphics: 0, textsTotal: 0, shown: [] };
    var roots = runtime.sceneContainer.children || [];
    out.scenes = roots.length;
    /* 父链可见性 / 父链透明度：节点自己 alpha=1 但祖先被隐藏或半透时，
       屏幕上就是「有字、有字号、有坐标，却看不见」。Pixi v8 没有 worldVisible，只能自己走。 */
    var chainVisible = function (node) {
      var p = node, hop = 0;
      while (p && hop++ < 40) {
        if (p.visible === false) return false;
        if (p === runtime.sceneContainer) return true;
        p = p.parent;
      }
      return true;
    };
    var chainAlpha = function (node) {
      var a = 1, p = node, hop = 0;
      while (p && hop++ < 40) {
        if (typeof p.alpha === 'number') a *= p.alpha;
        if (p === runtime.sceneContainer) break;
        p = p.parent;
      }
      return a;
    };
    var stack = roots.slice(), guard = 0;
    while (stack.length && guard++ < 30000) {
      var n = stack.pop();
      if (!n) continue;
      if (n.children && n.children.length) {
        for (var i = 0; i < n.children.length; i++) stack.push(n.children[i]);
      }
      if (typeof n.text === 'string' && n.text) {
        out.textsTotal++;
        if (out.shown.length < 6 && chainVisible(n)) {
          out.shown.push({
            t: String(n.text).slice(0, 4),
            fs: n.style ? Math.round(n.style.fontSize) : null,
            box: Math.round(n.width) + 'x' + Math.round(n.height),
            wa: Math.round(chainAlpha(n) * 100) / 100
          });
        }
      } else if (n.geometry || (n.context && n.context.instructions)) {
        out.graphics++;
      }
    }
    return out;
  }

  window.Tempera = {
    init: function (container, h) {
      host = container;
      if (h) {
        if (h.getTime) hooks.getTime = h.getTime;
        if (h.playing) hooks.playing = h.playing;
        if (h.hasTrack) hooks.hasTrack = h.hasTrack;
      }
    },
    setLyrics: function (lrc, m) {
      lrcLines = lrc || [];
      meta = m || {};
      if (!runtime) return;          // 还没起：start() 会按新歌词编译
      curSeed = makeSeed();
      var p = program(curSeed);
      if (!p) return;
      runtime.swapSong({ seed: curSeed, program: p, theme: activeTheme(), coverColors: [] })
        .then(function () {
          runtime.setSongMetadata({
            title: meta.title || '', artist: meta.artist || '', album: meta.album || ''
          });
        })
        .catch(function (e) { console.error('[tempera] swapSong', e); });
    },
    start: function () {
      if (started) return;
      if (!F() || !host) return;
      var lib = F();
      started = true;
      /* 首帧取色以 body.light 为准：applyTheme 可能早于本文件执行，
         那次 setTheme 只落了状态，这里对齐一次。 */
      isLight = document.body.classList.contains('light');
      currentTheme = themeFor(isLight);
      paintHostPaper();
      currentTime = new lib.MotionValue(0);
      lastPaused = false;
      demoClock = 0;
      demoPrev = 0;
      if (!lrcLines.length) curSeed = 'tempera-demo';
      var p = program(curSeed);
      if (!p) { started = false; return; }
      lib.TemperaPixiRuntime.create({
        host: host,
        songSeed: curSeed,
        program: p,
        theme: activeTheme(),
        tuning: tuning(),
        currentTime: currentTime,
        lyricsFontScale: 1,
        staticMode: false,
        coverColors: [],
        paused: false,
        songTitle: meta.title || '',
        songArtist: meta.artist || '',
        songAlbum: meta.album || ''
      }).then(function (rt) {
        runtime = rt;
        raf = requestAnimationFrame(drive);
      }).catch(function (e) {
        console.error('[tempera] create failed', e);
        started = false;
      });
    },
    stop: function () {
      started = false;
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      if (runtime) {
        try { runtime.destroy(); } catch (e) {}
        runtime = null;
      }
      if (host) { while (host.firstChild) host.removeChild(host.firstChild); }
    },
    resize: function () { /* Pixi runtime 自带 ResizeObserver 跟随 host 尺寸 */ },
    isRunning: function () { return started; },
    /* 运行时调参直通（自检 / 以后做设置面板用）：只改传进来的字段，其余保持原版默认。
       注意 runtime.setTuning 会按 requiresSceneRebuild 判断要不要丢掉已建场景。 */
    setTuning: function (patch) {
      var cur = tuning();
      if (patch) {
        for (var k in patch) if (Object.prototype.hasOwnProperty.call(patch, k)) cur[k] = patch[k];
      }
      if (runtime) {
        try { runtime.setTuning(cur); } catch (e) { console.error('[tempera] setTuning', e); }
      }
      return cur;
    },
    /* 终端【外观】黑 / 白 → 凝彩主题。
       沿用当前 seed 调 swapSong：runtime 见到 seed 未变会走 commitSong 直通分支，
       没有跨曲交接，只清掉已建段落、下一帧按新纸色重建。
       凝彩的纸色就是 theme.backgroundColor，所以这一步等于整屏底色翻转。 */
    setTheme: function (light) {
      light = !!light;
      if (light === isLight && currentTheme) return false;
      isLight = light;
      currentTheme = themeFor(light);
      paintHostPaper();
      if (!runtime) return true;
      var p = _lastProgram || program(curSeed);
      if (!p) return true;
      try {
        runtime.swapSong({ seed: curSeed, program: p, theme: currentTheme, coverColors: [] })
          .catch(function (e) { console.error('[tempera] setTheme', e); });
      } catch (e) { console.error('[tempera] setTheme', e); }
      if (lastPaused) runtime.renderOnce();   // 暂停态 ticker 是停的，手动补一帧
      return true;
    },
    themeName: function () { return activeTheme().name; },
    /* 把演示时间轴拨到 t 秒（自检用）。
       凝彩每个 shot 只有 2~4 个词、入场窗口不到 1 秒，随手截图很容易正好落在
       交接或入场的低透明度瞬间 —— 有了 seek 才能稳定停在「已落位」的画面上比对。 */
    seek: function (t) {
      t = Math.max(0, Number(t) || 0);
      demoClock = t;
      demoPrev = 0;
      if (currentTime) currentTime.set(t);
      if (runtime) {
        try { runtime.renderOnce(); } catch (e) {}
      }
      return t;
    },
    /* 自检用：frames 递增证明 ticker 在跑，paragraphs 为 0 就说明没东西可画。 */
    debug: function () {
      var th = activeTheme();
      return {
        started: started, demo: isDrift, frames: frameCount, time: lastTime,
        ticker: !!runtime, light: isLight, theme: th.name,
        /* 主题色原值：凝彩的纸色 = backgroundColor、墨色 = primaryColor、
           duo 色块由 accentColor / secondaryColor 混出来，任何一项缺失画面都会变灰。 */
        colors: {
          bg: th.backgroundColor, ink: th.primaryColor,
          accent: th.accentColor, secondary: th.secondaryColor
        },
        /* 运行时实际按多大画布排版：和 host 的 clientWidth/Height 不一致就说明
           ResizeObserver 还没生效，layout 会按错的尺寸算字号（实测表现为字小得看不见）。 */
        layout: runtime ? {
          w: runtime.lastWidth, h: runtime.lastHeight,
          resolution: runtime.renderResolution
        } : null,
        paragraphs: (_lastProgram && _lastProgram.paragraphs && _lastProgram.paragraphs.length) || 0,
        duration: timelineDuration(),
        scene: sceneDigest(),
        tuning: tuning()
      };
    }
  };
})();
