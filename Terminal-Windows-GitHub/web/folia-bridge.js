/* ==================================================================
 * folia-bridge.js — WebTerm「绘光」接入桥（Folia Lumiere 运行时）
 * ------------------------------------------------------------------
 * 依赖：web/folia/adapter.js（module，加载后暴露 window.LumiereFolia）
 * 职责：LRC → Line[] → compileLumiereProgram → LumierePixiRuntime
 *       每帧喂 currentTime / audioPower / audioBands，
 *       光效（镜头 / 光位 / 歌词点亮）由歌词时间轴驱动。
 * 接口：window.Lumiere = { init, setLyrics, start, stop, resize, isRunning }
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
  var raf = 0;
  var started = false;
  var lastPaused = false;

  var THEME = {
    name: 'WebTerm 绘光',
    backgroundColor: '#050208',
    primaryColor: '#fff8ec',
    accentColor: '#ffd98c',
    secondaryColor: '#9a8cff',
    fontStyle: 'sans',
    fontFamily: '"Microsoft YaHei","PingFang SC","Noto Sans SC",sans-serif',
    fontWeight: 700,
    animationIntensity: 'normal'
  };

  var TUNING = {
    lightIntensity: 1, audioResponse: 1, fogDensity: 1, darkField: 0.8,
    moteAmount: 1, bloom: 1, textBloom: 1, unlitOpacity: 0.2,
    windowNeighbors: 2, decay: 1, echo: 1, fogOctaves: 5,
    lineArt: true, frontBokeh: true, trails: true, hideTrails: false,
    seamlessTransitions: true, overlayFrame: false, textOnly: false,
    keywordColors: false, themeIcons: false, themeColorMix: 0.3,
    renderQuality: 'balanced'
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

  function program() {
    _lastProgram = window.LumiereFolia.compileLumiereProgram(buildLines(), 'webterm-' + (seedN++), {}, {
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

  function drive() {
    if (!runtime) return;
    var t = hooks.getTime ? (hooks.getTime() || 0) : 0;
    /* Folia 的 currentTime 是「秒」：audioElement.currentTime 直接 set。
     * 传毫秒会让时间轴放大 1000 倍，直接跳进片尾段落（光效抽风、歌词不点亮）。 */
    currentTime.set(t);
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
    } else {
      /* 无信号：原版「呼吸」0..40，全频段同值，光效不静止 */
      var breath = (Math.sin(Date.now() / 2000) + 1) * 20;
      audioPower.set(breath);
      audioBands.bass.set(breath);
      audioBands.lowMid.set(breath);
      audioBands.mid.set(breath);
      audioBands.vocal.set(breath);
      audioBands.treble.set(breath);
    }
    var playing = hooks.playing ? hooks.playing() : true;
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
      }
    },
    setLyrics: function (lrc, m) {
      lrcLines = lrc || [];
      meta = m || {};
      if (runtime) {
        runtime.swapSong({ seed: 'webterm-' + seedN, program: program(), theme: THEME })
          .then(function () {
            runtime.setSongMetadata({ title: meta.title || '', artist: meta.artist || '' });
          })
          .catch(function (e) { console.error('[lumiere] swapSong', e); });
      }
    },
    start: function () {
      if (started) return;
      if (!F() || !host) return;
      started = true;
      var lib = F();
      currentTime = new lib.MotionValue(0);
      audioPower = new lib.MotionValue(0);
      audioBands = {
        bass: new lib.MotionValue(0), lowMid: new lib.MotionValue(0),
        mid: new lib.MotionValue(0), vocal: new lib.MotionValue(0), treble: new lib.MotionValue(0)
      };
      lastPaused = false;
      /* 暂停时 ticker 停着，currentTime 变化要手动补一帧（同 Folia VisualizerLumiere；
       * stub MotionValue 提供 onChange 而非 framer-motion 的 on('change')） */
      currentTime.onChange(function () {
        if (lastPaused && runtime) runtime.renderOnce();
      });
      lib.LumierePixiRuntime.create({
        host: host,
        song: { seed: 'webterm', program: program(), theme: THEME },
        tuning: TUNING,
        currentTime: currentTime,
        audioPower: audioPower,
        audioBands: audioBands,
        staticMode: false,
        showText: true,
        paused: false,
        metadata: { title: meta.title || '', artist: meta.artist || '' }
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
    isRunning: function () { return started; }
  };
})();
