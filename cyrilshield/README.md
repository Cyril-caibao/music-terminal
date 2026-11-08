# CyrilShield · WebTerm 组件

在终端里直接给 EXE / APK 做保护，不用切窗口、不用另开一套界面。

| 能力 | 输入 | 产物 |
|---|---|---|
| 图标提取 | `.exe` `.dll` `.scr` `.cpl` `.ocx` `.sys` | 多尺寸 `.ico` / `.png` |
| UPX 压缩 | `.exe` | `<名>_upx.exe` |
| VM 虚拟壳 | `.exe` | `<名>_vm.exe`（自带解释器） |
| APK 加固 | `.apk` | `<名>_protected.apk`（重签名） |

## 安装

```powershell
# 源码运行：放到项目根的 components\ 下
Copy-Item -Recurse cyrilshield  D:\Terminal-Windows\components\

# 打包版：放到 exe 同目录的 components\ 下（Electron 版就是 resources\components\）
Copy-Item -Recurse cyrilshield  <WebTerm.exe 所在目录>\components\
```

整个文件夹一起拷（**`vendor\` 不能漏**），组件是**热插拔**的：文件夹放进去 → 重启 WebTerm → 【组件】面板点刷新 → 打开 CyrilShield。

## 依赖

**整个插件零外部依赖**：业务代码、UPX、APK 工具链、Python 运行时全部在本文件夹里，
整包拷走就能用，不需要另装 CyrilShield 项目、JDK、Android SDK、Python 或 Pillow。

| 项 | 位置 | 体积 |
|---|---|---|
| 业务代码（图标 / UPX / VM / APK 逻辑） | `vendor\cyrilshield\` | 240 KB |
| UPX 可执行文件 | `vendor\tools\upx.exe` | 0.6 MB |
| APK 工具链（精简 JRE + build-tools + android.jar） | `vendor\toolchain\` | 103 MB |
| 便携 Python + PyInstaller（VM 壳编译用） | `vendor\python\` | 64 MB |
| 上传暂存 / 构建中间产物 | `.work\`（自动创建、自动清理） | — |

| 功能 | 还需要什么 |
|---|---|
| 图标提取（`.ico` + `.png`） | 无（PNG 由内置 zlib 编码，不用 Pillow） |
| UPX 压缩 | 无 |
| VM 虚拟壳 | 无（用 `vendor\python` 里的 Python + PyInstaller） |
| APK 加固 | 无（用 `vendor\toolchain` 里的 JRE / d8 / apksigner / zipalign） |

`vendor\` 里缺少某一项时，对应功能会自动回退到系统里已装的那一份（本机 JDK / Android SDK /
Python），**不会让其它功能失效**；`.work` 不可写（插件放在只读位置）时才回退系统临时目录。

`bridge.py` 找业务代码的顺序（命中即用，不需要改任何配置）：

```
$CYRILSHIELD_HOME → 插件自身 vendor\ → 宿主目录下的 CyrilShield / PEShieldStudio
→ 宿主上一级目录 → D:\PEShieldStudio → D:\CyrilShield → %LOCALAPPDATA%\CyrilShield\app
```

工具链内部再按「插件 `vendor\toolchain\` → `_MEIPASS` → exe 同目录」找，插件永远优先。

## 后端接口

`bridge.py` 导出 `PREFIX = "/cs/"` 与 `handle_get` / `handle_post`，宿主把该前缀的请求转进来。**全部仅限本机回环**，局域网访问一律 403。

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/cs/env` | 项目位置、版本、工具链 / UPX / VM 后端状态 |
| GET | `/cs/job?id=` | 任务状态 + 增量日志 |
| GET | `/cs/get?job=&i=` | 取产物（只允许本任务产出的文件） |
| POST | `/cs/upload?name=` | 原始字节上传，返回 `fid` |
| POST | `/cs/run` | `{fid, tool, opts, outdir, outname}` 起任务 |
| POST | `/cs/open` | 资源管理器打开目录 |
| POST | `/cs/cancel` | 标记取消（不强杀子进程） |

## 说明

- 产物默认写 `桌面\CyrilShield-Output\`，面板里可改目录与文件名。
- 上传暂存与 VM 构建中间产物都在插件自己的 `.work\`（上传文件 6 小时自动清理），
  插件放在只读位置时才回退到系统临时目录。
- 加固会替换原签名，装新包前先卸载旧版本。
- 本组件只做自己程序的保护（加密、完整性校验、混淆），不含反调试 / 杀软对抗。
