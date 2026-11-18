# Kali Terminal

**基于 Kali 原版开源终端改造的桌面终端程序（GTK3 + VTE）**

Kali 默认终端（xfce4-terminal）底层就是开源终端组件 **VTE**。本程序直接用 VTE 构建 Kali 风格桌面终端——这是"真正的终端"，与 Kali 原版同源，彻底解决之前自绘 Tk 版的输入焦点问题和网页版的连接兼容问题。

---

## 一、为什么改用 VTE（本方案）

| 之前的问题 | VTE 方案 |
|-----------|---------|
| 无法输入文字（焦点问题） | ✅ VTE 原生处理键盘与 PTY，点击即输入 |
| vi / top / sudo 等交互程序支持有限 | ✅ 真终端，全交互程序完美支持 |
| 网页版 websockets 版本握手失败 | ✅ 纯桌面程序，不需要 websockets |

---

## 二、文件结构

```
D:\Terminal
├── kali_terminal.py   # ★ 桌面终端（VTE 版，主交付）
├── server.py          # ★ 网页版后端（纯标准库版，零依赖，已修复）
├── wallpaper.jpg      # 内置默认壁纸（桌面终端「壁纸」按钮使用）
├── web/               # 网页版前端（★ 需整个目录一起同步，含本地化资源）
│   ├── index.html     # 前端页面（xterm.js 已本地化，不再依赖外网 CDN）
│   ├── xterm.min.js   # 本地化终端核心库（283KB）
│   ├── xterm.min.css
│   ├── addon-fit.min.js
│   └── wallpaper.jpg  # 内置壁纸（网页版「壁纸」按钮使用）
├── check.sh           # 版本/依赖自检脚本（在 Kali 上运行）
├── deploy.sh          # 网页版一键启动脚本
├── README.md          # 本说明
├── requirements.txt   # 保留（网页版不再需要第三方依赖，可忽略）
└── test_client.py     # 网页版自测脚本
```

---

## 三、在 Kali 上运行（3 步）

### 1. 安装依赖（一次性，装依赖可用 sudo）
```bash
sudo apt update
sudo apt install -y python3-gi gir1.2-gtk-3.0 gir1.2-vte-2.91
# 可选：桌面端图片壁纸（Cairo 渲染）。视频壁纸桌面端已移除，
# 网页版视频壁纸由浏览器原生 <video> 支持，无需安装任何东西：
sudo apt install -y python3-cairo
```

### 2. 自检（确认文件/依赖到位）
```bash
cd ~/Desktop/Terminal
bash check.sh
```
全部显示 ✅ 再继续。

### 3. 运行（不要用 sudo 运行程序本身）
```bash
cd ~/Desktop/Terminal
python3 kali_terminal.py
```
打开即是一个 Kali 风格的终端窗口，直接输入命令使用（`ls`、`cd`、`sudo apt update`、`top`、`vi` 等都正常）。

> ⚠️ **运行程序不要加 sudo**。需要 root 权限时，在终端里正常敲 `sudo` 命令即可。

---

## 四、功能与快捷键

- ✅ **双主题**：黑色（Kali 默认深色）/ 白色（浅色）两套 16 色配色，**顶部按钮「黑」「白」**或右键菜单「主题」一键切换
- ✅ **自定义背景图**：顶部按钮「背景…」选本地图片替换终端背景（PNG/JPG/BMP）、「壁纸」用内置壁纸、「清除」恢复
- ✅ Kali 主题：16 色配色、深色背景、Kali 风格彩色提示符（红用户名@绿主机名:蓝目录）
- ✅ 真终端：完美支持所有交互程序（vi / top / htop / sudo / tmux 等）
- ✅ 右键菜单：复制 / 粘贴 / 清屏 / 主题 / 背景图
- ✅ 5000 行滚动回看、窗口自适应
- ✅ 使用系统默认 shell（bash/zsh）
- ✅ 窗口大小 / 位置自动记忆（移动或缩放后自动保存，下次启动恢复；最大化状态同样记住）

| 操作 | 效果 |
|------|------|
| 直接打字 | 输入命令，回车执行 |
| 鼠标中键/右键 | 粘贴 / 右键菜单复制粘贴 |
| 顶部「黑」「白」 | 黑色 / 白色界面切换 |
| 顶部「背景…」 | 用本地图片替换终端背景 |
| 顶部「壁纸」 | 一键使用内置壁纸（wallpaper.jpg） |
| 顶部「清除」/ 右键 → 清除背景 | 恢复默认背景色 |
| 右键 → 清屏 | 清空屏幕 |

---

## 五、后期迭代方向（待你提出）

1. **半透明磨砂质感界面**（类 Windows 窗口毛玻璃）——GTK3 原生支持背景透明，可在此基础上实现
2. **高颜值**：圆角、标签页、状态栏、自定义主题
3. **音乐可视化**：音频频谱/波形实时可视化

## 六、网页版（已可用，纯标准库零依赖）

新版 `server.py` **不依赖 websockets 库**，手写 RFC6455 WebSocket 协议（http.server + socket + pty），任何 Python 3.7+ 都能跑；前端 xterm.js **已本地化**（`web/` 目录自带，不依赖外网 CDN）。

```bash
cd ~/Desktop/Terminal
python3 server.py
```
浏览器打开 **http://127.0.0.1:8765/** 即出现 Kali 风格网页终端（页面大字标题 **Cyril**），正常使用。

**局域网远程访问（手机/其他设备）**：

```bash
python3 server.py --remote              # 开启远程：监听 0.0.0.0，启动时打印 LAN URL
python3 server.py --remote --port 9000  # 远程 + 自定义端口
python3 server.py --port 9000           # 仅自定义端口（本机访问）
```

- 默认 `python3 server.py` 只允许本机访问（127.0.0.1:8765）。
- 加 `--remote` 后局域网内设备用启动时打印的 **http://<本机局域网IP>:<端口>/** 访问（手机连同一 Wi-Fi 即可）。
- 若手机连不上，通常是防火墙拦截：放行对应端口即可（如 `sudo ufw allow 8765`，或图形界面防火墙放行）。
- 可选令牌保护：`export WEBTERM_TOKEN=你的密钥` 后启动，访问时需先认证（远程模式下强烈建议设置）。

网页版同样支持 **黑 / 白主题切换**（右上角「黑」「白」按钮，自动记住选择）、**终端背景图替换**（「背景…」选图、「壁纸」一键内置壁纸、「清除」恢复）。主题 / 壁纸 / 背景图 / 白罩均 **自动持久化**（主题与白罩存 localStorage，背景图 / 视频壁纸存 IndexedDB），刷新页面自动恢复上次设置。

> ⚠️ **同步注意**：`web/` 是整个目录（含 4 个文件）都要同步到 Kali，只同步 index.html 会导致 xterm.js 加载失败。
> ⚠️ 修改 `web/index.html` 后**无需重启** server.py，刷新页面即生效（页面每次请求实时读取）。
> 协议层已按 RFC6455 官方测试向量验证（握手密钥、掩码帧、半包/粘包、长短帧编码）；HTTP 层已验证（页面 + 本地静态资源 + 404）。完整链路（页面 + WebSocket + PTY 命令执行）需在 Kali/Linux 上运行验证。

## 七、自测

```bash
cd ~/Desktop/Terminal
bash check.sh        # 自检：文件版本 + 依赖 + 语法
python3 kali_terminal.py   # 桌面终端：正常打开窗口即可使用
python3 server.py          # 网页版：访问 http://127.0.0.1:8765/
```
