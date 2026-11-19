/* ==================================================================
 * nebbia-bridge.js — WebTerm「雾笺」接入桥（Folia Nebbia 运行时）
 * ------------------------------------------------------------------
 * 依赖：web/folia-adapter.js（module，加载后暴露 window.NebbiaFolia）
 * 职责：LRC → Line[] → NebbiaRuntime（WebGL2 雾玻璃 PV）
 *       雾笺是原版里接「智能动感」链路最深的可视化器：
 *         - 节拍/鼓点 → localAnalysisBoundary（beatFx 时钟 + 实时 FFT 打点）
 *         - 人声能量 → input.vocal（MotionValue，即 audioBands.vocal 同款 0..255）
 *       原版由播放器宿主每帧调 advanceLocalAnalysis + applyLocalAnalysisAudio；
 *       这里桥接层照搬同一套驱动，FFT 帧由宿主经 hooks.audioFrame() 提供
 *       （index.html 用 sonicAnalyser 那路 1024 点分析器，分辨率够 35-9000Hz 三段打点）。
 * 主题：原版两套预设跟终端黑白走；雾笺额外有 setDaylight（雾的浓淡配色）。
 * 接口：window.Nebbia = { init, setLyrics, start, stop, resize, isRunning,
 *                         setTheme, themeName, seek, debug, setTuning }
 * ================================================================== */
(function () {
  "use strict";

  var host = null;
  var hooks = { getTime: null, playing: null, hasTrack: null, audioFrame: null };
  var runtime = null;              // NebbiaRuntime
  var currentTime = null;          // MotionValue<number>（秒）
  var vocal = null;                // MotionValue<number>（0..255，audioBands.vocal 同款）
  var lrcLines = [], meta = {};
  var seedN = 0;
  var curSeed = 'nebbia-demo';
  var TUNING = null;               // NebbiaTuning（normalize 后）
  var raf = 0;
  var started = false;
  var lastPaused = null;
  var demoClock = 0, demoPrev = 0;
  var isDrift = false, frameCount = 0, lastTime = 0;
  var trackKey = 'nebbia-none';    // advanceLocalAnalysis 的 trackId
  var analysisOK = false;

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

  function F() { return window.NebbiaFolia || null; }

  function activeTheme() {
    if (!currentTheme) currentTheme = themeFor(isLight);
    return currentTheme;
  }

  /* 雾笺画布自带整幅窗景（不透明模式），host 铺同色兜底与凝彩同一做法 */
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

  /* tuning = 原版 DEFAULT_NEBBIA_TUNING 经 normalizeNebbiaTuning 校验 */
  function tuning() {
    if (TUNING) return TUNING;
    var lib = F();
    var base = (lib && lib.DEFAULT_NEBBIA_TUNING) || {
      behind: 'night', glass: 'clear', rain: 'none', credits: true
    };
    try {
      TUNING = lib && lib.normalizeNebbiaTuning
        ? lib.normalizeNebbiaTuning(JSON.parse(JSON.stringify(base)))
        : JSON.parse(JSON.stringify(base));
    } catch (e) { TUNING = base; }
    return TUNING;
  }

  /* LRC [{t,text}] → Folia Line[]：与 tempera/sonnet-bridge 同一套换算 */
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

  function demoLines() {
    var out = [];
    for (var i = 0; i < 60; i++) {
      out.push({
        id: 'nebbia-demo-' + i,
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

  function makeSeed() { seedN += 1; return 'webterm-nebbia-' + seedN; }

  function hasTrack() {
    if (!hooks.hasTrack) return true;
    try { return !!hooks.hasTrack(); } catch (e) { return true; }
  }

  /* 人声频段 0..255（与 folia-bridge.js 的 to255 / band(2000,6000) 同一套量纲） */
  function vocalBand() {
    var frame = audioFrame();
    if (!frame || !frame.frequency || !frame.frequency.length) return 0;
    var sr = frame.sampleRate || 44100;
    var n = frame.frequency.length, fft = frame.fftSize || (n * 2);
    var from = Math.max(0, Math.ceil(2000 * fft / sr));
    var until = Math.min(n, Math.ceil(6000 * fft / sr));
    var sum = 0;
    for (var i = from; i < until; i++) sum += frame.frequency[i];
    var avg = sum / Math.max(1, until - from);
    /* byte 0..255 已经是量纲，直接原值（folia-bridge 的 to255(b3,1.5) 输入是 0..1，
       这里 analyser 给的就是 0..255 byte，等价于 to255 输出） */
    return Math.max(0, Math.min(255, avg * 1.5));
  }

  function audioFrame() {
    if (!hooks.audioFrame) return null;
    try { return hooks.audioFrame(); } catch (e) { return null; }
  }

  /* 每帧驱动：时间 / 人声 MV / 智能动感分析链路（节拍时钟 + 实时 FFT 打点） */
  function drive() {
    if (!runtime || !currentTime) return;
    frameCount++;
    var drift = !hasTrack();
    isDrift = drift;
    var t, playing;
    if (drift) {
      var now = performance.now();
      if (!demoPrev) demoPrev = now;
      demoClock += Math.min(0.25, Math.max(0, (now - demoPrev) / 1000));
      demoPrev = now;
      var dur = 480;
      t = demoClock % Math.max(30, dur - 2);
      playing = true;
    } else {
      demoPrev = 0;
      t = hooks.getTime ? (hooks.getTime() || 0) : 0;
      playing = hooks.playing ? !!hooks.playing() : true;
    }
    lastTime = t;
    currentTime.set(t);
    vocal.set(drift ? (118 + 30 * Math.sin(Date.now() / 1000)) : vocalBand());

    /* 智能动感链路：原版播放器每帧调 advanceLocalAnalysis（秒 / 播放态），
       音频帧另走 applyLocalAnalysisAudio（30Hz FFT）。桥接层每帧喂一次。 */
    var lib = F();
    if (lib && lib.advanceLocalAnalysis) {
      try {
        if (!drift && trackKey === 'nebbia-none') trackKey = 'nebbia-track';
        lib.advanceLocalAnalysis(drift ? null : trackKey, t, playing);
        var frame = audioFrame();
        if (frame && frame.frequency && frame.frequency.length && playing) {
          lib.applyLocalAnalysisAudio({
            trackId: trackKey,
            playing: playing,
            position: t,
            frequency: frame.frequency,
            fftSize: frame.fftSize,
            sampleRate: frame.sampleRate
          });
          analysisOK = true;
        }
      } catch (e) { /* 分析链路失败不影响雾面本身 */ }
    }

    var paused = drift ? false : !playing;
    if (paused !== lastPaused) {
      lastPaused = paused;
      try { runtime.setPlayback(paused, false); } catch (e) {}
    }
    raf = requestAnimationFrame(drive);
  }

  window.Nebbia = {
    init: function (container, h) {
      host = container;
      if (h) {
        if (h.getTime) hooks.getTime = h.getTime;
        if (h.playing) hooks.playing = h.playing;
        if (h.hasTrack) hooks.hasTrack = h.hasTrack;
        if (h.audioFrame) hooks.audioFrame = h.audioFrame;
      }
    },
    setLyrics: function (lrc, m) {
      lrcLines = lrc || [];
      meta = m || {};
      if (!runtime) return;
      curSeed = makeSeed();
      try {
        runtime.setSong({
          lines: songLines(),
          seed: curSeed,
          title: meta.title || '',
          artist: meta.artist || ''
        });
      } catch (e) { console.error('[nebbia] setSong', e); }
    },
    start: function () {
      if (started) return;
      if (!F() || !host) return;
      var lib = F();
      if (!lib.NebbiaRuntime) return;
      started = true;
      isLight = document.body.classList.contains('light');
      currentTheme = themeFor(isLight);
      paintHostPaper();
      currentTime = new lib.MotionValue(0);
      vocal = new lib.MotionValue(0);
      lastPaused = null;
      demoClock = 0;
      demoPrev = 0;
      if (!lrcLines.length) curSeed = 'nebbia-demo';
      try {
        runtime = new lib.NebbiaRuntime({
          host: host,
          seeThrough: false,          // 画整幅窗景（透明模式留给以后「壁纸透出」玩）
          scene: 'night',             // tuning().behind 的画面族；换场景要重建运行时
          glass: tuning().glass,
          currentTime: currentTime,
          vocal: vocal,
          onFirstFrame: function () {},
          onFailed: function () { console.warn('[nebbia] runtime failed'); },
          onRestored: function () {}
        }, activeTheme(), tuning(), 1);
      } catch (e) {
        console.error('[nebbia] create failed', e);
        started = false;
        return;
      }
      try {
        runtime.setSong({
          lines: songLines(),
          seed: curSeed,
          title: meta.title || '',
          artist: meta.artist || ''
        });
      } catch (e) { console.error('[nebbia] setSong', e); }
      raf = requestAnimationFrame(drive);
    },
    stop: function () {
      started = false;
      if (raf) { cancelAnimationFrame(raf); raf = 0; }
      if (runtime) {
        try { runtime.destroy(); } catch (e) {}
        runtime = null;
      }
      var lib = F();
      if (lib && lib.clearLocalAnalysis) {
        try { lib.clearLocalAnalysis(null); } catch (e) {}
      }
      trackKey = 'nebbia-none';
      analysisOK = false;
      if (host) { while (host.firstChild) host.removeChild(host.firstChild); }
    },
    resize: function () { /* NebbiaRuntime 自带 ResizeObserver 跟随 host 尺寸 */ },
    isRunning: function () { return started; },
    setTuning: function (patch) {
      var cur = tuning();
      if (patch) {
        var lib = F();
        var merged = {};
        for (var k in cur) if (Object.prototype.hasOwnProperty.call(cur, k)) merged[k] = cur[k];
        for (var k2 in patch) if (Object.prototype.hasOwnProperty.call(patch, k2)) merged[k2] = patch[k2];
        try {
          cur = (lib && lib.normalizeNebbiaTuning) ? lib.normalizeNebbiaTuning(merged) : merged;
        } catch (e) { cur = merged; }
        TUNING = cur;
      }
      if (runtime) {
        try { runtime.setTuning(cur); } catch (e) { console.error('[nebbia] setTuning', e); }
      }
      return cur;
    },
    /* 终端【外观】黑 / 白 → 雾笺主题。雾的浓淡配色跟日夜（setDaylight）。 */
    setTheme: function (light) {
      light = !!light;
      if (light === isLight && currentTheme) return false;
      isLight = light;
      currentTheme = themeFor(light);
      paintHostPaper();
      if (!runtime) return true;
      try {
        runtime.setTheme(currentTheme);
        runtime.setDaylight(light);
      } catch (e) { console.error('[nebbia] setTheme', e); }
      return true;
    },
    themeName: function () { return activeTheme().name; },
    seek: function (t) {
      t = Math.max(0, Number(t) || 0);
      demoClock = t;
      demoPrev = 0;
      if (currentTime) currentTime.set(t);
      /* 雾笺自驱循环按 currentTime MV 读时间，无需手动补帧 */
      return t;
    },
    debug: function () {
      var th = activeTheme();
      var lib = F();
      var beats = 0;
      try { beats = (lib && lib.getBeatFxEvents) ? (lib.getBeatFxEvents() || []).length : 0; } catch (e) {}
      var laid = null;
      try { laid = runtime && runtime.laid; } catch (e) {}
      var charInfo = null;
      try {
        if (laid && laid.length) {
          var all = [], li;
          for (li = 0; li < laid.length; li++) all = all.concat(laid[li].chars || []);
          charInfo = {
            lines: laid.length, chars: all.length,
            withGlyph: all.filter(function (c) { return c.glyph; }).length,
            modes: laid.map(function (l) { return l.mode; }).slice(0, 6),
            firstT: all.length ? all[0].t : null, firstWt: all.length ? all[0].wt : null
          };
        }
      } catch (e) { charInfo = 'err:' + e.message; }
      return {
        started: started, demo: isDrift, frames: frameCount, time: lastTime,
        running: !!runtime, light: isLight, theme: th.name,
        laid: charInfo,
        colors: {
          bg: th.backgroundColor, ink: th.primaryColor,
          accent: th.accentColor, secondary: th.secondaryColor
        },
        tuning: tuning(),
        analysis: { fed: analysisOK, beats: beats }
      };
    }
  };
})();
