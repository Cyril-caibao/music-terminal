# 2026-11-19 维护记录

## 36. 第十一轮：修复无封面曲目无法更换封面（1.0.11）

- **根因**：`populateDetails()` 对无封面曲目给 `#dt-cover` 加 `nocover` 类，
  CSS 为 `display:none` 直接隐藏图片；而打开选图器的 click 只绑在这张
  `<img>` 上 → 没封面的歌永远点不到换图入口（提示文字「点击封面更换图片」
  一直显示，但点了没反应）。
- **修复**（`web/index.html`）：
  1. 新增 `#dt-cover-ph` 虚线「＋ 添加封面」占位框，`#dt-cover.nocover +
     #dt-cover-ph` 时显示（flex），深/浅色主题各一套样式（浅色用
     rgba(0,120,100,…) 系）；
  2. 点击事件从 `elDtCover` 改绑 `#dt-cover-row` 整行 —— 占位框 / 提示
     文字 / 封面图三处都触发 `#dt-cover-file` 选图；`#dt-cover-tip` 补
     cursor:pointer。
- **验证**：新探针 `_nocover_test.cjs` 12/12 —— nocover 态 img 隐藏且占位框
  可见可点（offsetWidth/Height > 0）/ 点占位框与点提示文字均触发选图（stub
  input.click 计数）/ DOM.setFileInputFiles 走真实 change 处理器后 nocover
  摘除、blob 预览显示、占位框消失、「新封面待保存」出现 / 保存后 IndexedDB
  写入 coverBlob / 有封面曲目点 img 本体回归正常 / 无 JS 异常。回归详情 22、
  队列 18 全过。截图 `_probe-nocover-ph.png`（注：长测试期间超级启动页会因
  空闲自动 show() 盖回最上层，属设计行为，截图前需再摘 super-on）。
- **版本**：1.0.11（PyInstaller + UPX，`--upx-dir components/cyrilshield/
  vendor/tools`）。

## 37. 本轮产物（1.0.11）

| 文件 | 大小 B | SHA256 |
| --- | --- | --- |
| `dist\WebTerm.exe` | 201,190,644 | `217e2fe0eb8a9d28db46280ae751a1acfe2dd7a154bd0b998610ee13413fe177` |
| `installer\WebTerm-Electron-Setup-1.0.11.exe` | 357,474,816 | `75839b20988c193cf5b43e181345a5ec9938fc29437ee56a11c7faae0ea16f90` |
| `installer\WebTerm-1.0.11-full.nupkg` | 356,638,777 | `f101824de1f01574ce1cf084597b4449d1b65b75a67a4f5852fc43660f3bead1` |

nupkg 内 `lib/net45/resources/WebTerm.exe` = `217e2fe0…`，与 dist 一致（已核对）。
三处同步：`dist` / `electron-dist\…\resources`（旧版备份
`WebTerm.exe.prev1.0.10`）/ `%LOCALAPPDATA%\WebTerm\app-1.0.6\resources`
（同样备份 `.prev1.0.10`）。
