# Music-Terminal · 音乐与终端的完美结合

浏览器里的 Windows 终端，叠加音乐可视化与音频处理：Python 标准库后端（ConPTY 伪终端）+ xterm.js / three.js / Pixi 前端，可选打包成单文件 EXE，或套一层无边框 Electron 桌面壳。借鉴了 Mineradio 的音域回响与 Folia 的绘光界面，并支持 `--remote` 在局域网内远程管理。

> 完整实现文档见 [`Terminal-Windows-GitHub/README.md`](Terminal-Windows-GitHub/README.md)。

## 界面预览

| 初始界面 | 结合 Mineradio 音域回响 | Folia 绘光 |
| --- | --- | --- |
| ![初始界面](https://github.com/user-attachments/assets/5ab21f76-597c-4f0c-a3d6-9d54e5070200) | ![音域回响](https://github.com/user-attachments/assets/568342d1-2348-4fb6-803e-8d00ab5de129) | ![Folia 绘光](https://github.com/user-attachments/assets/474eb3c2-92a0-4246-9141-fab56b7803f1) |

| 灵动 UI 界面 | 专业音乐调节 |
| --- | --- |
| ![灵动 UI](https://github.com/user-attachments/assets/f483695c-eb79-49de-acda-0f5b2eaaa0a4) | ![专业音乐调节](https://github.com/user-attachments/assets/b8c91cbc-316c-4356-9ea8-523d0314bc73) |

## 它能做什么

- **真·PowerShell 会话**：基于 ConPTY（pywinpty），提示符、Tab 补全、中文、彩色输出、`vim` / `python` REPL 等全屏 TUI 都可用。
- **音乐可视化与音频处理**：内置壁纸、玻璃质感顶栏、电台 / 均衡器 / 混响 / 环绕立体声、实时音频电平与 CPU / 内存 / GPU 占用组件、右侧文件树、AI 助手面板。
- **两种外壳**：Web 版（`python server.py`）与 Electron 版（frameless + 透明、圆角、真全屏、自绘标题栏、Cyril 启动动画）。
- **登录与局域网**：本机回环访问默认免登录；`--remote` 时必须登录，账号只能由本机管理员在【用户】面板创建（`users.json`，PBKDF2 20 万轮）。

## 快速开始

需要 Windows 10/11 + Python 3.9+：

```bash
cd Terminal-Windows-GitHub
pip install -r requirements.txt
python server.py
# 浏览器自动打开 http://127.0.0.1:8765/
```

常用参数：

```bash
python server.py --port 9000          # 自定义端口
python server.py --remote             # 监听 0.0.0.0，局域网可访问（必须登录）
python server.py --no-browser         # 不自动弹浏览器（Electron 壳内部使用）
```

完整目录结构、打包单文件 EXE、Electron 桌面壳、安全说明请见 [`Terminal-Windows-GitHub/README.md`](Terminal-Windows-GitHub/README.md)。

## 许可证

本项目以 [MIT License](LICENSE) 发布。随项目分发的第三方库（xterm.js、three.js、PixiJS、pywinpty、Electron 等）各自遵循其原许可证。
