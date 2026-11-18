# WebTerm for Windows（Terminal-Windows）

`D:\Terminal`（Kali WebTerm）的 Windows 移植版：**浏览器里的 PowerShell 终端**，支持局域网手机访问，可打包为单文件 exe。

## 与 Kali 版的关系

| 能力 | Kali 版（D:\Terminal） | Windows 版（本目录） |
|---|---|---|
| 会话 | bash（Linux PTY） | **PowerShell（Windows ConPTY）** |
| 后端依赖 | 纯标准库 | 纯标准库 + `pywinpty`（ConPTY） |
| 前端 | 同一份 web/index.html | 同一份（已做移动端适配） |
| 桌面 GTK 终端 | kali_terminal.py（Kali 可用） | 仅 Kali 可用，本目录保留副本 |
| deploy.sh / check.sh | Kali 部署脚本 | 仅 Kali 可用，保留副本 |

> 说明：`kali_terminal.py`、`deploy.sh`、`check.sh` 是 Kali/Linux 专用文件，
> 仅随目录保留，Windows 上不需要运行它们。

## 运行（源码方式，需要 Python 3.10+）

```powershell
# 安装依赖（只需一次）
pip install pywinpty

# 仅本机访问
python server.py

# 局域网访问（手机/其他设备）
python server.py --remote

# 自定义端口
python server.py --port 9000
python server.py --remote --port 9000
```

启动后自动打开浏览器 `http://127.0.0.1:8765/`；
`--remote` 时终端会打印 `LAN URL`，手机连同一 Wi-Fi 直接访问该地址。

端口 / 局域网开关也能在网页**【设置】**里改（写入同目录 `Settings.json`，**重启程序生效**）。
优先级：`--port / --remote` 命令行 > `Settings.json` > 环境变量 / 默认 8765。
`load_settings()` 已从打印阶段提前到 `bind` 之前，否则配置里的端口根本来不及生效。

## 打包 exe（PyInstaller）

```powershell
pip install pyinstaller pywinpty
python -m PyInstaller --clean --noconfirm WebTerm.spec
```

产物：`dist\WebTerm.exe`（单文件，目标机器无需安装 Python / pywinpty）。

`WebTerm.spec` 的两个要点：

- **winpty 目录按当前解释器动态解析**，不再写死某个 Python 环境；
- **llama/models 不进包**。GGUF 模型放在 `dist\llama\models\` 即可（运行时就是这么找的），
  旧配置把模型一起打进去，exe 会从 ~300MB 涨到 900MB+，且每次启动都要解压一遍。

## exe 用法

- **双击** `dist\WebTerm.exe`：启动服务并自动打开浏览器（默认 8765 端口）。
- **命令行带参**（推荐用 `cmd` 或 PowerShell 运行）：
  ```powershell
  WebTerm.exe --remote                # 局域网手机可访问
  WebTerm.exe --port 9000             # 自定义端口
  WebTerm.exe --remote --port 9000
  ```
- 关闭：在运行窗口按 `Ctrl+C`。
- `--no-browser`：启动后不自动打开系统浏览器（Electron 桌面壳专用，Web 版行为不变）。
- 端口 / 局域网开关也可在网页【设置】里保存，重启 Web 版 exe 或 Electron 壳都生效。

## 两种 EXE：Web 版 / Electron 桌面版

| | Web 版 | Electron 桌面版 |
| --- | --- | --- |
| 产物 | `dist\WebTerm.exe` | `electron-dist\WebTerm-Electron-win32-x64\WebTerm-Electron.exe` |
| 界面 | 自动打开系统浏览器 | 独立窗口，不弹浏览器 |
| 后端 | 自己就是后端 | 内置同一份 `WebTerm.exe`，由主进程拉起 |
| 依赖 | 无（PyInstaller 单文件） | 无（Electron 运行时随目录带上） |

两者共用同一份 `web/index.html` 和同一套 Python 后端，功能、登录、Settings.json、PTY 行为一致。

**目录结构**（构建依赖必须放在应用目录之外）：

| 路径 | 作用 |
| --- | --- |
| `electron\` | 应用源码：`main.js` / `preload.js` / `loading.html` / `cyril-splash.png` / `package.json` |
| `electron-tools\` | 构建依赖 `node_modules`（electron + @electron/packager）与离线运行时 `vendor\` |
| `electron-dist\` | 打包产物 |

**为什么依赖不能放进 `electron\node_modules`**：那里若有 `node_modules/electron`，
主进程里 `require('electron')` 会解析到本地 npm 包（它导出的是 electron.exe 路径字符串），
而不是 Electron 内置模块 —— `app.whenReady` 变成 undefined，窗口起不来。
同理 `vendor\` 里 158MB 的运行时 zip 也必须放在应用目录外，否则会被塞进 `app.asar`。

主进程做四件事：读 `resources/Settings.json` 里的 `port` / `remote`（命令行
`--port` / `--remote` 优先）→ 选服务端口（配置端口被别的程序占了才退回 8765 / 随机；
若那个端口上已经有 WebTerm 在跑就直接复用，不再起第二个后端）→
`spawn(resources/WebTerm.exe --port <port> --no-browser [--remote])`（隐藏控制台窗口）→
轮询 `/api/me` 通了再 `loadURL`。关窗用 `taskkill /T /F` 把 PyInstaller onefile
解出来的子进程一起收掉，不会留下占端口的孤儿进程。首次启动要解压运行时，
窗口先显示「正在启动本地服务…」加载页；运行日志在 `%TEMP%\webterm-electron-main.log`。

**Electron 版 `--remote` 之前为什么没用**：参数传给的是 `WebTerm-Electron.exe`，
Electron 自己吃掉后从没转发给 `resources\WebTerm.exe`，后端始终监听 `127.0.0.1`。
现在 `main.js` 的 `cliOpts() / readSvcCfg()` 合并命令行与 `Settings.json`，
`startBackend(p, remote)` 显式追加 `--remote`；网页【设置】里勾「局域网访问」
保存后重启窗口同样生效（后端 `main()` 也会读同一份配置）。本机回环仍免登录。

构建顺序（**必须先出 Web 版 exe**，Electron 包会把它一起带进去）：

```powershell
python -m PyInstaller --clean --noconfirm WebTerm.spec
cd electron-tools
npm install          # electron 45 + @electron/packager（含运行时，~250MB）
cd ..\electron
npm start            # 开发调试：直接用源码目录起窗口
npm run pack         # --extra-resource=../dist/WebTerm.exe
```

`npm run pack` 用 `--electron-zip-dir=../electron-tools/vendor` 走本地 zip，
**不需要联网**（Node 的下载器不读系统 `HTTP_PROXY`，联网安装经常卡住）。
zip 名必须是 `electron-v45.2.3-win32-x64.zip`，换 Electron 版本时要同步改 `--electron-version`。

**从 WorkBuddy / 其它 Electron 应用的终端里启动会失败**：这类终端会给子进程注入
`ELECTRON_RUN_AS_NODE=1`，Electron 会被当成纯 Node 跑，`require('electron')` 找不到。
正常双击 EXE、或在普通 CMD / PowerShell 里运行没有这个变量。非要在这类终端里验证，
先 `env -u ELECTRON_RUN_AS_NODE -u NODE_OPTIONS` 再启动。

### 无边框窗口 / 拖动区 / 启动动画

桌面版按 Mineradio 的做法去掉系统边框：主进程 `frame:false + transparent:true +
backgroundColor:'#00000000'`，窗口四角由页面 `body` 的 `clip-path: inset(0 round 34px)`
裁圆，圆外透出桌面；`hasShadow` 保留窗口阴影，`resizable:true` 显式打开，
边缘缩放另有一套自绘手柄（见下）。

**圆角（借鉴 `Mineradio-main/public/app.css` 的 `#desktop-window-shell`）**：
`body` 上除 `border-radius / overflow:hidden / clip-path` 外再加 `transform:translateZ(0)`
开合成层，并对每一层铺满全屏的 fixed 背景层（`#bgVideo / #bgOverlay / #radio /
#lumiere-canvas / #sonic-canvas / #loginScreen`）单独补一刀圆角裁剪 —— fixed 子元素
会逃过父级 clip-path，不补的话四角会被方形深色背景填平，看着仍是直角。
另叠一个 `#cyrilFrame`（全屏、圆角描边 + 内阴影、`pointer-events:none`）让「边缘」可见。
**圆角两态**：窗口态保留 34px 圆角；**只有真全屏**才把
`html / body / 各 fixed 背景层` 的 `border-radius:0 !important; clip-path:none !important;
box-shadow:none !important`，并隐藏 `#cyrilFrame`，四角铺满整块屏幕 —— 这正是
Mineradio `body.desktop-shell.desktop-fullscreen #desktop-window-shell` 的规则。
判据是主进程单独下发的 `cyril-fullscreen-state`（只认 `isFullScreen() / fullscreenActive`），
**绝不能用 `isMaximized()`**：透明无边框窗口启动时常被误判 maximized，早期版本
因此要么圆角从头没出现、要么普通窗口被摘成直角。`.cyril-maximized` 现在只切按钮图标。

**真正让圆角失效的是「背景传播」**（本轮修复）：CSS 2.1 §14.2 规定，当 `html` 的
`background-color` 为 `transparent` 且 `background-image` 为 `none` 时，浏览器会把
**`body` 的** `background-color` / `background-image`（含用户设的壁纸）提升为 canvas
背景，平铺整个视口；这层绘制在根元素层，`body` 上的 `clip-path` 根本裁不到，
于是四角永远被方形底色填满。旧壳里恰好写了
`html.cyril-desktop-root { background: transparent }`，把 body 背景推上了 canvas。
Mineradio `public/app.css` 的 `body.desktop-shell{background:transparent}` 是同一个规避。
现在改成给 `html` 一个「非 none 但 100% 透明」的
`background-image: linear-gradient(rgba(0,0,0,0), rgba(0,0,0,0))`：按规范不满足传播条件，
body 背景回到 body 盒内被圆角裁掉，窗口四角仍是全透明，不额外叠任何底色；
同时给 `html` 本身也补一刀 `border-radius / clip-path` 双保险。
`electron/loading.html` 的 `radial-gradient` 同理，两个页面一起改。

**拖动（不再用 `-webkit-app-region: drag`）**：transparent + frameless 窗口在 Windows 上
命中测试不走常规路径，顶栏计算值明明是 `drag`、窗口却拖不动。改成
`preload.js` 在 `pointerdown` 判定可拖区（顶栏 `header` 空白处 + 自绘标题栏 +
窗口四周 28px 边缘；按钮 / 输入框 / 浮层 / 文件树 / 终端正文一律排除）→ IPC
`cyril-drag-start`，主进程按 `screen.getCursorScreenPoint()` 每 16ms 算增量
`win.setPosition()`，`pointerup / pointercancel / blur` 发 `cyril-drag-end`。
用屏幕光标轮询而不是 DOM 的 mousemove：鼠标拖出窗口后页面收不到事件。
顶栏空白处双击 = 全屏 / 还原；日志里能看到 `drag start / drag end`。

**窗口态缩放（本轮修复「无法调整窗口大小」）**：两个原因叠加 ——
① 进全屏时 `setResizable(false)` 是必要的全屏锁，但透明窗口偶尔不发
`leave-full-screen`，锁就再没解开；② `frame:false` 没有系统边框，而四周 28px
原本全被判成「拖动区」，拖边缘只会把窗口挪走。现在：
`applyWindowedBounds()` 幂等地 `setResizable(true) + setMinimumSize(940,560) + setBounds`，
退出全屏的 IPC 路径立刻解锁，并有一个 320ms 兜底（事件来了就取消，避免
move/resize 风暴）；`preload.js` 把左 / 右 / 下边 9px、底部两角 26px 优先判为缩放
（角区放宽是为了让落点落在 34px 圆角的圆内，不被 `clip-path` 裁掉），
`cyril-resize-start{dir} / end` → 主进程按屏幕光标增量 `setBounds`，
夹在 `minWidth/minHeight` 内；顶栏整条仍是拖动区，全屏期间手柄与拖动都关闭。

**「最大化」= 真正全屏**：普通 `win.maximize()` 只占工作区，Windows 任务栏照样露着。
借鉴 Mineradio `toggleFullscreen()`：先 `setBounds(display.bounds)` 铺满整块屏幕
（含任务栏区域）→ `setResizable(false)` → `setFullScreen(true)`；退出由权威的
`leave-full-screen` 事件还原工作区内的居中窗口。初始窗口也按
`workArea` 算尺寸（旧版写死 1440×900，在 1280×800 屏上被裁成整屏，成了「假全屏」，
任务栏盖在上面、`isFullScreen()` 还乱报）。
主进程自己维护 `fullscreenActive`，因为透明窗口可能已盖满屏幕而 `isFullScreen()` 仍 false。
`sendWindowState()` 下发两条通道：`cyril-window-state`（只管按钮图标）与
`cyril-fullscreen-state`（只管摘圆角 / 关缩放手柄），互不串扰。

- `electron/loading.html`：启动页自带同一套圆角两态 / 边缘缩放 / 拖动、自绘标题栏和
  三枚窗口按钮（最小化 / 全屏 / 关闭），中央放 `cyril-splash.png`（1200×400 透明 APNG）。
- `electron/preload.js`：给后端提供的 `http://127.0.0.1:<port>/` 页面注入壳样式与
  自绘标题栏，`contextBridge` 暴露 `window.__cyrilDesktop`（minimize / toggleMaximize /
  close / isMaximized / isFullscreen / onWindowState / onFullscreenState /
  dragStart / dragEnd / resizeStart / resizeEnd）。
- 只改壳内文件时无需重打 600MB 整包：用 `@electron/asar` 从 `electron/` 重新生成
  `app.asar`，覆盖 `electron-dist\...\resources\app.asar` 即可（重启窗口生效）。

> 改完要**重启** Electron 版窗口才会加载新的 `app.asar`；Web 版（`dist\WebTerm.exe`）
> 是浏览器窗口，不带这套无边框壳。

## 局域网访问（--remote）防火墙说明

`--remote` 会监听 `0.0.0.0`；若局域网设备无法访问，最常见原因是 **Windows 防火墙拦截了新打包的 exe 入站**（源码跑 `python.exe` 时可能已放行过，打包成 `WebTerm.exe` 是全新程序，默认被拦）。

启动 `--remote` 时程序会自动尝试放行防火墙（需管理员权限）；非管理员时终端会打印手动放行指引，任选其一：

- 以**管理员**身份运行一次 `WebTerm.exe --remote`（自动放行）；
- 或 控制面板 → Windows Defender 防火墙 → 高级设置 → 入站规则 → 新建规则：
  - 规则类型选「程序」→ 指向 `WebTerm.exe` → 允许连接（专用+公用）；
- 或管理员 PowerShell 执行：
  ```powershell
  netsh advfirewall firewall add rule name="WebTerm Remote 8765" dir=in action=allow program="<仓库目录>\dist\WebTerm.exe" profile=any
  ```

仍无法访问时检查：①手机/设备与本机同一局域网网段 ②路由器未开 AP 隔离 ③本机未开 VPN 虚拟网卡。

## 登录与用户管理

分两种身份，判据是**连接来源**，不是密码：

| 来源 | 是否需要登录 | 权限 |
|---|---|---|
| 本机回环地址（`127.0.0.1` / `localhost` / `::1`） | 不用，直接就是管理员 | 能用终端 + 管理所有账号 |
| 局域网（`--remote` 后用本机 IP 访问） | 必须登录 | 只能用终端，**看不到【用户】按钮** |

- **远程没有注册入口**：账号只能由本机管理员在【用户】面板里开。
  面板里可以**新增 / 删除 / 改名 / 改密码**，初始密码由管理员设。
- **口令存储**：`users.json`（exe 同目录）只存 `PBKDF2-HMAC-SHA256`（20 万轮 + 16 字节随机盐）的散列，
  校验用 `hmac.compare_digest` 常数时间比较，明文口令既不落盘也不进日志。文件权限收紧到 0600。
- **会话**：登录成功发一枚 `cyril_sid` 随机令牌（`secrets.token_urlsafe(32)`），
  Cookie 打 `HttpOnly; SameSite=Strict`，有效期 7 天，只存内存（重启服务端需重新登录）。
  **改密码 / 改名 / 删人会把该用户的旧会话立刻踢掉**，正在用的窗口下一次请求就失效。
- **WebSocket 握手阶段就鉴权**：没登录直接 `401`，连终端都连不上，不存在"页面进去了终端没进"的中间态。
- **登录限速**：同一 IP 连续失败 6 次锁 10 分钟（成功即清零），失败还会加 250ms 延时拖慢字典尝试。
- **CSRF 兜底**：所有写操作校验 `Origin` 与 `Host` 同源，跨站 POST 一律 403。
- 不加 `--remote` 时只监听 `127.0.0.1`，局域网根本连不上，登录页也就不会出现。

> 说明：`--remote` 的账号体系是「局域网内谁能进终端」，不是互联网级多租户。
> 要在公网暴露，请自己在前面套 HTTPS 反向代理 / 内网穿透，别直接把 0.0.0.0 映射出去。

## 内置 AI（女朋友人设 + 终端助手）

本地 llama.cpp（llama-server）驱动，无需联网。模型放 `llama/models/`（GGUF，如 `MiniCPM5-1B-heretic-Q4_K_M.gguf`），启动时自动加载。

- **人设**：AI 扮演你的女朋友，称呼你「宝宝」，语气温柔俏皮；
- **终端指导**：问它命令怎么做，会给出可直接粘贴执行的 PowerShell 命令（``` 代码块），回复中点击「在终端输入」把命令打进终端；
- **感知你输入的命令**：你在终端里敲的命令会被 AI 记住（自动拼装回车确认），它知道你在做什么、可主动点评，你问它也能答上来；
- **正在听的歌**：播放器切歌时自动上报，AI 会自然地聊你正在听的歌；
- **主动聊天**：打开页面打招呼、切歌点评、每 10 分钟主动找你说话；
- **AI 打字**：支持把 AI 回复的命令/文本直接写入 PowerShell 会话，你回车后执行。

网页左上角「AI」按钮打开 AI 面板；无模型/未就绪时面板会显示原因。

## 终端指令：音乐 / AI（在终端里直接控制）

不用切到电台面板，在终端里敲就行：

| 命令 | 短别名 | 作用 |
|---|---|---|
| `Cyril-Start` | `play` | 播放 / 继续播放（并取消待定的停止） |
| `Cyril-Stop` | `stop` | 立即停止 |
| `Cyril-Stop -3s` | `stop -3s` | 3 秒后停止 |
| `Cyril-Stop -3min` | `stop -3min` | 3 分钟后停止（也认 `-0.5h` / `-90`） |
| `Cyril-Continue` | `cont` / `resume` | 继续播放当前曲目（接着进度，不从头） |
| `Cyril-Ai <问题>` | `ai <问题>` | 在终端里问 AI，回复直接打印在这里 |
| `Cyril-Music` | `music` | 打开命令帮助窗口 |
| `Cyril-Help` | `chelp` | 同上（等同顶栏【帮助】） |
| `Cyril-Img <路径>` | `img <路径>` | 在窗口里预览图片（png/jpg/gif/webp/bmp） |
| `Cyril-Theme [dark\|light]` | `theme` | 黑白主题切换（不带参数就来回切） |
| `Cyril-Bg [数值]` | `bg -40` | 背景明暗 -100…0…+100（不带参数显示当前值） |
| `Cyril-Tree` | `ctree` | 右侧文件目录树开 / 关 |
| `Cyril-Import` | `cimport` | 打开导入面板（选文件 / 选整个文件夹） |
| `Cyril-About` | `about` | 版本信息 |

`stop` 之后 `play` 会接着放（记住停在第几首）；`stop -3s` 等待期间再敲 `play` 就取消这次延时。
`Cyril-Continue` 在 `stop` 之后同样能用，而且**接着停下的秒数**放：`stopCurrent()` 会
`removeAttribute('src')` + `load()` 把解码器放掉，音频元素变成「空元素」，这时只调
`audio.play()` 只会被 reject（错误还被 `.catch` 吞掉）—— 表现就是终端打印「继续播放」、
控制台却停在 `0:00 / 0:00`。所以 `stopCurrent()` 在清 src 之前先把「曲目 key + 播放秒数」
记进 `lastStop`，`resume` 发现没有 src 就按 key 找回来重新挂载、`loadedmetadata` 后
seek 回原进度（见 `playAt(idx, startAt)`）。
完整列表也能在【帮助】面板里点一行直接打进命令行（只输入不回车，方便改参数）。

### 为什么主命令都带 `Cyril-` 前缀

因为 `start` 是 PowerShell 里 `Start-Process` 的 **ReadOnly + AllScope** 别名：
删不掉（`Remove-Item` 报成功但实际还在），也改不了（不带 `-Option AllScope` 的 `Set-Alias`
会抛「无法从别名 start 中删除 AllScope 选项」）。硬占就得破坏 `start notepad` 这种正常用法，
不值当。所以主命令统一加 `Cyril-` 前缀，**绝不占用任何现有命令名**。

`stop` / `play` / `ai` / `music` / `img` / `theme` / `bg` / `about` 这些短别名是安全的
（PowerShell 里没有同名别名或内置命令）。`start` 故意没做别名。

`continue` 和 `help` **不能**做别名：前者是 PowerShell 关键字（`Set-Alias continue …`
直接把解析器搞挂），后者被 `Get-Help` 占用。所以「继续播放」的短名是
`cont` / `resume`，「帮助」的短名是 `chelp`，全名仍是 `Cyril-Continue` / `Cyril-Help`。

### 实现方式（为什么不是前端拦按键）

前端拦 Enter 就得「擦掉用户已经敲进去的那行、再自己重画提示符」，而 ConPTY 那条线的光标
在哪前端根本不知道，必然抢光标（错位 / 多空行 / 提示符被吃掉）。所以改成：

1. 启动会话时用 `-EncodedCommand` 预置 4 个函数，函数唯一的作用是打印一行标记
   `__CYRIL__:名字 参数`；
2. 前端 `feedTerm()` 截获这行，执行真正的动作（停止 / 定时 / 提问），再把这一行原地换成结果。

回显、换行、提示符全由会话自己产生，↑ 调历史、Tab 补全、Ctrl+C 都照常可用。

**三个踩过的坑**（动这块之前先看）：

- **必须 `-EncodedCommand`，不能 `-Command "脚本"`**：pywinpty 的 `spawn` 不按 Windows 参数
  规则处理引号，外层引号会被原样保留，于是整个脚本被当成一个字符串字面量求值输出 ——
  屏幕上打出脚本原文、函数一个都没定义；而且 `$args` 在传参阶段就被吃掉了（实测脚本里的
  `($args -join ' ')` 变成 `( -join ' ')`）。
- **输出必须单行且不超过终端宽度**：这行是绕过 PTY 直接写进缓冲区的，会话只当多了一行；
  一旦写多行或长到折行，它后面的输出就从「标记行 + 1」开始盖，把刚打的内容逐行覆盖掉。
  所以帮助压成一行，AI 回复也把换行换成空格并按 `term.cols` 截断（完整回复在 AI 面板里看）。
- **不能用 `ESC[1A` 上移**：标记前面的片段已经把光标推到标记行，再上移一行正好落到命令回显
  那一行，会把用户敲的命令擦掉。只在当前行内 `CR` + 清行就够（标记行本来就是空的）。

另外 `play` / `stop` / `ai` / `music` 这些指令**不进** AI 的「终端操作上下文」——
否则模型会把 `Cyril-Stop -0.1s` 当成歌名去点歌（实测如此）。

## 启动横幅与光标位置（改这块之前务必先读）

**横幅是 PowerShell 自己打印的，不是服务端发的。** 这一条是硬约束，别再改回去。

ConPTY 内部维护一份自己的 VT 屏幕。服务端绕过 PTY 往终端塞横幅时，ConPTY 的屏幕里
没有这些行 —— 它始终认为光标在「自己的第 1 行」。之后 PSReadLine 重绘输入框发出
`ESC[1;1H`，ConPTY 翻译成「屏幕顶行」，用户敲的字就画到字模上（截图里压在 CYRIL
上的那个 `ls`）。xterm.js 社区把这类问题叫 **Coordinate Lying**：前端注入了 PTY
不知道的行，任何绝对定位（CUP）都错；微软 terminal 仓库也有同源 issue（#20009）。

所以现在 `build_ps_cmd(rows)` 把横幅 base64 塞进启动命令，用 `[Console]::Write`
打到 stdout —— 横幅成了 shell 的第一段输出，ConPTY 完全知情，屏幕/光标同源。

配套的四条：

1. **尺寸**：`spawn` 的 `dimensions` 必须是真实行列。先按 80×24 起来再 `setwinsize`，
   ConPTY 会重排已经画好的横幅（拆行、并字、留半截字符）。
2. **起 shell 的时机**：等浏览器报出真实尺寸（resize 静默 180ms，兜底 2.2s）。
3. **前端 `sendResize` 有个老 bug**：xterm 并没有公开的 `term.dimensions`，旧代码
   `if (dims && dims.rows…)` 永远不成立 —— PTY 一直停在 80×24，浏览器里却是 47×172。
   现在直接读 `term.rows / term.cols`，并且遇到 `24×80`（xterm 的默认值，说明字体还没
   量完）会重试最多 12 次再报，避免把 PTY 起成 80 列。
4. **不要自己应答 `ESC[6n`**：xterm.js 本来就会用真实光标位置应答。服务端再答一遍，
   PSReadLine 会收到两份，晚到的那份常和用户下一帧输入粘在一起被吞掉首字符
   （`img` → `mg`、`Cyril-help` → `yril-help`）。所以服务端的 CursorTracker 已删除。

矮窗口（`rows < 32`）自动改用 4 行精简横幅：整块字模有十几行，窗口一矮就把缓冲区顶到
滚动，滚动 + 重绘会留下半截字符。

Linux 版同理，只是横幅改在 `build_term_rc(rows)` 里用 `printf` 输出（rc 由
`bash --rcfile` 在启动时执行，属于 shell 自己的 stdout）。

## 导入音乐：文件 / 整个文件夹

顶栏【电台导入】打开导入面板，两个入口：

- **选择文件**：可多选，同名 `.lrc` 一并导入；
- **选择文件夹**：`<input webkitdirectory directory multiple>`，浏览器把整棵目录树的
  文件一次交过来（含子目录），里面的 `.lrc` 也会按文件名匹配到同名曲目。

两条路都走同一个 `importFileList()`。已导入过的曲目按「文件名 + 大小 + 修改时间」判重跳过。

## 终端里看图（Cyril-img）

`img D:\pic\a.png`（也支持 `~` 与相对路径）→ 弹出独立的【图片】窗口。

**为什么不做「把图片嵌进终端输出流」**：终端内容由 PTY 决定，PowerShell 根本不知道图片
占几行，ConPTY 每次重绘都按自己的行列来，图片立刻被后续输出压掉。所以走
「后端读文件 → base64 → 前端 data URL」的独立窗口，尺寸/格式信息在标题栏
（宽高由文件头手写解析：PNG/GIF/BMP/WebP/JPEG，读不出来就只显示大小）。
单张上限 8 MB。

## 界面：浮层窗口（十一个，统一标准）

外观 / 队列 / 歌词 / 均衡器 / AI / 帮助 / 导入 / 图片 / **用户** / **组件** / **设置** 十一个浮层是
**独立的可拖动窗口**，行为完全一致：

- 都是 `body` 直接子级 + `class="float-panel"`，`position:fixed`；
- **按住标题栏可拖动**（Pointer Events，鼠标和触屏都行）；
- **刚打开时自动避让**：新窗口若与已开窗口重叠，就自动挪到旁边
  （右侧排满就往左、窄屏改向下错层），所以连开五个也不会互相盖住；
- 拖过任意一个窗口后，它的位置归你，不再被自动排布挪动；
- 每个窗口右上角都有 **✕** 关闭按钮；
- 「词 / EQ / ☰」三个按钮有**开合两态**：窗口打开时按钮变实心高亮，关闭时恢复描边；
- 十个浮层共用同一套玻璃材质（半透明 + 背景模糊 + 白色内高光 + 20px 圆角），**顶栏也是这同一套**；
- 底部状态条（`Win WebTerm — 基于…`）已删除，终端占满整个窗口高度；
- 电台模式下底部音乐控制条收窄为 `min(660px, 58vw)` 并居中（不再横跨整屏）；
- 顶栏与音乐控制条平时淡出，**鼠标贴到屏幕上/下沿才浮现**（详见下下节）；
- 控制条的材质是 **Mineradio 原版同款的「全透 + 光线折射」**（不是毛玻璃，详见下节）。

新增窗口照这个模子加：DOM 放到 `body` 下、带 `float-panel`，开的时候调一次
`placePanel(el)`，再在第二段 IIFE 末尾加一行 `makeDraggable(el, el-head)`。

> 【外观】以前是挂在顶栏按钮下的**下拉框**（`position:absolute`），下拉框没法拖，
> 还会被 `header` 的 `z-index:2` 夹在自己的层叠上下文里，所以整块搬到了 `body` 层。
> 顺带把「点页面其他地方自动收起」去掉了 —— 浮层是常驻窗口，点终端不该把它关掉，
> 收起只走顶栏【外观】或标题栏 ✕。

## 顶栏 + 音乐控制条：玻璃材质 & 靠近才显现

顶栏不再是"一条 62% 白 / 55% 黑的实底带"，而是**和 EQ / 歌词面板同一套玻璃**：
`rgba(0,0,0,.05)` + `blur(12px) saturate(1.8) brightness(1.16)` + 白色内高光 + 20px 圆角，
四周留 10px 缝做成悬浮胶囊（移动端 6px）。材质直接复用那条共享玻璃选择器——`header`
已经加进去了，别再抄一遍数值。

`--head-bg` 这个变量已经删干净了（`:root` / `body.light` / `body.on-dark` 三处定义）。
留着它的下场是：只要有人再写一句 `background: var(--head-bg)`，实底就会把玻璃感抹掉。

几何（改动前先量）：`margin: 6px 10px 0` + `padding: 7px 14px` → 高度 38、底边 **44**；
浮层统一 `top: 46px`，正好留 2px 缝。

### 两条栏平时是藏起来的

鼠标贴到屏幕上沿才浮出顶栏，贴到屏幕下沿才浮出音乐控制条，各自独立（`body.head-on` /
`body.ctrl-on`）。判据只有指针 y 坐标：

- 顶栏热区 = `y <= 顶栏底边 + 10`；
- 控制条热区 = `y >= innerHeight - max(120, min(170, 控制条高度 + 40))`
  （收起成一条时用 120 保底，否则热区会缩到贴着屏幕底边，很难点）；
- `:hover` 在这里没用：两条栏隐藏时都是 `pointer-events:none`，压根收不到 hover。

**为什么不改成 `position:fixed`**：脱流后终端会顶到 `y=0`，右上文件树的标题栏会被
悬空的顶栏压住，点不到关闭按钮；而且每次显隐都会触发终端重新 fit。所以只改
`opacity` + `translateY(±6px)`，**位置仍占着**（顶栏隐藏时那 44px 是背景色，终端本身
也是透明的，视觉上没有断层）。

几个必须保留的细节：

- **CSS 默认是"常显"**，脚本接管后才给 `body` 挂 `.bars-ready` 改成按指针显隐。
  反过来的话，JS 一挂，整排按钮就再也点不到了。
- 隐藏态必须 `pointer-events:none`，否则透明的胶囊照样吃掉终端底部的点击与文字选择。
- 控制条隐藏态的 `transform` 要写全 `translateX(-50%) translateY(6px)`，
  漏掉前半截会跳到屏幕右半边（居中全靠它）。
- **首屏 / 进电台时 pe 一次**：页面加载 1.5s、进电台 1.6s 内强制露出，否则用户不知道
  按钮在哪。键盘 Tab 走到顶栏按钮（`:focus-visible`）也锁 5s。
  只认 `:focus-visible`——鼠标点完按钮同样触发 `focusin`，那时锁 5s 就是"移开鼠标还不消失"。
- 在控制条里按住拖进度/音量时上锁（`rcLock`），松手 800ms 后才允许淡出，
  否则拖到一半指针偏出热区、控制条当场消失、拖动中断。

> 踩过的坑：`peek(0)` 原本写成 `Date.now() + (ms || 1400)`，0 是假值 → 反而锁 1400ms，
> 调试时"归零"永远不生效。改成 `ms == null ? 1400 : ms`。

## 控制台玻璃：中间全透 + 边缘光线折射

原版 Mineradio 那条控制条**不是毛玻璃**。它中间是真的全透，只有四周一圈在「掰弯光线」，
掰弯时 RGB 三个通道幅度不同，于是边缘散出彩虹。实现全在一个 SVG 位移滤镜里
（`web/index.html` 的 `#cyril-glass-filter`，直接照搬原版 `#mineradio-control-glass-filter`）：

1. **位移贴图**（`feImage`，由 JS 按控制条实际宽高 + 圆角程序化生成，见 `cyrilGlassDisplacementMap()`）：
   黑底 → 铺「透明→红」水平渐变 → 叠「透明→蓝」垂直渐变（`mix-blend-mode:difference`）→
   中间盖一块内缩的 **50% 灰**圆角矩形并 `blur(11px)`。
   灰 = 0.5 表示位移量 0，所以**正中间一点都不动，背景原样透出来**；被 blur 晕开的那一圈
   边界才有非零位移，于是只有外圈在折射。
2. **色散**：`feDisplacementMap` 对背景跑三遍，`scale` 分别 **110 / 104 / 98**，
   每一遍再 `feOffset` 水平错开 **55px**，取 R/G/B 单通道后用 `screen` 混回来 —— 这就是彩虹边。
   （原版是 180/170/160 + 错开 90px，那套数值按它 1080×92 的宽条调；我们的条是 660×126，
   同样数值会糊成一片，所以整体收小约四成。想再弱一点就继续等比降这两个数。）
3. **引用方式**：`backdrop-filter: url(#cyril-glass-filter) saturate(1)`，
   **不带 blur**。原来那层 `blur(16px)` 正是「中间发白半透」的元凶。
   填充只留 `rgba(0,0,0,0.10)`（原版同值），边框 0，圆角 44px（移动端 34px），
   白色内高光用 `inset` 阴影堆出来。

几个坑：

- 滤镜 SVG 容器必须 `width:0;height:0;overflow:hidden`，**不能 `display:none`** —— 否则
  `url(#…)` 引用失效，`backdrop-filter` 直接无效（表现成「只有 10% 黑」的裸透明）。
- 贴图尺寸必须跟着盒子走（圆角也要），否则折射圈会和胶囊边缘错位；所以挂了
  `ResizeObserver`，折叠/展开、改窗口宽度都会自动重生成。面板 `display:none` 时量不到
  尺寸，等它变 `flex` 会自动再触发。
- Safari（非 Chrome 内核）/ Firefox 不支持 `backdrop-filter` 引用 SVG 滤镜，命中时退回
  `blur(12px) saturate(1.8) brightness(1.16)`（即原版的降级方案）。

## 外观面板：主题 / 背景明暗 / 背景源

顶栏现在是 `外观` `音乐` `电台导入` `文件` `帮助` `组件` `用户` `AI` 八个按钮（原来并排的
`壁纸` `Mineradio` `绘光` 都收进了【外观】浮层）。顶栏本身是**半透明 + 毛玻璃**
（`--head-bg` 变量，黑 `rgba(20,20,20,0.55)` / 白 `rgba(245,245,244,0.62)`，
配 `backdrop-filter: blur(14px) saturate(1.5)`），壁纸和音域回响能透上来一点。
**注意**：`backdrop-filter` 会让 `header` 成为 `position:fixed` 后代的包含块 ——
所以浮层必须留在 `body` 直接子级，别塞进 `header`，否则拖不出顶栏。

【音乐】等于一键直达：【音域回响】背景 + 播放器 + 顺便把队列面板打开，和自己点两次一样。

【外观】浮层四行：

| 行 | 控件 | 说明 |
|---|---|---|
| 主题 | 黑 / 白 | `body.light` 切换，终端配色跟着变 |
| **背景** | 滑块 −100 ~ +100 | 原来的「白罩」。中间 0 = 背景原貌，**往左压黑、往右提白** |
| **背景源** | 默认壁纸 / **音域回响** / 绘光 | 三选一，互斥，当前生效的实心高亮 |
| 自定义 | 图片/视频 ▸ · 清除 | 本地图片或视频做背景；清除回纯终端 |

「背景」是同一层 `#bgOverlay` 双向生效：`v>0 → rgba(255,255,255,v)`、`v<0 → rgba(0,0,0,|v|)`。
壁纸 / 视频 / 音域回响 / 绘光都罩得住，只改背景明暗，不碰终端文字。
轨道做成黑→灰→白渐变，一眼能看出两边分别是什么。

背景源：

| 按钮 | 做了什么 |
|---|---|
| 默认壁纸 | 退出电台，铺内置壁纸（黑主题 `wallpaper_dark.jpg` / 白主题 `wallpaper.jpg`） |
| 音域回响 | 进电台并切到**音域回响**背景（星河 + 地形，Mineradio 预设 7 移植） |
| 绘光 | 进电台并切到**绘光**背景（舞台光束 + 烟雾 + 浮尘） |

三者互斥，当前生效的那个按钮为实心高亮。**点已选中的那个不会有反应**（不是开关），
要回纯终端就点【清除】。

> 为什么【默认壁纸】必须先退出电台：音域/绘光是铺满全屏的 WebGL 画布（`z-index:0`），
> 会整块盖住 `body` 的背景图。反过来进电台也会让终端变透明去透出画布。

### 亮底配色：`body:is(.light, .on-light)`

黑主题的浮层文字是浅青系（`#d6f8ff` / `#bfe6d8` …）。浅底下同一块玻璃变成浅底，
浅青字几乎看不见。所以把这些颜色统一抽成 CSS 变量，在 `body.light` **或 `body.on-light`**
里整体换成墨黑/深青（`on-light` = 黑主题压在亮壁纸/提白滑块上，见下一节）：

| 变量 | 黑主题 | 白主题 | 用在哪 |
|---|---|---|---|
| `--cy-text` | `#d6f8ff` | `#0b2621` | 浮层标题、文件名、队列标题 |
| `--cy-label` | `#7fae9e` | `#3a6157` | 次要标签（`主题` `背景` `背景源`） |
| `--cy-body` | `#bfe6d8` | `#17332c` | 列表正文 |
| `--cy-lyric` | `#8fb3a4` | `#24453c` | 歌词未唱行 |
| `--cy-dim` | `#5f7f74` | `#5a7d73` | 序号、大小、空状态 |
| `--cy-hi` | `#fff0b8` | `#6b4e00` | 当前行 / 选中项 |
| `--cy-accent` | `#9cffdf` | `#06695a` | 青色强调、悬停 |
| `--cy-soft` | `#cfe9e2` | `#0e2b25` | 外观面板按钮字 |
| `--cy-ai` / `--cy-ai2` | `#e8f6f2` / `#b8d8ce` | `#0e2320` / `#1b3a33` | AI 回复 / AI 设置正文 |
| `--cy-hint` / `--cy-mute` | `#6f9a8c` / `#7f8c86` | `#4a7365` / `#63776f` | 提示小字 / 关闭态按钮 |

所有写 `body.light` 的规则一律写成 `body:is(.light, .on-light)`，否则新增的 `on-light`
态会漏掉整套深字覆盖。两个例外：亮底的次级文字（顶栏副标题 / `.ctrl-label` 的 `#888`
只有约 3:1，12px 小字不够读）单独换 `var(--cy-dim)`；用户面板里的行内按钮不显式给
`color` 会落到浏览器默认 `buttonface`（浅灰底 + 纯黑字），也改成走 `var(--cy-text)`。
以后新增浮层：**文字一律用 `var(--cy-*)`，不要再写字面量**。
（深色背景时这套变量会被 `body.on-dark` 再整体翻回浅色系，见下一节。）

### 明暗三态：`on-dark` / `on-light` / 都不挂

规则只有一条：**文字要跟着它压着的那块背景走，而不是跟着主题走。**
顶栏 / 浮层 / 控制条都是 5% 黑玻璃 + blur，压什么就几乎是什么颜色的磨砂，
所以「主题」不再是判据，「背景亮度」才是。三个状态互斥：

| 状态 | 触发 | 玻璃上的字 |
|---|---|---|
| `body.on-dark` | **白主题** + 音域回响 / 绘光 / 暗壁纸 / 滑块 ≤ −20 | 整套浅色（连 `--Win-fg` / `--Win-green` 一起翻，旧 `.btn` / `.title` 也压在玻璃上） |
| `body.on-light` | **黑主题** + 亮壁纸 / 滑块 ≥ +20 | 整套墨黑深青（复用白主题那套 `body:is(.light, .on-light)` 规则） |
| 都不挂 | 黑主题暗底、白主题亮底 | 沿用主题本身的配色 |

判据是背景采样亮度 `< 0.38` 算暗、`> 0.62` 算亮；音域/绘光是纯黑，无条件算暗；
没有自定义背景时 `_bgLuma = null`。±20 是「人眼已经能判明底色」的临界点，滑块越过即翻。

```js
function syncOnDarkLight() {          // 只挂/摘两个类，绝不调用 applyTheme（会递归）
  var dark  = scene || (_bgLuma !== null && _bgLuma < 0.38);
  var light = !scene && (_bgLuma !== null && _bgLuma > 0.62);
  if (wv >= 20)      { dark = false; light = true; }
  else if (wv <= -20){ dark = true;  light = false; }
  cl.toggle('on-dark',  dark  && isWhite);   // 白主题 + 暗背景
  cl.toggle('on-light', light && !isWhite);  // 黑主题 + 亮背景
  return changed;                            // 变了才让外层重设终端配色
}
function updateOnDark() { if (syncOnDarkLight()) applyTheme(themeName); }
```

- **终端配色也按背景翻**：`applyTheme()` 里 `light + on-dark` 用 `THEMES.dark` 做底，
  `dark + on-light` 用 `THEMES.light` 做底，否则墨黑字落纯黑星空 / 浅字落亮壁纸都等于看不见；
- `applyTheme()` 开头自己调一次 `syncOnDarkLight()`：切主题时背景没变，但「该用浅字还是深字」
  的结论可能反转，两个类必须跟着主题走。只改类、不回头调 `applyTheme`，避免递归；
- **亮度从哪来**：图片背景缩到 16×16 画到 canvas 算相对亮度
  （`0.2126R+0.7152G+0.0722B`）；视频背景在 `loadeddata` 后取一帧同法采样。

**重算的调用点（这轮真实踩过的坑）**：`updateOnDark()` 只在切主题、换壁纸/视频、
清背景、拖「背景明暗」滑块时被调用。而 `sonic-on` / `lumiere-on` 这两个类是在**第二段 IIFE**
的 `applyBackground()` 里挂的，第一段感知不到 —— 曾出现「点了音域回响，背景全黑，
字却还是白主题的墨黑」。所以 `applyBackground()` 末尾必须显式调一次
`window.__cyrilUpdateOnDark()`；退出电台走 `__cyrilApplyTheme()`（内部会重算），
另留了 `__cyrilUpdateOnDark` 兜底。**以后凡是新增会改变背景明暗的入口，都要补这一句。**

登录页是整屏深色 `radial-gradient` 遮罩，固定浅字，不参与三态翻转。

## 可选组件：左侧实时电平 / 系统占用

顶栏【组件】里两个开关，互相独立，都默认关（开关记进 `Settings.json` 的 `components`）：

| 组件 | 内容 |
|---|---|
| 实时电平 | 左侧竖排两条 dBFS 柱（L / R）+ 峰值保持线 + 顶部过载灯 |
| 系统占用 | CPU / 内存 / 显卡三行，各带一条占用条 |

- **电平表**不是猜的：`initBassAnalyzer` 里从总输出（`fxOut`）再分一路 `ChannelSplitter` 给两个
  `AnalyserNode`（`fftSize 2048`），每帧取 `getFloatTimeDomainData` 算峰值 —— 主分析器是混成单声道的，
  量不出左右。绘制按 dBFS 线性化：−60dB 到底、0dB 到顶，62% 以上转黄、96% 以上转红；
  顶到 0dB 点亮过载灯并保持 1.5s。峰值 1.2s 不落，之后缓慢回落，瞬时过载不会被帧率吃掉。
- **系统占用**由后端采样，前端只摆数字（不猜平台）：Windows 走 `ctypes` 调 `GetSystemTimes`
  两次差分算 CPU、`GlobalMemoryStatusEx` 取内存；显卡走 `nvidia-smi --query-gpu=…`
  （**3 秒缓存**，别把进程创建放进 1.5s 轮询里）——没 N 卡时该行显示「无 N 卡 / 未检测到」。
  接口是 `GET /api/sysinfo` → `{cpu, mem:{total,used,percent}, gpu:{name,util,mem_used,mem_total}|null}`。
- 左栏是 `position:fixed`，开着的组件会给 `#terminal` 加 `margin-left:56px`（**用 margin 不用 padding**：
  `main` 留缝会露出背景明暗的底色），并调一次 `__cyrilRefit()` 让 xterm 重新 fit、把新行列报给 PTY。
- 浅色底 / 深色底两套配色跟着 `on-dark` 走（左侧小盒子的底色与文字色都在里面）。

## 混响 + 环绕立体声（均衡器面板下半段）

音频链路（`initBassAnalyzer`，顺序不能改）：

```
src → Width(M/S) → Balance → 10× peaking EQ → 环绕 Surround ─┬→ 干声 ──────────┐
                                                            └→ 预延迟 → 卷积混响 → 低通阻尼 → 湿声 ─┴→ fxOut → analyser → destination
```

六个滑块（`Settings.json` 的 `fx` 段）：

| 滑块 | 范围 | 换算 |
|---|---|---|
| 混响 | 0–100 | 湿声量 × 湿声增益 × 0.9（留 10% 余量，叠加干声不削顶） |
| 阻尼 | 0–100 | 低通 `18000 × 0.067^(v/100)` Hz：0 → 18kHz（几乎不切），100 → 约 1.2kHz（闷） |
| 房间 | 0–100 | 脉冲响应长度 `0.4 + 2.6×v/100` 秒（衣柜 0.4s → 大厅 3.0s） |
| 湿声 | 0–200 | 湿声增益（显示为 0.00–2.00） |
| 预延迟 | 0–120 | ms，拉开干湿距离感 |
| 环绕 | 0–100 | 交叉馈送串音 `0.55×v/100`，延时 `8 + 20×v/100` ms（左右差 13%，避免中间挖洞） |

几个实现要点：

- **IR 是现场生成的**，不加载任何音频文件：噪声 × 指数衰减 `(1-t)^2.6` + 几颗稀疏早期反射脉冲。
  `room` 变化才重新生成（`_fxIR` 按整数值缓存），拖滑块时不会每秒重建几十次 buffer。
- **湿声默认 0**：等于纯干声，混响链常驻但不出声，开滑块立即听到。
- **参数写入要看上下文状态**：页面加载时 `AudioContext` 是 `suspended`（Chrome 等用户手势），
  这时 `currentTime` 不前进，`setTargetAtTime` 的斜坡永远走不完 —— 所以 `_fxSet()` 在
  `state !== 'running'` 时直接赋 `value`，否则「默认湿声 0」会实际停在构造值 1（一开声就是满混响）。
- **环绕 ≠ 上游的 Width**：Width 是中侧（M/S）加宽，只改左右差值；环绕是 Haas 交叉馈送
  （左右各延迟 8–28ms 灌进对侧），做的是「声音从两侧包过来」。两者可叠加。
- 参数落盘只在滑块的 `change`（松手）时做一次，拖动过程只改节点 —— 否则每帧都在写 `Settings.json`。
- 想确认节点真的接上了：控制台执行 `__cyrilFxState()`，会回 `{ctx, ir, irDur, wet, damp, pre, cross, surDelay, meter}`。

## 统一配置：Settings.json

以前配置是散的：主题 / 背景明暗 / 树宽 / 树开合躺在浏览器 `localStorage`，
AI 配置单独一个 `ai-config.json`。换浏览器、换机器、删 exe 重下就全丢。
现在**全部收进程序同目录的 `Settings.json`**（源码运行时就是 `server.py` 旁边，
打包后是 `WebTerm.exe` 旁边），**启动时读一次**。

```json
{
  "version": 1,
  "theme": "dark",            // dark | light
  "bg_level": 0,              // 背景明暗 -100..100
  "bg_kind": "",              // '' | custom | video | builtin-light | builtin-dark
  "bg_source": "builtin",     // builtin | sonic | lumiere
  "tree_width": 280,
  "tree_open": false,
  "panels": {                 // 每个浮层最后停在哪
    "appearancePanel": { "x": 962, "y": 176 },
    "radio-lyrics": { "x": 100, "y": 60 }
  },
  "ai": { "enabled": true, "base_url": "…", "api_key": "…", "model": "…", "temperature": 1.0 },
  "components": { "audio": false, "sys": false },   // 左侧电平 / 系统占用
  "fx": { "wet": 0, "damp": 45, "room": 35, "gain": 100, "pre": 20, "sur": 0 },  // 混响 / 环绕
  "port": 8765,                // 监听端口，重启生效（命令行 --port 优先）
  "remote": false,             // 局域网访问，重启生效（命令行 --remote 优先）
  "link_bars": true,           // 鼠标停在顶栏时联动显示其它面板
  "cursor":  { "hide": false, "delay": 3 },            // 自动隐藏鼠标指针 / 静止秒数
  "terminal": {                                        // 终端字体，立即生效
    "font_family": "Consolas, …", "font_size": 15, "line_height": 1.0
  }
}
```

`port` / `remote` 是**启动期配置**：HTTP server 已经 bind 了，改完只能重启。
Web 版 exe、Electron 壳的 `resources\WebTerm.exe` 和 Electron 主进程 `readSvcCfg()`
读的都是各自同目录的 `Settings.json`，所以三个入口结果一致。

> 账号不在 `Settings.json` 里：局域网账号单独存 `users.json`（同目录，只放口令散列，见「登录与用户管理」）。
> 两个文件都是运行时才创建的，删掉 `Settings.json` = 恢复默认外观，删掉 `users.json` = 清空所有局域网账号。

协议就两条帧，前端连上后先要、改动再写：

| 方向 | 帧 | 说明 |
|---|---|---|
| 前端 → 后端 | `{"type":"settings_get"}` | 连上后（等一拍让 auth 先处理）取整包 |
| 后端 → 前端 | `{"type":"settings_resp","data":{…}}` | `data` 是整包；**文件还不存在时回 `{}`** |
| 前端 → 后端 | `{"type":"settings_set","patch":{…}}` | 只发改动的那几个键 |
| 后端 → 前端 | `{"type":"settings_saved","ok":true}` | 写失败时 `ok:false`，前端提示检查目录权限 |

几个坑，改这块之前先看：

- **`settings_get` 在没有配置文件时必须回空对象**。若回默认值，前端会拿
  `tree_open:false` 把用户刚打开的树关掉、把 `theme` 打回 dark。后端用 `_settings_exist`
  区分「文件真的存在过」和「刚装还没写过」。
- **合并写入**：顶层键直接覆盖，`panels` / `ai` 这类 dict 按子键合并 ——
  只拖动一个浮层，不能把其他浮层的位置冲掉。
- **落盘时机去抖**：滑块用 `change` 而不是 `input`（拖动一次会触发几十次）；
  树宽拖动在松手（`pointerup`）才写最终宽度；浮层拖动同理。
- **`cfgSend()` 不能直接引用 `ws`**：它在初始化 `applyTheme()` 那一刻就会被调用，
  而 `ws` 在后面才 `let` 声明，直接引用会撞上暂时性死区（TDZ）并打断整个 `applyTheme`。
  统一走 `window.__cyrilSock` 这个桥。
- **背景图片/视频的二进制不进 JSON**（几十 MB 转 base64 会拖慢启动），仍在浏览器
  `IndexedDB`；`Settings.json` 只记 `bg_kind` 这种「当前用什么」的元信息。
  所以换台机器能恢复主题 / 明暗 / 树 / 浮层位置，但**恢复不了自定义图片和视频**。

## 【设置】面板：端口 / 光标 / 终端字体

顶栏【设置】打开第十一个浮层（和其余浮层同一套 `float-panel` + `placePanel()` +
`makeDraggable()`），三组控件，**所有文字色走 `var(--cy-*)`**，白主题 / 深背景三态自动适配：

| 组 | 控件 | 生效时机 | 配置键 |
| --- | --- | --- | --- |
| 服务 · 开放端口 | 监听端口（1–65535）、「局域网访问」开关 | **重启程序** | `port` / `remote` |
| 交互 | 「顶栏联动」开关、「隐藏光标」开关、静止时间（1–30s） | 立即 | `link_bars` / `cursor` |
| 终端字体 | 6 个等宽预设 + 自定义字体串、字号 10–26、行高 1.0–1.6 | 立即 | `terminal` |

- 端口 / `remote` 只是配置：HTTP server 已经 `bind`，改完必须重启 Web 版 exe 或 Electron 壳。
  面板上单独显示「当前端口」（`location.port`），保存时提示「重启后生效」。
- 自动隐藏的是**鼠标指针**：`pointermove / pointerdown / pointerup / wheel / keydown /
  focusin` 任一活动都重置计时器，静止 N 秒后给 `body` 挂 `.cursor-idle`
  （`body.cursor-idle * { cursor:none !important }`），失焦立即恢复。xterm 的文字光标不受影响，
  拖浮层时 `pointermove` 持续触发也不会中途藏掉指针。
- 终端字体由第一段的 `window.__cyrilSetTermFont()` 逐键写 `term.options`
  （整体展开会把构造期选项 `cols` 带进去报错），延后 90ms 再 `fit.fit()` +
  `__cyrilRefit()` 重算行列 —— 立刻 fit 会拿旧字体度量算错 PTY 列数。
  预设只给等宽字体：可变字体（Cascadia Code 等）会让 xterm 字符宽度测量错位。
- 后端配置回包由 `cfgApply()` 调 `window.__cyrilApplySettings()` 套到控件；**只赋值、
  不派发 `change`**，避免 `cyrilCfgApplying=true` 期间把同样的值回写一遍。

## 右侧文件目录树

顶栏【文件】按钮开合；面板停靠在工作区右端，**靠改自身宽度挤占终端**（不是浮层盖住终端）。

- 左边缘可拖动改宽度（160–640px），宽度存 `Settings.json` 的 `tree_width`；
- 目录点一下展开/收起，**每次只请求一层**（展开子目录才发下一次请求）；
- 点文件名 = 把它的路径直接打进当前命令行（不回车），带空格的路径自动加引号；
- `↑` 上级、`⟳` 刷新、`✕` 关闭；
- 顶栏路径显示的是服务器解析后的绝对路径。

为什么让树「挤占」而不是浮在上面：终端背后是真 PTY，行列数由容器像素换算。
浮层盖住右边的列时 PTY 仍按原尺寸输出，右侧内容被压在浮层底下还找不到；
挤占会让 fit 重算 `cols`，PowerShell 的宽度跟着变，输出自然换行。

**协议**：前端发 `{"type":"fs_list","id":N,"path":"D:\\xxx"}`（`path` 为空 = 服务器 cwd），
后端回 `{"type":"fs_list_resp","id":N,"ok":true,"path":"…","parent":"…","entries":[{"n","p","d","s"}],"truncated":false}`。
只读：不递归、不执行任何东西；单层最多返回 1500 项。

## 左右边缘感应：左侧组件 / 右侧文件目录

和顶栏、音乐控制栏同一套手感：**鼠标靠近窗口边缘才浮现，移开自动淡出**。

- **左边缘（x ≤ 68px）**：显示左侧组件栏（实时电平 / CPU·内存·显卡占用）。
  一个组件都没勾选时改显示「⊞ 组件」小胶囊，点它直接打开【组件】设置面板。
  这里只改 `opacity/transform`，**不动 `#terminal` 的 `margin-left`** —— 一改就触发 fit，
  PTY 列数会跟着抖。脚本没接管时不挂 `.edge-ready`，组件栏保持常显（JS 挂掉也不会看不见）。
- **右边缘（x ≥ 视口宽 − 26px）**：停留约 170ms 后展开文件目录；鼠标移进树里就一直保持，
  移出树区域约 240ms 后收起。展开用 `.tree-peek`，和手动点【文件】的 `.tree-on`
  共用同一条宽度规则（仍是挤占终端，不是浮层），展开/收起都会 refit。
  `.tree-peek` **不写 localStorage、不回写 Settings.json**：鼠标扫一下不会被记成
  「用户固定打开了文件树」，刷新页面状态干净。
- 手动点【文件】/ `✕` 之后有 900ms 冷却，避免鼠标还停在右上角按钮上时刚关掉的树又被弹开；
  手动固定打开时 hover 逻辑完全不干预。

**「任意窗口边缘」是统一触发区**：距窗口四周 28px 内（`nearAnyEdge()`）时，
顶栏、音乐控制栏、左组件栏、右文件目录**一起**浮现，不再各管各的边 —— 无边框窗口
没有系统边界，找不到隐藏控件时扫一下任意一条边就够了。右边缘展开仍留 170ms 防误触；
顶 / 底 / 左边缘到边即同步展开并清掉旧定时器（避免 timer 状态残留导致上边缘不展开）；
鼠标回到窗口中间 240ms 后收起。音乐控制栏只在电台模式（`radio-on`）下才显示。
窗口四周 28px 边缘同时也是拖动区；其中左 / 右 / 下边最外 9px 与底部两角 26px
优先作为**缩放手柄**（见「无边框窗口」一节），所以拖这里改的是尺寸、不是位置。

**顶栏联动**：鼠标停在顶栏任意位置（不限于 28px 边缘）时，左组件栏 / 右文件目录 /
音乐控制栏也一起显出 —— `barsInit()` 里 `linked = nearAnyEdge() || (linkBars && onHeader())`，
`onHeader()` 用的是缓存的 `hBottom + 10` 而不是实时读布局。首屏 `peek(1500)` 只露顶栏，
不会一进页面就把文件树挤出来。这个联动可以在【设置】里关掉（`link_bars`），关掉后
顶栏只管自己，回到「只有窗口边缘才联动」；开关走 `window.__cyrilSetLinkBars()`。

状态自检：控制台 `__cyrilEdgeState()` 返回 `{rail, peek, pinned, treeW, px}`。
Linux 版没有组件栏、顶栏常显，因此边缘热区只用来唤出文件目录（同一套 `.tree-peek` 机制）。

## 电台背景：音域回响 ⇄ 绘光（两态）

> 星点太淡/太粗可以现场调（控制台执行，不重启）：
> `CyrilSonic.setStarPixelScale(2.5)`（默认 1，只做 DPR 归一化）、
> `CyrilSonic.setStarBloomStrength(1.2)`（原版面板范围 0 ~ 1.6，默认 0.9）。

底部控制条右侧的背景按钮显示**当前**背景名，点一下换另一种：

| 按钮 | 背景 | 来源 |
|---|---|---|
| 音域 | 星河星空 + 音乐驱动的发光地形 | **Mineradio 原版预设 7「音域回响」（移植 Ajin）整件搬来**，THREE.js |
| 绘光 | 舞台光束 + 烟雾 + 浮尘 | Folia Lumiere 移植 |

顶栏那种「绘光一键入口」已并入【外观】的背景三选一（见上一节）。

> **旧版那个独立「银河」背景已删除。** 它原来是另一套 Canvas 2D 手写的大光斑星带，
> 每帧全屏重绘、只画星不带地形，跟原版 Mineradio 的关系其实只是「观感对齐」。
> 现在换成原版真正的那片星河（下面第一节），于是背景只剩两态。

## 音域里的星河：原版预设 5 的星尘层

原版 Mineradio 的「星河」不是单独一个背景，它就是主粒子系统里 `lane >= 0.80` 的那 20% 深空星尘
（另外 80% 是预设 5 的极光带，属于「壁纸粒子 · 音乐律动」那块主视觉，压在地形下面会抢画面，没搬）。
这一层现在整段搬进 `sonic-host.js`，**跟地形同处一个 THREE 场景、共用同一台相机**，
所以景深、透视、视差天然一致，切窗口也不会两层错位。

位置 / 颜色 / 眨眼 / 尺寸四段全部照抄原版公式，常数一个没改：

| 量 | 原版公式 |
|---|---|
| 横向漂移 | `fract(u + t*(0.0014 + seed*0.0048) + seed*0.63)` |
| 团簇 | `snoise(vec3(seed*2, q*3.2, t*0.007)) * 3.4` —— 3D simplex，整段照搬 |
| 纵深 | `mix(-32, 18, seed)`，前前后后铺满 |
| 眨眼 | `pow(0.5 + 0.5*sin(t*(0.24 + seed*0.42) + rand*17), 5)` |
| 明暗筛 | `smoothstep(0.22, 0.98, hash(rand*661 + floor(q*160)))` |
| 颜色 | `mix(封面色, rgb(0.92,0.97,1.0), 0.62 + twinkle*0.14)` —— 冷白，带一点封面主色 |
| 点尺寸 | `clamp(36 / -viewZ * (1.05 + 音频驱动), 1.00, 5.45) * DPR` |

几个容易踩的点，已经处理：

- **网格照原版**：183×183 里只取 `lane >= 0.80` 的 37 行，正好 **6771** 颗星，不是随手捏的随机数；
- **`gl_PointSize` 是设备像素**：窗口缩放 / 自动降质都要重设 `uPixel`，否则星点会忽大忽小；
- **位置在 shader 里生成**，THREE 算不出包围球，必须手写 `boundingSphere` 并关掉 `frustumCulled`，
  不然整层会被当成零半径几何裁掉（表现就是「代码没报错但天上没星」）；
- **亮度压到 0.82**：原版在音域预设下就把这层压到 0.82（`uParticleDim`），否则星空会盖过地形；
- **主体层 + 辉光层两层**（原版 `particles` / `bloomParticles` 的做法）：同一个几何画两遍，
  主体层 1~5px 实点、开深度测试；辉光层点尺寸 ×2.65、`tex.a²` 软边、`AdditiveBlending`、
  **关掉深度测试**，所以连被地形挡住的星点也会透出一点光。只有主体层的话，6771 颗星
  实测只点亮 0.26% 的像素、平均亮度 0.05，肉眼基本看不见；
- **星点尺寸按 DPR 归一**：原版 `gl_PointSize` 是设备像素，在 DPR=2 的屏幕上每颗星占 2×2 个
  设备像素，光斑贴图能露出亮核；DPR=1 时整张 64×64 光斑被压进 1 个像素，alpha 只剩平均值
  （约 0.25），星点就成灰雾。`sonic-host.js` 把每颗星的设备像素数统一归到 DPR=2 那一档
  （`STAR_TARGET_DPR`），高 DPR 屏幕不额外放大、低 DPR 屏幕补足。实测这条补上之后，
  天空区亮于 0.16 的像素占比从 0.047% 回到 0.21%（原版参考图同一区域约 0.25%）；
- **`depthTest` 与绘制次序**：主体层 `renderOrder = 1`、辉光层 `2`，都排在地形之后 ——
  这样地形圆盘外的星点亮、圆盘上的星点只剩辉光，跟原版一致。

音域回响这一层是**原版实现**，不是重画：

- `three.r128.min.js` / `sonic-audio-monitor.js` / `sonic-topography-preset.js` 三个文件从
  Mineradio 原样拷贝，**一行未改**（含文件头原始署名，GPL-3.0）；
- `sonic-host.js` 只补它需要的三样外部输入：THREE 场景与相机、频段帧、封面色板；
- **相机**用原版预设 7 的基线：半径 8.4 / 仰角 0.18 / 方位 0，lookAt 原点、FOV 45；
- **参数**全走原版默认档（密度 46、幅度 50、范围 82、纵深 62、自转 50、辉光 68、悬浮块 80）；
- **音频**走原版那条细粒度路径：额外挂一个 `fftSize 1024` 的分析器（512 段，与原版监视器 `BASE_BINS=512`
  完全一致），由它算八段频段 + 真 kick 包络。没用粗粒度兜底——那样地形起伏和涟漪触发会跟原版对不上；
- **配色**跟着封面走：按原版 `lyricGroundPaletteFromHsl` 的规则从封面主色算出
  高饱和主色 / 副色 / 强调色，地形冷核暖核随之变色；封面是黑白或没封面时退回原版的银蓝兜底色。

只搬了 Ajin 那一版（预设 7，three.js 重写）。CmzYa 的壁纸引擎原版（预设 8）是整套 iframe 工程，
没有一起搬。

性能：默认与原版同档（`high`，像素预算 5.2M / DPR 上限 1.35）。若连续两秒掉到 38fps 以下，
会自动退到 `eco` 档（网格 156→112、像素预算 2.4M），只降一档，不来回抖。

## AI：自定义 API（可选）

默认用本地 llama.cpp，也可以在「AI 面板 → ⚙ 设置」里换成任意 **OpenAI 兼容** 接口
（DeepSeek / 通义 / Moonshot / OpenAI / 自建 one-api 都行）：

- 填「接口地址 + API Key + 模型名」，点保存立即生效，无需重启；
- 地址只填到域名或 `/v1` 也能用，程序会自动补成 `/v1/chat/completions`；
- 配置写进程序目录的 `Settings.json` 的 `ai` 段（**API Key 为明文，注意不要外传**）；
- 老版本的 `ai-config.json` 会在首次启动时**自动迁移**进 `Settings.json`，之后只认后者；
- 不想用云端就把开关关掉，会退回本地模型。

## AI：操控歌单 + 认识你的歌

- **会点歌**：直接说「放首晴天」「下一首」「声音小一点」「单曲循环」，AI 会输出一个
  ```player 指令块，前端解析后真的去操作播放器（播放/暂停/切歌/音量/进度/循环/随机/开面板），
  并在气泡下方显示执行回执（♪ 播放《晴天》）。
- **控制类操作有本地兜底**：内置 1B 小模型对「下一首 / 暂停 / 小声点 / 循环」这类指令遵循度不稳
  （有时只嘴上答应，甚至把「下一首」当成点歌）。因此切歌、暂停、音量、循环、开面板这些
  **在发送时由前端本地立即执行**，并让模型这一轮不要重复动作；只有「点歌」交给模型解析歌名，
  模型没动手时再由本地兜底。**想让它更聪明，建议在设置里换成云端大模型**（见上节）。
- **认识曲库**：前端会把音乐库（歌名 + 歌手）同步给后端，AI 点歌时先查曲库，找不到会直说，不会编歌名。
  （曲库为空时它自然「没歌可放」，所以先导入音乐再让它点歌。）
- **看得懂歌词**：切歌时会把当前歌词一起上报，AI 能就歌词内容陪你聊；系统提示里只截取前 12 行，避免撑爆小模型的上下文。

## 说明与限制

- 会话为**真正的 PowerShell 交互**（ConPTY 伪终端）：提示符、Tab 补全、彩色输出、中文均正常；全屏 TUI 程序（如 `python` REPL、`vim`）也可运行。
- **光标定位**：ConPTY 会把子程序的光标查询（`ESC[6n`）透传给终端宿主。本服务内置一个精简 VT 光标追踪器，
  会按屏幕上的真实行列应答，因此 `msfconsole`、`vim`、交互式 Python 这类程序的提示符位置是对的。
  （设备属性查询 `ESC[c` 故意不应答——conhost 不解析该响应，会被当成按键打进提示符后面。）
- 可选令牌：设置环境变量 `WEBTERM_TOKEN=xxx` 后，访问需加 `?token=xxx`；不设置则免密直连（同 Kali 版）。
  这层是**单令牌**的轻量门，和上面的 `users.json` 账号体系是两回事：`--remote` 下真正拦人的是登录会话，
  令牌只适合「临时给朋友看一眼」这种场景。
- 端口可用环境变量 `WEBTERM_PORT` 覆盖。
