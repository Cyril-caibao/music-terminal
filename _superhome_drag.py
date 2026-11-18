# -*- coding: utf-8 -*-
"""超级终端：节点可拖动（自动保存）+ 点击展开分支。

只改 Windows 版（用户明确「不用更新 Linux」）。
每处替换都断言命中次数，命中不为 1 立即报错，不产出半成品。
"""
import io
import sys

HTML = "D:/Terminal-Windows/web/index.html"
PY = "D:/Terminal-Windows/server.py"


def patch(path, reps):
    s = io.open(path, encoding="utf-8").read()
    orig = s
    for i, (old, new) in enumerate(reps, 1):
        n = s.count(old)
        if n != 1:
            print("FAIL 第 %d 处命中 %d 次（应为 1）:\n%s" % (i, n, old[:120]))
            sys.exit(1)
        s = s.replace(old, new)
    if s == orig:
        print("FAIL 无任何改动")
        sys.exit(1)
    io.open(path, "w", encoding="utf-8", newline="").write(s)
    print("  %s 已更新，%d 处替换，%d 字节" % (path, len(reps), len(s.encode("utf-8"))))


# ---------------------------------------------------------------- 1. 样式
CSS_NEW = '''  /* ---- 节点拖动 / 分支 ---- */
  .sh-node.open { border-color: rgba(156,255,223,.85); box-shadow: 0 0 34px rgba(120,220,255,.42), inset 0 0 18px rgba(120,220,255,.16); }
  .sh-node.dragging { transition: none; cursor: grabbing; z-index: 3; }
  /* 有下级的节点右下角点一个小标记，暗示「可以展开」 */
  .sh-node.has-kids::after {
    content: ''; position: absolute; right: 5px; bottom: 5px;
    width: 5px; height: 5px; border-radius: 50%;
    background: rgba(156,255,223,.78);
  }
  .sh-kid {
    position: absolute; transform: translate(-50%, -50%);
    padding: 7px 15px; border-radius: 999px; cursor: pointer;
    background: rgba(6, 16, 24, .82);
    border: 1px solid rgba(156,255,223,.34);
    color: #d8f0fb; font-family: inherit; font-size: 12px; letter-spacing: .10em;
    white-space: nowrap; -webkit-backdrop-filter: blur(6px); backdrop-filter: blur(6px);
    box-shadow: 0 0 18px rgba(90,180,215,.18), inset 0 0 12px rgba(120,220,255,.06);
    transition: transform .22s cubic-bezier(.32,.72,0,1), border-color .22s, box-shadow .22s;
  }
  .sh-kid:hover, .sh-kid:focus-visible {
    transform: translate(-50%, -50%) scale(1.08);
    border-color: rgba(156,255,223,.8);
    box-shadow: 0 0 26px rgba(120,220,255,.34);
    outline: none;
  }
  .sh-link-branch { stroke: rgba(156,255,223,.24); stroke-dasharray: 3 5; }

'''

JS_NEW = '''    /* ================= 节点：可拖动 + 位置落盘 + 分支展开 =================
       位置存两份：localStorage（连上后端前的即时值，避免先闪一帧原位）与
       Settings.json 的 super_nodes（权威值，换浏览器 / 重装 exe 不丢）。
       拖动阈值 4px：小于阈值仍按点击处理（想点却手抖不该变成移动）。
       不用 setPointerCapture —— 指针捕获会把 click 吞掉，节点就点不开了；
       改成 window 上临时挂 pointermove / pointerup，抬手时摘掉。 */
    var POS_KEY = 'Win-webterm-super-nodes';
    var DEFAULT_POS = { music: [24, 28], file: [76, 28], comp: [24, 72], settings: [76, 72] };
    var positions = {};
    var nodeEls = {};
    function clampPct(v) { return Math.max(6, Math.min(94, Number(v) || 0)); }
    function copyPositions() {
      var o = {};
      Object.keys(positions).forEach(function (k) { o[k] = positions[k].slice(); });
      return o;
    }
    Object.keys(DEFAULT_POS).forEach(function (k) { positions[k] = DEFAULT_POS[k].slice(); });
    (function () {
      var raw = null;
      try { raw = JSON.parse(localStorage.getItem(POS_KEY) || 'null'); } catch (e) { raw = null; }
      if (!raw || typeof raw !== 'object') return;
      Object.keys(DEFAULT_POS).forEach(function (k) {
        var v = raw[k];
        if (v && v.length === 2 && isFinite(v[0]) && isFinite(v[1])) {
          positions[k] = [clampPct(v[0]), clampPct(v[1])];
        }
      });
    })();
    Array.prototype.forEach.call(root.querySelectorAll('[data-sh-go]'), function (b) {
      nodeEls[b.getAttribute('data-sh-go')] = b;
    });
    function applyPositions() {
      Object.keys(nodeEls).forEach(function (k) {
        var p = positions[k];
        if (!p) return;
        nodeEls[k].style.left = p[0] + '%';
        nodeEls[k].style.top = p[1] + '%';
      });
    }
    applyPositions();
    function persistPositions() {
      try { localStorage.setItem(POS_KEY, JSON.stringify(positions)); } catch (e) {}
      if (window.__cyrilCfgSend) window.__cyrilCfgSend({ super_nodes: copyPositions() });
    }
    /* 后端配置到货：以 Settings.json 为准 */
    window.__cyrilSuperNodes = function (raw) {
      if (!raw || typeof raw !== 'object') return;
      Object.keys(nodeEls).forEach(function (k) {
        var v = raw[k];
        if (v && v.length === 2 && isFinite(v[0]) && isFinite(v[1])) {
          positions[k] = [clampPct(v[0]), clampPct(v[1])];
        }
      });
      applyPositions();
      closeBranch();
    };

    var drag = null, dragMoved = false;
    function onDown(e) {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      var b = e.currentTarget;
      var r = b.getBoundingClientRect();
      drag = { el: b, key: b.getAttribute('data-sh-go'),
               x0: e.clientX, y0: e.clientY,
               cx: r.left + r.width / 2, cy: r.top + r.height / 2,
               id: e.pointerId, moved: false };
      dragMoved = false;
      window.addEventListener('pointermove', onMove, true);
      window.addEventListener('pointerup', onUp, true);
      window.addEventListener('pointercancel', onUp, true);
      e.preventDefault();
    }
    function onMove(e) {
      if (!drag || e.pointerId !== drag.id) return;
      var dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
      if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 4) return;
      if (!drag.moved) {
        drag.moved = true; dragMoved = true;
        closeBranch();
        drag.el.classList.add('dragging');
      }
      var nx = clampPct((drag.cx + dx) / innerWidth * 100);
      var ny = clampPct((drag.cy + dy) / innerHeight * 100);
      positions[drag.key] = [nx, ny];
      drag.el.style.left = nx + '%';
      drag.el.style.top = ny + '%';
    }
    function onUp(e) {
      if (!drag || (e && e.pointerId !== drag.id)) return;
      window.removeEventListener('pointermove', onMove, true);
      window.removeEventListener('pointerup', onUp, true);
      window.removeEventListener('pointercancel', onUp, true);
      if (drag.moved) { drag.el.classList.remove('dragging'); persistPositions(); }
      drag = null;
    }

    /* ---- 分支：音乐三视图 / 组件清单（后端 comp_list） ---- */
    var GO = { music: 'btnMusic', file: 'btnTree', comp: 'btnComp', settings: 'btnSettings' };
    var MUSIC_KIDS = [
      { label: '音域回响', act: 'view:sonic' },
      { label: '封面拼贴', act: 'view:lattice' },
      { label: '绘光', act: 'view:lumiere' }
    ];
    var branch = null;
    function closeBranch() {
      if (!branch) return;
      branch.kids.forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); });
      branch.lines.forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); });
      var owner = nodeEls[branch.key];
      if (owner) owner.classList.remove('open');
      branch = null;
    }
    function kidList(key) {
      if (key === 'music') return MUSIC_KIDS.slice();
      if (key !== 'comp') return [];
      var snap = (window.__cyrilCompSnapshot && window.__cyrilCompSnapshot()) || [];
      return snap.map(function (c) { return { label: c.name, act: 'comp:' + c.id }; });
    }
    function openBranch(key) {
      closeBranch();
      var p = positions[key];
      if (!p) return;
      var kids = kidList(key);
      var waiting = !kids.length;
      if (waiting) kids = [{ label: '读取中…', act: '' }];
      /* 上半屏的节点向下排、下半屏的向上排：竖直一列，不挤向左右边缘 */
      var dir = p[1] < 50 ? 1 : -1;
      var stage = document.getElementById('superStage');
      var g = document.getElementById('superBranchLines');
      branch = { key: key, kids: [], lines: [] };
      kids.forEach(function (k, i) {
        var ky = Math.max(8, Math.min(92, p[1] + dir * 8.2 * (i + 1)));
        if (g) {
          var ln = document.createElementNS('http://www.w3.org/2000/svg', 'line');
          ln.setAttribute('x1', (p[0] * 10).toFixed(2));
          ln.setAttribute('y1', (p[1] * 10).toFixed(2));
          ln.setAttribute('x2', (p[0] * 10).toFixed(2));
          ln.setAttribute('y2', (ky * 10).toFixed(2));
          ln.setAttribute('class', 'sh-link sh-link-branch');
          g.appendChild(ln);
          branch.lines.push(ln);
        }
        var btn = document.createElement('button');
        btn.className = 'sh-kid';
        btn.style.left = p[0] + '%';
        btn.style.top = ky + '%';
        btn.textContent = k.label;
        btn.title = k.act ? '打开 ' + k.label : k.label;
        btn.addEventListener('click', function (e) {
          e.stopPropagation();
          if (k.act) runAct(k.act);
        });
        stage.appendChild(btn);
        branch.kids.push(btn);
      });
      if (key === 'comp' && waiting && window.__cyrilCompWhenReady) {
        window.__cyrilCompWhenReady(function () {
          if (branch && branch.key === 'comp') openBranch('comp');   // 清单到货后换成真列表
        });
      }
    }
    function runAct(act) {
      touched = true;
      closeBranch();
      hide();
      setTimeout(function () {
        if (act.indexOf('view:') === 0) {
          if (window.__cyrilViewOpen) window.__cyrilViewOpen(act.slice(5));
        } else if (act.indexOf('comp:') === 0) {
          if (window.__cyrilCompOpen) window.__cyrilCompOpen(act.slice(5));
        }
      }, 0);
    }

    /* ---- 节点 / 中心 / 顶栏返回 ---- */
    Array.prototype.forEach.call(root.querySelectorAll('[data-sh-go]'), function (b) {
      var key = b.getAttribute('data-sh-go');
      b.addEventListener('pointerdown', onDown);
      b.addEventListener('click', function () {
        if (dragMoved) { dragMoved = false; return; }      // 刚拖完，这次抬起不算点击
        if (key === 'music' || key === 'comp') {           // 有下级：点开 / 收起分支
          if (branch && branch.key === key) { closeBranch(); return; }
          openBranch(key);
          return;
        }
        touched = true;
        hide();
        var id = GO[key], el = id && document.getElementById(id);
        if (el) setTimeout(function () { el.click(); }, 0);
      });
    });
    /* 点空白收起分支；点子项 / 节点由它们自己处理 */
    root.addEventListener('click', function (e) {
      if (!branch) return;
      if (e.target && e.target.closest &&
          (e.target.closest('.sh-kid') || e.target.closest('[data-sh-go]'))) return;
      closeBranch();
    });
'''

reps_html = [
    # 1) 样式：插在 .sh-foot 之前
    ("""  .sh-foot {
    position: absolute; left: 0; right: 0; bottom: 22px; text-align: center;""",
     CSS_NEW + """  .sh-foot {
    position: absolute; left: 0; right: 0; bottom: 22px; text-align: center;"""),
    # 2) 节点光标改成「可拖」
    ("""    width: 58px; height: 58px; padding: 0; border-radius: 50%; cursor: pointer;""",
     """    width: 58px; height: 58px; padding: 0; border-radius: 50%; cursor: grab;"""),
    # 3) 分支连线组
    ("""        <line class="sh-link" x1="500" y1="500" x2="760" y2="720"></line>
      </svg>""",
     """        <line class="sh-link" x1="500" y1="500" x2="760" y2="720"></line>
        <g id="superBranchLines"></g>
      </svg>"""),
    # 4) 音乐节点：标记有下级
    ("""      <button class="sh-node" data-sh-go="music" style="left:24%;top:28%" title="音乐 · 进入电台与播放队列">""",
     """      <button class="sh-node has-kids" data-sh-go="music" style="left:24%;top:28%" title="音乐 · 点击展开：音域回响 / 封面拼贴 / 绘光">"""),
    # 5) 组件节点：标记有下级
    ("""      <button class="sh-node" data-sh-go="comp" style="left:24%;top:72%" title="组件 · 打开组件面板">""",
     """      <button class="sh-node has-kids" data-sh-go="comp" style="left:24%;top:72%" title="组件 · 点击展开组件清单">"""),
    # 6) 底部提示补一句拖动
    ("""      <div class="sh-foot">CYRIL WEBTERM · 从终端进入任意模块，顶栏 ⌂ 可返回</div>""",
     """      <div class="sh-foot">CYRIL WEBTERM · 图标可拖动（自动保存）· 点【音乐】【组件】展开分支 · 顶栏 ⌂ 可返回</div>"""),
    # 7) hide() 收起分支
    ("""      root.setAttribute('aria-hidden', 'true');
      galaxyStop();""",
     """      root.setAttribute('aria-hidden', 'true');
      galaxyStop();
      closeBranch();"""),
    # 8) 主体逻辑
    ("""    /* ---- 节点 / 中心 / 顶栏返回 ---- */
    var GO = { music: 'btnMusic', file: 'btnTree', comp: 'btnComp', settings: 'btnSettings' };
    Array.prototype.forEach.call(root.querySelectorAll('[data-sh-go]'), function (b) {
      b.addEventListener('click', function () {
        touched = true;
        hide();
        var id = GO[b.getAttribute('data-sh-go')], el = id && document.getElementById(id);
        if (el) setTimeout(function () { el.click(); }, 0);
      });
    });""", JS_NEW),
    # 9) 配置到货时套用节点位置
    ("""      if (window.__cyrilSuperHomeApply) window.__cyrilSuperHomeApply(data.start_page);""",
     """      if (window.__cyrilSuperHomeApply) window.__cyrilSuperHomeApply(data.start_page);
      if (data.super_nodes && window.__cyrilSuperNodes) window.__cyrilSuperNodes(data.super_nodes);"""),
    # 10) 组件清单到货 → 通知等待者（启动页分支要从这里取名字）
    ("""  function compApplyList(items) {
    if (Array.isArray(items)) _compList = items;
    compRender();
    applyComp(_compState, true);      // 用新清单重挂一遍（silent：这不是用户操作）
  }""",
     """  function compApplyList(items) {
    if (Array.isArray(items)) _compList = items;
    compRender();
    applyComp(_compState, true);      // 用新清单重挂一遍（silent：这不是用户操作）
    if (_compWaiters.length) {        // 启动页在等清单：到货后让它换成真名字
      var ws = _compWaiters; _compWaiters = [];
      ws.forEach(function (f) { try { f(); } catch (e) {} });
    }
  }

  /* ---- 超级终端（启动页）桥：清单快照 / 按 id 打开 / 清单到货回调 ----
     启动页在另一个 IIFE 里，拿不到 _compList 与 openCompPanel，这里只暴露
     这几个入口，不复制开窗逻辑。 */
  var _compWaiters = [];
  window.__cyrilCompSnapshot = function () {
    return _compList.map(function (i) {
      return { id: i.id, name: i.name || i.id, kind: i.kind || '', builtin: !!i.builtin };
    });
  };
  window.__cyrilCompWhenReady = function (cb) {
    if (typeof cb !== 'function') return;
    if (_compList.length) { cb(); return; }
    _compWaiters.push(cb);
  };
  window.__cyrilCompOpen = function (id) {
    var item = compFind(id);
    if (!item) return false;
    if (item.kind === 'panel') { openCompPanel(item); return true; }   // 面板型：开独立窗口
    toggleComp(item.id, true);                                        // 栏型：直接挂到左栏
    if (window.__cyrilOpenPanel) window.__cyrilOpenPanel('comp');
    return true;
  };"""),
    # 11) 视图桥（音域 / 绘光 / 封面拼贴）
    ("""  btnMode.addEventListener('click', function () {
    enterRadio('sonic');          // 【Mineradio】= 音域回响背景
    if (window.__cyrilApClose) window.__cyrilApClose();
  });""",
     """  btnMode.addEventListener('click', function () {
    enterRadio('sonic');          // 【Mineradio】= 音域回响背景
    if (window.__cyrilApClose) window.__cyrilApClose();
  });

  /* 给超级终端启动页用：按名字直接进某个视图（不模拟按钮点击，避免二次弹层） */
  window.__cyrilViewOpen = function (v) {
    if (v === 'sonic' || v === 'lumiere') { enterRadio(v); return true; }
    if (v === 'lattice') { if (!latOn) latticeOpen(); return true; }
    return false;
  };"""),
]

reps_py = [
    ("""        "start_page": "super",
""",
     """        "start_page": "super",
        # 超级终端四个节点的位置（百分比）：{music:[x,y], file:[...], ...}
        # 拖动即写这里；localStorage 只是连上后端前的缓存
        "super_nodes": {},
"""),
]

if __name__ == "__main__":
    print("== web/index.html ==")
    patch(HTML, reps_html)
    print("== server.py ==")
    patch(PY, reps_py)
    print("完成")
