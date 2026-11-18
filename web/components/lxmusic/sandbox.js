/**
 * 音源沙箱：模拟洛雪音乐的自定义源运行环境（lx API）
 * 只实现音源真正用到的部分：EVENT_NAMES / on / send / request / env / version
 * 请求走同源 /lx/proxy（由后端转发，绕开 CORS 与防盗链）
 */
(function () {
  'use strict';

  var EVENT_NAMES = {
    request: 'request',
    inited: 'inited',
    updateAlert: 'updateAlert',
  };

  var handlers = Object.create(null);
  var sent = [];          // 记录音源 send 出去的事件
  var runtime = {
    inited: null,
    alert: null,
    error: null,
    loaded: false,
    info: null,
  };

  function toForm(obj) {
    var qs = new URLSearchParams();
    Object.keys(obj || {}).forEach(function (k) {
      var v = obj[k];
      qs.append(k, v == null ? '' : String(v));
    });
    return qs.toString();
  }

  function proxyRequest(url, options, callback) {
    var o = options || {};
    var headers = Object.assign({}, o.headers || {});
    var body = null;
    if (o.form) {
      body = toForm(o.form);
      if (!headers['Content-Type'] && !headers['content-type']) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
      }
    } else if (o.body != null) {
      body = typeof o.body === 'string' ? o.body : JSON.stringify(o.body);
    }

    fetch('/lx/proxy', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: url,
        method: String(o.method || 'GET').toUpperCase(),
        headers: headers,
        body: body,
        bodyEnc: 'utf8',
        timeout: o.timeout || 15,
      }),
    })
      .then(function (r) {
        return r.json();
      })
      .then(function (d) {
        if (!d.ok) {
          var e = new Error(d.error || '请求失败');
          e.code = d.code;
          callback(e);
          return;
        }
        var text = d.body != null ? d.body : '';
        if (d.bodyB64) {
          try {
            text = atob(d.bodyB64);
          } catch (err) {
            text = '';
          }
        }
        var parsed = text;
        try {
          parsed = JSON.parse(text);
        } catch (err) {}
        callback(null, {
          statusCode: d.status,
          headers: d.headers || {},
          body: parsed,
          raw: text,
        });
      })
      .catch(function (e) {
        callback(e || new Error('网络错误'));
      });
  }

  /* ------- 加密工具（少量音源会用 md5 签名） ------- */
  var MD5_K = null;
  var MD5_S = [7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
               5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
               4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
               6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21];

  function md5(str) {
    var bytes = new TextEncoder().encode(String(str));
    var len = bytes.length;
    var total = (((len + 8) >> 6) + 1) * 64;
    var buf = new Uint8Array(total);
    buf.set(bytes);
    buf[len] = 0x80;
    var dv = new DataView(buf.buffer);
    var bits = len * 8;
    dv.setUint32(total - 8, bits >>> 0, true);
    dv.setUint32(total - 4, Math.floor(bits / 4294967296), true);

    if (!MD5_K) {
      MD5_K = new Uint32Array(64);
      for (var i = 0; i < 64; i++) {
        MD5_K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) >>> 0;
      }
    }

    var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
    var M = new Uint32Array(16);

    for (var off = 0; off < total; off += 64) {
      for (var j = 0; j < 16; j++) M[j] = dv.getUint32(off + j * 4, true);
      var A = a0, B = b0, C = c0, D = d0;
      for (var k = 0; k < 64; k++) {
        var F, g;
        if (k < 16) { F = (B & C) | (~B & D); g = k; }
        else if (k < 32) { F = (D & B) | (~D & C); g = (5 * k + 1) % 16; }
        else if (k < 48) { F = B ^ C ^ D; g = (3 * k + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * k) % 16; }
        F = (F + A + MD5_K[k] + M[g]) >>> 0;
        A = D;
        D = C;
        C = B;
        var sh = MD5_S[k];
        B = (B + ((F << sh) | (F >>> (32 - sh)))) >>> 0;
      }
      a0 = (a0 + A) >>> 0;
      b0 = (b0 + B) >>> 0;
      c0 = (c0 + C) >>> 0;
      d0 = (d0 + D) >>> 0;
    }

    function hex(n) {
      var out = '';
      for (var i = 0; i < 4; i++) {
        var b = (n >>> (i * 8)) & 0xff;
        out += (b < 16 ? '0' : '') + b.toString(16);
      }
      return out;
    }
    return hex(a0) + hex(b0) + hex(c0) + hex(d0);
  }

  var lx = {
    EVENT_NAMES: EVENT_NAMES,
    env: 'desktop',
    version: '2.0.0',
    currentScriptInfo: {},
    on: function (name, handler) {
      if (typeof handler !== 'function') return;
      (handlers[name] || (handlers[name] = [])).push(handler);
    },
    send: function (name, data) {
      if (name === EVENT_NAMES.inited) runtime.inited = data;
      else if (name === EVENT_NAMES.updateAlert) runtime.alert = data;
      sent.push({ name: name, data: data });
    },
    request: proxyRequest,
    utils: {
      crypto: {
        md5: md5,
        randomBytes: function (n) {
          var a = new Uint8Array(n);
          crypto.getRandomValues(a);
          return a;
        },
      },
    },
  };

  globalThis.lx = lx;

  /** 载入音源脚本（在 sandbox iframe 内执行，与宿主同源但作用域隔离） */
  window.loadSource = function (code, info) {
    handlers = Object.create(null);
    runtime.inited = null;
    runtime.alert = null;
    runtime.error = null;
    runtime.loaded = false;
    runtime.info = info || {};
    lx.currentScriptInfo = Object.assign({ rawScript: code }, info || {});
    try {
      // 音源脚本按普通脚本语义执行（顶层 this / 全局 lx 都可用）
      var fn = new Function('lx', 'globalThis', code + '\n//# sourceURL=lx-source.js');
      fn.call(globalThis, lx, globalThis);
      runtime.loaded = true;
      return { ok: true };
    } catch (e) {
      runtime.error = String((e && e.message) || e);
      return { ok: false, error: runtime.error };
    }
  };

  /** 调音源的 request 处理器 */
  window.callSource = function (payload) {
    var list = handlers[EVENT_NAMES.request] || [];
    if (!list.length) return Promise.reject(new Error('音源没有注册 request 处理器'));
    var p;
    try {
      p = list[list.length - 1](payload);
    } catch (e) {
      return Promise.reject(e);
    }
    return Promise.resolve(p);
  };

  window.getState = function () {
    return {
      loaded: runtime.loaded,
      error: runtime.error,
      inited: runtime.inited,
      alert: runtime.alert,
      hasRequest: !!(handlers[EVENT_NAMES.request] || []).length,
    };
  };

  parent.postMessage({ type: 'lx-sandbox-ready' }, '*');
})();
