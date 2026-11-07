/**
 * 音域回响 (Sonic Topography) 宿主层 —— 视觉预设 7 的落地壳。
 *
 * 地形本体住在 sonic-topography-preset.js（从 Mineradio 原版整件搬来，未改一行）；
 * 音域监视器住在 sonic-audio-monitor.js（同为原版件）。
 * 这两个文件只认三样外部输入，本文件就补这三样：
 *
 *   1. THREE 场景 + 相机 —— 原版由 app.js 提供，这里自建。
 *      相机基线照抄原版预设 7 的 orbit 值：radius 8.4 / phi 0.18 / theta 0，
 *      lookAt 原点、FOV 45（原版 BASE_FOV）。位置换算公式与原版一致：
 *        x = r·cosφ·sinθ   y = r·sinφ   z = r·cosφ·cosθ
 *      → (0, 1.5036, 8.2640)。差一点点观感就会变，别随手改。
 *
 *   2. 音域监视器需要的细粒度频谱帧（八段 Hz + 真 kick 包络）——
 *      由电台那边的 soniAnalyser(fftSize 1024 → 512 段) 每帧喂进来。
 *      原版默认就开着这个监视器；只给 bass/mid/treble 粗值的话，预设内部会走
 *      fallback 分支反推八段，地形起伏和涟漪触发跟原版对不上（差异很明显）。
 *
 *   3. 封面色板（stageLyrics.coverPalette.groundPrimary/Secondary/Highlight）——
 *      原版拿这一份高饱和封面色同时画歌词和地形。Terminal 没有原版那套歌词配色，
 *      所以这里单独按原版 lyricGroundPaletteFromHsl 的规则从封面算一份，只喂地形。
 *      没有封面 / 封面是黑白时退回原版的 silverBlue 兜底色。
 *
 * 本文件不碰 DOM 结构、不碰播放器，只管画布与每帧推进。
 */
(function (global) {
  'use strict';

  var PRESET_INDEX = 7;
  var BASE_FOV = 45;
  var CAM_RADIUS = 8.4;
  var CAM_PHI = 0.18;
  var CAM_THETA = 0.0;

  /* 原版 app.js:3198 的三档渲染预算，照抄：
     cap = 逻辑像素上限倍数, min = 下限, budget = 总渲染像素预算。
     原版 fxDefaults 的默认档是 'high'，这里也用 high。 */
  var RENDER_PROFILES = {
    eco: { cap: 0.95, min: 0.56, budget: 2400000 },
    balanced: { cap: 1.12, min: 0.66, budget: 3800000 },
    high: { cap: 1.35, min: 0.72, budget: 5200000 },
    ultra: { cap: 1.75, min: 0.85, budget: 7800000 }
  };

  /* 原版 silverBlueLyricPalette() 的原值：封面没彩色时的地形兜底色 */
  var SILVER_BLUE = { primary: '#d8f1ff', secondary: '#9db8cf', highlight: '#eef7ff' };

  var canvasEl = null;
  var renderer = null;
  var scene = null;
  var camera = null;
  var running = false;
  var rafHandle = 0;
  var lastTs = 0;
  var clockTime = 0;
  var audioProvider = null;
  var qualityDowngraded = false;
  var perf = { frames: 0, ms: 0, checks: 0 };
  /* 星点「设备像素密度」补偿。
     原版 gl_PointSize 直接乘 renderer.getPixelRatio()，点尺寸是设备像素：
     DPR=2 的屏幕上，一颗 1 CSS px 的星实际占 2×2 个设备像素，64×64 的光斑贴图
     能露出亮核；DPR=1 时整张光斑被压进 1 个像素，alpha 只剩平均（约 0.25），
     星点就退化成一层灰雾 —— 实测天空区亮于 0.16 的像素只有参考图的 1/5。
     所以把「每颗星的设备像素数」统一归到 DPR=2 那一档（参考图就是这个观感），
     高 DPR 屏幕不额外放大，低 DPR 屏幕补足。 */
  var STAR_TARGET_DPR = 2.0;
  var starPixelScale = 1.0;        // 叠在归一化之上的手工系数（调试接口）

  /* 预设 7 的参数：全部保持原版默认档。
     密度 46 / 幅度 50 / 范围 82 / 下压 68 / 纵深 62 / 自转 50 / 辉光 68 / 悬浮块 80。
     Terminal 没有原版那套调参面板，写死默认值才叫「还原」。 */
  var fx = {
    preset: PRESET_INDEX,
    speed: 1,
    bloomStrength: 0,
    performanceQuality: 'high'
  };

  /* 原版把地形旋转绑在封面粒子的 rotation 上（拖拽手势共用）。
     这里没有粒子层，给一个恒定值：rotation 恒定 0，靠预设内部的 autoYaw 自转。 */
  var visualRotation = { x: 0, y: 0 };

  var ZERO_AUDIO = {
    subBass: 0, bass: 0, lowMid: 0, mid: 0, highMid: 0,
    presence: 0, brilliance: 0, air: 0,
    treble: 0, kickEnvelope: 0, energy: 0,
    sharpness: 0, smoothness: 1, density: 0.4
  };

  var ctx = {
    scene: null,
    fx: fx,
    time: 0,
    visualRotation: visualRotation,
    visualRotationActive: false,
    audio: ZERO_AUDIO
  };

  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function clamp01(v) { return clamp(isFinite(v) ? v : 0, 0, 1); }

  function topo() {
    return (typeof global.MineradioSonicTopography !== 'undefined' && global.MineradioSonicTopography)
      ? global.MineradioSonicTopography : null;
  }
  function monitor() {
    return (typeof global.MineradioSonicAudio !== 'undefined' && global.MineradioSonicAudio)
      ? global.MineradioSonicAudio : null;
  }

  /* ---------------- 颜色工具：与 app.js 的 rgbToHsl / hslToRgb / clampRange 同规则 ---------------- */

  function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    var max = Math.max(r, g, b), min = Math.min(r, g, b);
    var h = 0, s = 0, l = (max + min) / 2;
    if (max !== min) {
      var d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
      else if (max === g) h = ((b - r) / d + 2) / 6;
      else h = ((r - g) / d + 4) / 6;
    }
    return { h: h, s: s, l: l };
  }

  function hslToRgb(h, s, l) {
    h = ((h % 1) + 1) % 1; s = clamp01(s); l = clamp01(l);
    if (s === 0) {
      var v = Math.round(l * 255);
      return { r: v, g: v, b: v };
    }
    var q = l < 0.5 ? l * (1 + s) : l + s - l * s;
    var p = 2 * l - q;
    function hue(t) {
      if (t < 0) t += 1;
      if (t > 1) t -= 1;
      if (t < 1 / 6) return p + (q - p) * 6 * t;
      if (t < 1 / 2) return q;
      if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
      return p;
    }
    return {
      r: Math.round(hue(h + 1 / 3) * 255),
      g: Math.round(hue(h) * 255),
      b: Math.round(hue(h - 1 / 3) * 255)
    };
  }

  function rgbToHex(c) {
    function h2(n) { var s = clamp(Math.round(n), 0, 255).toString(16); return s.length < 2 ? '0' + s : s; }
    return '#' + h2(c.r) + h2(c.g) + h2(c.b);
  }

  function parseCssColor(css) {
    var s = String(css == null ? '' : css).trim();
    var m;
    if (/^#[0-9a-fA-F]{6}$/.test(s)) {
      return { r: parseInt(s.substr(1, 2), 16), g: parseInt(s.substr(3, 2), 16), b: parseInt(s.substr(5, 2), 16) };
    }
    if (/^#[0-9a-fA-F]{3}$/.test(s)) {
      return { r: parseInt(s[1] + s[1], 16), g: parseInt(s[2] + s[2], 16), b: parseInt(s[3] + s[3], 16) };
    }
    m = s.match(/^rgba?\(\s*([.\d]+)\s*,\s*([.\d]+)\s*,\s*([.\d]+)/i);
    if (m) return { r: clamp(Number(m[1]) || 0, 0, 255), g: clamp(Number(m[2]) || 0, 0, 255), b: clamp(Number(m[3]) || 0, 0, 255) };
    return null;
  }

  /**
   * 原版同名函数：任意 CSS 颜色 → #rrggbb，并把太暗的颜色整体抬到 minLum 亮度之上。
   * sonic-topography-preset.js 的配色链会优先调它，深色封面才不会糊成一团黑。
   * @returns {string} #rrggbb
   */
  function lyricPaletteColorToHex(value, fallback, minLum) {
    var floor = minLum == null ? 0.36 : Number(minLum);
    var c = parseCssColor(value) || parseCssColor(fallback) || { r: 157, g: 184, b: 207 };
    var lum = (c.r / 255) * 0.299 + (c.g / 255) * 0.587 + (c.b / 255) * 0.114;
    if (isFinite(floor) && lum < floor) {
      var lift = (floor - lum) * 255;   // 原版是归一化后 1:1 加常数，等价于按 255 折算
      c = { r: clamp(c.r + lift, 0, 255), g: clamp(c.g + lift, 0, 255), b: clamp(c.b + lift, 0, 255) };
    }
    return rgbToHex(c);
  }

  /**
   * 原版 lyricGroundPaletteFromHsl 的地形分支：高饱和主色 + 暖一档副色 + 更亮的强调色。
   * 分辨率不足或封面偏黑白时退回 silverBlue（与原版同一条兜底）。
   * @param {{h:number,s:number,l:number}} hsl 封面主色
   * @param {{avgL:number,chroma:number,monochrome:boolean}} stat 整幅封面的统计量
   * @returns {{primary:string, secondary:string, highlight:string}}
   */
  function groundPaletteFromHsl(hsl, stat) {
    if (stat.monochrome || stat.avgL < 0.16 || stat.chroma < 0.055 || hsl.s < 0.060) {
      return { primary: SILVER_BLUE.primary, secondary: SILVER_BLUE.secondary, highlight: SILVER_BLUE.highlight };
    }
    var hue = hsl.h;
    if (stat.avgL < 0.30 && (hue < 0.06 || hue > 0.86 || (hue > 0.75 && hue < 0.86))) {
      return { primary: SILVER_BLUE.primary, secondary: SILVER_BLUE.secondary, highlight: SILVER_BLUE.highlight };
    }
    var sampledBright = hsl.l >= 0.62 || stat.avgL >= 0.64;
    var s = sampledBright ? clamp(Math.max(hsl.s, 0.90), 0, 1)
      : clamp(Math.max(hsl.s * 1.20, 0.90), 0, 1);
    var l = sampledBright ? clamp(Math.max(hsl.l, 0.70), 0.66, 0.94)
      : clamp(Math.max(hsl.l + 0.30, 0.74), 0.70, 0.90);
    return {
      primary: rgbToHex(hslToRgb(hue, s, l)),
      secondary: rgbToHex(hslToRgb((hue + 0.08) % 1, clamp(Math.max(s * 0.90, 0.78), 0, 1), clamp(l - 0.10, 0.58, 0.86))),
      highlight: rgbToHex(hslToRgb((hue + 0.03) % 1, clamp(Math.max(s * 0.82, 0.72), 0, 1), clamp(l + 0.12, 0.80, 0.96)))
    };
  }

  var palette = {
    groundPrimary: SILVER_BLUE.primary,
    groundSecondary: SILVER_BLUE.secondary,
    groundHighlight: SILVER_BLUE.highlight
  };

  /* 预设层每帧读 stageLyrics.coverPalette，所以这个对象改一次就够，不用通知它 */
  global.stageLyrics = global.stageLyrics || {};
  global.stageLyrics.coverPalette = palette;
  global.lyricPaletteColorToHex = lyricPaletteColorToHex;

  /* ==================================================================
   * 背景星河 —— 原版 Mineradio app.js 预设 5「星河」里 lane >= 0.80 的星尘层
   *
   * 为什么是这一层：原版那一整块粒子由同一份顶点着色器生成，lane < 0.80 的
   * 80% 是会呼吸的极光带（预设 5 的「壁纸粒子 · 音乐律动」），lane >= 0.80
   * 的 20% 才是深空星尘。终端这边只要星尘当底图 —— 极光带是主视觉，
   * 压在音域地形下面会抢画面，所以不搬。
   *
   * 位置 / 颜色 / 眨眼 / 尺寸四段全部照抄原版公式，一个常数都没改：
   *   drift = fract(aUv.x + t*(0.0014 + seed*0.0048) + seed*0.63)   ← 横向漂移
   *   x = (drift-0.5)*(45 + seed*22) + snoise(...)*3.4              ← 团簇
   *   y = (hash-0.5)*22 + sin(t*(0.018+seed*0.028) + seed*7)*0.86   ← 纵向摆动
   *   z = mix(-32, 18, seed) + sin(t*(0.02+seed*0.032) + aRand*8)*1.05
   *   twinkle = pow(0.5 + 0.5*sin(t*(0.24+seed*0.42) + aRand*17), 5) ← 眨眼
   *   dust = smoothstep(0.22, 0.98, hash(aRand*661 + floor(q*160)))  ← 明暗筛
   *   vColor = mix(封面色, rgb(0.92,0.97,1.0), 0.62 + twinkle*0.14)  ← 冷白偏封面色
   *   size = clamp(36 / -viewZ * (1.05 + 音频驱动), 1.00, 5.45) * DPR
   *
   * 原版在音域预设下把这一层压到 0.82 亮度（uParticleDim），这里同样 0.82。
   * ================================================================== */

  var STAR_GRID = 183;             // 原版 coverParticleGridForResolution(1.55) = 183
  var STAR_LANE_MIN = 0.80;        // lane >= 0.80 才是星尘
  var STAR_DIM = 0.82;             // 原版音域预设下把这一层压到 0.82（uParticleDim）
  var STAR_ALPHA = 0.96;           // 原版 uAlpha 稳定值
  var STAR_BLOOM_SIZE = 2.65;      // 原版 uBloomSize
  var STAR_BLOOM_STRENGTH = 0.90;  // 原版 uBloomStrength 默认 0.62；这里 0.90 补偿低 DPR 下的亮度损失
  var STAR_COLOR_BOOST = 1.10;     // 原版 uColorBoost（fx.color 默认值）

  var starPoints = null;           // 主层：NormalBlending，写进画面主体
  var starBloom = null;            // 辉光层：同一份几何，AdditiveBlending + depthTest 关
  var starUniforms = null;

  /** 原版 makeDotTexture()：64×64 白核 → 柔边 → 全透明的径向渐变点 */
  function makeDotTexture() {
    var cv = document.createElement('canvas');
    cv.width = cv.height = 64;
    var ctx = cv.getContext('2d');
    var g = ctx.createRadialGradient(32, 32, 0, 32, 32, 31);
    g.addColorStop(0.00, 'rgba(255,255,255,0.96)');
    g.addColorStop(0.42, 'rgba(255,255,255,0.78)');
    g.addColorStop(0.72, 'rgba(255,255,255,0.22)');
    g.addColorStop(1.00, 'rgba(255,255,255,0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, 64, 64);
    var tex = new THREE.CanvasTexture(cv);
    tex.minFilter = THREE.LinearFilter;
    tex.magFilter = THREE.LinearFilter;
    return tex;
  }

  /* 原版顶点着色器用的 3D simplex 噪声（只为了那一团 ±3.4 的团簇），照抄 */
  var GLSL_NOISE = [
    'vec3 mod289(vec3 x){return x-floor(x*(1.0/289.0))*289.0;}',
    'vec4 mod289v(vec4 x){return x-floor(x*(1.0/289.0))*289.0;}',
    'vec4 perm(vec4 x){return mod289v(((x*34.0)+1.0)*x);}',
    'float snoise(vec3 v){',
    '  const vec2 C=vec2(1.0/6.0,1.0/3.0);',
    '  const vec4 D=vec4(0.0,0.5,1.0,2.0);',
    '  vec3 i=floor(v+dot(v,C.yyy));',
    '  vec3 x0=v-i+dot(i,C.xxx);',
    '  vec3 g=step(x0.yzx,x0.xyz); vec3 l=1.0-g;',
    '  vec3 i1=min(g.xyz,l.zxy); vec3 i2=max(g.xyz,l.zxy);',
    '  vec3 x1=x0-i1+C.xxx;',
    '  vec3 x2=x0-i2+C.yyy;',
    '  vec3 x3=x0-D.yyy;',
    '  i=mod289(i);',
    '  vec4 p=perm(perm(perm(i.z+vec4(0.0,i1.z,i2.z,1.0))+i.y+vec4(0.0,i1.y,i2.y,1.0))+i.x+vec4(0.0,i1.x,i2.x,1.0));',
    '  float n_=0.142857142857;',
    '  vec3 ns=n_*D.wyz-D.xzx;',
    '  vec4 j=p-49.0*floor(p*ns.z*ns.z);',
    '  vec4 x_=floor(j*ns.z); vec4 y_=floor(j-7.0*x_);',
    '  vec4 x=x_*ns.x+ns.yyyy; vec4 y=y_*ns.x+ns.yyyy;',
    '  vec4 h=1.0-abs(x)-abs(y);',
    '  vec4 b0=vec4(x.xy,y.xy); vec4 b1=vec4(x.zw,y.zw);',
    '  vec4 s0=floor(b0)*2.0+1.0; vec4 s1=floor(b1)*2.0+1.0;',
    '  vec4 sh=-step(h,vec4(0.0));',
    '  vec4 a0=b0.xzyw+s0.xzyw*sh.xxyy; vec4 a1=b1.xzyw+s1.xzyw*sh.zzww;',
    '  vec3 p0=vec3(a0.xy,h.x); vec3 p1=vec3(a0.zw,h.y); vec3 p2=vec3(a1.xy,h.z); vec3 p3=vec3(a1.zw,h.w);',
    '  vec4 norm=inversesqrt(vec4(dot(p0,p0),dot(p1,p1),dot(p2,p2),dot(p3,p3)));',
    '  p0*=norm.x; p1*=norm.y; p2*=norm.z; p3*=norm.w;',
    '  vec4 m=max(0.6-vec4(dot(x0,x0),dot(x1,x1),dot(x2,x2),dot(x3,x3)),0.0);',
    '  m=m*m;',
    '  return 42.0*dot(m*m,vec4(dot(p0,x0),dot(p1,x1),dot(p2,x2),dot(p3,x3)));',
    '}',
    'float hash11(float p){return fract(sin(p * 127.1) * 43758.5453123);}'
  ].join('\n');

  var STAR_VS = [
    'precision highp float;',
    'uniform float uTime, uBass, uMid, uTreble, uBeat, uEnergy;',
    'uniform float uPixel, uPointScale, uBloomSize, uColorBoost;',
    'uniform vec3 uCoverColor;',
    'attribute vec2 aUv;',
    'attribute float aRand;',
    'varying vec3 vColor;',
    'varying float vAlpha, vBright, vRipple, vSourceLum;',
    GLSL_NOISE,
    'void main(){',
    '  float t = uTime;',
    '  float bassGlow = smoothstep(0.07, 0.78, uBass) * 0.34 + uBeat * 0.014;',
    '  float highGlow = smoothstep(0.04, 0.46, uTreble) * 0.46;',
    '  float lane = aUv.y;',
    '  float q = (lane - 0.80) / 0.20;',
    '  float seed = hash11(aRand * 917.0 + floor(q * 130.0));',
    '  float depthV = mix(-32.0, 18.0, seed);',
    '  float drift = fract(aUv.x + t * (0.0014 + seed * 0.0048) + seed * 0.63);',
    '  float cluster = snoise(vec3(seed * 2.0, q * 3.2, t * 0.007));',
    '  float x = (drift - 0.5) * (45.0 + seed * 22.0) + cluster * 3.4;',
    '  float y = (hash11(aRand * 331.0 + seed * 5.0) - 0.5) * 22.0',
    '          + sin(t * (0.018 + seed * 0.028) + seed * 7.0) * 0.86;',
    '  float z = depthV + sin(t * (0.020 + seed * 0.032) + aRand * 8.0) * 1.05;',
    '  float twinkle = pow(0.5 + 0.5 * sin(t * (0.24 + seed * 0.42) + aRand * 17.0), 5.0);',
    '  float dust = smoothstep(0.22, 0.98, hash11(aRand * 661.0 + floor(q * 160.0)));',
    '  vec3 pos = vec3(x, y, z);',
    '  vAlpha = dust * (0.16 + twinkle * 0.46 + highGlow * 0.025 + bassGlow * 0.018)',
    '         * (1.0 - q * 0.06);',
    '  float maxAmp = twinkle * highGlow * 0.055 + dust * bassGlow * 0.030;',
    '  vRipple = clamp(maxAmp * 1.5, 0.0, 1.0);',
    '  vColor = mix(uCoverColor, vec3(0.92, 0.97, 1.0), 0.62 + twinkle * 0.14)',
    '         * (0.72 + twinkle * 0.62 + bassGlow * 0.025);',
    /* 原版先量出「源头亮度」判黑白封面，再做颜色提亮（uColorBoost = fx.color 1.10） */
    '  vSourceLum = dot(max(vColor, vec3(0.0)), vec3(0.299, 0.587, 0.114));',
    '  vColor = pow(max(vColor, vec3(0.0)), vec3(1.0 / max(0.35, uColorBoost)));',
    /* 原版 preset > 4.5 的 vBright：0.94 起步，低频/能量再往上带一点 */
    '  vBright = 0.94 + maxAmp * 0.34 + uBass * 0.020 + uEnergy * 0.026;',
    '  vec4 mvPos = modelViewMatrix * vec4(pos, 1.0);',
    '  float depthSize = 36.0 / max(0.5, -mvPos.z);',
    '  float flowDrive = uBass * 0.070 + uMid * 0.046 + uTreble * 0.060 + uBeat * 0.055;',
    '  float sz = clamp(depthSize * (1.05 + flowDrive), 1.00, 5.45);',
    '  gl_PointSize = sz * uPixel * uPointScale * uBloomSize;',
    '  gl_Position = projectionMatrix * mvPos;',
    '}'
  ].join('\n');

  var STAR_FS = [
    'precision highp float;',
    'uniform sampler2D uDotTex;',
    'uniform float uAlpha, uParticleDim;',
    'varying vec3 vColor;',
    'varying float vAlpha, vBright, vRipple, vSourceLum;',
    'void main(){',
    '  vec4 tex = texture2D(uDotTex, gl_PointCoord);',
    '  if (tex.a < 0.02) discard;',
    '  vec3 col = vColor * vBright;',
    '  col = mix(col, col * 1.2, vRipple * 0.4);',
    /* 原版的「亮粒子挖暗边」：亮星点中间会留一圈更暗的边，看起来像真星有光晕 */
    '  float keepBlack = 1.0 - smoothstep(0.025, 0.115, vSourceLum);',
    '  float nonBlack = 1.0 - keepBlack;',
    '  float dotDist = length(gl_PointCoord - vec2(0.5)) * 2.0;',
    '  float readableRim = smoothstep(0.44, 0.94, dotDist) * (1.0 - smoothstep(0.94, 1.08, dotDist)) * tex.a;',
    '  float outLum = dot(col, vec3(0.299, 0.587, 0.114));',
    '  float lightParticle = smoothstep(0.50, 0.82, outLum) * nonBlack;',
    '  float darkParticle = (1.0 - smoothstep(0.20, 0.50, outLum)) * nonBlack;',
    '  col = mix(col, vec3(0.0), readableRim * lightParticle * 0.38);',
    '  col = mix(col, vec3(1.0), readableRim * darkParticle * 0.20);',
    '  col = clamp(col, vec3(0.0), vec3(1.6));',
    '  gl_FragColor = vec4(col, tex.a * uAlpha * uParticleDim * vAlpha);',
    '}'
  ].join('\n');

  /**
   * 原版第二层「辉光」（bloomParticles）的片元着色器。
   * 主体层只画 1-5px 的实点，真正让星点看起来是「发光的星星」的是这一层：
   * 同一个几何、点尺寸 ×2.65、alpha 用 tex.a² 做软边、AdditiveBlending，
   * 并且 depthTest 关掉 —— 所以连地形挡在前面的星点也会透出一点光。
   * 没有这一层，6771 颗星只剩 0.26% 的像素被点亮，肉眼几乎看不见（实测）。
   */
  var STAR_BLOOM_FS = [
    'precision highp float;',
    'uniform sampler2D uDotTex;',
    'uniform float uAlpha, uBloomStrength, uParticleDim;',
    'varying vec3 vColor;',
    'varying float vAlpha, vBright, vRipple, vSourceLum;',
    'void main(){',
    '  vec4 tex = texture2D(uDotTex, gl_PointCoord);',
    '  if (tex.a < 0.01) discard;',
    '  float soft = tex.a * tex.a;',
    '  vec3 col = vColor * (0.55 + vBright * 0.62);',
    '  col = clamp(col, vec3(0.0), vec3(1.8));',
    '  float pulse = 1.0 + vRipple * 0.65;',
    '  float keepBlack = 1.0 - smoothstep(0.025, 0.115, vSourceLum);',
    '  float bloomKeep = 1.0 - keepBlack * 0.92;',
    '  gl_FragColor = vec4(col, soft * uAlpha * uBloomStrength * uParticleDim',
    '                           * pulse * 0.55 * vAlpha * bloomKeep);',
    '}'
  ].join('\n');

  /**
   * 建星尘层（懒建：只有真要画的时候才分配 6771 个顶点）。
   * 网格照原版：gx 0..182 全量，gy 只取 lane >= 0.80 的那 37 行。
   */
  function buildStars(targetScene) {
    if (starPoints) return true;
    if (!targetScene) return false;
    var rows = [], gy, gx;
    for (gy = 0; gy < STAR_GRID; gy++) {
      if ((gy + 0.5) / STAR_GRID >= STAR_LANE_MIN) rows.push(gy);
    }
    var count = rows.length * STAR_GRID;
    var uvs = new Float32Array(count * 2);
    var rnd = new Float32Array(count);
    var idx = 0;
    for (var r = 0; r < rows.length; r++) {
      var v = (rows[r] + 0.5) / STAR_GRID;
      for (gx = 0; gx < STAR_GRID; gx++) {
        uvs[idx * 2] = (gx + 0.5) / STAR_GRID;
        uvs[idx * 2 + 1] = v;
        rnd[idx] = Math.random();
        idx++;
      }
    }
    var geo = new THREE.BufferGeometry();
    /* position 只是占位：真位置全在 shader 里算，但 THREE 需要这个属性 */
    geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(count * 3), 3));
    geo.setAttribute('aUv', new THREE.BufferAttribute(uvs, 2));
    geo.setAttribute('aRand', new THREE.BufferAttribute(rnd, 1));
    /* 位置在 shader 里生成，包围球必须手写，否则会被当成零半径几何裁掉 */
    geo.boundingSphere = new THREE.Sphere(new THREE.Vector3(0, 0, 0), 90);

    var coverCol = new THREE.Color(palette.groundPrimary);
    starUniforms = {
      uTime: { value: 0 },
      uBass: { value: 0 },
      uMid: { value: 0 },
      uTreble: { value: 0 },
      uBeat: { value: 0 },
      uEnergy: { value: 0 },
      uPixel: { value: 1 },
      uPointScale: { value: 1 },
      uAlpha: { value: STAR_ALPHA },
      uParticleDim: { value: STAR_DIM },
      uBloomStrength: { value: STAR_BLOOM_STRENGTH },
      uBloomSize: { value: 1 },
      uColorBoost: { value: STAR_COLOR_BOOST },
      uCoverColor: { value: coverCol },
      uDotTex: { value: makeDotTexture() }
    };

    /* 主体层：实点，写进画面；开深度测试，被地形挡住的星点就该消失 */
    var mat = new THREE.ShaderMaterial({
      uniforms: starUniforms,
      vertexShader: STAR_VS,
      fragmentShader: STAR_FS,
      transparent: true,
      depthWrite: false,
      depthTest: true,
      blending: THREE.NormalBlending
    });

    starPoints = new THREE.Points(geo, mat);
    starPoints.renderOrder = 1;      // 地形(0)之后画
    starPoints.frustumCulled = false;
    targetScene.add(starPoints);

    /* 辉光层：同一份几何再画一遍（原版 bloomParticles 的做法）。
       uBloomSize 是 2.65 —— 这层不复制几何、只改点尺寸和混合方式，所以成本很低。
       除 uBloomSize 外全部共用同一批 uniform 对象，driveStars 改一次两层同时生效。 */
    var bloomUniforms = {};
    for (var uk in starUniforms) {
      if (Object.prototype.hasOwnProperty.call(starUniforms, uk)) bloomUniforms[uk] = starUniforms[uk];
    }
    bloomUniforms.uBloomSize = { value: STAR_BLOOM_SIZE };
    var bloomMat = new THREE.ShaderMaterial({
      uniforms: bloomUniforms,
      vertexShader: STAR_VS,
      fragmentShader: STAR_BLOOM_FS,
      transparent: true,
      depthWrite: false,
      depthTest: false,             // 原版就是关的：辉光要能透在地形之上
      blending: THREE.AdditiveBlending
    });
    starBloom = new THREE.Points(geo, bloomMat);
    starBloom.renderOrder = 2;       // 最后画，加色叠在整幅画面上
    starBloom.frustumCulled = false;
    targetScene.add(starBloom);
    return true;
  }

  /** 封面色变了就同步给星尘（原版的 coverColor 就是从封面采的） */
  function syncStarCover() {
    if (starUniforms) {
      try { starUniforms.uCoverColor.value.set(palette.groundPrimary); } catch (e) { }
    }
  }

  /** 频谱帧 → 星尘的五个驱动量（原版 uBass/uMid/uTreble/uBeat/uEnergy） */
  function driveStars(frame) {
    if (!starUniforms) return;
    var u = starUniforms;
    u.uTime.value = clockTime;
    u.uBass.value = clamp01(frame.bass);
    u.uMid.value = clamp01(frame.mid);
    u.uTreble.value = clamp01(frame.treble != null ? frame.treble : frame.brilliance);
    u.uBeat.value = clamp01(frame.kickEnvelope != null ? frame.kickEnvelope : frame.beat);
    u.uEnergy.value = clamp01(frame.energy);
  }

  /**
   * 从已画好的封面画布采一份地形配色。
   * 采样规则照原版：先按「彩度 × 亮度」给每个像素打分挑主色，
   * 再统计整幅封面的平均彩度 —— 彩度太低就认定是黑白封面，退回 silverBlue。
   * @param {HTMLCanvasElement|HTMLImageElement} src 封面（同源，可读像素）
   * @returns {boolean} 是否取到了新配色
   */
  function applyPaletteFromCover(src) {
    if (!src) return false;
    var N = 32;
    var off = document.createElement('canvas');
    off.width = N; off.height = N;
    var g;
    try { g = off.getContext('2d', { willReadFrequently: true }); } catch (e) { return false; }
    if (!g) return false;
    var data;
    try {
      g.drawImage(src, 0, 0, N, N);
      data = g.getImageData(0, 0, N, N).data;
    } catch (e) { return false; }   // 跨域 / 空图
    if (!data || !data.length) return false;

    var best = null, bestScore = -1;
    var sumChroma = 0, sumLum = 0, maxChroma = 0, colorful = 0, count = 0;
    for (var i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 8) continue;
      var r = data[i], gg = data[i + 1], b = data[i + 2];
      var lum = (r * 0.299 + gg * 0.587 + b * 0.114) / 255;
      var chroma = (Math.max(r, gg, b) - Math.min(r, gg, b)) / 255;
      count++;
      sumChroma += chroma;
      sumLum += lum;
      if (chroma > maxChroma) maxChroma = chroma;
      if (chroma > 0.055) colorful++;
      // 近黑 / 近白都不适合当地形主色，跳掉
      if (lum < 0.12 || lum > 0.95 || chroma < 0.045) continue;
      var score = chroma * (0.35 + lum * 0.65);
      if (score > bestScore) {
        bestScore = score;
        best = { r: r, g: gg, b: b, hsl: rgbToHsl(r, gg, b), chroma: chroma };
      }
    }
    if (!count) return false;

    var avgChroma = sumChroma / count;
    var avgL = sumLum / count;
    var colorfulRatio = colorful / count;
    var monochrome = maxChroma < 0.095 || avgChroma < 0.026 || colorfulRatio < 0.014;

    if (!best) {
      palette.groundPrimary = SILVER_BLUE.primary;
      palette.groundSecondary = SILVER_BLUE.secondary;
      palette.groundHighlight = SILVER_BLUE.highlight;
      syncStarCover();
      return true;
    }
    var ground = groundPaletteFromHsl(best.hsl, {
      avgL: avgL,
      chroma: Math.max(best.chroma, 0),
      monochrome: monochrome
    });
    palette.groundPrimary = ground.primary;
    palette.groundSecondary = ground.secondary;
    palette.groundHighlight = ground.highlight;
    syncStarCover();
    return true;
  }

  /* ---------------- 渲染器 / 相机 ---------------- */

  function ensureRenderer() {
    if (renderer) return true;
    if (typeof global.THREE === 'undefined' || !global.THREE) return false;
    canvasEl = canvasEl || document.getElementById('sonic-canvas');
    if (!canvasEl) return false;
    try {
      renderer = new THREE.WebGLRenderer({
        canvas: canvasEl,
        antialias: false,                 // 原版同样关掉：地形是海量小方块，AA 只会拖帧
        alpha: false,                     // 原版 alpha:true 是叠在 #000 body 上；这里直接铺黑，省一次合成
        powerPreference: 'high-performance'
      });
    } catch (e) { renderer = null; return false; }
    renderer.setClearColor(0x000000, 1);
    scene = new THREE.Scene();
    camera = new THREE.PerspectiveCamera(BASE_FOV, 1, 0.1, 100);
    var cy = Math.cos(CAM_PHI), sy = Math.sin(CAM_PHI);
    var ct = Math.cos(CAM_THETA), st = Math.sin(CAM_THETA);
    camera.position.set(CAM_RADIUS * cy * st, CAM_RADIUS * sy, CAM_RADIUS * cy * ct);
    camera.lookAt(0, 0, 0);
    buildStars(scene);          // 星河底图：跟地形同一个场景，共用这台相机
    resize();
    return true;
  }

  /**
   * 原版 getRenderPixelRatio()：先按像素预算反解一个倍数，再夹到档位上下限和设备 DPR 之间。
   * 大窗口 / 低预算机器上会低于 1，比写死 1.5 稳得多。
   * @returns {number}
   */
  function renderPixelRatio() {
    var device = global.devicePixelRatio || 1;
    var w = Math.max(1, global.innerWidth || 1);
    var h = Math.max(1, global.innerHeight || 1);
    var q = RENDER_PROFILES[fx.performanceQuality] || RENDER_PROFILES.high;
    var budgetCap = Math.sqrt(q.budget / (w * h));
    return Math.max(q.min, Math.min(device, Math.min(q.cap, budgetCap)));
  }

  /**
   * 星点的 uPixel：把每颗星的设备像素数归到 STAR_TARGET_DPR 这一档。
   * DPR 2 → 1.0（与原版完全一致）；DPR 1 → 2.0（补足，否则 1px 光斑只剩灰雾）。
   * @returns {number} 乘进 gl_PointSize 的系数
   */
  function starPixelBoost() {
    var pr = renderer ? renderer.getPixelRatio() : (global.devicePixelRatio || 1);
    pr = pr || 1;
    return Math.max(1, STAR_TARGET_DPR / pr) * starPixelScale;
  }

  function resize() {
    if (!renderer || !canvasEl) return;
    var w = Math.max(1, global.innerWidth || 1);
    var h = Math.max(1, global.innerHeight || 1);
    renderer.setPixelRatio(renderPixelRatio());
    renderer.setSize(w, h, false);
    canvasEl.style.width = w + 'px';
    canvasEl.style.height = h + 'px';
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    // 星点的 gl_PointSize 是设备像素，必须跟着渲染分辨率走，否则缩放窗口后星点会忽大忽小
    if (starUniforms) starUniforms.uPixel.value = starPixelBoost();
  }

  /* ---------------- 音频帧 ---------------- */

  /**
   * 监视器缺失时的兜底：直接用粗粒度三段顶上去。
   * 正常路径下走不到这里 —— 监视器脚本随页面一起加载。
   * @returns {object} 粗粒度音频壳
   */
  function coarseFromSpectrum(data, sampleRate, fftSize) {
    if (!data || !data.length) return ZERO_AUDIO;
    var nyquist = (sampleRate || 48000) / 2;
    var binHz = nyquist / data.length;
    function band(hzStart, hzEnd) {
      var i0 = Math.max(0, Math.floor(hzStart / binHz));
      var i1 = Math.min(data.length, Math.max(i0 + 1, Math.ceil(hzEnd / binHz)));
      var sum = 0;
      for (var i = i0; i < i1; i++) sum += data[i];
      return clamp01(sum / ((i1 - i0) * 255));
    }
    var bass = band(32, 180), mid = band(260, 1800), treble = band(1800, 9000);
    return {
      bass: bass, mid: mid, treble: treble,
      beat: clamp01(bass * 1.15), energy: clamp01((bass + mid + treble) / 3)
    };
  }

  function readAudio(dt) {
    var p = null;
    if (audioProvider) {
      try { p = audioProvider(dt); } catch (e) { p = null; }
    }
    if (!p || !p.data) {
      // 还没有分析节点（音频没初始化 / 还没播过任何歌）
      return ZERO_AUDIO;
    }
    var mon = monitor();
    if (!mon) return coarseFromSpectrum(p.data, p.sampleRate, p.fftSize);
    var frame = mon.step(p.data, dt, {
      fx: fx,
      playing: p.playing !== false,
      beat: 0,
      sampleRate: p.sampleRate,
      fftSize: p.fftSize,
      currentTime: p.currentTime
    });
    return frame || ZERO_AUDIO;
  }

  /* ---------------- 主循环 ---------------- */

  function tick(ts) {
    if (!running) return;
    rafHandle = global.requestAnimationFrame(tick);
    var dt = lastTs ? Math.min(0.05, Math.max(0.001, (ts - lastTs) / 1000)) : 1 / 60;
    lastTs = ts;
    clockTime += dt;

    var mod = topo();
    if (!mod) return;
    ctx.scene = scene;
    ctx.time = clockTime;
    ctx.audio = readAudio(dt);
    driveStars(ctx.audio);
    try { mod.update(dt, ctx); } catch (e) { stop(); return; }
    try { renderer.render(scene, camera); } catch (e) { stop(); return; }

    // 帧时间超过 26ms（≈38fps）连续两次就往 eco 档退：地形网格 156→112，
    // 与原版 quality 下拉里的「节能」档同一条路径。
    if (!qualityDowngraded) {
      perf.frames++;
      perf.ms += dt * 1000;
      if (perf.frames >= 90) {
        var avg = perf.ms / perf.frames;
        perf.frames = 0; perf.ms = 0;
        if (avg > 26) {
          perf.checks++;
          if (perf.checks >= 2) {
            qualityDowngraded = true;
            fx.performanceQuality = 'eco';
            resize();               // 像素预算跟着档位一起降
          }
        } else {
          perf.checks = 0;
        }
      }
    }
  }

  /* ---------------- 对外接口 ---------------- */

  function start() {
    if (running) return false;
    if (!ensureRenderer()) return false;
    var mod = topo();
    if (!mod) return false;
    resize();
    if (canvasEl) canvasEl.style.display = 'block';
    fx.preset = PRESET_INDEX;
    running = true;
    lastTs = 0;
    clockTime = 0;
    perf.frames = 0; perf.ms = 0; perf.checks = 0;
    try {
      mod.onPresetChange(-1, PRESET_INDEX, { scene: scene, fx: fx });
    } catch (e) { /* 首次入场失败就靠 update 里的 ensureLayer 兜 */ }
    rafHandle = global.requestAnimationFrame(tick);
    return true;
  }

  function stop() {
    if (!running) {
      if (canvasEl) canvasEl.style.display = 'none';
      return;
    }
    running = false;
    if (rafHandle) { global.cancelAnimationFrame(rafHandle); rafHandle = 0; }
    var mod = topo();
    if (mod) { try { mod.clear(); } catch (e) { } }
    var mon = monitor();
    if (mon) { try { mon.reset(); } catch (e) { } }
    if (canvasEl) canvasEl.style.display = 'none';
  }

  /**
   * 注册频谱提供者。电台那边每帧被调用一次，须同步返回：
   * { data: Uint8Array(频谱), sampleRate, fftSize, playing, currentTime }
   * @param {function(number):object} fn
   */
  function setAudioProvider(fn) {
    audioProvider = (typeof fn === 'function') ? fn : null;
  }

  /** 供外部（本地模型 / 调试）临时改画质档；'eco' | 'balanced' | 'high' | 'ultra' */
  function setQuality(q) {
    if (['eco', 'balanced', 'high', 'ultra'].indexOf(q) < 0) return;
    fx.performanceQuality = q;
    qualityDowngraded = true;    // 手动指定后不再自动降质
    if (running) resize();
  }

  /** 星点尺寸补偿（1 = 只做 DPR 归一化；越大星点越粗、星空越亮） */
  function setStarPixelScale(k) {
    var v = Number(k);
    if (!isFinite(v) || v <= 0) return false;
    starPixelScale = Math.min(4, Math.max(0.25, v));
    resize();
    return true;
  }

  /** 星点辉光强度（原版 fx.bloomStrength，面板范围 0 ~ 1.6，默认 0.62） */
  function setStarBloomStrength(s) {
    var v = Number(s);
    if (!isFinite(v)) return false;
    if (starUniforms && starUniforms.uBloomStrength) {
      starUniforms.uBloomStrength.value = Math.min(1.6, Math.max(0, v));
    }
    return true;
  }

  global.addEventListener('resize', function () {
    if (running) resize();
  });

  global.CyrilSonic = {
    PRESET_INDEX: PRESET_INDEX,
    isRunning: function () { return running; },
    start: start,
    stop: stop,
    setAudioProvider: setAudioProvider,
    applyPaletteFromCover: applyPaletteFromCover,
    setQuality: setQuality,
    setStarPixelScale: setStarPixelScale,
    setStarBloomStrength: setStarBloomStrength,
    /** 打一道涟漪（世界坐标，原版 pointerRipple 同签名） */
    ripple: function (x, z, strength) {
      var mod = topo();
      if (mod && running) { try { mod.pointerRipple(x, z, strength); } catch (e) { } }
    },
    /** 调试用：返回当前配色 */
    palette: function () {
      return {
        groundPrimary: palette.groundPrimary,
        groundSecondary: palette.groundSecondary,
        groundHighlight: palette.groundHighlight
      };
    },
    /** 调试用：背景星河的状态 */
    stars: function () {
      if (!starPoints || !starUniforms) return null;
      return {
        count: starPoints.geometry.attributes.aRand.count,
        drawn: renderer ? renderer.info.render.points : 0,   // 本帧真正画出的点数量
        visible: starPoints.visible,
        dim: starUniforms.uParticleDim.value,
        pointPixel: starUniforms.uPixel.value,
        time: starUniforms.uTime.value,
        cover: '#' + starUniforms.uCoverColor.value.getHexString(),
        bloom: !!(starBloom && starBloom.visible),
        bloomStrength: starUniforms.uBloomStrength.value
      };
    },
    /**
     * 调试用：只渲染星尘层，读回像素做亮度直方图。
     * 用来回答「星点到底有没有落进画布、亮度到什么量级」——纯功能度量，不判观感。
     * @returns {object|null} 统计结果
     */
    debugStarPixels: function () {
      if (!renderer || !scene || !camera || !starPoints) return null;
      var saved = [], i, ch;
      for (i = 0; i < scene.children.length; i++) {
        ch = scene.children[i];
        saved.push([ch, ch.visible]);
        ch.visible = (ch === starPoints || ch === starBloom);
      }
      var out = null;
      try {
        renderer.render(scene, camera);
        var gl = renderer.getContext();
        var w = renderer.domElement.width, h = renderer.domElement.height;
        var px = new Uint8Array(w * h * 4);
        gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
        var lit = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, maxL = 0, sum = 0;
        for (i = 0; i < px.length; i += 4) {
          var l = (px[i] * 0.299 + px[i + 1] * 0.587 + px[i + 2] * 0.114) / 255;
          if (l > 0.008) { lit++; sum += l; }
          if (l > 0.03) b1++;
          if (l > 0.08) b2++;
          if (l > 0.16) b3++;
          if (l > 0.32) b4++;
          if (l > maxL) maxL = l;
        }
        out = {
          w: w, h: h, total: w * h,
          lit: lit,                       // 有星点的像素数
          pct: +(lit / (w * h) * 100).toFixed(2),
          gt003: b1, gt008: b2, gt016: b3, gt032: b4,
          maxLum: +maxL.toFixed(3),
          meanLum: lit ? +(sum / lit).toFixed(3) : 0
        };
      } catch (e) {
        out = { error: String(e && e.message || e) };
      }
      for (i = 0; i < saved.length; i++) saved[i][0].visible = saved[i][1];
      return out;
    }
  };
})(typeof window !== 'undefined' ? window : globalThis);
