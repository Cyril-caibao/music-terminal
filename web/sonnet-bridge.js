/* ==================================================================
 * sonnet-bridge.js — WebTerm「商籁」接入桥（Folia Sonnet 运行时）
 * ------------------------------------------------------------------
 * 依赖：web/folia-adapter.js（module，加载后暴露 window.SonnetFolia）
 * 职责：LRC → Line[] → compileSonnetProgram → SonnetPixiRuntime
 *       商籁是**绝对时间驱动**的印刷排版 PV（镜头流 + 字形排版 + 网点/镜头
 *       滤镜 + 背景 MG），不吃音频能量 —— audioPower/audioBands 是可选参，
 *       原版 VisualizerSonnet 只在有-energy 镜头才用，这里不喂（与凝彩同路）。
 * 主题：原版 services/baseThemes.ts 两套预设，跟终端黑 / 白走：
 *       午夜墨染（深） / 日光素白（浅）。
 * 接口：window.Sonnet = { init, setLyrics, start, stop, resize, isRunning,
 *                         setTheme, themeName, seek, debug, setTuning }
 * ================================================================== */
(function () {
  "use strict";

  var host = null;
  var hooks = { getTime: null, playing: null, hasTrack: null };
  var runtime = null;              // SonnetPixiRuntime
  var currentTime = null;          // MotionValue<number>（秒）
  var lrcLines = [], meta = {};
  var seedN = 0;
  var curSeed = 'sonnet-demo';
  var _lastProgram = null;
  var TUNING = null;
  var raf = 0;
  var started = false;
  var lastPaused = false;
  var demoClock = 0, demoPrev = 0;
  var isDrift = false, frameCount = 0, lastTime = 0;

  /* WebTerm 只覆盖字体（必须走 fontFamilyStack 数组，见 tempera-bridge.js 的长注） */
  var WEBTERM_FONT = {
    fontStyle: 'sans',
    fontFamily: 'Microsoft YaHei',
    fontFamilyStack: ['Microsoft YaHei', 'PingFang SC', 'Noto Sans SC'],
    fontWeight: 700,
    animationIntensity: 'normal'
  };

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

  function F() { return window.SonnetFolia || null; }

  function activeTheme() {
    if (!currentTheme) currentTheme = themeFor(isLight);
    return currentTheme;
  }

  /* 商籁自带整幅纸底（印刷排版 PV，画面是"纸上印刷"），host 铺同色只是
     防透明帧闪烁的兜底，与凝彩 paintHostPaper 同一做法。 */
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

  /* 渲染参数 = 原版 DEFAULT_SONNET_TUNING（types.ts:587）。兜底同样是原版值。 */
  var FALLBACK_TUNING = {
    cameraIntensity: 1, typographyMotion: 1, mgDensity: 1,
    showOnlyText: false, showGuide: false, showBackgroundMg: true,
    showFixedGeo: true, showGiantDecorativeText: true, showBackgroundDecor: true,
    enableTransitions: true, outerFrameMode: 'none', textureResolution: 1.5,
    postProcessEnabled: true, postProcessGrain: 0.12, postProcessContrast: 0,
    postProcessRgbShift: 0, postProcessHalftone: 0, postProcessVignette: 0.5,
    postProcessLensDistortion: 0, postProcessLensDispersion: 0
  };

  function tuning() {
    if (TUNING) return TUNING;
    var lib = F();
    var base = (lib && lib.DEFAULT_SONNET_TUNING) || FALLBACK_TUNING;
    try { TUNING = JSON.parse(JSON.stringify(base)); } catch (e) { TUNING = base; }
    return TUNING;
  }

  /* LRC [{t,text}] → Folia Line[]：与 tempera-bridge.js 同一套换算 */
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

  /* 没载入曲目时的演示歌词：60 个 8 秒「♪」虚拟行（原版纯音乐同款做法） */
  function demoLines() {
    var out = [];
    for (var i = 0; i < 60; i++) {
      out.push({
        id: 'sonnet-demo-' + i,
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

  function makeSeed() { seedN += 1; return 'webterm-sonnet-' + seedN; }

  function program(seed) {
    var lib = F();
    if (!lib) return null;
    _lastProgram = lib.compileSonnetProgram(songLines(), seed || curSeed);
    return _lastProgram;
  }

  function hasTrack() {
    if (!hooks.hasTrack) return true;
    try { return !!hooks.hasTrack(); } catch (e) { return true; }
  }

  function drive() {
    if (!runtime) return;
    frameCount++;
    var drift = !hasTrack();
    isDrift = drift;
    var t;
    if (drift) {
      var now = performance.now();
      if (!demoPrev) demoPrev = now;
      demoClock += Math.min(0.25, Math.max(0, (now - demoPrev) / 1000));
      demoPrev = now;
      var dur = timelineDuration();
      t = demoClock % Math.max(30, dur - 2);
    } else {
      demoPrev = 0;
      t = hooks.getTime ? (hooks.getTime() || 0) : 0;
    }
    currentTime.set(t);
    lastTime = t;

    var playing = drift ? true : (hooks.playing ? hooks.playing() : true);
    if (playing !== lastPaused) {
      lastPaused = playing;
      runtime.setPaused(!playing);
      if (!playing) { try { runtime.renderOnce(); } catch (e) {} }
    }
    raf = requestAnimationFrame(drive);
  }

  function timelineDuration() {
    if (lrcLines.length) {
      return Math.max(30, (Number(lrcLines[lrcLines.length - 1].t) || 0) + 12);
    }
    return 480;
  }

  window.Sonnet = {
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
      if (!runtime) return;
      curSeed = makeSeed();
      var p = program(curSeed);
      if (!p) return;
      runtime.swapSong({ seed: curSeed, program: p, theme: activeTheme() })
        .then(function () {
          runtime.setSongMetadata({
            title: meta.title || '', artist: meta.artist || '', album: meta.album || ''
          });
        })
        .catch(function (e) { console.error('[sonnet] swapSong', e); });
    },
    start: function () {
      if (started) return;
      if (!F() || !host) return;
      var lib = F();
      started = true;
      isLight = document.body.classList.contains('light');
      currentTheme = themeFor(isLight);
      paintHostPaper();
      currentTime = new lib.MotionValue(0);
      lastPaused = false;
      demoClock = 0;
      demoPrev = 0;
      if (!lrcLines.length) curSeed = 'sonnet-demo';
      var p = program(curSeed);
      if (!p) { started = false; return; }
      lib.SonnetPixiRuntime.create({
        host: host,
        songSeed: curSeed,
        program: p,
        theme: activeTheme(),
        tuning: tuning(),
        renderScale: 1,
        antialias: true,
        currentTime: currentTime,
        lyricsFontScale: 1,
        staticMode: false,
        transparentBackground: false,
        paused: false,
        songTitle: meta.title || '',
        songArtist: meta.artist || '',
        songAlbum: meta.album || ''
      }).then(function (rt) {
        runtime = rt;
        raf = requestAnimationFrame(drive);
      }).catch(function (e) {
        console.error('[sonnet] create failed', e);
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
    setTuning: function (patch) {
      var cur = tuning();
      if (patch) {
        for (var k in patch) if (Object.prototype.hasOwnProperty.call(patch, k)) cur[k] = patch[k];
      }
      if (runtime) {
        try { runtime.setRenderScale(1); } catch (e) {}
      }
      /* 商籁的 tuning 没有运行时热更方法（renderScale 除外），整份换要走重建。
         这里只记状态，下次 start / setLyrics 生效。 */
      return cur;
    },
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
        runtime.swapSong({ seed: curSeed, program: p, theme: currentTheme })
          .catch(function (e) { console.error('[sonnet] setTheme', e); });
      } catch (e) { console.error('[sonnet] setTheme', e); }
      return true;
    },
    themeName: function () { return activeTheme().name; },
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
    debug: function () {
      var th = activeTheme();
      var paragraphs = 0;
      try {
        paragraphs = _lastProgram
          ? ((_lastProgram.paragraphs && _lastProgram.paragraphs.length) ||
             (_lastProgram.shots && _lastProgram.shots.length) || 0)
          : 0;
      } catch (e) {}
      return {
        started: started, demo: isDrift, frames: frameCount, time: lastTime,
        ticker: !!runtime, light: isLight, theme: th.name,
        colors: {
          bg: th.backgroundColor, ink: th.primaryColor,
          accent: th.accentColor, secondary: th.secondaryColor
        },
        layout: runtime ? { w: runtime.lastWidth, h: runtime.lastHeight } : null,
        paragraphs: paragraphs,
        duration: timelineDuration(),
        tuning: tuning()
      };
    }
  };
})();
