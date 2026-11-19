package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 0 验证：一个符合官方移动端规范的 LX 脚本，能否在 QuickJS 里完成
 * 「加载 → inited → request(musicUrl) → 拿到 URL」这条主链路。
 */
class LxScriptRuntimeTest {

    private val inlineScript = """
        const { EVENT_NAMES, on, send } = globalThis.lx

        on(EVENT_NAMES.request, function (payload) {
          if (payload.action !== 'musicUrl') return Promise.reject(new Error('unsupported action'))
          var sid = payload.info.musicInfo.songmid
          return Promise.resolve(
            'https://cdn.example.com/' + payload.source + '/' + sid + '?q=' + payload.info.type
          )
        })

        send(EVENT_NAMES.inited, {
          sources: {
            kw: { name: '酷我音乐', type: 'music', actions: ['musicUrl'], qualitys: ['128k', '320k', 'flac'] },
            local: { name: '本地音乐', type: 'music', actions: ['musicUrl', 'lyric', 'pic'], qualitys: [] },
            wy: { name: '电台', type: 'radio', actions: ['musicUrl'], qualitys: ['320k'] }
          }
        })
    """.trimIndent()

    private fun mockClient(): HttpClient = HttpClient(
        MockEngine { request ->
            respond(
                content = """{"token":"abc123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    @Test
    fun `initialize keeps only music sources and normalizes qualities`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load("fixture.js", inlineScript)
            val meta = runtime.initialize()

            assertEquals(setOf("kw"), meta.platforms, "local 与非 music 类型必须被排除")
            assertEquals("酷我音乐", meta.sources.getValue("kw").name)
            // 官方音质顺序归一，且 flac24bit 未声明时不出现在结果里
            assertEquals(listOf("128k", "320k", "flac"), meta.qualitiesOf("kw"))
            assertEquals(emptyList(), meta.qualitiesOf("wy"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `requestMusicUrl returns the url produced by the script`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load("fixture.js", inlineScript)
            runtime.initialize()

            val url = runtime.requestMusicUrl(
                platform = "kw",
                musicInfo = buildJsonObject {
                    put("songmid", "228908")
                    put("name", "晴天")
                    put("singer", "周杰伦")
                },
                quality = "flac",
            )
            assertEquals("https://cdn.example.com/kw/228908?q=flac", url)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `request before inited still dispatches`() = runTest {
        // 官方语义里 initialize 失败即脚本不可用，但 dispatch 本身不应依赖 initialize 被调用过。
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load("fixture.js", inlineScript)
            val url = runtime.requestMusicUrl(
                platform = "kw",
                musicInfo = buildJsonObject { put("songmid", "1") },
                quality = "128k",
            )
            assertTrue(url.startsWith("https://cdn.example.com/kw/1"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `script without inited is rejected`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load(
                "no-init.js",
                """
                globalThis.lx.on('request', function () { return Promise.resolve('https://x/y.flac') })
                """.trimIndent(),
            )
            val error = assertFailsWith<LxProtocolException> { runtime.initialize() }
            assertTrue(error.message.orEmpty().contains("inited"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `script that never registers request fails on dispatch`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load(
                "no-handler.js",
                """
                const { EVENT_NAMES, send } = globalThis.lx
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['128k'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            assertFailsWith<LxScriptException> {
                runtime.requestMusicUrl("kw", buildJsonObject { put("songmid", "1") }, "128k")
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `busy loop is interrupted by the evaluation timeout`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            assertFailsWith<LxScriptException> {
                runtime.load(
                    "spin.js",
                    """
                    while (true) { }
                    """.trimIndent(),
                    timeoutMs = 400,
                )
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `lx request goes through the host and rejects private addresses`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load(
                "net.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                on(EVENT_NAMES.request, function (payload) {
                  return new Promise(function (resolve, reject) {
                    var target = payload.info.musicInfo.target
                    lx.request(target, { method: 'GET' }, function (err, resp, body) {
                      if (err) return reject(new Error(err))
                      resolve('https://cdn.example.com/' + resp.body.token + '.flac')
                    })
                  })
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['flac'] } }
                })
                """.trimIndent(),
                allowedHosts = setOf("api.example.com"),
            )
            runtime.initialize()

            // 公网地址：走宿主 Ktor，MockEngine 返回 token
            val ok = runtime.requestMusicUrl(
                "kw",
                buildJsonObject {
                    put("songmid", "1")
                    put("target", "https://api.example.com/token")
                },
                "flac",
            )
            assertEquals("https://cdn.example.com/abc123.flac", ok)

            // 私网地址：必须在宿主侧被拒，脚本拿不到响应
            val blocked = assertFailsWith<LxScriptException> {
                runtime.requestMusicUrl(
                    "kw",
                    buildJsonObject {
                        put("songmid", "2")
                        put("target", "http://192.168.1.1/admin")
                    },
                    "flac",
                )
            }
            assertTrue(
                blocked.message.orEmpty().contains("私网") || blocked.message.orEmpty().contains("拒绝"),
                "实际错误: ${blocked.message}",
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `cross-host redirect is rejected before the allowlist can be bypassed`() = runTest {
        // 目录只批准 api.example.com；上游 302 到 evil.example.net 必须被挡住，
        // 否则白名单等于没有——这正是设计文档要求拦截的跨域重定向。
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.host == "api.example.com") {
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "https://evil.example.net/steal"),
                    )
                } else {
                    respond("secret", HttpStatusCode.OK)
                }
            },
        )
        val runtime = LxScriptRuntime(client)
        try {
            runtime.load(
                "redirect.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                on(EVENT_NAMES.request, function () {
                  return new Promise(function (resolve, reject) {
                    lx.request('https://api.example.com/token', { method: 'GET' }, function (err, resp) {
                      if (err) return reject(new Error(err))
                      resolve('https://cdn.example.com/' + resp.body)
                    })
                  })
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['flac'] } }
                })
                """.trimIndent(),
                // 只批准 api.example.com：跳转目标不在白名单内，必须拒绝。
                allowedHosts = setOf("api.example.com"),
            )
            runtime.initialize()
            val error = assertFailsWith<LxScriptException> {
                runtime.requestMusicUrl("kw", buildJsonObject { put("songmid", "1") }, "flac")
            }
            assertTrue(
                error.message.orEmpty().contains("重定向"),
                "实际错误: ${error.message}",
            )
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `same-host redirect is followed`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/token" -> respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, "/v2/token"),
                    )
                    else -> respond("""{"token":"moved"}""", HttpStatusCode.OK)
                }
            },
        )
        val runtime = LxScriptRuntime(client)
        try {
            runtime.load(
                "redirect-ok.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                on(EVENT_NAMES.request, function () {
                  return new Promise(function (resolve, reject) {
                    lx.request('https://api.example.com/token', { method: 'GET' }, function (err, resp) {
                      if (err) return reject(new Error(err))
                      resolve('https://cdn.example.com/' + resp.body.token)
                    })
                  })
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['flac'] } }
                })
                """.trimIndent(),
                allowedHosts = setOf("api.example.com"),
            )
            runtime.initialize()
            val url = runtime.requestMusicUrl("kw", buildJsonObject { put("songmid", "1") }, "flac")
            assertEquals("https://cdn.example.com/moved", url)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `buffer helpers support hex utf8 and base64`() = runTest {
        val runtime = LxScriptRuntime(mockClient())
        try {
            runtime.load(
                "buffer.js",
                """
                const { EVENT_NAMES, on, send } = globalThis.lx
                on(EVENT_NAMES.request, function () {
                  var hexBuf = lx.utils.buffer.from('5a6b', 'hex')
                  var roundTrip = lx.utils.buffer.bufToString(hexBuf, 'hex')
                  var b64 = lx.utils.buffer.bufToString(lx.utils.buffer.from('hi', 'utf8'), 'base64')
                  var text = lx.utils.buffer.bufToString(lx.utils.buffer.from(b64, 'base64'), 'utf8')
                  return Promise.resolve('https://cdn.example.com/' + roundTrip + '/' + b64 + '/' + text)
                })
                send(EVENT_NAMES.inited, {
                  sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['flac'] } }
                })
                """.trimIndent(),
            )
            runtime.initialize()
            val url = runtime.requestMusicUrl("kw", buildJsonObject { put("songmid", "1") }, "flac")
            assertEquals("https://cdn.example.com/5a6b/aGk=/hi", url)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `end to end search then lx request resolves matching song identity`() = runTest {
        // 这个测试不再让脚本直接拼 URL：脚本真的调用 lx.request 打到"解析 API"，
        // 从 JSON 响应里取播放地址；并用 MockEngine 记录请求里携带的 songmid。
        // 目标：证明"搜索命中 → songmid → 解析请求 → 播放 URL"整条桥接链路身份一致。
        val songmid = "228908"
        val title = "晴天"
        val artist = "周杰伦"

        // 搜索端返回酷我真实结构的 JSON。
        val searchBody = """
            {"abslist":[
              {"MUSICRID":"MUSIC_$songmid","SONGNAME":"$title","ARTIST":"$artist","ALBUM":"叶惠美","DURATION":"269"}
            ]}
        """.trimIndent()
        val searcher = KuwoSongSearcher(
            HttpClient(MockEngine { respond(searchBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
        )
        val hits = searcher.search(title, "kw")
        assertEquals(1, hits.size)
        assertEquals(songmid, hits[0].songId)
        assertEquals(title, hits[0].rawFields["SONGNAME"])
        assertEquals(artist, hits[0].rawFields["ARTIST"])

        var resolvedSongId: String? = null
        val apiClient = HttpClient(MockEngine { request ->
            // 解析 API：读取脚本通过 lx.request 传来的 songmid，返回带播放地址与身份的 JSON。
            resolvedSongId = request.url.parameters["songmid"]
            assertEquals("api.example.com", request.url.host)
            respond(
                content = """{"code":200,"data":{"mid":"$resolvedSongId","url":"https://cdn.example.com/$resolvedSongId/flac.flac","name":"$title","singer":"$artist"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

        val script = """
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, function (payload) {
              var songmid = payload.info.musicInfo.songmid
              return new Promise(function (resolve, reject) {
                lx.request('https://api.example.com/v1/url?songmid=' + songmid, { method: 'GET' }, function (err, resp) {
                  if (err) return reject(new Error(err))
                  var data = resp.body.data
                  // 身份核验：解析响应里的 mid 必须和请求的一致，否则拒绝返回（防止错歌）
                  if (data.mid !== songmid) return reject(new Error('identity mismatch'))
                  resolve(data.url)
                })
              })
            })
            send(EVENT_NAMES.inited, {
              sources: { kw: { name: 'kw', type: 'music', actions: ['musicUrl'], qualitys: ['flac'] } }
            })
        """.trimIndent()

        val runtime = LxScriptRuntime(apiClient)
        try {
            runtime.load("realish.js", script, allowedHosts = setOf("api.example.com"))
            runtime.initialize()
            val musicInfo = buildJsonObject {
                put("songmid", songmid)
                put("name", title)
                put("singer", artist)
            }
            val url = runtime.requestMusicUrl("kw", musicInfo, "flac")

            // 全链路身份一致：搜索 ID == 解析请求 ID == URL 里的 ID == 脚本核验的 mid
            assertEquals(songmid, resolvedSongId)
            assertEquals("https://cdn.example.com/$songmid/flac.flac", url)
            assertTrue(url.contains(songmid), "播放 URL 必须来自被点选的那首歌")
        } finally {
            runtime.close()
        }
    }
}
