# WebTerm for Windows

浏览器里的 Windows PowerShell 终端：Python 标准库后端 + ConPTY 伪终端 + xterm.js 前端，
可选打包成单文件 EXE，或套一层无边框 Electron 桌面壳。

<!-- 有截图时再启用：![WebTerm](docs/screenshot.png) -->

> 仓库内当前不含截图文件，避免 README 出现破图；如需配图请自行放入 `docs/`。

## 它能做什么

- **真·PowerShell 会话**：ConPTY（pywinpty），提示符、Tab 补全、中文、彩色输出、
  `vim` / `python` REPL 等全屏 TUI 都可用；不是「把命令丢给 subprocess 再回显」。
- **单文件 Web 前端**：`web/index.html` + 本地化的 xterm.js / three.js / Pixi 打包产物，
  无外网 CDN 依赖；内置壁纸、玻璃质感顶栏、电台 / 均衡器 / 混响 / 环绕立体声、
  实时音频电平与 CPU / 内存 / GPU 占用组件、右侧文件树、AI 助手面板。
- **登录与局域网**：本机回环访问默认 `admin` 免登录；`--remote` 时必须登录，
  且没有注册入口，账号只能由本机管理员在【用户】面板创建（`users.json`，PBKDF2 20 万轮）。
  会话用 HttpOnly Cookie，WebSocket 握手即校验，写操作校验同源。
- **两种外壳**：Web 版（`dist/WebTerm.exe` 或 `python server.py`）与
  Electron 版（frameless + transparent，34px 圆角、真全屏、自绘标题栏、Cyril 启动动画）。
- **配置持久化**：全部写进程同目录的 `Settings.json`（主题、树宽、浮层位置、
  端口、局域网开关、顶栏联动、自动隐藏光标、终端字体等）。

## 目录结构

```
.
├── server.py                  # 后端：HTTP / WebSocket / ConPTY / 登录 / 配置 / 系统采样
├── WebTerm.spec                # PyInstaller 打包配置
├── requirements.txt           # 唯一第三方 Python 依赖：pywinpty
├── Settings.example.json      # 配置模板（复制为 Settings.json 后按需修改）
├── web/                       # 前端全部资源（含本地化的第三方库与壁纸）
├── electron/                  # 桌面壳：main.js / preload.js / loading.html / 启动动画
└── docs/
    ├── README-Windows.md      # 完整实现说明（架构、协议、踩坑、构建）
    └── PUBLISH.md             # 提交到 GitHub 的步骤与公开前检查清单
```

## 快速开始

需要 Windows 10/11 + Python 3.9+（后端本体是纯标准库，只有 PTY 依赖第三方包）：

```bash
pip install -r requirements.txt
python server.py
# 浏览器自动打开 http://127.0.0.1:8765/
```

常用参数：

```bash
python server.py --port 9000           # 自定义端口
python server.py --remote              # 监听 0.0.0.0，局域网可访问（必须登录）
python server.py --remote --port 9000
python server.py --no-browser          # 不自动弹浏览器（Electron 壳内部使用）
```

优先级：命令行 > `Settings.json` > 环境变量 / 默认值。`port` 与 `remote` 是启动期配置，
改完要重启进程。首次运行时把 `Settings.example.json` 复制为 `Settings.json`；
该文件与 `users.json` 已被 `.gitignore` 排除，不会进入仓库。

### 打包单文件 EXE

```bash
python -m PyInstaller --clean --noconfirm WebTerm.spec
# 产物 dist/WebTerm.exe，运行时同目录会生成 Settings.json / users.json
```

`WebTerm.spec` 会按当前解释器动态定位 `winpty`；便携环境可用环境变量
`WEBTERM_WINPTY_DIR` 指定。可选地把 llama.cpp 运行时放到 `llama/` 下才会打进包
（模型 GGUF 不打包，运行时从 `llama/models/` 读取）——该目录不随仓库分发。

### Electron 桌面壳

```bash
cd electron
npm install --save-dev electron@45.2.3 @electron/packager @electron/asar
npm start                 # 开发启动（会先拉起 dist/WebTerm.exe 后端）
npm run pack              # 生成 ../electron-dist/WebTerm-Electron-win32-x64/
```

壳只负责起后端、轮询 `/api/me`、加载 `http://127.0.0.1:<port>/`，
`resources/WebTerm.exe` 就是上一步的 PyInstaller 产物。只改壳文件时可只重打
`app.asar`，无需重建后端 EXE。

## 安全说明

- 局域网访问（`--remote`）会绑定 `0.0.0.0`，请自行确认防火墙范围；本机回环永远免登录，
  因此不要把服务直接暴露到公网。
- `users.json` 存的是 PBKDF2 口令散列，仍属敏感数据，**不要提交**。
- AI 面板的 `api_key` 只写本机 `Settings.json`，模板中留空。
- 仓库不含任何账号、面板位置、运行日志、构建产物或本机绝对路径。

## 第三方组件

`web/` 内随项目分发的 `xterm.min.js` / `addon-fit.min.js` / `three.r128.min.js`
以及 `folia-adapter.js`（PixiJS 相关打包产物）各自遵循原项目许可证；
后端依赖 `pywinpty`，桌面壳依赖 Electron。
**本仓库以 [MIT License](../../LICENSE) 发布**：根目录已包含 `LICENSE` 文件；随项目分发的第三方库
（xterm.js、three.js、PixiJS、pywinpty、Electron 等）各自遵循其原许可证，使用前请核对上述第三方库的许可条款。

## 与本地工作副本的差异

整理时只做了「可公开化」处理，未改动功能逻辑：

| 文件 | 处理 |
|---|---|
| `WebTerm.spec` | 移除写死在本机用户目录的 winpty 兜底路径，改为 `WEBTERM_WINPTY_DIR` 环境变量 |
| `electron/package.json` | `../electron-tools/...` 本地离线工具路径改为标准 `electron` / `electron-packager` 命令 |
| `web/folia-adapter.js` | 仅把 sourcemap `//#region` 注释中的本机绝对路径替换成 `vendor-src/` |
| `docs/README-Windows.md` | 防火墙示例里的绝对路径改为 `<仓库目录>` 占位 |

未纳入仓库：`dist/` `build/` `electron-dist/` `electron-tools/` `llama/`、
所有 EXE / asar / zip / 日志、`_backup/`、`_cdp2/`、临时探针 `_*`、
`web/index - 副本.html`、根目录重复的壁纸、以及属于 Kali 版的
`kali_terminal.py` / `check.sh` / `deploy.sh` / `test_client.py` / 旧 `README.md`。

## 提交

见 [`docs/PUBLISH.md`](docs/PUBLISH.md)。
