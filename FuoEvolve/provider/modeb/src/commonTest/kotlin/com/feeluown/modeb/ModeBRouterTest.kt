package com.feeluown.modeb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class InMemoryModeBStorage : ModeBStorage {
    val map = mutableMapOf<String, String>()
    override suspend fun readText(key: String): String? = map[key]
    override suspend fun writeText(key: String, content: String) {
        map[key] = content
    }
}

class ModeBRouterTest {

    // Ktor MockEngine runs on Dispatchers.Default; runTest virtual time fast-forwards withTimeout.
    // Use real-time runBlocking with an explicit Unit return so JUnit4 sees a void test method.
    private fun blockingTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { block() }


    private fun defaultJson(
        remoteUrl: String = "https://mock.local/config.json",
        timeoutMs: Long = 500,
    ) = """
        {
          "schemaVersion": 1,
          "configVersion": "1.0.0",
          "manualOverride": null,
          "remoteConfigUrls": ["$remoteUrl"],
          "healthPolicy": {
            "failureThreshold": 3,
            "cooldownSeconds": 600,
            "requestTimeoutMillis": $timeoutMs,
            "probeOnTrip": false
          },
          "sources": [
            {
              "id": "src_a",
              "name": "主源 A",
              "apiBase": "https://mock.local/src_a",
              "urlPath": "/url",
              "searchPath": "/search",
              "params": { "quality": "lossless" },
              "urlField": "data.url",
              "priority": 100,
              "enabled": true
            },
            {
              "id": "src_b",
              "name": "备用源 B",
              "apiBase": "https://mock.local/src_b",
              "urlPath": "/url",
              "params": { "quality": "lossless" },
              "urlField": "data.url",
              "priority": 80,
              "enabled": true
            },
            {
              "id": "src_c",
              "name": "禁用源 C",
              "apiBase": "https://mock.local/src_c",
              "urlPath": "/url",
              "params": {},
              "urlField": "data.url",
              "priority": 50,
              "enabled": false
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `1_cache_first_startup_never_blocked_by_slow_remote`() = blockingTest {
        val storage = InMemoryModeBStorage()
        var now = 1_000_000L
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/config.json" -> {
                    delay(2000) // 超过 500ms 超时
                    respond("{}", HttpStatusCode.OK)
                }
                "/src_a/url" -> respond(
                    """{"data":{"url":"https://cdn.test/a.flac"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val router = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { now })

        val cfg = router.initialize()
        assertEquals("1.0.0", cfg.configVersion)

        val job = router.startupSyncAsync(this)
        val res = router.resolvePlayUrl(mapOf("id" to "101"))
        assertEquals("src_a", res.sourceId)
        assertEquals("https://cdn.test/a.flac", res.url)
        job.join()
    }

    @Test
    fun `2_remote_sync_rejects_broken_json_and_updates_valid_config`() = blockingTest {
        val storage = InMemoryModeBStorage()
        var remoteBody = """{"schemaVersion":1,"configVersion":"bad","sources":[{"id":"x"}]}"""
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/config.json" -> respond(
                    remoteBody,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/src_b/url" -> respond(
                    """{"data":{"url":"https://cdn.test/b_promoted.flac"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("err", HttpStatusCode.InternalServerError)
            }
        }
        // 本用例验证「坏 JSON 被校验拒绝」语义，不是超时边界；放宽超时避免全量并行时
        // JVM 冷启动导致 MockEngine 首包超过默认 500ms 而误走到「拉取超时」分支。
        // 超时边界由用例 1（delay(2000)）单独覆盖。
        val router = ModeBRouter(HttpClient(engine), storage, defaultJson(timeoutMs = 3000L), nowMillis = { 1000L })

        val badSync = router.syncRemoteConfig()
        assertFalse(badSync.updated)
        assertTrue("校验失败" in badSync.message)
        assertEquals("1.0.0", router.currentConfig().configVersion)

        // 推送合法配置：将 src_b 提升到 priority 200（同样放宽超时，理由见上）
        remoteBody = defaultJson(timeoutMs = 3000L)
            .replace(""""configVersion": "1.0.0"""", """"configVersion": "1.0.2"""")
            .replace(""""priority": 80""", """"priority": 200""")
        val goodSync = router.syncRemoteConfig()
        assertTrue(goodSync.updated)
        assertEquals("1.0.2", router.currentConfig().configVersion)

        val res = router.resolvePlayUrl(mapOf("id" to "202"))
        assertEquals("src_b", res.sourceId)
    }

    @Test
    fun `3_lazy_check_never_pings_backup_sources`() = blockingTest {
        val storage = InMemoryModeBStorage()
        val calls = mutableMapOf("src_a" to 0, "src_b" to 0, "src_c" to 0)
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.startsWith("/src_a") -> {
                    calls["src_a"] = calls["src_a"]!! + 1
                    respond("""{"data":{"url":"https://cdn.test/a.flac"}}""", HttpStatusCode.OK)
                }
                path.startsWith("/src_b") -> {
                    calls["src_b"] = calls["src_b"]!! + 1
                    respond("""{"data":{"url":"https://cdn.test/b.flac"}}""", HttpStatusCode.OK)
                }
                else -> {
                    calls["src_c"] = calls["src_c"]!! + 1
                    respond("""{"data":{"url":"https://cdn.test/c.flac"}}""", HttpStatusCode.OK)
                }
            }
        }
        val router = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { 1000L })
        router.resolvePlayUrl(mapOf("id" to "1"))
        assertEquals(1, calls["src_a"])
        assertEquals(0, calls["src_b"])
        assertEquals(0, calls["src_c"])
    }

    @Test
    fun `4_single_track_404_does_not_trip_circuit_breaker`() = blockingTest {
        val storage = InMemoryModeBStorage()
        var srcAStatus = HttpStatusCode.NotFound
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/src_a/url" -> if (srcAStatus == HttpStatusCode.OK) {
                    respond("""{"data":{"url":"https://cdn.test/a.flac"}}""", HttpStatusCode.OK)
                } else {
                    respond("not found", HttpStatusCode.NotFound)
                }
                "/src_b/url" -> respond("""{"data":{"url":"https://cdn.test/b.flac"}}""", HttpStatusCode.OK)
                else -> respond("err", HttpStatusCode.InternalServerError)
            }
        }
        val router = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { 1000L })
        repeat(4) {
            val res = router.resolvePlayUrl(mapOf("id" to "404song"))
            assertEquals("src_b", res.sourceId)
        }
        assertTrue(router.breaker.snapshot().isEmpty())

        srcAStatus = HttpStatusCode.OK
        val okRes = router.resolvePlayUrl(mapOf("id" to "normal"))
        assertEquals("src_a", okRes.sourceId)
    }

    @Test
    fun `5_circuit_breaker_persists_across_restart_and_recovers_on_half_open`() = blockingTest {
        val storage = InMemoryModeBStorage()
        var now = 1_000_000L
        var srcACalls = 0
        var srcAHealthy = false

        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/src_a/url" -> {
                    srcACalls++
                    if (srcAHealthy) {
                        respond("""{"data":{"url":"https://cdn.test/a_ok.flac"}}""", HttpStatusCode.OK)
                    } else {
                        respond("down", HttpStatusCode.InternalServerError)
                    }
                }
                "/src_b/url" -> respond("""{"data":{"url":"https://cdn.test/b_ok.flac"}}""", HttpStatusCode.OK)
                else -> respond("err", HttpStatusCode.NotFound)
            }
        }

        val router1 = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { now })
        repeat(3) {
            val res = router1.resolvePlayUrl(mapOf("id" to "s$it"))
            assertEquals("src_b", res.sourceId)
        }
        assertEquals(3, srcACalls)

        // 冷却期内第 4 次请求跳过 src_a
        router1.resolvePlayUrl(mapOf("id" to "s3"))
        assertEquals(3, srcACalls)

        // 模拟 App 重启（新建实例但共享 storage），依然记得 src_a 在冷却中
        val router2 = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { now })
        router2.resolvePlayUrl(mapOf("id" to "s4"))
        assertEquals(3, srcACalls)

        // 推进 601 秒并恢复 src_a -> HALF_OPEN 单次试探成功恢复 CLOSED
        now += 601_000L
        srcAHealthy = true
        val recovered = router2.resolvePlayUrl(mapOf("id" to "s5"))
        assertEquals("src_a", recovered.sourceId)
        assertEquals(4, srcACalls)
    }

    @Test
    fun `6_all_sources_down_raises_explicit_error_and_manual_override_works`() = blockingTest {
        val storage = InMemoryModeBStorage()
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/src_c/url" -> respond("""{"data":{"url":"https://cdn.test/c_debug.flac"}}""", HttpStatusCode.OK)
                else -> respond("down", HttpStatusCode.ServiceUnavailable)
            }
        }
        val router = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { 1000L })

        assertFailsWith<AllSourcesDownException> {
            router.resolvePlayUrl(mapOf("id" to "999"))
        }

        router.setManualOverride("src_c")
        val debugRes = router.resolvePlayUrl(mapOf("id" to "debug"))
        assertEquals("src_c", debugRes.sourceId)

        assertFailsWith<SourceConfigException> {
            router.setManualOverride("not_exists")
        }
    }

    @Test
    fun `7_search_tracks_uses_pure_data_mapping`() = blockingTest {
        val storage = InMemoryModeBStorage()
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/src_a/search" -> respond(
                    """{"data":[{"id":"t1","title":"晴天","artist":"周杰伦"}]}""",
                    HttpStatusCode.OK,
                )
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val router = ModeBRouter(HttpClient(engine), storage, defaultJson(), nowMillis = { 1000L })
        val hits = router.searchTracks("晴天")
        assertEquals(1, hits.size)
        assertEquals("t1", hits[0].trackId)
        assertEquals("晴天", hits[0].title)
    }

    @Test
    fun `8_path_id_placeholder_replaced_and_removed_from_query`() = blockingTest {
        val storage = InMemoryModeBStorage()
        var seenUrl = ""
        val engine = MockEngine { req ->
            seenUrl = req.url.toString()
            respond("""{"url":"https://cdn.test/audio.flac"}""", HttpStatusCode.OK)
        }
        val json = """
            {
              "schemaVersion": 1, "configVersion": "1.0.0", "manualOverride": null,
              "remoteConfigUrls": [],
              "healthPolicy": {"failureThreshold":3,"cooldownSeconds":600,"requestTimeoutMillis":2000,"probeOnTrip":false},
              "sources": [
                {"id":"path_src","name":"路径源","apiBase":"https://mock.local","urlPath":"/v1/audio/{id}/",
                 "params":{"app_name":"FuoEvolve"},"urlField":"url","priority":100,"enabled":true}
              ]
            }
        """.trimIndent()
        val router = ModeBRouter(HttpClient(engine), storage, json, nowMillis = { 1000L })
        val res = router.resolvePlayUrl(mapOf("id" to "abc123"))
        assertEquals("https://cdn.test/audio.flac", res.url)
        assertTrue("/v1/audio/abc123/" in seenUrl, "路径占位符应被 id 替换: $seenUrl")
        assertTrue("id=" !in seenUrl, "id 不应再出现在查询串: $seenUrl")
        assertTrue("app_name=FuoEvolve" in seenUrl, "其它 params 应保留: $seenUrl")
    }
}
