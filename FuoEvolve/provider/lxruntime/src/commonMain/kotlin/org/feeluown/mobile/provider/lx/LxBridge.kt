package org.feeluown.mobile.provider.lx

/**
 * 注入到 QuickJS 全局的 `lx` 桥接层（JS 侧）。
 *
 * 为什么不把 `lx` 用 Kotlin 对象一格一格搭出来：LX 脚本会读 `lx.EVENT_NAMES`、把
 * `lx.on` 存下来的回调当普通 JS 函数反复调用，还会对 `lx.utils.buffer` 的返回值直接
 * 做 `.length` / `.toString()`。这些行为在 JS 侧表达最自然，Kotlin 只需要提供少数几个
 * 真实需要跨界的原语（HTTP、字节编解码）。
 *
 * 跨界的 Kotlin binding：
 * - `__lxHttp(requestJson)` → suspend，返回 `{statusCode, status, body, headers}` 的 JSON
 * - `__lxBufferFrom(text, format)` → ByteArray（QuickJS 侧即 Uint8Array）
 * - `__lxBufToString(bytes, format)` → String
 * - `__lxCryptoUnsupported(name)` → 明确告知该 crypto 原语尚未实现，而不是静默返回错值
 * - `__lxDelay(ms)` → suspend，宿主侧可取消的计时器；JS 的 setTimeout 基于它实现，
 *   runtime 关闭时所有 pending delay 一起取消，回调不会在 context 销毁后触发
 *
 * 兼容性说明：官方文档写 `lx.request(url, options, callback)` 的回调是 `(err, resp, body)`，
 * 而社区脚本绝大多数写成 `(err, resp) => resp.body`。这里同时满足：第三个参数传 body，
 * 且 `resp.body` 也带同一份内容。
 */
internal object LxBridge {

    fun source(scriptName: String): String = SHIM.replace("__LX_SCRIPT_NAME__", jsonQuote(scriptName))

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    private const val SHIM = """
(function () {
  'use strict';
  var handlers = Object.create(null);
  var initedPayload = null;
  var updateAlert = null;

  // 受控计时器：回调留在 JS 侧执行（仍在 QuickJS context 内），宿主只负责"延时到了"这一信号。
  // clearTimeout 通过递增的 token 失效化，即便 delay 已到也不会再触发；
  // runtime 关闭时宿主取消所有 delay 协程，回调同样不会执行。
  var timers = Object.create(null);
  var timerSeq = 0;
  var MAX_TIMERS = 64;

  globalThis.setTimeout = function (fn, delay) {
    if (typeof fn !== 'function') return 0;
    var ids = Object.keys(timers);
    if (ids.length >= MAX_TIMERS) return 0;
    var id = ++timerSeq;
    var ms = Number(delay);
    if (!isFinite(ms) || ms < 0) ms = 0;
    if (ms > 2147483647) ms = 2147483647;
    timers[id] = true;
    __lxDelay(ms).then(function () {
      if (!timers[id]) return;
      delete timers[id];
      try { fn(); } catch (e) { /* 脚本回调异常不影响宿主 */ }
    }).catch(function () { /* runtime 关闭导致取消，忽略 */ });
    return id;
  };

  globalThis.clearTimeout = function (id) {
    if (id && timers[id]) delete timers[id];
  };

  var EVENT_NAMES = {
    inited: 'inited',
    request: 'request',
    updateAlert: 'updateAlert'
  };

  var utils = {
    buffer: {
      from: function (value, format) {
        return __lxBufferFrom(value === undefined || value === null ? '' : String(value),
                              format === undefined || format === null ? 'utf8' : String(format));
      },
      bufToString: function (buffer, format) {
        return __lxBufToString(buffer,
                               format === undefined || format === null ? 'utf8' : String(format));
      }
    },
    crypto: {
      md5: function (input) { return __lxCryptoUnsupported('md5'); },
      randomBytes: function (size) { return __lxCryptoUnsupported('randomBytes'); },
      aesEncrypt: function (buffer, mode, key, iv) { return __lxCryptoUnsupported('aesEncrypt'); },
      rsaEncrypt: function (buffer, key) { return __lxCryptoUnsupported('rsaEncrypt'); }
    },
    zlib: {
      inflate: function () { return Promise.reject(new Error('lx.utils.zlib.inflate 在移动端未实现')); },
      deflate: function () { return Promise.reject(new Error('lx.utils.zlib.deflate 在移动端未实现')); }
    }
  };

  var lx = {
    version: '2.0.0',
    env: 'mobile',
    currentScriptInfo: {
      name: __LX_SCRIPT_NAME__,
      description: '',
      version: '',
      author: '',
      homepage: '',
      rawScript: ''
    },
    EVENT_NAMES: EVENT_NAMES,
    utils: utils,
    on: function (event, handler) {
      if (typeof handler === 'function') handlers[event] = handler;
    },
    send: function (event, data) {
      if (event === EVENT_NAMES.inited) initedPayload = data;
      else if (event === EVENT_NAMES.updateAlert) updateAlert = data;
    },
    request: function (url, options, callback) {
      options = options || {};
      var cancelled = false;
      var payload = {
        url: String(url),
        method: options.method === undefined ? 'GET' : String(options.method),
        headers: options.headers === undefined || options.headers === null ? {} : options.headers,
        body: options.body === undefined || options.body === null ? null : String(options.body),
        timeout: options.timeout === undefined ? 0 : options.timeout
      };
      __lxHttp(JSON.stringify(payload)).then(function (raw) {
        if (cancelled) return;
        var resp = JSON.parse(raw);
        if (typeof callback === 'function') callback(null, resp, resp.body);
      }).catch(function (err) {
        if (cancelled) return;
        if (typeof callback === 'function') callback(String((err && err.message) || err), null, null);
      });
      return function () { cancelled = true; };
    }
  };

  globalThis.lx = lx;

  globalThis.__lxInited = function () {
    return initedPayload === null ? null : JSON.stringify(initedPayload);
  };

  globalThis.__lxUpdateAlert = function () {
    return updateAlert === null ? null : JSON.stringify(updateAlert);
  };

  globalThis.__lxHasHandler = function (event) {
    return typeof handlers[event] === 'function';
  };

  globalThis.__lxDispatch = function (payloadJson) {
    var handler = handlers[EVENT_NAMES.request];
    if (typeof handler !== 'function') {
      throw new Error('脚本未注册 request 事件');
    }
    var result = handler(JSON.parse(payloadJson));
    if (result && typeof result.then === 'function') return result;
    return Promise.resolve(result);
  };
})();
"""
}
