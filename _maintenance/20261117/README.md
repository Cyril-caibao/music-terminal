# 2026-11-17 维护记录

## 1. 清理历史版本

规则：每个位置保留「当前版 + 最新 1 份 `.prev`」，其余删除；删除前先算 SHA256 并落清单。

- 待删除：120 个文件，11.35 GB
- 清理前：17.18 GB / 23,412 文件
- 清理后：5.82 GB / 23,297 文件
- 清单：`delete-manifest.csv`（含 sha256 / bytes / mtime / group / path）
- 保留：
  - `dist\WebTerm.exe.prev7`（2026-11-16 14:10:57）
  - `electron-dist\WebTerm-Electron-win32-x64\resources\WebTerm.exe.prev11`（2026-11-16 15:28:56）
  - 注：这两份 `.prev` 后续已按用户要求删除，见第 7 节。
- 删除分组：
  - `electron-dist\_prev_archive` 20 个 / 4.59 GB
  - `dist\WebTerm.exe.prev*` 9 个 / 2.17 GB
  - `resources\WebTerm.exe.prev*` 10 个 / 2.42 GB
  - `installer\_prev` 6 个 / 1.85 GB
  - `electron-dist\_staging` 构建缓存 75 个 / 0.60 GB

## 2. P0 安全修复（server.py）

1. `Handler._ws_origin_ok()`：WebSocket 握手校验 `Origin` 与 `Host` 完全一致；
   - 有 Origin 且不匹配 → 403
   - `Origin: null` → 403
   - 无 Origin → 仅允许回环（本机 CLI）
2. `settings_public()`：`settings_get` 不再下发 `ai.api_key`（只给 `has_key`），本机/远程一致。
3. `settings_remote_filter()`：远程连接的 `settings_set` 丢弃 `ai` / `port` / `remote`，
   响应里带 `dropped` 列表；本机不受影响。
4. `ai_config_set`：远程连接直接拒绝（`AI 配置只允许本机修改`）。

回归测试（`test_p0.py`）：
- 伪造 Origin → 403
- 同源 Origin → 101
- 无 Origin（本机 CLI）→ 101
- `Origin: null` → 403
- `settings_public` → 无 api_key / has_key=true
- `settings_remote_filter` → 丢弃 ai/port/remote

真实 exe 冒烟（`smoke_exe.py`，端口 18123）：
- `/api/me` 正常
- 同源 WS 101，PowerShell 横幅与 `echo CYRIL-SMOKE` 正常
- 伪造 Origin 403
- `settings_public` 已脱敏

## 3. 构建环境

- Python：3.13.16（Doubao sandbox base `772984f6...`）
- PyInstaller：6.22.3
- pywinpty：3.1.2（ConPTY）
- UPX：5.3.0（`components\cyrilshield\vendor\tools\upx.exe`）
- Electron：45.2.3（离线 zip：`electron-tools\vendor\electron-v45.2.3-win32-x64.zip`）
- 打包：`@electron/packager` + `electron-winstaller`（Squirrel.Windows）

## 4. 产物

| 文件 | 大小 | SHA256 |
|---|---:|---|
| `dist\WebTerm.exe` | 189.5 MB | `575F165A3D110E96391FA91917BDF786EA6B08FFBA78D051D6BA3C4565BD815B` |
| `installer\WebTerm-Electron-Setup-1.0.4.exe` | 338.5 MB | `79F9D27331625964C5E9D55F67041CB61D02DE2CB33A74BBE27B96685D6DD768` |
| `installer\WebTerm-1.0.4-full.nupkg` | 337.7 MB | `B720672E328B5524E793BDAAAA435A2C87370876102284D013C717B6A45B19BF` |

校验和汇总：`_maintenance\20261117\SHA256SUMS-1.0.4.txt`。

## 5. 安装状态（2026-11-17）

- 已用 `WebTerm-Electron-Setup-1.0.4.exe --silent` 覆盖安装，退出码 0。
- 安装目录：`%LOCALAPPDATA%\WebTerm\app-1.0.4`。
- 注册表：`Cyril Web Terminal`，版本 `1.0.4`。
- 安装后 `app-1.0.4\resources\WebTerm.exe` 的 SHA256 与 `dist\WebTerm.exe` 一致。
- 安装版单独配置端口 `19001`（`app-1.0.4\resources\Settings.json`），避免复用 8765 上的 UPX 测试后端。
- 启动冒烟：`http://127.0.0.1:19001/api/me` 返回 `{"ok": true, "authed": true, "user": "local", "admin": true, "local": true, "remote_mode": false}`，PASS。
- `installer` 目录保留 1.0.3 作为回滚；`RELEASES` 同时列出 1.0.3 与 1.0.4。

## 6. 注意事项

- 当前仍在运行的 Electron 便携实例（`electron-dist\WebTerm-Electron-win32-x64`）仍用旧 `resources\WebTerm.exe`；
  便携版已不再维护，热替换用的 `resources\WebTerm.exe.new` 已随便携版清理删除。
  建议关闭旧实例，改用已安装的 1.0.4。
- 安装包未签名，SmartScreen 可能提示；这是原有发布方式。
- Electron 官方不支持真正的单文件免安装运行；便携版方案已取消并清理，只保留
  `Setup-1.0.4.exe` 单文件安装包。

## 7. 便携版清理（2026-11-17）

用户确认便携版与安装包定位重复，不再保留便携版，已删除：

- 单文件便携 EXE：`installer\WebTerm-Portable-1.0.4-Single.exe`，311.8 MB
- 7z 便携包：`_build\20261117\WebTerm-Portable-1.0.4.7z`，309.8 MB
- 便携缓存：`%LOCALAPPDATA%\WebTerm-Portable`，550.1 MB
- 重复 Electron 构建：`electron-dist\_rebuild-20261117`，548.2 MB
- PyInstaller 缓存：`build`，246.0 MB
- CDP 测试配置：`_cdp13`，28.9 MB
- 便携版后端备份：`dist\WebTerm.exe.prev7`、`resources\WebTerm.exe.prev11`、`resources\WebTerm.exe.new`，合计 673.3 MB
- 清单：`portable-cleanup.txt`

保留：`electron-dist\WebTerm-Electron-win32-x64` 旧便携应用主体（仍在运行，未删除）。


