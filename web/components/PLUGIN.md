# 组件（插件）规范

组件 = **一个文件夹**，丢进 `components\` 就出现，不用改程序、不用重启。
`components\` 与 exe 同目录（源码运行时是项目根目录）。

```
components\
  my-widget\
    manifest.json      必需：元信息
    index.js           必需（rail 型）：入口
    index.html         必需（panel 型）：独立页面
    style.css          可选
    ...                其它文件随便放（js/css/图片/字体）
```

> 组件 id **一律等于文件夹名**。`manifest.json` 里写 `id` 不参与寻址，
> 写错反而会让 `/comp/<id>/...` 对不上。

---

## 一、两种类型

| kind | 表现 | 适合 |
|---|---|---|
| `rail`（默认） | 挂在左侧组件栏，一个竖条小窗 | 电平表、时钟、便签、监控 |
| `panel` | 点「打开」弹独立窗口（iframe），可拖动/最大化 | 播放器、编辑器等大界面 |

```json
{
  "name": "音乐",
  "desc": "导入音源搜索、试听、下载",
  "version": "1.0.0",
  "author": "Cyril",
  "kind": "panel",
  "entry": "index.html",
  "panel_width": 960,
  "panel_height": 660,
  "icon": "♪"
}
```

### manifest 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 显示名（≤40 字） |
| `desc` | string | 一句话说明 |
| `version` | string | 版本号 |
| `author` | string | 作者 |
| `kind` | `"rail"` / `"panel"` | 缺省 `rail` |
| `entry` | string | panel 型入口页面，缺省 `index.html` |
| `panel_width` / `panel_height` | number | panel 型窗口初始尺寸，缺省 900×620 |
| `icon` | string | panel 型清单里的小图标（一个字符，如 `♪`） |
| `script` | string | rail 型入口，缺省 `index.js` |
| `style` | string | rail 型样式，缺省空 |
| `width` | number | rail 型宽度，缺省 44（>44 会自动把终端挤开） |
| `height` | number | rail 型固定高度，缺省自适应 |
| `backend` | string | 可选的宿主后端文件（相对组件目录，可进子目录）。缺省按 `bridge.py` → `bridge.pyw` → `bridge.pyc` → `bridge` → 同 stem 其它后缀查找 |

---

## 二、rail 型：`index.js`

```js
Cyril.component('my-widget', {          // 必须和文件夹名一致
  mount: function (root, api) {
    root.innerHTML = '<div class="t">hi</div>';
    api.every(function () {             // 定时器，卸载自动清
      root.querySelector('.t').textContent = new Date().toLocaleTimeString();
    }, 1000);
  },
  unmount: function () {
    // 可选：清理自己创建的资源
  }
});
```

### `api`

| 成员 | 说明 |
|---|---|
| `api.id` | 组件 id |
| `api.send(patch)` | 写配置，例：`api.send({ components: {...} })` |
| `api.open(panel)` | 打开内置浮层，如 `'comp'` / `'set'` |
| `api.every(fn, ms)` | 定时器，卸载时自动清理 |
| `api.clear()` | 立即清掉 `every` 注册的所有定时器 |
| `api.onUnmount(fn)` | 注册卸载回调（等价 `unmount`） |

- `root` 是一个 `div`，宽高由 manifest 的 `width/height` 决定。
- 组件脚本和样式**只加载一次**；`mount` 可能被多次调用（关掉再开）。
- 左栏整体是 `pointer-events:none` 的纯显示层，`.rail-comp` 自己会收回指针事件；
  收起状态下组件不挡终端点击。

---

## 三、panel 型：`index.html` + 你自己的页面

panel 型组件是一个**完整 HTML 页面**，跑在 `iframe` 里，等于一个独立小应用：

- 与宿主**同源**（同端口），可以直接 `fetch('/api/...')`、`fetch('/lx/...')`
- 与宿主 DOM 隔离，自己的 CSS 不会污染终端
- 想跟随宿主明暗：读 `parent.document.body.className` 是否含 `light`

```html
<!DOCTYPE html>
<html><head><meta charset="utf-8"><link rel="stylesheet" href="style.css"></head>
<body>
  <h1>Hello</h1>
  <script src="app.js"></script>
</body></html>
```

---

## 四、可用后端接口

组件里能直接调的（都要求本机回环访问）：

| 接口 | 用途 |
|---|---|
| `GET /api/me` | 当前登录态 |
| `GET /api/sysinfo` | CPU / 内存 / 显卡 |
| `GET /comp/<id>/<file>` | 取自己的静态文件（js/css/html/图片/字体/mp3/flac） |
| `POST /lx/proxy` | 通用 HTTP 转发（绕开 CORS 与防盗链） |
| `GET /lx/stream?u=<url>` | 音频/视频流代理，支持 Range（可拖动进度） |
| `POST /lx/download` | 下载并写音频标签 |
| `GET /lx/progress?tid=<id>` | 下载进度（配进度条用） |
| `GET /lx/sources` | 列出音源目录里的 `.js` 及 `@name/@version/@author` |
| `GET /lx/source?f=<file>` | 读某个音源源码 |
| `POST /lx/source` | 存音源 `{file, code}` |
| `POST /lx/source/delete` | 删音源 `{file}` |
| `GET /lx/config` | 读音乐插件配置（下载目录、歌词格式等，`_dir` 是实际落盘目录） |
| `POST /lx/config` | 改配置 `{patch}` |
| `POST /lx/open-dir` | 用资源管理器打开目录 `{which:"sources"\|"music", path?}` |

> `/lx/*` 全部要求**本机回环**，局域网访问一律 403。
> 不用音乐功能的话，这些接口完全不参与其它逻辑，可以忽略。


### `POST /lx/proxy`

```json
{ "url": "https://...", "method": "GET|POST",
  "headers": { "Referer": "..." },
  "body": "字符串或 base64", "bodyEnc": "utf8|base64", "timeout": 15 }
```

响应：`{ ok, status, headers, body }`；二进制内容回 `bodyB64`。

### `POST /lx/download`

```json
{ "url": "音频直链", "name": "歌名", "singer": "歌手", "album": "专辑",
  "ext": "mp3|flac|m4a", "cover": "封面直链", "lyric": "LRC 文本" }
```

后端会：下载音频 → 写 `TIT2/TPE1/TALB` 标签 → 按设置内嵌
歌词（mp3 `USLT` / flac `LYRICS` / m4a `©lyr`）与封面（`APIC` / `PICTURE` / `covr`）
→ 另存 `.lrc`（UTF-8/GBK 带 BOM）→ 落到下载目录。

响应：`{ ok, path, lrc, tagged, size, dir }`。

### `GET /lx/progress?tid=<id>`

下载进度。请求 `/lx/download` 时带一个自取的 `tid`，然后轮询这个接口就能做进度条：

```json
{ "ok": true, "phase": "fetch", "done": 2097152, "total": 9437184, "pct": 22 }
```

`phase` 依次是 `prep`（拿下地址/歌词）→ `fetch`（下音频，`pct` 有意义）
→ `tag`（写标签）→ `done`；失败为 `error` 并带 `error` 文案。
任务存在内存里，10 分钟后自动清掉。

---

## 五、参考实现：`lxmusic`

自带组件 `components\lxmusic\` 是一个 panel 型的完整例子，可直接抄结构：

| 文件 | 作用 |
|---|---|
| `manifest.json` | `kind:"panel"`、`entry:"index.html"`、窗口 960×660 |
| `index.html` / `style.css` | 界面（顶部搜索、列表、底部播放器、两个抽屉） |
| `app.js` | 状态、搜索、试听、下载、配置读写 |
| `sdk.js` | 5 个平台的搜索实现（esbuild 打成的 IIFE，暴露 `globalThis.LXSDK`） |
| `sandbox.html` / `sandbox.js` | 用隐藏 iframe 当沙箱跑用户音源（模拟 `globalThis.lx`） |

要点：**用户提供的音源是第三方 JS，不要在主页面里直接 `eval`**。
`lxmusic` 把它丢进 `sandbox.html` 这个空页里执行，主页面只通过
`postMessage` 要结果，音源出错也污染不到插件本身。

---

## 六、加载与调试

- 打开【组件】面板 → **刷新** 重新扫目录；rail 型勾选即生效，panel 型点「打开」
- `/comp/` 一律 `Cache-Control: no-store`，改完文件刷新页面就是最新
- 组件脚本报错不会拖垮终端：rail 型显示「组件出错」，panel 型是独立页面
- 新建组件：面板里点「＋ 新建组件」会生成一个能跑的时钟模板

## 七、边界与安全

- `/comp/<id>/<file>` 只放行白名单后缀（js/css/json/html/图片/字体/音视频），
  且校验 `normpath` 前缀，`../` 逃不出组件目录
- 新建 / 删除 / 打开目录**只允许本机回环**，局域网用户只能看清单和开关
- panel 型是同源 iframe，等于给了组件完整页面权限 —— 只装你信任的组件
- **后端权限更重**：组件目录里的后端文件（默认 `bridge.py`）会被宿主进程直接 import，顶层代码在加载阶段
  就执行，不经过任何登录态或回环校验（请求级鉴权是插件自己在 `handle_xxx` 里该做的事）。
  等同本机代码执行权限：谁能写 `components\` 目录，谁就能在宿主进程里跑代码；
  `--remote` 部署时尤其要收紧该目录权限
- 删除组件会删掉 `components\<id>\` 整个文件夹；随包自带的示例组件删不掉

---

## 八、带后端的插件：`bridge.py`

纯前端组件只能调已有接口。需要跑本机程序（编译器、签名工具、文件处理）时，
在组件目录里放一个后端文件（默认名 `bridge.py`，后缀不限），宿主会把它作为 **Python 模块**加载，
并把该前缀的请求转进去。

**前端 `index.html` 不需要、也无法加载后端文件**：`/comp/<id>/` 的静态后缀白名单里没有 `.py`，
后端代码只能通过宿主的 `importlib` 进入进程。两者只靠 HTTP 路径前缀（如 `/cs/`）约定相遇。

```
components\
  my-plugin\
    manifest.json
    index.html
    app.js
    bridge             ← 可选：后端（没有后缀也行）
```

**后端文件名不强制 `.py`**，按下面顺序查找，命中即用（路径必须落在组件目录内）：

| 序 | 位置 | 例子 |
|---|---|---|
| 1 | `manifest.json` 的 `backend` 字段（相对组件目录，可进子目录） | `"backend": "core/server"` |
| 2 | 约定名 | `bridge.py` → `bridge.pyw` → `bridge.pyc` → `bridge`（无后缀） |
| 3 | 同 stem 的其它后缀 | `bridge.txt`、`bridge.python`（`.py` 类排前，其余按名字排） |

导入时宿主显式指定 `SourceFileLoader`（`.pyc` 用 `SourcelessFileLoader`），
**后缀完全不参与判定**——落盘一个名叫 `bridge` 的无后缀文件照样加载。
同一目录里存在多个候选时按上表优先级取第一个，其余忽略。

宿主**不要求 `manifest.json`**：没有它，只要有约定名的后端文件就会加载。
`manifest.json` 里的 `backend` 只用于指定非约定文件名或子目录里的文件；
写了但指向不存在 / 越界的路径时，安静退回约定名查找。

但「被 import」不等于「能响应路由」：模块还得有非空的 `PREFIX`，且至少实现
`handle_get` / `handle_post` 之一，才会登记进路由表并打印「插件后端已加载」。
缺任一项时顶层代码照样执行，只是静默跳过、不打日志；import 抛异常也只在 stderr
记一行「插件后端加载失败」，不影响宿主和其它插件。同名 id 按目录优先级去重，
exe 同目录那份优先。

约定只有两条：模块级 `PREFIX`，以及 `handle_get(handler, path)` / `handle_post(handler, path)`。

```python
# components/my-plugin/bridge.py
PREFIX = "/cs/"

def handle_get(h, path):        # h 就是宿主 Handler，可用 _send_json / _read_json
    if path == "/cs/ping":
        h._send_json({"ok": True, "pong": 1})

def handle_post(h, path):
    if path == "/cs/run":
        data = h._read_json() or {}
        h._send_json({"ok": True})
```

| 事项 | 说明 |
|---|---|
| 加载时机 | **进程内首次有请求走到插件路由时**扫一次各组件目录的后端文件并 import；触发与路径是否命中前缀无关（见下方「加载链路」）。之后按 `(路径, 修改时间, 大小)` 签名比对，**文件变了会自动重载**，不必重启（见「热重载与生命周期」） |
| 后端文件 | 不强制 `.py`：`manifest.json` 的 `backend` > 约定名 `bridge.py`/`.pyw`/`.pyc`/`bridge` > 同 stem 其它后缀。加载用显式 `SourceFileLoader`，后缀不参与判断 |
| 重载粒度 | 只重载签名变化的那个插件，其它插件继续用已加载的模块；两次检查之间有 0.5 秒节流，改完最多半秒生效 |
| 失败重试 | 导入失败不会永久缓存：原因记进登记表，文件一改就重试；也可 `POST /api/plugins/reload` 强制重来 |
| 生命周期 | 组件被删除时立刻摘掉前缀路由；插件可选实现 `unload()`，宿主在卸载（删除 / 重载 / 强制重载）前调用一次 |
| 分发顺序 | GET：`/api/`、`/`、`/comp/` 等静态分支先返回，才轮到插件路由；POST：同源校验后**第一个**就是插件路由。命中后不再走 `/lx/`、`/api/`，不命中则继续往下 |
| 扫描范围 | `_comp_dirs()` = exe 同目录 `components\` 优先 + 随包 `web\components\`；按文件夹名（id）去重，同名只加载一次 |
| 异常处理 | 插件抛异常只影响本次请求（返回 500）；`sys.exit()` / `SystemExit` 会被拦截（顶层拦截后该插件不加载，处理函数里拦截后只让本次请求 500），**插件无法用退出码把宿主带走** |
| 契约校验 | `PREFIX` 必须非空且以 `/` 开头，否则不参与路由并在 `/api/plugins` 里给出原因；不写 `PREFIX` 或两个 handle 都没实现则标记为 `skip` |
| 前缀 | 建议用自己专属的一段（如 `/cs/`），不要抢 `/api/`、`/comp/`、`/lx/`；**多个插件不能同前缀**，`_plugin_route()` 按目录优先级遍历，先匹配的先响应并直接结束分发；冲突时后来者在登记表里记 `dup_prefix`，stderr 有告警，不静默 |
| 鉴权 | `do_POST` 已做同源校验；**文件读写、执行类接口必须在插件内自行判断本机回环**（`h.client_address`），局域网用户不能触发 |
| 可用的 Handler 方法 | `_send_json(obj, code)`、`_read_json(limit)`、`send_response` / `send_header` / `wfile` |

### 加载链路（以 CyrilShield 为例）

```
WebTerm 进程
├─ 前端，浏览器侧
│    manifest.json 的 kind:"panel" + entry:"index.html"
│      → 点开组件时宿主创建 <iframe src="/comp/cyrilshield/index.html">
│      → _serve_component() 按后缀白名单把 html / js / css 当静态文件发出
│      → app.js 里 fetch('/cs/env') 只是发一个同源 HTTP 请求，不 import 任何 Python
└─ 后端，宿主 Python 侧
     _plugin_backend_of(<组件目录>) 先看 manifest 的 backend，再按约定名找桥接文件
       → _plugin_refresh() 遍历 _comp_dirs()，逐个算后端文件签名
       → 签名没变：复用登记表里已加载的模块（不重复 exec）
       → 签名变了 / 新增：SourceFileLoader 显式加载（后缀不参与判断）
       → 读模块级 PREFIX、确认有 handle_get / handle_post → 登记进 _PLUGIN_REG
       → 请求路径以 PREFIX 开头时，_plugin_route() 调对应的 handle_xxx(handler, path)
```

- 宿主**不依赖 `manifest.json` 决定后端**：目录里有约定名的后端文件且导出了 `PREFIX` 就会加载，
  连 `manifest.json` 都可以没有（实测：一个只有 `bridge` 无后缀文件的临时目录照样响应自己的前缀，
  stderr 打印「插件后端已加载」）。`manifest.json` 的 `backend` 只用来指认非约定文件名，
  它同时也决定前端是否出现在组件清单里、长什么样、怎么打开。
  多个不同前缀的插件后端可以同时加载，互不干扰。
- 加载的触发与路径**是否命中 `PREFIX` 无关**：`_plugin_route()` 先无条件调 `_plugin_refresh()`，
  再逐个比对前缀。所以任何走到这一行的请求都会让**全部**候选后端被 import 一次；
  GET 需绕过前面已经 `return` 的分支（`/`、`/index.html`、`/favicon.ico`、`/api/`、`/comp/`、
  WebSocket），POST 在同源校验之后第一站就是它。实测：一条与插件无关的 `GET /zzz-probe`
  （最终 404）即可触发加载，既不需要打开首页，也不需要打开插件面板。
  面板打开后的第一个 `fetch('/cs/...')` 只是通常最早走到这一行的请求，不是触发条件本身。
- 后端鉴权要自己写：宿主 `do_POST` 只做同源校验，`/cs/*` 的「仅本机回环」
  是插件内部用 `h.client_address` 判断的。
- import 失败（典型是冻结环境缺 `uuid`）会在 stderr 打 `[webterm] 插件后端加载失败`，
  该插件所有接口变成 404，前端 `/cs/env` 无响应就显示「后端未加载」——看起来像路由没生效，
  实际是模块根本没进进程。
- 由此产生一条排障顺序：先看 stderr 有没有「插件后端已加载」，再谈前端和路由。

### 进程模型：不是「启动一个后端服务」

`bridge.py` 不是被启动的独立服务，而是被 import 进宿主进程的一段请求处理代码：

| 它有 | 它没有 |
|---|---|
| 宿主的 HTTP 服务器和同一个端口 | 独立监听端口 |
| 宿主进程的 Python 解释器和内存 | 独立进程、独立 PID |
| 每次请求时在 `handle_xxx` 里执行 | 常驻事件循环、开机自启 |
| 自己起线程 / 子进程的能力 | 宿主提供的生命周期管理 |

所以**任何插件都能用 `bridge.py` 挂后端**，不认插件名、不需要注册、不需要改 `server.py`；
但准确说法是「挂一段请求处理代码」，不是「启动后端」。需要长时间跑的任务，用
「一次 POST 起线程 + 轮询任务状态接口」的模式（`cyrilshield` 的 `/cs/run` + `/cs/job`），
不要假设后台有一个独立服务一直在。

确实需要常驻独立进程（本地模型、数据库等）时，插件可以自己 `subprocess.Popen` 启动。
卸载有钩子，但**宿主进程本身退出时仍然不保证任何回调**（被强杀、拔电、任务管理器结束）：

- 卸载路径是有的：删组件、改 `bridge.py`、`POST /api/plugins/reload` 都会先调
  插件的可选 `unload()`，再从 `sys.modules` 摘掉模块；
- 但宿主没有 `atexit` / 信号回调统一通知插件，进程结束时子进程要靠插件自己收尾。

由此产生三条硬约束：

- **常驻子进程必须插件自己管**：只绑 `127.0.0.1`、自己提供停止接口、在 `unload()` 里
  结束自己起的子进程、用 `atexit.register` 兜底（被强杀时不保证触发）、不要把 PID 或端口写死。
- **`unload()` 里只做收尾，不要再处理请求**：它执行时旧模块正在被摘掉，此时并发请求
  可能还持有旧模块引用，钩子里抛异常只会在 stderr 记一行，不影响卸载流程。
- 没有跨插件的服务发现。前缀是唯一寻址方式，也是唯一的命名空间，务必独占。

> ⚠️ **只能用 exe 里已经打进去的标准库。** 打包后的 `WebTerm.exe` 是 PyInstaller
> 冻结环境，宿主自己没 `import` 过的标准库不会进包。插件里写 `import uuid` 会在
> 加载时直接失败，报 `No module named 'uuid'`，症状是**整个插件后端失效、接口全 404**，
> 前端看起来像「路由没生效」。
>
> `WebTerm.spec` 里的 `_PLUGIN_STDLIB` 已经预置了一批常用模块：
> `uuid / secrets / zipfile / tarfile / gzip / bz2 / lzma / base64 / struct / mimetypes /
> csv / configparser / sqlite3 / xml.etree / email / queue / concurrent.futures …`
> 要用清单外的模块，先加进 spec 再重新打包。第三方库同理（要么打进包，要么别依赖）。

### 热重载与生命周期

改后端不用再重启进程：宿主用 `(绝对路径, mtime_ns, 文件大小)` 给每个后端文件算签名，
每次路由分发前比对（0.5 秒节流），按结果做三种事：

| 变化 | 行为 |
|---|---|
| 新增后端文件（`bridge.py` / `bridge` / manifest 指定的文件…） | 导入并登记，stderr 打「插件后端已加载：<id>」 |
| 文件改了（含 touch / 覆盖 / 换行 / 改后缀优先级） | 先 `unload()` 旧模块、从 `sys.modules` 摘掉，再导入新的 |
| 目录被删 / 后端文件被删 | 立即卸载，前缀路由变 404，不再留幽灵后端；同目录还有其它候选时自动换成下一个 |
| 内容没变 | 复用已加载模块，不重复执行顶层代码（20 并发首次请求实测只 exec 一次） |

切换判据同时看**绝对路径**和 `(mtime_ns, size)`：把一个目录里的后端从 `bridge.py` 改成 `bridge`
（或反过来）会立刻换用新文件，不需要重启。`GET /api/plugins` 的每项都带 `backend` 字段，
直接告诉你这个插件当前用的是哪个文件。

插件可以选实现 `unload()`，宿主在「卸载 / 重载 / 强制重载」前调用一次：

```python
PREFIX = "/cs/"

def unload():
    """可选：收尾自己起的线程、子进程、临时文件。不要在这里处理请求。"""
    global _worker
    if _worker is not None:
        _worker.stop()
        _worker = None
```

观测与兜底：

| 接口 | 作用 |
|---|---|
| `GET /api/plugins` | 登记表：`id` / `prefix` / `state`（ok、skip、error）/ `error` / `dup_prefix` / `version` / `size` / `loaded_at` / `file`（路径只给本机） |
| `POST /api/plugins/reload` | 强制全部重载（不看签名），仅本机 / 管理员；用于「文件没改但状态脏了」的兜底 |

`PLUGIN_VERSION = "1.2.0"` 是可选约定，写了会出现在 `/api/plugins` 的 `version` 里，方便确认
远端跑的是哪一版。

热重载的边界：

- **模块级全局状态会随重载清空**（连接池、缓存、计数器）。需要跨重载保留的东西写磁盘。
- **正在处理的请求持有的是旧模块对象**，会按旧代码跑完；`unload()` 别急着释放它们正在用的资源。
- 重载只认文件签名。改 `vendor\` 里的依赖文件不会自动重载（顶层 import 已缓存），
  要连依赖一起换，用 `POST /api/plugins/reload` 或重启。
- 插件顶层或处理函数里调用 `sys.exit()` 不会关闭 WebTerm：顶层那次会被拦下并记为加载失败，
  处理函数里那次只结束本次请求（500）。但**死循环、巨量内存分配仍然会拖垮宿主**，这是进程内
  模型的固有代价。
- v1 的插件一行都不用改：不实现 `unload()`、不写 `PLUGIN_VERSION` 也照常工作。

### 让插件自带依赖（环境集中）

插件需要的运行时、工具链、第三方包**全部放进组件自己的目录**：别人整个文件夹拷过去就能用，
不依赖宿主 exe 目录、系统 PATH、`%TEMP%` 或用户主目录里的东西。

```
components\my-plugin\
  vendor\                 ← 自带的一切外部依赖（只读使用）
    mylib\
    tools\upx.exe
    toolchain\            ← 可选：JDK / Android SDK 这类大件
    python\               ← 可选：便携 Python（要编译产物时才需要）
  .work\                  ← 运行时临时目录（首次使用时自动创建）
```

约定：

- 所有路径从 `__file__` 推导，**不要写死盘符或宿主路径**；插件换个目录必须照常工作。
- 要写临时文件时优先 `<插件>/.work\`，插件放在只读位置（写不进去）再回退 `%TEMP%`。
- 大件缺失时回退系统安装并明确提示，**不要让整个插件加载失败**。
- 卸载 = 删掉这个文件夹，不残留任何别处的文件。

参考 `cyrilshield`：业务代码 + `upx.exe` + APK 工具链（103 MB）+ 便携 Python/PyInstaller（64 MB）
全在 `vendor\` 里，`.work\` 存上传暂存与构建中间产物，整个文件夹 168 MB 拷走即可用。

### 新增一个带后端插件的最小步骤

1. 建 `components\<id>\`，放 `manifest.json`（`kind:"panel"` + `entry:"index.html"`）。
   id 必须匹配 `^[a-z0-9][a-z0-9_-]{0,31}$`，且等于文件夹名。
2. 写 `index.html` / `app.js`，用 `fetch('/<自己的前缀>...')` 发同源请求。
3. 写后端文件（默认 `bridge.py`，也可以是 `bridge` 无后缀、`bridge.pyw`，或 manifest 里
   `"backend": "core/server"` 指定的任意名字）：模块级 `PREFIX = "/xx/"`，实现
   `handle_get` / `handle_post`；涉及文件读写、启动外部程序的接口，先用 `h.client_address`
   判本机回环。
4. 确认用到的标准库在 `WebTerm.spec` 的 `_PLUGIN_STDLIB` 清单里；第三方依赖整个放进
   组件自己的 `vendor\`，临时文件写 `.work\`。
5. 刷新页面：第一个走到插件路由的请求会加载后端，stderr 出现
   `[webterm] 插件后端已加载：<id>`。之后改后端文件 **保存即生效**（≤0.5 秒），
   改前端刷新页面即可；排查用 `GET /api/plugins` 看 `backend` / `state` / `error`。

不需要做：改 `server.py`、注册路由表、开新端口、改 `WebTerm.spec`（除非要新增标准库）、
操作端口或防火墙。`manifest.json` 里也**不必**声明后端——只有后端文件名不属于约定名时，
才需要写 `backend` 指一下。

随包示例见 `components\cyrilshield\`：前端 panel + `bridge.py` 后端，覆盖上传、
异步任务、进度轮询、产物下载、打开目录的完整链路。

