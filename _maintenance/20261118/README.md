# 2026-11-18 维护记录：绘光主题跟随黑白 + 拼贴终端最小化

## 1. 需求

1. 封面拼贴里的终端浮窗增加**最小化**。
2. 把 Folia 绘光的两套内置主题（日光素白 / 午夜墨染）从「绘光自己选」改成
   由【设置】→【外观】的**黑 / 白**决定。

## 2. 改动

### 2.1 移植原版 baseThemes（新增文件）

| 文件 | 说明 |
|---|---|
| `build-folia/lumiere-src/services/baseThemes.ts` | 从 `Mineradio-main/vendor-src/folia/src/services/baseThemes.ts` **原样移植**（午夜墨染 `#09090b` / 日光素白 `#f5f5f4`+橙 `#ea580c`），一字未改 |
| `build-folia/adapter.ts` | 导出并挂到 `window.LumiereFolia.BASE_DUAL_THEME / DEFAULT_THEME / DAYLIGHT_THEME` |
| `web/folia-adapter.js` | `node build-folia/build.mjs` 重新构建（rolldown，2729 KB） |

### 2.2 桥接层

`web/folia-bridge.js`

- 删掉写死的深色 `THEME`，改为 `themeFor(light)`：取原版两套预设 + WebTerm 字体覆盖；
- 新增 `Lumiere.setTheme(light)`：沿用当前 `seed` 调 `runtime.swapSong`，
  runtime 见同 seed 走 `commitSong` 直通分支（无跨曲交接动画），只清段落、下一帧按新配色重建；
- `start()` 里按 `body.light` 对齐一次，消化「applyTheme 早于本文件执行」的时序；
- `debug()` 增加 `light` / `theme` 字段。

### 2.3 前端

`web/index.html`

- `applyTheme()` 末尾：`Lumiere.setTheme(name === 'light')`；
- 【外观】黑 / 白两个按钮加 title 说明（绘光同步为午夜墨染 / 日光素白）；
- `#termFloat` 标题栏新增 `#tfMin`（`—`）：`#termFloat.min` 收成 30px 标题条（宽 420px），
  再点展开；`tfMin` 落 `localStorage`（`Win-webterm-termfloat.min`）；
- `tfReflow()` 在最小化时直接返回（`display:none` 量不到高度，fit 会算出假行列报给 ConPTY）；
- 展开时先 `tfApplyPos()` 夹回可视区，再 rAF 强制重报行列、80ms 后补一次并还焦点。

### 2.4 文档

`README-Windows.md` 新增两节：`### 绘光的主题：跟着终端的黑 / 白走`、
`### 拼贴里的终端浮窗（可最小化）`，并更新【外观】表格的「主题」行。

## 3. 验证

### 3.1 真实浏览器（headless Chrome + 手写 CDP 客户端，无第三方依赖）

`node _lumiere_theme_test.cjs <baseUrl>` → **19/19 PASS**，源码服务与打包 exe 各跑一遍：

| 检查 | 结果 |
|---|---|
| adapter 加载 + `BASE_DUAL_THEME` 在产物里 | PASS |
| 白主题 → 绘光「日光素白 · WebTerm」/ 黑主题 → 「午夜墨染 · WebTerm」 | PASS |
| 换主题后 ticker 帧数继续增长（没被换主题打断） | PASS（20 → 34） |
| 拼贴态终端搬进 `#tfBody`，展开高度 432px | PASS |
| 点 `—` 后高度 30px、挂 `.min`、`#terminal` 仍在 `#tfBody` 里 | PASS |
| `localStorage.min === true` | PASS |
| 再点恢复 432px、`.min` 摘除 | PASS |
| 展开后输入 `echo CYRIL-MIN-OK` 并回车，xterm 缓冲区出现回显 | PASS |
| 页面无 console.error / 未捕获异常 | PASS |

截图：`lumiere-midnight.png` / `lumiere-daylight.png` / `lattice-term-min.png`。

### 3.2 语法

`node --check web/folia-bridge.js`；`index.html` 3 段内联脚本经 `new vm.Script()` 解析，0 错误。

## 4. 产物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `dist\WebTerm.exe` | 189.6 MB | `8B619B9E08B5766901DDA41C6D8F26C29A5AAA1887AA0F789D1DCF73CF25DB5B` |
| `electron-dist\...\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `%LOCALAPPDATA%\WebTerm\app-1.0.4\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `installer\WebTerm-Electron-Setup-1.0.5.exe` | 见构建输出 | 见 `SHA256SUMS-1.0.5.txt` |

构建：PyInstaller 6.22.3 + UPX 5.3.0（`components\cyrilshield\vendor\tools\upx.exe`，
不加 `--upx-dir` 会产出 253 MB 未压缩包，与 1.0.4 体积对不上）。

## 5. 回滚点

| 文件 | 说明 |
|---|---|
| `dist\WebTerm.exe.prev12` | 1.0.4 的 exe（198,689,246 B，`575f165a…`） |
| `electron-dist\...\resources\WebTerm.exe.prev13` | 11-16 的便携版旧后端（253,607,119 B，`2576e0f5…`，**早于 P0 安全修复，不要配 remote 使用**） |
| `installer\WebTerm-Electron-Setup-1.0.4.exe` | 1.0.4 安装包（可覆盖安装回滚） |
| `_maintenance\20261118\pre-lumiere-theme-tfmin.zip` | 改动前的 `web/index.html`、`web/folia-bridge.js`、`web/folia-adapter.js`、`build-folia/` |

## 6. 已知边界

- **日光素白不会照亮舞台**：原版 `resolveLumiereDarkField()` 对亮背景主题强制
  `LUMIERE_BRIGHT_DARK_FIELD_FLOOR = 0.94` 暗场保底（「绘光始终在暗场里」），
  白主题下舞台仍是近黑底，差异体现在光色 / 字色（暖橙 vs 冷银白）。
  要让舞台跟着变白，需改 `lumiereDarkField.ts` 的 0.94 —— 那是改原版视觉，本次未动。
- **安装包仍不含 `components/` 与 `lxmusic-data/`**：`build-installer.js` 的 `SKIP_NAMES`
  与 nuspec 都排除了它们，装完是裸终端（CyrilShield 与音源需手工拷）。本次未改打包策略。
- 替换便携版 / 安装版 exe 时，正在运行的 Electron 实例（09:16 启动，PID 43916 等）仍用旧后端，
  **重启窗口**后才加载新 exe。

---

# 第二轮（13:30–14:05）：补「效果不全」

起因：上一轮交付后反馈「效果不全」。逐项对照原版后发现两类缺口 ——
绘光把原版**默认开着**的效果关了一半，主题也只覆盖了光束颜色。

## 7. 补回原版默认效果（`web/folia-bridge.js` 的 `TUNING`）

对照基线：`Mineradio-main/vendor-src/folia/src/types.ts:761` `DEFAULT_LUMIERE_TUNING`。

| 字段 | 旧（WebTerm 保守值） | 新（原版默认） | 影响 |
|---|---|---|---|
| `overlayFrame` | false | **true** | 取景器画框：四角括号 + 左右对位十字 + 顶边虚线（`overlay.ts`） |
| `keywordColors` | false | **true** | 主题 `wordColors` 关键词着色 |
| `themeIcons` | false | **true** | 主题 `lyricsIcons` 线稿图标 |
| `renderQuality` | balanced（图形组 0.7×，倍频≤4） | **full** | 雾气/辉光满分辨率、倍频按 5 |
| `darkField` | 0.8 | **0.75** | 暗场强度 |
| `unlitOpacity` | 0.2 | **0.22** | 未唱字透明度 |

`Lumiere.debug()` 现在额外回 `tuning`，可直接核对喂给 runtime 的这份参数。

## 8. 主题覆盖补全（`web/index.html`）

- **画框跟着主题**：`commitSong` 在主题变了时会 `drawOverlay()`，画框线色取 `resolveLumierePalette().light`
  —— 黑＝冷银、白＝暖橙。
- **歌词浮层**：原来两条 `body.lumiere-on #radio-lyrics*` 写死暖金，黑主题下冷暖打架。
  现在拆两套：`body.lumiere-on`（冷银 `#f4f4f5` / 玻璃 `rgba(8,10,14,.28)`）与
  `body.lumiere-on.light`（暖金 `#fff3cf` / `rgba(16,8,4,.28)`）。
- **画布底**：`body.light #lumiere-canvas { background:#0f0f0e }`，即原版暗场色（`#f5f5f4 × 0.06`），
  黑主题仍是 `#050208`。
- 元数据补 `album`（片尾卡 `LumiereCreditsLayer` 会用到），两处 `setLyrics` 都带上。
- 种子一致性：`program(seed)` 接受 seed，`setLyrics` 用 `makeSeed()` 生成后同时给
  program 与 song：之前 `curSeed` 比编译时的 seedN 多 1，程序随机序列与歌曲身份对不上。

## 9. 最小化补全（`web/index.html`）

- 收起时 `#tfSize` 一并 `display:none`（没有尺寸可调，留着只会「点了没反应」）；
- 提示文案随状态切换：收起 → `点 ▢ 展开 · 拖动标题移动 · 双击复位`，展开恢复原文案。

## 10. 验证

- `_lumiere_render_probe.cjs`（headless Chrome + CDP）：载入 6 行假 LRC 后截图，画框四角/十字/虚线可见；
  探针读出两套主题下 `#radio-lyrics` 的计算样式：
  黑 `rgba(8,10,14,0.28)` / 边框 `rgba(210,225,245,0.2)`，白 `rgba(16,8,4,0.28)` / `rgba(255,205,140,0.22)`；
  `#lumiere-canvas` 计算背景 `rgb(15,15,14)`。
- `_lumiere_theme_test.cjs` 19 项断言，**源码服务 / 打包 exe / 安装版 1.0.6 三处各跑一遍，全 PASS**。
- 证据图：`probe-lumiere-midnight.png`（冷银 + 画框）、`probe-lumiere-daylight.png`（暖金 + 画框）。

## 11. 本轮产物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `dist\WebTerm.exe` | 189.6 MB | `f87efe9421cf2a64b0fa05e868dfc26001f0f302bd8c3a029615ba49881b890a` |
| `electron-dist\...\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources\WebTerm.exe` | 同上（Squirrel 安装） | 同上 |
| `installer\WebTerm-Electron-Setup-1.0.6.exe` | 338.6 MB | `f6244077e02e6d84383416a82a903f4c929f2cf3e7a98c2b456a3ecadc050ff4` |
| `installer\WebTerm-1.0.6-full.nupkg` | 337.8 MB | `3aab2301bbd95d13cbcb5f814e116270edd371ad938c6a9a44caaad39f78b6a5` |

回滚点新增：`dist\WebTerm.exe.prev-1.0.5`（`8b619b9e…`，上一轮产物）、
`_maintenance\20261118\post-effects-lumiere-tuning.zip`（本轮改后的三个前端文件）。

---

# 第三轮（14:05–14:55）：移植凝彩（Folia Tempera）

起因：「把凝彩也加进 Terminal 来」—— 原版 Folia 有**两个**可视化器，绘光（Lumiere，舞台光束）之外
还有凝彩（Tempera，网点图形色块 PV + 大字拼贴，121 种镜头分 13 族）。前两轮只搬了绘光。

## 12. 源码移植

| 动作 | 说明 |
|---|---|
| `cp -r` 整目录 | `Mineradio-main/vendor-src/folia/src/components/visualizer/tempera/` → `build-folia/lumiere-src/components/visualizer/tempera/`，**51 文件 8613 行，与原版 `diff -rq` 无差异** |
| 补依赖 | `pixiDisplayResources.ts`（`pixi/`）、`sonnet/sonnetLensFilter.ts`、`sonnet/sonnetPrintFilters.ts` —— 三者 `diff -q` 与原版一致 |
| `adapter.ts` | 追加 `compileTemperaProgram` / `findTemperaParagraphIndexAtTime` / `TemperaPixiRuntime` / `DEFAULT_TEMPERA_TUNING` / `segmentTextWords`，挂 `window.TemperaFolia`（绘光的 `window.LumiereFolia` 不动） |
| `node build-folia/build.mjs` | `web/folia-adapter.js` 2,794,173 → **3,078,779 B**（闭包 54 文件，产物含全部 shot kind：`apex-mass`/`ziggurat`/`bubble-drift`/`ring-eye`/`ridge-eye`/`dial-scale`/`ridge-line`/`chasm`/`block-wipe`/`monogatari`） |

只搬**运行时**，React 外壳与设置面板不进包。

## 13. 桥接层（新增 `web/tempera-bridge.js`，427 行）

对外 `window.Tempera = { init, setLyrics, start, stop, resize, isRunning, setTuning, setTheme, themeName, seek, debug }`。

- **绝对时间驱动**：与绘光相反，凝彩不吃音频能量，只喂 `currentTime` / `paused`；节奏来自歌词时间轴与镜头脚本。
- **演示态**：无曲目时按原版纯音乐逻辑灌 60 个 8 秒 `♪` 虚拟行，时间轴自走并按总长回绕（否则钉在 0 秒、永久第一帧）。
- **换主题**：`setTheme(light)` 用**同 seed** 再 `swapSong`，runtime 走 `commitSongContext` 直通，无跨曲交接。
- **纸色**：`paintHostPaper()` 把 `theme.backgroundColor` 写到 `#tempera-canvas` 内联 style（Pixi 透明底，镂空族的洞靠它透背景）。
- **自检**：`debug()` 回 `{started,demo,frames,time,theme,colors,layout,paragraphs,scene}`；`scene.shown` 列「真正会画出来」的文字节点
  （`t/fs/box/wa`，父链 `chainVisible`/`chainAlpha`）；`seek(t)` 拨演示时间轴并 `renderOnce()`，用于停在已落位画面比对。

## 14. 两个踩坑

1. **字体（关键 bug）**：`fontStacks.ts` 的 `quoteFontFamily` 把 `theme.fontFamily` 当成**一个字体名**再套引号，
   桥接层初始把整条 CSS 栈塞进去 → 拼出非法 `ctx.font` 串被浏览器静默忽略 → 全部文字回落 canvas 默认 10px。
   表现是「`scene.shown` 里 `fs=202`、alpha=1、坐标正中，画面上只有 11×14 像素」。
   修法：`WEBTERM_FONT` 改用 `fontFamily: 'Microsoft YaHei'` + `fontFamilyStack: [...]`。修后 `box` 变 `222×274`。
   **`web/folia-bridge.js` 有同一份错误配置，一并修正**（`scene.ts`/`credits.ts` 也走 `resolveThemeFontStack`）。
2. **CDP 软件渲染**：探针最初用 `--use-gl=angle --use-angle=swiftshader`，difference filter 的 back texture 采样崩，
   整屏中灰、主歌词消失。改为默认用真实 GPU，仅 `WT_SW=1` 时才加 SwiftShader 参数。

## 15. 三态改造（`web/index.html`）

- `BG_MODES = ['sonic','lumiere','tempera']`，`BG_LABEL` 加 `tempera: '凝彩'`；
- `#tempera-canvas`（`position:fixed; inset:0`，黑 `#09090b` / 白 `#f5f5f4`）+ `body.tempera-on` 显隐；
- 【外观】背景源加【凝彩】按钮（四个按钮加 `flex-wrap` 防溢出）；「视」菜单加 `data-view="tempera"`；
  模块菜单 `view:tempera`；`window.__cyrilViewOpen` 支持 `tempera`；
- `applyBackground()` / `stopBackground()` / `exitRadio()` 三处接管 `Tempera.start()/stop()`；
- `applyTheme()` 末尾追加 `Tempera.setTheme(light)`；`pushLyricsToVisualizers()` 统一给绘光与凝彩下发歌词；
- **顺带修既有 bug**：`viewMenuPick` 原来只调 `applyBackground`，而 `applyBackground` 仅在 `mode==='radio'` 时才 `start()`
  → 在终端态点「视」菜单选音域/绘光，只加了 body class、runtime 没起。现统一走 `enterRadio(v)`。
- 凝彩**不参与** `syncOnDarkLight`（纸色跟随主题，硬翻字色会黑白对撞）。

## 16. 验证

- `_tempera_probe.cjs`（CDP）：载 6 行假 LRC，`seek(3/7/11/15/19/23/27)` 逐点截图 → 大字拼贴、跨明暗色块反色、
  取景角标、贯穿线都在；黑 `#09090b` 深纸 / 白 `#f5f5f4` 浅纸+橙。
  证据：`probe-tempera-dark-1..4.png`、`probe-tempera-light-1..3.png`、`probe-tempera-t3/t7/t11/t15/t19/t23/t27.png`。
- `_tempera_ui_test.cjs` 25 项断言，**源码服务与打包 exe 各跑一遍，25/25 PASS**（含：菜单含凝彩项、点选起画布、
  runtime 创建、演示段落 27 段、ticker 帧数递增、外观 4 按钮同排不溢出、黑白切午夜墨染/日光素白+纸色、
  切走时画布与 runtime 收干净、`view:tempera` 动作表、退出电台无残留、无 console 报错）。
- 绘光回归 `_lumiere_theme_test.cjs` **19/19 PASS**（字体修正未破坏绘光）。

## 17. 本轮产物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `dist\WebTerm.exe` | 198,880,360 B | `7b4d51727db5e91f3c5a960321cdad19ced452b189cfe8508d15b0c14bb581f6` |
| `electron-dist\...\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `installer\WebTerm-Electron-Setup-1.0.7.exe` | 355,162,624 B | `e4a0181ec2c66e817d7ee429055c7668d381e636c662f4147c7ca445c7d81401` |
| `installer\WebTerm-1.0.7-full.nupkg` | 354,328,815 B | `582335248049ef0a5f7f7b549aeb7d99015b80d3dcd6627a398333dd9ae0e2be` |

`WebTerm-1.0.7-full.nupkg` 内 `lib/net45/resources/WebTerm.exe` 已核对：198,880,360 B / `7b4d5172…`，与 `dist` 一致。

**打包必须带 `--upx-dir`**：`python -m PyInstaller --noconfirm --upx-dir components/cyrilshield/vendor/tools WebTerm.spec`。
漏掉它，`llama\` 下的 `dnnl.dll` / `ggml-sycl.dll` / `ggml-vulkan.dll` 不被 UPX 压，exe 从 198.9 MB 涨到 **253.7 MB**（本轮踩过）。
另外 `--clean` 会触发本机安全删除钩子（一次删 115 个 bincache 目录被拦，构建直接退出），日常增量构建不要带。

**1.0.7 未做静默安装**：检测到用户 13:56 起的 Electron 1.0.6 还在运行，`--silent` 升级会把它关掉（终端会话会断），
留给用户自行选择时机。安装包内容已用 nupkg 内 exe 哈希核对，不需要装一遍才能确认打对了。

回滚点：`_maintenance\20261118\pre-tempera.zip`（改动前 `web/` 三件 + 文档）、
`dist\WebTerm.exe.prev-1.0.6`、`electron-dist\...\resources\WebTerm.exe.prev1.0.6`。

---

# 第四轮（15:00–15:20）：英文歌歌词只剩译文

反馈原文：「放英文歌时只有翻译，没有原文」。

## 18. 根因

LX Music / 酷我这类来源存下来的 `.lrc` 是**「原文块 + 空行 + 翻译块」**，同一句的两个时间戳
**完全相同**：

```
[00:07.455]I caught a sickness and they don't know the name of it
...
[00:07.455]我不幸染上疾病 他们却不知道名字是什么
```

`parseLrc()` 原先把这当作两条独立行收下；而当前行是按
`for (i…) if (curLrc[i].t <= t) idx = i; else break;` 取的 —— 同时间戳里**最后一条**胜出，
正好是译文。于是面板高亮、绘光/凝彩的大字都落到中文上，原文被当成「已唱过去的行」。
实测 `Amazing - Ed Sheeran.lrc`：102 行解析成 102 条，其中 49 对同时间戳。

## 19. 改动（`web/index.html`）

1. **`parseLrc()` 解析期合并**：按毫秒对齐时间戳，第一条进 `text`，第二条进 `trans`；
   第三条起并回正文（`A / C`，宁多勿丢）；同一句重复标注不去重成两条。
   产物形如 `{ t: 7.455, text: 'I caught…', trans: '我不幸染上疾病…' }`。
2. **歌词面板**：`renderLrc()` 每行改成 `.l-main` + `.l-trans` 两个 span；
   CSS 加 `.l-trans { display:block; font-size:.86em; opacity:.66; font-weight:normal }`
   （当前行整行 bold 不该把译文也加粗）。
3. **拼贴歌词** `latGroupLrc()`：优先吃 `text`/`trans`，仍保留 ≤0.6 秒近邻合并兜住
   「译文晚 0.x 秒标注」的老式 LRC。
4. **顺带修一个真实缺陷**（这轮查导入链路时撞出来的）：`dbMerge()` 读不到旧记录时
   仍 `store.put(merged)`，而 `tracks` 表的 keyPath 是 `key` → `DataError: key path did not
   yield a value`（未捕获，控制台报错、歌词静默不落库）。触发条件是**同名 `.lrc` 比音频先读完**：
   `attachLrc()` 在 `dbPut()` 之前跑。现在读不到旧记录就直接返回 —— 紧随其后的 `dbPut()`
   会带着 `item.lrc` 一次性写全，不丢数据。

绘光 / 凝彩吃的是同一份数组且只读 `text`，所以解析层改完后它们的画面自动变成原文
（`Tempera.debug().scene.shown` 从中文变成 `E` / `-` 这类拉丁字形）。

## 20. 验证

- `_lrc_parse_test.cjs`：**22/22 PASS**。合成用例（原文块 + 翻译块 / 一行多时间戳 / 纯标签行 /
  第三条不丢内容）+ 两首真歌（Amazing、Free Loop），并复刻 `updateLrcHighlight` 的取行规则，
  在 20.5 / 30.5 / 60.5 / 120.5 秒四个时刻断言选中的是原文行。
- `_lrc_ui_test.cjs`：**11/11 PASS**（源码与打包 exe 各一遍）。真导入 mp3 + LRC
  （`DOM.setFileInputFiles` 走 `<input type=file>`，与用户点选同一条路）→ 点队列项 → 断言
  49 行不翻倍、46 行带译文、0 行错位、正文全英文、无 console 报错（`DataError` 已消失）。
- `_lrc_visual_probe.cjs`：证据图 `probe-lrc-en-panel.png`（原文 + 译文小字）、
  `probe-lrc-en-tempera.png`（凝彩大字是 `Amazing`）。
- 回归：`_tempera_ui_test.cjs` **25/25**、`_lumiere_theme_test.cjs` **19/19**。
  注意这两套都会改 `Settings.json` 的 theme，**必须逐个跑**：连着跑时后一方会读到前一方
  改过的主题，`启动主题 = 黑 → 午夜墨染` 这条会假失败（本轮踩过一次，单跑即过）。

## 21. 本轮产物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `dist\WebTerm.exe` | 198,881,445 B | `be84b8835701abebced8b0d8ee1ae1f4506c3a94601ed3c4318237b60e29f91a` |
| `electron-dist\...\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `installer\WebTerm-Electron-Setup-1.0.7.exe` | 355,164,160 B | `1db7e7bb549a925d38d520d2bf08318e3b80ce1d0b766c69ba26dbceeb334772` |
| `installer\WebTerm-1.0.7-full.nupkg` | 354,330,630 B | `98386adc89a5ef9cb9cf8dedda0440bd11f6c5fff5ce6ec180c029b7fe3378dd` |

nupkg 内 `lib/net45/resources/WebTerm.exe` = `be84b883…`，与 dist 一致。
1.0.7 把三轮（凝彩）与四轮（歌词）都收进同一个版本号，因为 1.0.7 从未静默安装过。

`dist\Settings.json` 被测试改过（theme / bg_source），已恢复成测试前的 `light` / `sonic`。

---

# 第五轮（15:20–16:20）：可视化器中文字幕条 + 播放中切全屏报错

反馈两条：①「@截图 没中文了」——凝彩/绘光的大字只剩英文原文；②「修复窗口转全屏时报错问题」，
后补关键条件「**播放有歌词的歌曲时**切换全屏才会报错」。

## 22. 中文字幕条（`#viz-sub`）

第四轮把正文修成原文后，可视化器大字只吃 `text`（原文），中文译文没了。原版 Folia 的做法是
**大字幕归可视化器、翻译归独立字幕层**（`VisualizerSubtitleOverlay`），照此在 `web/index.html`
加 `#viz-sub`：

- DOM：`<div id="radio">` 内、`#tempera-canvas` 之后；电台态且当前句带 `trans` 才显示；
- `updateLrcHighlight()` 里同步刷新（暂停态没有 timeupdate，进电台时也要立刻挂一次）；
- 位置 `bottom: clamp(88px, 12vh, 140px)`，在 `#radio-controls`（播放条）**上方**，
  不压终端提示符；退出电台立即收起；
- 颜色跟主题：深色主题浅字（`var(--cy-lyric)` 系）；**凝彩浅纸下用深字**——白主题的
  `body:is(.light,.on-light)` 通用翻色对凝彩是黑白对撞（纸是浅的、字必须深），所以浅色断言
  在测试里是先进 `window.__cyrilViewOpen('tempera')` 再切主题。

验收 `_viz_sub_test.cjs`：**14/14 PASS**（源码 + 打包 exe 各一遍），含「3 句歌词不被译文撑成
6 行」「译文条在播放条上方」「凝彩大字仍是英文原文」「退出后收起」「无 console 异常」。
证据：`probe-vizsub-tempera-dark.png`。

## 23. 播放中切全屏报 `FilterSystem` 空引用

报错：`Cannot read properties of null (reading 'source')`，栈在 Pixi
`FilterSystem._findFilterResolution`。

- **为什么之前没测出来**：无歌词时场景少、滤镜栈浅；有歌词时凝彩场景 + 后处理滤镜同时存在，
  resize 才会踩中。用户补的「播放有歌词的歌才报错」正是缺的条件。
- **根因**：凝彩/绘光 runtime 的 `ResizeObserver` 回调里**同步** `renderer.resize()`。
  resize 会把滤镜占用的纹理交回纹理池（引用清空），而本帧后处理滤镜栈里还留着这些纹理——
  下一帧滤镜系统按栈里的引用找 `resolution` 就读到 null。
- **修法**（两套 runtime 同改）：
  1. 回调里只立 `pendingResize = true`，真正 resize 排到 `requestAnimationFrame`
     （等当前渲染帧走完），`destroy()` 时 `cancelAnimationFrame` + 断开 observer；
  2. resize 后调用 `resetFilterStack()`：遍历 `renderer.filters`，把持有已销毁纹理的
     filter 从栈里摘掉，强制下帧重建。
- **回归**：`_resize_with_lyrics.cjs`（真导入 mp3 + 译文 LRC → 进背景 →
  `Emulation.setDeviceMetricsOverride` 连续抖 12 档尺寸 × 3 轮）：凝彩与绘光 **均 0 报错**，
  runtime 存活、`layout.resolution` 正常。修复前凝彩必现 1 条上述异常。

同时把全屏下的 `#viz-sub` / `#radio-controls` 位置也核对了一遍（`bottom` 用 clamp，
全屏加高后仍在播放条上方）。

## 24. 本轮产物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `dist\WebTerm.exe` | 198,883,449 B | `598f0c022cd722274c7d69a96ce6d2f214efa2893fa35bf3db3b7e4ed4240115` |
| `electron-dist\...\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources\WebTerm.exe` | 同上（已同步） | 同上 |
| `installer\WebTerm-Electron-Setup-1.0.7.exe` | 355,164,672 B | `910205b338ae4ffb498941d55a14765f72b46fbf3be5f3570f275f8f3c7b2df2` |
| `installer\WebTerm-1.0.7-full.nupkg` | 354,331,362 B | `80349b0f74a9b151f30d037f3870f04c1fc96f996455323877e23c5dd18c0a3c` |

nupkg 内 `lib/net45/resources/WebTerm.exe` = `598f0c02…`，与 dist 一致。

凝彩 25/25、绘光 19/19（打包 exe 单跑）、歌词 11/11。`dist\Settings.json` 测试后被改为
`dark`/`lumiere`，已恢复 `light`/`sonic`。

---

# 第六轮（16:28–16:45）：译文条去白框 / 译文开关 / 终端补指令

反馈：「下方译文不要白框。而且可在设置中关闭译文。完善终端指令（设置和插件）：Cyril-」，
后补「**命令不要中文**」。

## 25. 译文条去白框

`#viz-sub` 原来带 `padding + border-radius:999px + background + backdrop-filter`（胶囊底）。
浅色凝彩下是一块白框压在浅纸上。现全部去掉：**纯文字叠在背景上**，只靠双层
text-shadow 保可读性（深色浅字配黑影、浅纸深字配白影）。

## 26. 译文开关

- 设置 → 交互 → **电台译文** 勾选框（`#setVizSub`），默认开；
- 状态存 `localStorage['Win-webterm-viz-sub']`（与主题 / 背景源同待遇的界面偏好），
  改动立即生效（`setVizSubEnabled` → `updateVizSubtitle()`）；
- 终端 `trans` / `trans on` / `trans off` 同一状态源，两处互相同步。

## 27. 终端补指令（全英文）

server.py 的 `TERM_CMDS_PS` 新增 5 个 PowerShell 函数 + 别名：

| 命令 | 动作 |
|---|---|
| `settings` | 打开设置面板 |
| `appearance` | 打开外观面板 |
| `plugin` | 打开组件面板（实时电平 / 系统占用） |
| `users` | 打开用户管理（仅本机管理员，非管理员终端里也拒） |
| `trans` | 电台译文开关 |

- **不用 `comp` 当别名**：System32 里有 `comp.exe`（文件比较），别名会把它遮住，`plugin` 无冲突；
  `Cyril-Comp` 全名仍可用（前端 dispatcher 认 `comp`）。
- 曾按用户原话加了中文别名（设置/插件/…）并放宽了前端标记正则，用户随后明确
  「命令不要中文」，已全部撤掉：别名只留英文，标记正则回到 `[a-zA-Z]+`。
- 欢迎横幅（`LOGIN_BANNER` / `LOGIN_BANNER_COMPACT`）加一行
  `Panel: settings 设置  comp 插件  trans 译文开关  appearance 外观`。
- 帮助面板加「面板与电台」一节；`termCmdPanel()` 复用设置面板【面板入口】同一条开窗路径
  （外观走原按钮 click，用户面板先验 `authInfo.admin` 再刷新列表）。

## 28. 验证

- `_viz_sub_test.cjs` 扩到 **22 项全过**（源码服务）：新增「两种主题译文条无背景框」
  「开关默认开」「终端 trans off/on 改 localStorage 并同步勾选框」「settings / plugin
  经真实 PTY 打开对应面板」。
- 回归：凝彩 25/25、绘光 19/19（单跑）、歌词 11/11；`server.py` 语法检查通过。
- 一个测试基建坑：上轮清理时把 `_maintenance/20261118/test-lyric-en.mp3` 删了，导致
  导入阶段 9 项 FAIL——音频补回即过，断言本身没问题。

## 29. 本轮产物

| `dist\\WebTerm.exe` | 198,884,742 B | `9d7127cc5bf64db9110e561dc43722a38201da2c0a0464fb8818f269d25809b0` |
| `installer\\WebTerm-Electron-Setup-1.0.7.exe` | 355,165,696 B | `921e138d5e79894a80410bdc5f18ecf5b62ebf6b1b559feb238cb17f4732f58b` |
| `installer\\WebTerm-1.0.7-full.nupkg` | 354,333,127 B | `0ae60c246beb21d654238c5d4d494916e875e4c84768db30272ca12f61eba7bc` |

nupkg 内 exe = `9d7127cc…`，与 dist 一致。三处（dist / Electron 壳 / 已装 1.0.6）已同步。

打包版 22/22、25/25、19/19（单跑）、11/11 全过。Settings.json 测试后已还原 `light`/`sonic`。

## 30. 第八轮：商籁（Sonnet）+ 雾笺（Nebbia）移植

- **源**：`C:/Users/Cuisi/Downloads/Mineradio-main/vendor-src/folia/src/components/visualizer/{sonnet,nebbia}`。
- **sonnet**（55 文件）：印刷排版 PV，绝对时间驱动。新增共享依赖 `utils/lyricRenderQuality.ts`、
  `visualizer/visualizerSeedHash.ts`、`pixiRuntimeHost.ts`；刷新 `types.ts`（新增 SonnetTuning 等）、
  `wordSegmentation.ts`、`colorMix.ts`。`sonnet/sonnetLensFilter.ts`、`sonnetPrintFilters.ts` 沿用上一轮已拷贝版本。
- **nebbia**（53 文件 + 资源）：WebGL2 雾玻璃 PV，接节拍与人声。新增 `services/lucideIconResolver.ts`、
  `services/beatFx/{beatEffects,clock}.ts`、`mineradio/local/{localLiveAnalysis,localAnalysisBoundary}.ts`、
  `stores/motionSignals.ts`、`utils/playerBottomBarLayout.ts`、`visualizer/tuningDefinition.ts`（新 read/storage API）。
  `nebbiaRaindrop.png` 转 `nebbiaRaindropAsset.ts`（内联 base64）；`nebbiaStrokes.ts` 的
  `import.meta.env.BASE_URL` 改为 `./nebbia-strokes.json` 相对路径（rolldown 无 import.meta.env），
  `web/nebbia-strokes.json` 随包分发。`useVisualSettings` 用薄垫片（WebTerm 无该 store，喂固定值）。
- **构建链**：Mineradio 侧 node_modules 已被清理 → `build-folia` 本地 `npm i rolldown pixi.js@8.21.0
  @chenglou/pretext@0.0.9 react@19 react-dom@19 lucide-react`（真 lucide 替换空 stub，图标效果完整）；
  `build.mjs` 的 `FOLIA_NM` 改为本地 `node_modules`。产物 6816 KB。
- **桥接**：`web/sonnet-bridge.js`（347 行）、`web/nebbia-bridge.js`（381 行），接口与 Tempera 桥一致
  （init/setLyrics/start/stop/resize/isRunning/setTuning/setTheme/themeName/seek/debug）。雾笺驱动
  `vocal` MV（人声频段 2k-6k）+ `advanceLocalAnalysis`/`applyLocalAnalysisAudio` 智能动感链路。
- **前端**：BG_MODES 五态（sonic/lumiere/tempera/sonnet/nebbia），外观面板【商籁】【雾笺】按钮、
  「视」菜单两项、`BG_LABEL` 更新、译文条浅色规则扩展到五态。
- **文字自适应**（用户反馈「有些字在特定场景很难看清」）：
  1. 雾笺主着色器：字迹=擦开雾见窗外，窗外与雾亮度接近时融掉。现在按「对比度不足才干预」
     自适应：雾偏暗把字迹往亮端推（逆光描白）、雾偏亮往暗端压，暗雾另加暖色 rim 光晕
     （`nebbiaShaders.ts` 擦开区合成段）。
  2. 终端文字：电台可视化模式下 xterm 文字加 text-shadow 描影（浅色主题且凝彩/商籁/雾笺
     用白描影；绘光白主题仍是压暗舞台，保持暗描影）。
  3. `__cyrilApplyTheme(name)` 支持可选参数（原来忽略参数，测试与外部调用传主题名不生效）。
- **验证**：`_sonnet_nebbia_test.cjs` 19/19（挂载/桥接 API/双模式切换渲染/主题/resize 压测/互斥全停/
  全程无 JS 异常）；`_nebbia_ink_probe.cjs` 暗景与日雾截图各一，字迹可读；既有四套全过：
  凝彩 25/25、绘光 19/19、歌词 11/11、译文条 22/22（浅色深字断言修复后）。
- **教训**：①探针先 setLyrics 再进电台会被播放器推来的空歌词覆盖 → 必须 start 之后 set；
  ②歌词字迹随歌词行结束淡出，截图要卡在行内；③`_lumiere_theme_test` 默认连 18765，
  与测试服务端口不一致时页面导航失败表现为 chrome-error://。

## 31. 本轮产物（1.0.8）

| 文件 | 大小 B | SHA256 |
| --- | --- | --- |
| `dist\WebTerm.exe` | 201,183,597 | `784bc7d91202362d01ae3d602194109c30903e1ffebae9b6f056fb3a33cb1559` |
| `installer\WebTerm-Electron-Setup-1.0.8.exe` | 357,466,624 | `c2cab10023144696c0658410e04432bfbdda2e60c389181234b1bb69f7dd4ab1` |
| `installer\WebTerm-1.0.8-full.nupkg` | 356,631,190 | `fb9fca9bbf7a665ac24a4b6ef46c8f575f38067bf444b47ce009d2d9ef62b268` |

nupkg 内 `lib/net45/resources/WebTerm.exe` = `784bc7d9…`，与 dist 一致。
三处（dist / `electron-dist\...\resources` / `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources`）已同步
201,183,597 B。`Settings.json` 已还原 `theme=light / bg_source=sonic`。

## 32. 第九轮：播放队列窗口 — 搜索 + 按歌手/专辑分组

- **搜索**：队列面板头部新增 `#q-search` 输入框。命中范围 = 歌名/文件名/歌手/专辑，
  归一用 AI 点歌同一套 `normKey`（去书名号引号空格、小写、双向包含）。
  过滤时计数显示「x / y 首」，无结果显示「没有匹配『…』的曲目」提示行；
  Esc 在框内清空并收焦（stopPropagation，不触发浮层关闭）。
- **分组**：`全部 / 歌手 / 专辑` 三态切换按钮。分组顺序 = 各组在队列里首次出现的
  顺序（不重排队列）；组头显示组名 + 首数，点击折叠/展开（`qCollapsed` 记状态）。
  无标签曲目归「未知歌手 / 未知专辑」。行下标始终是完整队列的真实下标，
  过滤/分组态下点行播放、点 × 删除都落对（`data-qidx`）。
- **文件名兜底元数据**（分组可用的前提）：用户曲库大量文件无内嵌标签（抽检
  告白气球/Westlife/Run 三个 FLAC 的 VORBIS_COMMENT 只有 `encoder=Lavf58`），
  且命名约定混用——『告白气球 - 周杰伦』是 歌名-歌手，『Westlife - My Love(Radio Edit)』
  却是 歌手-歌名。批量启发式 `buildNameMetaMap()`：同批导入里某侧名字出现 ≥2 次
  且从没在另一侧出现 → 认定该侧是歌手（歌手名跨文件复用、歌名不会）；
  无证据时按最常见的 歌名-歌手 兜底；紧连「-」（周杰伦-麦芽糖）只在左段已被
  认定为歌手时才拆。序号前缀（`1_` / `01.`）与尾注（`_(Instrumental)` 等）先剥掉。
  内嵌标签永远优先，只补缺失字段；导入时随 `dbPut` 持久化，旧库记录在
  `reparsingMeta` 里用单文件保守规则（只认 歌名-歌手）补。
- **样式**：工具栏/搜索框/分组按钮/组头全套深浅主题双份（`body:is(.light,.on-light)`）。
- **验证**：`_queue_test.cjs` 18/18（真导入 5 首多歌手曲目：搜索命中/计数/无结果
  提示/Esc 清空/歌手分组聚合/折叠展开/专辑分组/未知专辑归组/过滤态点行播放/
  active 保留/无 JS 异常）；回归歌词 11/11、译文条 22/22、商籁雾笺 19/19。
  截图 `probe-queue-grouped.png`：周杰伦组正确聚合 告白气球+稻香。
- 教训：无头 Chrome 里 `Page.captureScreenshot` 拍到的是最上层画面，超级终端
  启动页 `hide()` 只淡出还盖着，截图前要 `classList.remove('super-on')` + 兜底 display:none。

## 33. 本轮产物（1.0.9）

| 文件 | 大小 B | SHA256 |
| --- | --- | --- |
| `dist\WebTerm.exe` | 201,186,958 | `62db8512cf4154ea45a1d63178c04ebd1a95e0836f799c1e2a7d7ae56cb9b455` |
| `installer\WebTerm-Electron-Setup-1.0.9.exe` | 357,471,232 | `abad293b16364ff17ee3842018f1da40fed9015e6cd9ac6da3da0d2782fb3c70` |
| `installer\WebTerm-1.0.9-full.nupkg` | 356,635,025 | `e88383b27983ed56c0c1db3a787405b3aab82a17a0850f37ecc4eefe7df419ae` |

nupkg 内 `lib/net45/resources/WebTerm.exe` = `62db8512…`，与 dist 一致。
三处（dist / `electron-dist\…\resources` / `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources`）
已同步 201,186,958 B。`Settings.json` 保持 `theme=light / bg_source=sonic`。

## 34. 第十轮：播放模式四态合一 + 歌曲详情（改封面/标签/歌词）

- **播放模式单按钮**：`rbtn-shuffle`（⇄）+ `rbtn-loop`（↻）合并为 `rbtn-mode`，
  四态循环 顺序(→) → 列表循环(↻) → 单曲循环(↻1) → 随机(⇄)，非顺序态高亮。
  `setShuffle` / `setLoopMode` 保留为底层接口（AI 指令 `shuffle` / `loop` 不变），
  但按四态模型互斥：设循环清随机标志、设随机清循环模式，按钮图标/标题始终同步。
- **歌曲详情面板** `#radio-details`（float-panel 家族：panelMap / placePanel 避让 /
  makeDraggable / data-close 全套接入，控制条 ℹ 与队列行 ✎ 两个入口）：
  - 封面：点预览图换图（选图先本地预览 + 「新封面待保存」标记，点保存才生效，
    旧 coverUrl revoke）；
  - 标签：标题/歌手/专辑输入框，空值存 null；
  - 歌词：LRC 文本域，保存后 `parseLrc` 重解析，正编辑的是当前曲目时刷新
    歌词面板/大字译文条；
  - 保存统一走 `dbMerge`（标签+歌词+coverBlob 一次写回，保留音频 blob），
    队列行/播放条/拼贴墙/曲库同步/正在播放上报全链路刷新。
- **修复**：详情面板忘了 `position: fixed`（.float-panel 类不含定位，各面板自己
  声明）——样式里 top/left 全部无效、面板按 static 流到文档末尾被截断；
  以及「先开后填」会让 placePanel 按空提示态的小尺寸放置 → 改为先 populate 再
  showPanel，另加开窗后视口夹取兜底。
- **验证**：`_details_test.cjs` 22/22（四态循环/AI 指令兼容/面板填充/改标签后队列行
  与播放条同步/改歌词后歌词面板渲染/换封面预览与待保存标记/IndexedDB 持久化核对
  (artist+coverBlob+lrc)/行✎定位第2首/视口内完整显示/无 JS 异常）；回归队列 18、
  歌词 11、译文条 22、商籁雾笺 19 全过。截图 `probe-details-panel.png`。

## 35. 本轮产物（1.0.10）

| 文件 | 大小 B | SHA256 |
| --- | --- | --- |
| `dist\WebTerm.exe` | 201,190,328 | `a02321fb5c03e6476d11370931a58bf3e7d2078c8ac0c5830ec0538de4b45b96` |
| `installer\WebTerm-Electron-Setup-1.0.10.exe` | 357,473,792 | `ebf421fd7a5cdb8166ba681d2713482be9883658a4faa1189130fab8d18c22c3` |
| `installer\WebTerm-1.0.10-full.nupkg` | 356,638,321 | `af7f11204dfc5f91bda5f8e6dffaea1d3c1c1409189e6145b94ac3e9f10a292f` |

nupkg 内 `lib/net45/resources/WebTerm.exe` = `a02321fb…`，与 dist 一致。
三处（dist / `electron-dist\…\resources` / `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources`）
已同步 201,190,328 B。`Settings.json` 保持 `theme=light / bg_source=sonic`。
