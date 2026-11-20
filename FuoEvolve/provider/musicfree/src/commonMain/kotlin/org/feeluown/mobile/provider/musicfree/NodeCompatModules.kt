package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 I2：MusicFree 插件真实需要的 Node 模块安全子集。
 *
 * 需求来源（2026-11-20 真实取证，经 7897 代理从 Gitee v0.1/dist 下载插件正文静态扫描）：
 * - audiomack (sha 1b1f49fc… 与 G2 台账一致)：axios / cheerio / crypto-js / dayjs
 * - bilibili：取证 sha 3e49093f… 与台账 930e3258… 不符 → 版本漂移，拒绝使用，不作为需求依据
 * - geciwang (sha b9535714… 一致)：axios / cheerio
 * - navidrome (sha 2f1e930e… 一致)：axios / crypto-js
 * - webdav (sha 64e00a41… 一致)：webdav
 * - youtube (sha 75b8a113… 一致)：axios
 *
 * 设计原则：**不追求完整 Node.js 兼容**，只实现上述真实调用面内有安全边界的子集。
 * 每个模块都标注支持/不支持/安全限制；文件系统、进程、原生网络、动态代码加载一律不提供。
 * 网络（axios/webdav）不在此文件实现，必须经 ProviderNetworkGateway（见 AxiosCompatModule）。
 */
object NodeCompatModules {

    /** 模块规格：支持范围、不支持范围、安全限制，全部随代码可审查。 */
    data class Spec(
        val name: String,
        val supported: String,
        val unsupported: String,
        val security: String,
    )

    val specs: List<Spec> = listOf(
        Spec(
            "events", "EventEmitter.on/emit/off/once/removeAllListeners",
            "自定义高水位/maxListeners 警告、EventEmitter 子类继承语义",
            "纯 JS 内存对象，无宿主能力，单 Context 生命周期",
        ),
        Spec(
            "querystring", "parse/stringify（字符串键值，URI 编解码）",
            "嵌套对象、数组序列化、自定义分隔符的高级语义",
            "纯字符串变换，输入长度受脚本体积上限约束",
        ),
        Spec(
            "url", "URLSearchParams、parse 的 protocol/host/pathname/search/query",
            "WHATWG URL 完整解析、file: 协议、跨域相对解析",
            "纯字符串；不发起任何网络；file:/javascript: 一律不解析为可用 URL",
        ),
        Spec(
            "path", "posix 纯字符串：join/basename/dirname/extname",
            "真实文件系统访问、resolve 到绝对路径、win32 盘符",
            "纯字符串运算，不触碰 fs；结果仅为逻辑路径片段",
        ),
        Spec(
            "util", "format（%s/%d/%j）、inspect 极简",
            "promisify、callbackify、inspect 全量、deprecate",
            "纯字符串；不暴露任何宿主对象引用",
        ),
        Spec(
            "crypto", "md5/sha1、HmacSHA1、enc.Base64/enc.Hex、enc.Utf8（audiomack OAuth1 与 navidrome 所需）",
            "AES/RSA/PBKDF2/公钥体系、随机数、证书、任何密钥托管",
            "纯计算；密钥由调用方一次性传入不落盘；不提供随机源；timestamp 由宿主注入",
        ),
        Spec(
            "buffer", "Buffer.from(string,encoding).toString(encoding)、byteLength",
            "文件/网络 Buffer、allocUnsafe、流式操作、原生字节数组逃逸",
            "基于 JS 字符串与 Uint8Array，不暴露宿主内存；编码仅 utf8/base64/hex/latin1",
        ),
        Spec(
            "dayjs", "dayjs.unix(ts).format('YYYY-MM-DD') 等纯格式化",
            "时区数据库、相对时间 humanize、插件生态、本地时区依赖",
            "纯计算使用 UTC；不读宿主时钟（时间由插件传入 ts）",
        ),
        Spec(
            "he", "encode/decode HTML 实体（命名+数字）",
            "完整 WHATWG 实体表的超大子集逐条保证",
            "纯字符串；常见实体+数字实体全覆盖，未知实体原样保留（fail-safe）",
        ),
        Spec(
            "cheerio", "load(html) 后的 $('sel').text()/attr()/find()/eq()/length 只读提取",
            "DOM 修改、事件、表单序列化、复杂 CSS 选择器引擎、HTML 解析容错全兼容",
            "只读正则/子串提取，不执行 HTML 中的脚本，不加载外部资源；输入为调用方已取得的 HTML 字符串",
        ),
        Spec(
            "axios", "get(url,{params,headers,responseType}) 受控请求，返回 {status,data,headers}",
            "任意域名、POST/PUT/DELETE、重定向自动跟随、代理、Cookie/Jar、拦截器、上传下载流",
            "必须经 ProviderNetworkGateway，域名/版本/SHA 绑定授权，默认拒绝；无网关注入时一律 Denied",
        ),
        Spec(
            "webdav", "createClient({url}).getFileContents(path) 受控只读",
            "PUT/MKCOL/删除/属性修改、认证、目录列举写操作",
            "端点为动态配置，静态不可审 → 按 H2 策略 deny-all，不放宽 SSRF；此模块登记但不启用",
        ),
        Spec(
            "stream", "当前不提供（真实插件调用面未出现）",
            "全部 stream API",
            "按需再评估，不为兼容无条件实现",
        ),
    )

    /** 已取证的真实插件依赖矩阵（sha 前缀用于核对台账，漂移即不使用）。 */
    data class PluginRequires(val pluginId: String, val shaPrefix: String, val requires: Set<String>)
    val auditedPluginRequires: List<PluginRequires> = listOf(
        PluginRequires("mf-audiomack", "1b1f49fc", setOf("axios", "cheerio", "crypto-js", "dayjs")),
        PluginRequires("mf-geciwang", "b9535714", setOf("axios", "cheerio")),
        PluginRequires("mf-navidrome", "2f1e930e", setOf("axios", "crypto-js")),
        PluginRequires("mf-webdav", "64e00a41", setOf("webdav")),
        PluginRequires("mf-youtube", "75b8a113", setOf("axios")),
        // bilibili 取证 sha 3e49093f ≠ 台账 930e3258 → 不纳入（版本漂移 fail-closed）
    )

    /** 全部真实需要的模块名（去重），供 allowlist 注册参考。 */
    fun requiredModuleNames(): Set<String> =
        auditedPluginRequires.flatMap { it.requires }.toSet()
}
