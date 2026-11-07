/* ==================================================================
 * lumiere.js — 「绘光」舞台光歌词背景（Folia Lumiere 的轻量 Canvas 移植）
 * ------------------------------------------------------------------
 * 视觉对齐 oirge/Mineradio → Folia「绘光」：
 *   · 舞台追光（交叉追光 + 天井光）：锥形体积光束、条纹、核心亮线、慢速摆动
 *   · 烟雾（烟机 haze）：低透明度大光斑缓慢漂移，随低音变浓
 *   · 浮尘（motes）：细小光点向上漂移 + 闪烁，随高频变亮
 *   · 光晕（glare）：光源灯头光晕 + 歌词焦点区光晕（歌词像被光束照亮）
 * 实现 = 纯 Canvas 2D（与 galaxy 同级），零外部依赖。
 * 用法：Lumiere.init(canvas, {band: fn(f0,f1)→0..1, playing: fn()→bool})
 *       Lumiere.start() / Lumiere.stop() / Lumiere.resize()
 * ================================================================== */
(function () {
  "use strict";
  var cv = null, ctx = null;
  var running = false, handle = 0;
  var hooks = { band: null, playing: null };
  var W = 0, H = 0, dpr = 1;

  /* ---------------- 常量（绘光暖场：香槟金 / 暖白 / 微冷氛围） ---------------- */
  var BEAMS = [
    // 左上追光：香槟金，指向歌词焦点 (0.50, 0.58)
    { sx: 0.13, sy: -0.03, angle: Math.PI / 2, spread: 0.30, width: 0.035, length: 1.35,
      tint: [255, 205, 140], core: 0.85, streaks: 0.55, streakFreq: 7,
      swayAmp: 0.035, swayPeriod: 12, swayPhase: 0, base: 0.55, bassK: 0.45 },
    // 右上追光：暖白，交叉指向同一焦点
    { sx: 0.87, sy: -0.03, angle: Math.PI / 2, spread: 0.30, width: 0.035, length: 1.35,
      tint: [255, 236, 205], core: 0.80, streaks: 0.50, streakFreq: 8,
      swayAmp: 0.035, swayPeriod: 12, swayPhase: 0.55, base: 0.50, bassK: 0.42 },
    // 天井光：微冷白、宽而淡，垂直洒下作氛围底
    { sx: 0.50, sy: -0.06, angle: Math.PI / 2, spread: 0.62, width: 0.05, length: 1.10,
      tint: [214, 228, 255], core: 0.35, streaks: 0.18, streakFreq: 5,
      swayAmp: 0.012, swayPeriod: 19, swayPhase: 1.3, base: 0.28, bassK: 0.18 }
  ];
  var FOCUS = { x: 0.50, y: 0.58 };   // 歌词焦点：光束交汇、歌词被照亮处

  /* 烟雾：低透明度大光斑（暖灰 / 冷紫交替），screen 混合 */
  var HAZE_N = 34;
  var haze = [];
  var HAZE_COLS = ['255,214,158', '196,172,255', '255,236,205', '150,180,255'];

  /* 浮尘：细光点（香槟金 / 暖白 / 淡紫） */
  var MOTES_N = 150;
  var motes = [];
  var MOTE_COLS = ['255,222,170', '255,244,224', '214,196,255', '255,200,150'];
  var moteSprite = null;

  function rand(seed) { return Math.abs(Math.sin(seed * 3187.917) * 43758.5453) % 1; }

  function makeMoteSprite() {
    var s = document.createElement('canvas');
    s.width = 48; s.height = 48;
    var g = s.getContext('2d');
    var gr = g.createRadialGradient(24, 24, 0, 24, 24, 24);
    gr.addColorStop(0, 'rgba(255,255,255,1)');
    gr.addColorStop(0.22, 'rgba(255,236,200,0.85)');
    gr.addColorStop(1, 'rgba(255,236,200,0)');
    g.fillStyle = gr;
    g.fillRect(0, 0, 48, 48);
    return s;
  }

  function initParticles() {
    haze = [];
    for (var i = 0; i < HAZE_N; i++) {
      haze.push({
        x: rand(i * 3.1), y: rand(i * 7.7),
        r: 0.10 + rand(i * 5.3) * 0.26,
        a: 0.016 + rand(i * 9.1) * 0.030,
        dx: (rand(i * 2.2) - 0.5) * 0.012, dy: (rand(i * 4.4) - 0.5) * 0.008,
        col: HAZE_COLS[i % HAZE_COLS.length],
        ph: rand(i * 6.6) * 6.283
      });
    }
    motes = [];
    for (var j = 0; j < MOTES_N; j++) {
      motes.push({
        x: rand(j * 1.7), y: rand(j * 8.9),
        vy: 0.008 + rand(j * 3.3) * 0.030,          // 向上漂移
        vx: (rand(j * 5.5) - 0.5) * 0.006,
        size: 0.6 + rand(j * 4.1) * 1.9,
        tw: rand(j * 7.4) * 6.283, twSpd: 0.6 + rand(j * 2.9) * 1.6,
        sw: rand(j * 6.2) * 6.283, swSpd: 0.12 + rand(j * 3.7) * 0.35,
        col: MOTE_COLS[j % MOTE_COLS.length]
      });
    }
  }

  function resize() {
    if (!cv || !ctx) return;
    dpr = Math.min(1.35, Math.max(1, window.devicePixelRatio || 1));
    W = innerWidth;
    H = innerHeight;
    cv.width  = Math.max(1, Math.floor(W * dpr));
    cv.height = Math.max(1, Math.floor(H * dpr));
    cv.style.width  = W + 'px';
    cv.style.height = H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (!haze.length) initParticles();
  }

  /* 频段能量 0..1（由主脚本注入的 hooks.band 读取 analyser） */
  function band(f0, f1) {
    if (!hooks.band) return 0;
    var v = hooks.band(f0, f1);
    return v;
  }

  /* ---------------- 体积光束：锥形渐变 + 条纹 + 核心亮线 ---------------- */
  function drawBeam(t, b, bass, treble) {
    var sway = Math.sin(t / b.swayPeriod * 6.283 + b.swayPhase) * b.swayAmp;
    var angle = b.angle + sway;
    var L = b.length * Math.max(W, H);
    var halfW = b.width * W * 0.5;
    var halfS = Math.tan(b.spread) * L;
    var intensity = Math.min(1, b.base + bass * b.bassK);

    ctx.save();
    ctx.translate(b.sx * W, b.sy * H);
    ctx.rotate(angle);
    ctx.globalCompositeOperation = 'lighter';

    // 主锥体：沿长度方向 头亮 → 尾淡，横向向边缘柔化（分 6 条叠加出 softness）
    var steps = 6;
    for (var s = 0; s < steps; s++) {
      var tt = s / (steps - 1);
      var edgeA = Math.pow(1 - tt, 2.2) * intensity * 0.30;
      if (edgeA <= 0.003) continue;
      var inner = halfW * (1 - tt * 0.55);
      var outer = halfW + halfS * (1 - tt * 0.25) + tt * halfS * 0.4;
      var grad = ctx.createLinearGradient(0, 0, L, 0);
      grad.addColorStop(0, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',' + (edgeA * 1.25) + ')');
      grad.addColorStop(0.55, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',' + (edgeA * 0.75) + ')');
      grad.addColorStop(1, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',0)');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.moveTo(0, -inner);
      ctx.lineTo(L, -outer);
      ctx.lineTo(L, outer);
      ctx.lineTo(0, inner);
      ctx.closePath();
      ctx.fill();
    }

    // 核心亮线（更强的中心光柱）
    var coreA = b.core * intensity;
    var cg = ctx.createLinearGradient(0, 0, L, 0);
    cg.addColorStop(0, 'rgba(255,255,255,' + (coreA * 0.55) + ')');
    cg.addColorStop(0.5, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',' + (coreA * 0.40) + ')');
    cg.addColorStop(1, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',0)');
    ctx.fillStyle = cg;
    var cw = halfW * 0.34;
    ctx.beginPath();
    ctx.moveTo(0, -cw);
    ctx.lineTo(L, -cw * (1 - 0.6));
    ctx.lineTo(L, cw * (1 - 0.6));
    ctx.lineTo(0, cw);
    ctx.closePath();
    ctx.fill();

    // 条纹（streaks）：沿光束的细亮条，随音乐微微移动
    if (b.streaks > 0) {
      var n = Math.max(2, Math.round(b.streakFreq));
      for (var k = 0; k < n; k++) {
        var ft = (k / n + t * 0.02) % 1;
        var fx = L * (0.08 + ft * 0.92);
        var fh = (1 - ft) * halfS * 0.5 + 2;
        var fa = b.streaks * intensity * (0.10 + 0.10 * Math.sin(t * 1.3 + k * 2.1));
        var fg = ctx.createLinearGradient(fx - 20, 0, fx + 20, 0);
        fg.addColorStop(0, 'rgba(255,255,255,0)');
        fg.addColorStop(0.5, 'rgba(255,255,255,' + fa + ')');
        fg.addColorStop(1, 'rgba(255,255,255,0)');
        ctx.fillStyle = fg;
        ctx.fillRect(fx - 20, -fh, 40, fh * 2);
      }
    }
    ctx.restore();

    // 灯头光晕（glare at source）
    var gA = intensity * (0.5 + treble * 0.3);
    var gR = b.width * W * 1.6;
    var gg = ctx.createRadialGradient(b.sx * W, b.sy * H, 0, b.sx * W, b.sy * H, gR);
    gg.addColorStop(0, 'rgba(255,255,255,' + (gA * 0.5) + ')');
    gg.addColorStop(0.4, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',' + (gA * 0.18) + ')');
    gg.addColorStop(1, 'rgba(' + b.tint[0] + ',' + b.tint[1] + ',' + b.tint[2] + ',0)');
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = gg;
    ctx.fillRect(b.sx * W - gR, b.sy * H - gR, gR * 2, gR * 2);
    ctx.restore();
  }

  /* ---------------- 烟雾（烟机 haze） ---------------- */
  function drawHaze(t, bass) {
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (var i = 0; i < haze.length; i++) {
      var h = haze[i];
      var x = (h.x + t * h.dx) % 1.2 - 0.1;
      var y = (h.y + t * h.dy) % 1.2 - 0.1;
      var a = h.a * (1 + bass * 1.1) * (0.85 + 0.15 * Math.sin(t * 0.35 + h.ph));
      var px = x * W, py = y * H, pr = h.r * Math.max(W, H);
      var g = ctx.createRadialGradient(px, py, 0, px, py, pr);
      g.addColorStop(0, 'rgba(' + h.col + ',' + a + ')');
      g.addColorStop(1, 'rgba(' + h.col + ',0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.arc(px, py, pr, 0, 6.283);
      ctx.fill();
    }
    ctx.restore();
  }

  /* ---------------- 浮尘（motes） ---------------- */
  function drawMotes(t, treble) {
    if (!moteSprite) moteSprite = makeMoteSprite();
    var boost = 1 + treble * 1.4;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (var i = 0; i < motes.length; i++) {
      var m = motes[i];
      var y = (m.y - t * m.vy) % 1.15 + 0.02;
      var x = (m.x + Math.sin(t * m.swSpd + m.sw) * 0.012 + t * m.vx) % 1.1 - 0.05;
      var tw = Math.pow(0.5 + 0.5 * Math.sin(t * m.twSpd + m.tw), 2.4);
      var a = (0.10 + tw * 0.55) * boost;
      if (a <= 0.01) continue;
      var r = m.size * (0.8 + tw * 0.5) * (1 + treble * 0.25);
      ctx.globalAlpha = Math.min(1, a);
      ctx.drawImage(moteSprite, x * W - r * 3.2, y * H - r * 3.2, r * 6.4, r * 6.4);
    }
    ctx.restore();
    ctx.globalAlpha = 1;
  }

  /* ---------------- 歌词焦点光晕（交汇处，随音乐脉动） ---------------- */
  function drawFocus(t, bass, treble) {
    var fx = FOCUS.x * W, fy = FOCUS.y * H;
    var pulse = 1 + 0.18 * Math.sin(t * 1.9) + bass * 0.55;
    var r = Math.max(W, H) * 0.16 * pulse;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    var g = ctx.createRadialGradient(fx, fy, 0, fx, fy, r);
    g.addColorStop(0, 'rgba(255,238,205,' + (0.30 + bass * 0.35 + treble * 0.10) + ')');
    g.addColorStop(0.5, 'rgba(255,190,120,' + (0.10 + bass * 0.12) + ')');
    g.addColorStop(1, 'rgba(255,190,120,0)');
    ctx.fillStyle = g;
    ctx.fillRect(fx - r, fy - r, r * 2, r * 2);
    ctx.restore();
  }

  /* ---------------- 主循环 ---------------- */
  function frame(nowMs) {
    if (!running || !ctx) return;
    var t = nowMs * 0.001;
    var playing = hooks.playing ? hooks.playing() : false;
    if (playing) t *= 1.2;
    var bass = band(20, 250);      // 低音 → 光束/烟雾
    var treble = band(6000, 16000); // 高频 → 浮尘/灯头
    if (!playing) { bass *= 0.55; treble *= 0.5; }

    // 深邃暖黑底（绘光始终在暗场里）
    ctx.globalCompositeOperation = 'source-over';
    ctx.globalAlpha = 1;
    ctx.fillStyle = '#050208';
    ctx.fillRect(0, 0, W, H);

    drawHaze(t, bass);
    for (var i = 0; i < BEAMS.length; i++) drawBeam(t, BEAMS[i], bass, treble);
    drawFocus(t, bass, treble);
    drawMotes(t, treble);
    handle = requestAnimationFrame(frame);
  }

  /* ---------------- 公共接口 ---------------- */
  window.Lumiere = {
    init: function (canvas, h) {
      cv = canvas;
      ctx = cv ? cv.getContext('2d', { alpha: false }) : null;
      if (h) { if (h.band) hooks.band = h.band; if (h.playing) hooks.playing = h.playing; }
      resize();
    },
    start: function () {
      if (running || !cv || !ctx) return;
      running = true;
      if (!haze.length) initParticles();
      resize();
      frame(performance.now());      // 首帧同步绘制（避免黑屏闪烁）
    },
    stop: function () {
      running = false;
      if (handle) { cancelAnimationFrame(handle); handle = 0; }
    },
    resize: resize,
    isRunning: function () { return running; }
  };
})();
