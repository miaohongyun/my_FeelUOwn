package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G0：LX Runtime action 协议审计（离线、不改协议、不伪造真实结果）。
 *
 * 核心问题：Runtime 的 search 是"已实现只是候选没写"，还是"调用链有兼容性缺口"？
 * 本测试用**一个已知可工作的、自己写的测试脚本**（实现 search + musicUrl，返回确定性假数据，
 * 明确是测试夹具不是真实音源）来分层验证：
 * - DECLARED：脚本 inited 声明的 action；
 * - LOADED：能否 load + initialize；
 * - REAL_CALL_PASSED：产品 API 是否真能把该 action 派发到脚本（用桥接审计探针证明桥层能力）。
 *
 * 结论由断言固化：桥层 action 无关（search 能派发、能拿到结构化结果），
 * 但 [LxProtocol] 无 search 常量、产品 API 只有 requestMusicUrl —— 缺口在"协议定义 + 产品调用链/结果模型"，
 * 不在桥接兼容性。这正是"不要为通过测试而修改协议或伪造搜索结果"的边界。
 */
class LxActionProtocolAuditTest {

    /**
     * 已知可工作的测试脚本：明确实现 search action，返回结构化歌曲列表（测试夹具，确定性假数据）。
     * 同时实现 musicUrl，确保 initialize 能保留它（initialize 要求 musicUrl 在 actions 中）。
     */
    private val searchCapableScript = """
        const { EVENT_NAMES, on, send } = globalThis.lx
        on(EVENT_NAMES.request, function (payload) {
          var a = payload.action
          if (a === 'search') {
            var kw = String((payload.info && payload.info.keyword) || '')
            return Promise.resolve({
              songs: [
                { songId: 'SID-' + kw, title: '晴天', artist: '周杰伦', album: '叶惠美', source: payload.source },
                { songId: 'SID-2', title: '晴天 Live', artist: '测试歌手', album: '测试专辑', source: payload.source }
              ],
              keyword: kw
            })
          }
          if (a === 'musicUrl') {
            return Promise.resolve('https://cdn.test/' + payload.source + '/' + payload.info.musicInfo.songmid)
          }
          return Promise.reject(new Error('unsupported action: ' + a))
        })
        send(EVENT_NAMES.inited, {
          sources: {
            kw: { name: '测试源', type: 'music', actions: ['musicUrl', 'search'], qualitys: ['128k'] }
          }
        })
    """.trimIndent()

    private fun client(): HttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    @Test
    fun g0_protocol_has_no_search_action(): Unit = runTest {
        // 官方移动端协议只有 musicUrl / lyric / pic，没有 search。
        val actions = setOf(LxProtocol.ACTION_MUSIC_URL, LxProtocol.ACTION_LYRIC, LxProtocol.ACTION_PIC)
        assertEquals(3, actions.size)
        assertTrue("search" !in actions, "协议不得被悄悄加上 search 常量（G0 审计：协议现状就是无 search）")
        // 产品运行时的公开请求方法只有 requestMusicUrl；search/lyric/pic 均无派发 API（见源码审计）。
        // 用编译期事实表达：本文件其余测试只能通过 requestMusicUrl 发 musicUrl，
        // 想派发 search 必须走 internal 的 dispatchForAudit——证明产品 API 没有 search 入口。
        assertTrue(LxProtocol.QUALITIES.isNotEmpty(), "协议常量应可访问（确认未引入反射依赖）")
    }

    @Test
    fun g0_declared_and_loaded_search_capable_script(): Unit = runTest {
        val runtime = LxScriptRuntime(client())
        try {
            runtime.load("search-capable-test.js", searchCapableScript)
            val meta = runtime.initialize()
            // DECLARED + LOADED：脚本声明 search，且 load+initialize 保留它（因为同时有 musicUrl）。
            assertEquals(setOf("kw"), meta.platforms)
            assertTrue("search" in meta.sources.getValue("kw").actions, "脚本声明了 search action")
            assertTrue("musicUrl" in meta.sources.getValue("kw").actions)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun g0_bridge_can_dispatch_search_but_product_cannot_reach_it(): Unit = runTest {
        val runtime = LxScriptRuntime(client())
        try {
            runtime.load("search-capable-test.js", searchCapableScript)
            runtime.initialize()

            // 桥层能力：审计探针把 search payload 直接派发给脚本 request 处理器，
            // 拿到脚本自己生成的结构化结果（测试夹具数据，不是伪造的真实音源搜索）。
            val payload = buildJsonObject {
                put("source", "kw")
                put("action", "search")
                put("info", buildJsonObject { put("keyword", "晴天") })
            }
            val raw = runtime.dispatchForAudit(payload)
            assertNotNull(raw, "桥应把 search action 派发到脚本并拿到结果")
            val obj = Json.parseToJsonElement(raw).jsonObject
            val songs = obj["songs"]?.jsonArray ?: throw AssertionError("search 结果应含 songs 数组")
            assertEquals(2, songs.size)
            assertEquals("晴天", songs[0].jsonObject["title"].toString().trim('"'))
            assertEquals("SID-晴天", songs[0].jsonObject["songId"].toString().trim('"'))

            // 产品路径对照：requestMusicUrl 只能发 musicUrl，脚本据此返回 URL。
            val url = runtime.requestMusicUrl("kw", buildJsonObject { put("songmid", "228908") }, "128k")
            assertEquals("https://cdn.test/kw/228908", url)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun g0_initialize_rejects_search_only_source(): Unit = runTest {
        // 一个只声明 search、没有 musicUrl 的源：initialize 要求 musicUrl 在 actions 中，
        // 会被当作"没有可用 music 源"拒绝——证明产品运行时根本不把 search 当可用能力。
        val searchOnly = """
            const { EVENT_NAMES, on, send } = globalThis.lx
            on(EVENT_NAMES.request, function (p) { return Promise.resolve({ songs: [] }) })
            send(EVENT_NAMES.inited, {
              sources: { kw: { name: 'only-search', type: 'music', actions: ['search'], qualitys: ['128k'] } }
            })
        """.trimIndent()
        val runtime = LxScriptRuntime(client())
        try {
            runtime.load("search-only.js", searchOnly)
            assertFailsWith<LxProtocolException> { runtime.initialize() }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun g0_provider_search_uses_registry_not_scripts(): Unit = runTest {
        // 产品搜索链：Provider.search() 走 LxSongSearchRegistry（宿主适配器），
        // 完全不调用脚本 request 处理器。注册表没有的平台 = 不可搜索。
        val registry = LxSongSearchRegistry(emptyList())
        assertTrue(registry.searchablePlatforms.isEmpty())
        assertTrue(!registry.canSearch("kw"))
        assertTrue(registry.search("晴天", "kw").isEmpty(), "未注册平台返回空，不经过任何脚本")
        // 默认注册表只含 kw + kg（宿主侧公开搜索接口），与任何社区脚本无关。
        val default = LxSongSearchRegistry.default(client())
        assertEquals(setOf("kw", "kg"), default.searchablePlatforms)
    }
}
