/* ==================================================================
 * folia-bridge.js — WebTerm「绘光」接入桥（Folia Lumiere 运行时）
 * ------------------------------------------------------------------
 * 依赖：web/folia/adapter.js（module，加载后暴露 window.LumiereFolia）
 * 职责：LRC → Line[] → compileLumiereProgram → LumierePixiRuntime
 *       每帧喂 currentTime / audioPower / audioBands，
 *       光效（镜头 / 光位 / 歌词点亮）由歌词时间轴驱动。
 * 主题：用 Folia 原版 services/baseThemes.ts 的两套内置预设（原样移植进
 *       build-folia/lumiere-src/services/），但**不再由绘光自己选**：
 *       午夜墨染 = 终端黑主题，日光素白 = 终端白主题，跟着【设置】→【外观】走。
 * 接口：window.Lumiere = { init, setLyrics, start, stop, resize, isRunning, setTheme }
 * ================================================================== */
(function () {
  "use strict";

  var host = null;            // host 容器（#lumiere-canvas，已改为 div）
  var hooks = { band: null, playing: null, getTime: null };
  var runtime = null;         // LumierePixiRuntime
  var currentTime = null, audioPower = null, audioBands = null;
  var lrcLines = [], meta = {};
  var seedN = 0;
  var _lastProgram = null;
  var curSeed = 'webterm';    // 当前已 commit 的 seed：换主题时沿用同一个才不走跨曲转场
  var raf = 0;
  var started = false;
  var lastPaused = false;     // 存的是「上一帧是否在动」，与 setPaused 取反
  var demoClock = 0, demoPrev = 0;   // 演示态时间轴（没载入曲目时自走）
  var isDrift = false, frameCount = 0, lastTime = 0;   // 自检用

  /* 原版两套预设之外，WebTerm 只覆盖字体（歌词字形跟终端走，别用 Folia 自带的 sans）。
     颜色一个字不改：午夜墨染 #09090b / 日光素白 #f5f5f4 都是 baseThemes.ts 的原值。 */
  var WEBTERM_FONT = {
    fontStyle: 'sans',
    fontFamily: '"Microsoft YaHei","PingFang SC","Noto Sans SC",sans-serif',
    fontWeight: 700,
    animationIntensity: 'normal'
  };

  /* adapter 还没重新构建时的兜底（= 旧版写死的那套深色），保证绘光不至于无主题可渲染 */
  var LEGACY_THEME = {
    name: 'WebTerm 绘光',
    backgroundColor: '#050208',
    primaryColor: '#fff8ec',
    accentColor: '#ffd98c',
    secondaryColor: '#9a8cff'
  };

  var isLight = false;                  // 当前是否走「日光素白」
  /* 懒求值：adapter.js 是 module（defer），本文件是普通 script，加载顺序上先于它 ——
     所以不能在 IIFE 顶层就取主题，得等真正要渲染时再读 window.LumiereFolia。 */
  var currentTheme = null;

  function activeTheme() {
    if (!currentTheme) currentTheme = themeFor(isLight);
    return currentTheme;
  }

  function themeFor(light) {
    var lib = F();
    var dual = lib && lib.BASE_DUAL_THEME;
    var base = dual ? (light ? dual.light : dual.dark) : LEGACY_THEME;
    var t = {};
    for (var k in base) if (Object.prototype.hasOwnProperty.call(base, k)) t[k] = base[k];
    for (var f in WEBTERM_FONT) if (Object.prototype.hasOwnProperty.call(WEBTERM_FONT, f)) t[f] = WEBTERM_FONT[f];
    t.name = (light ? '日光素白' : '午夜墨染') + ' · WebTerm';
    return t;
  }


  /* 渲染参数 = 原版 DEFAULT_LUMIERE_TUNING（vendor-src/folia/src/types.ts:761）逐字段对齐。
     之前这里是一份「保守值」：关掉了画框（overlayFrame）、关键词着色（keywordColors）、
     主题图标（themeIcons），画质压到 balanced，暗场 0.8、未唱字 0.2 —— 于是 WebTerm 里的
     绘光比原版少了取景器画框、雾与辉光也糊一档，观感是「效果不全」。
     现在按原版默认打开：画框是四角括号 + 左右对位十字 + 顶边虚线（overlay.ts），
     配色跟着主题走（浅色主题下画框线变成强调色）。 */
  var TUNING = {
    lightIntensity: 1, audioResponse: 1, fogDensity: 1, darkField: 0.75,
    moteAmount: 1, bloom: 1, textBloom: 1, unlitOpacity: 0.22,
    windowNeighbors: 2, decay: 1, echo: 1, fogOctaves: 5,
    lineArt: true, frontBokeh: true, trails: true, hideTrails: false,
    seamlessTransitions: true, overlayFrame: true, textOnly: false,
    keywordColors: true, themeIcons: true, themeColorMix: 0.3,
    renderQuality: 'full'
  };

  function F() { return window.LumiereFolia || null; }

  /* LRC [{t,text}] → Folia Line[]（词时间按字符长度比例分摊到行区间） */
  function buildLines() {
    var out = [];
    for (var i = 0; i < lrcLines.length; i++) {
      var ln = lrcLines[i];
      var text = String(ln.text || '').trim();
      if (!text) continue;
      var start = Number(ln.t) || 0;
      var end = (i + 1 < lrcLines.length) ? (Number(lrcLines[i + 1].t) || start + 4) : (start + 4);
      if (end <= start) end = start + 4;
      var segs = null;
      try { segs = window.LumiereFolia.segmentTextWords ? window.LumiereFolia.segmentTextWords(text) : null; } catch (e) {}
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

  function songDuration() {
    if (!lrcLines.length) return 480;
    return (Number(lrcLines[lrcLines.length - 1].t) || 0) + 12;
  }

  /* 每次换歌给一个新 seed：同 seed 的 swapSong 会走「同曲直通」分支（换主题用），
     真换歌必须换 seed 才会走跨曲交接动画。program 的编译 seed 与 song.seed 必须是同一个，
     否则运行时认为「还是这首歌」但画面按另一套随机序列建 —— 之前就是 curSeed 比编译时的 seedN 多 1。 */
  function makeSeed() { seedN += 1; return 'webterm-' + seedN; }

  function program(seed) {
    _lastProgram = window.LumiereFolia.compileLumiereProgram(buildLines(), seed || makeSeed(), {}, {
      duration: songDuration(),
      seamless: !!TUNING.seamlessTransitions
    });
    return _lastProgram;
  }

  function band(f0, f1) { return hooks.band ? (hooks.band(f0, f1) || 0) : 0; }

  /* 0..1 输入 → 0..255 输出（对齐 Folia 主播放器量纲：getByteFrequencyData 均值 + pow 压缩）。
   * sampler 见过 >1 就按 /255 归一化，统一量纲避免 rawScale 粘住误判。 */
  function to255(value, boost) {
    var v = Math.max(0, Math.min(1, value || 0));
    return Math.pow(v, boost) * 255;
  }

  /* 是否已经载入曲目。没载入时不能按「暂停」处理 —— 见 drive() 里的说明。
   * 页面侧没提供钩子时按「有曲目」处理，保持原有行为不变。 */
  function hasTrack() {
    if (!hooks.hasTrack) return true;
    try { return !!hooks.hasTrack(); } catch (e) { return true; }
  }

  function drive() {
    if (!runtime) return;
    /* ---------- 演示态（没载入音乐，只是把绘光当背景看） ----------
       runtime.setPaused(true) 会 app.stop() 停掉 Pixi ticker，只 renderOnce 一帧；
       时间轴也钉在 0 秒，音频能量归零 —— 于是整屏只剩一帧静止暗场，
       用户看到的就是「点了绘光没效果」。
       所以没曲目时按演示态跑：时间轴自己走（按节目时长循环），
       画面常亮、镜头照常运动，音量给一条可见的呼吸基线。 */
    var drift = !hasTrack();
    isDrift = drift;
    frameCount++;
    var t;
    if (drift) {
      var now = performance.now();
      if (!demoPrev) demoPrev = now;
      demoClock += Math.min(0.25, Math.max(0, (now - demoPrev) / 1000));   // 切后台回来不跳帧
      demoPrev = now;
      var dur = (_lastProgram && _lastProgram.duration) || 460;
      t = demoClock % Math.max(30, dur - 4);        // 到片尾前回绕，段落永远接得上
    } else {
      demoPrev = 0;
      t = hooks.getTime ? (hooks.getTime() || 0) : 0;
    }
    /* Folia 的 currentTime 是「秒」：audioElement.currentTime 直接 set。
     * 传毫秒会让时间轴放大 1000 倍，直接跳进片尾段落（光效抽风、歌词不点亮）。 */
    currentTime.set(t);
    lastTime = t;
    var b0 = band(20, 150), b1 = band(150, 400), b2 = band(400, 2000),
        b3 = band(2000, 6000), b4 = band(6000, 16000);
    if (b0 + b1 + b2 + b3 + b4 > 0.02) {
      /* 有音频信号：按原版 process 压缩进 0..255 */
      audioPower.set(to255((b0 + b1) / 2, 3));
      audioBands.bass.set(to255(b0, 1.8));
      audioBands.lowMid.set(to255(b1, 2));
      audioBands.mid.set(to255(b2, 2));
      audioBands.vocal.set(to255(b3, 1.5));
      audioBands.treble.set(to255(b4, 2));
    } else if (drift) {
      /* 演示态：给一条 0..255 量纲里的「舞台呼吸」。原版暂停态那套 0..40
         是给静态封面兜底的，照搬过来光会暗到看不见（实测整屏只有尘埃点）。
         各频段错相位，光束慢、浮尘快，层次才不像一个灯在闪。 */
      var cyc = Date.now() / 1000;
      var base = 118 + 44 * Math.sin(cyc / 1.9);
      var bassV = Math.max(0, base + 36 * Math.sin(cyc / 3.1));
      var midV = Math.max(0, base * 0.86 + 26 * Math.sin(cyc / 2.4 + 1.1));
      var trebV = Math.max(0, base * 0.62 + 30 * Math.sin(cyc / 0.9 + 2.2));
      audioPower.set(base);
      audioBands.bass.set(bassV);
      audioBands.lowMid.set(midV);
      audioBands.mid.set(midV);
      audioBands.vocal.set(trebV);
      audioBands.treble.set(trebV);
    } else {
      /* 有曲目但暂停：保持原版「呼吸」0..40，全频段同值，光效不静止 */
      var breath = (Math.sin(Date.now() / 2000) + 1) * 20;
      audioPower.set(breath);
      audioBands.bass.set(breath);
      audioBands.lowMid.set(breath);
      audioBands.mid.set(breath);
      audioBands.vocal.set(breath);
      audioBands.treble.set(breath);
    }
    var playing = drift ? true : (hooks.playing ? hooks.playing() : true);
    if (playing !== lastPaused) {
      lastPaused = playing;
      runtime.setPaused(!playing);
    }
    raf = requestAnimationFrame(drive);
  }

  window.Lumiere = {
    init: function (container, h) {
      host = container;
      if (h) {
        if (h.band) hooks.band = h.band;
        if (h.playing) hooks.playing = h.playing;
        if (h.getTime) hooks.getTime = h.getTime;
        if (h.hasTrack) hooks.hasTrack = h.hasTrack;
      }
    },
    setLyrics: function (lrc, m) {
      lrcLines = lrc || [];
      meta = m || {};
      if (runtime) {
        curSeed = makeSeed();
        runtime.swapSong({ seed: curSeed, program: program(curSeed), theme: activeTheme() })
          .then(function () {
            runtime.setSongMetadata({
              title: meta.title || '', artist: meta.artist || '', album: meta.album || ''
            });
          })
          .catch(function (e) { console.error('[lumiere] swapSong', e); });
      }
    },
    start: function () {
      if (started) return;
      if (!F() || !host) return;
      started = true;
      var lib = F();
      /* 首帧取色以 body.light 为准（终端白主题 = 日光素白）：
         applyTheme 在启动时可能早于本文件执行，那次 setTheme 只落了状态，这里对齐一次。 */
      isLight = document.body.classList.contains('light');
      currentTheme = themeFor(isLight);
      currentTime = new lib.MotionValue(0);
      audioPower = new lib.MotionValue(0);
      audioBands = {
        bass: new lib.MotionValue(0), lowMid: new lib.MotionValue(0),
        mid: new lib.MotionValue(0), vocal: new lib.MotionValue(0), treble: new lib.MotionValue(0)
      };
      lastPaused = false;
      demoClock = 0;
      demoPrev = 0;
      /* 暂停时 ticker 停着，currentTime 变化要手动补一帧（同 Folia VisualizerLumiere；
       * stub MotionValue 提供 onChange 而非 framer-motion 的 on('change')） */
      currentTime.onChange(function () {
        if (lastPaused && runtime) runtime.renderOnce();
      });
      lib.LumierePixiRuntime.create({
        host: host,
        song: { seed: curSeed, program: program(curSeed), theme: activeTheme() },
        tuning: TUNING,
        currentTime: currentTime,
        audioPower: audioPower,
        audioBands: audioBands,
        staticMode: false,
        showText: true,
        paused: false,
        metadata: { title: meta.title || '', artist: meta.artist || '', album: meta.album || '' }
      }).then(function (rt) {
        runtime = rt;
        raf = requestAnimationFrame(drive);
      }).catch(function (e) {
        console.error('[lumiere] create failed', e);
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
    /* 终端【外观】黑 / 白 → 绘光主题（午夜墨染 / 日光素白）。
       没起 runtime 时只记状态，start() 会直接用新主题；起了就当场换色。
       返回 true 表示真的换了一套。 */
    setTheme: function (light) {
      light = !!light;
      if (light === isLight && currentTheme) return false;
      isLight = light;
      currentTheme = themeFor(light);
      if (!runtime) return true;
      /* 沿用当前 seed：runtime 的 swapSong 见到同 seed 会走 commitSong 直通分支，
         不做跨曲交接动画，只清掉已建段落、下一帧按新配色重建。 */
      var p = _lastProgram || program();
      try {
        runtime.swapSong({ seed: curSeed, program: p, theme: currentTheme })
          .catch(function (e) { console.error('[lumiere] setTheme', e); });
      } catch (e) { console.error('[lumiere] setTheme', e); }
      if (lastPaused) runtime.renderOnce();   // 暂停态 ticker 是停的，手动补一帧
      return true;
    },
    themeName: function () { return activeTheme().name; },
    /* 自检用：demo=true 表示没载入曲目、走的是演示态（时间轴自走 + 舞台光常亮）。
       frames 递增即证明 ticker 没被 setPaused(true) 停掉。 */
    debug: function () {
      return {
        started: started, demo: isDrift, frames: frameCount, time: lastTime,
        ticker: !!runtime, light: isLight, theme: activeTheme().name,
        tuning: TUNING
      };
    }
  };
})();
