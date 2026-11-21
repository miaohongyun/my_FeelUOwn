package org.feeluown.mobile.nucleus

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.desktop.DesktopMpvBackendEvent
import org.feeluown.mobile.desktop.DesktopMpvPlaybackEngine
import org.feeluown.mobile.desktop.createDesktopFfmMpvNativeApi
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient
import org.feeluown.mobile.provider.jamendo.JamendoMusicProvider
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 阶段 K1.1/K1.2：Jamendo 正式播放链路的真实解码验收（opt-in，默认不跑）。
 *
 * 启用：FUOE_K1_REAL_PLAYBACK=1；client_id 默认内置公开值，可用 JAMENDO_CLIENT_ID 覆盖。
 * 音频输出：默认 ao=null（无声解码，验证解码器与位置推进，不代表人工听音）。
 *
 * 链路：正式 JamendoMusicProvider.search（真实网络）→ resolve（trackById 重取时效 URL）
 * → 生产 DesktopMpvPlaybackEngine + 生产 FfmMpvBackend + native libmpv → 真实解码 →
 * 记录 load/playing/position/end/error → 身份关联核验 → pause/resume/stop → close。
 *
 * 不直接把测试 URL 喂给 libmpv；URL 必须来自正式 Provider 的 resolve。
 * 完整 App UI 入口（搜索页点击→PlaybackStartCoordinator）依赖 GUI/AppScope，本测试在其下层
 * 使用正式播放引擎与正式后端，报告中如实标注该边界，不冒充 UI 端到端。
 */
class JamendoRealPlaybackTest {

    /** 证据同时写入文件，便于报告取证（Gradle 默认不显示测试 stdout）。 */
    private fun logEvidence(line: String) {
        println(line)
        runCatching {
            val f = java.io.File("build/k1-evidence.txt")
            f.parentFile?.mkdirs()
            f.appendText(line + System.lineSeparator())
        }
    }

    private val enabled = System.getenv("FUOE_K1_REAL_PLAYBACK") == "1"
    private val clientId = System.getenv("JAMENDO_CLIENT_ID")?.takeIf { it.isNotBlank() } ?: "5ace5988"
    private val playMs = (System.getenv("FUOE_K1_PLAY_MS")?.toLongOrNull() ?: 30_000L).coerceAtLeast(1_000L)
    private val overallTimeoutMs = playMs + 90_000L

    private data class Evidence(
        val providerId: String,
        val originalId: String,
        val trackId: String,
        val title: String,
        val artist: String,
        val urlHostAndTrackId: String,
        val events: List<String>,
        val statuses: List<String>,
        val maxPositionMs: Long,
        val identityOk: Boolean,
    )

    @Test
    fun jamendoRealPlaybackViaProductionEngine(): Unit = runBlocking {
        if (!enabled) {
            logEvidence("K1_REAL_PLAYBACK=NOT_RUN reason=设置 FUOE_K1_REAL_PLAYBACK=1 启用（常规 CI 不依赖外网/音频设备）")
            return@runBlocking
        }
        // 让 native bridge 以 ao=null 运行：无音频设备时仍真实解封装/解码并推进 time-pos。
        System.setProperty("fuoevolve.libmpv.ao", "null")

        val http: HttpClient = createSharedProviderHttpClient()
        val provider = JamendoMusicProvider(
            providerCredentials = InMemoryProviderCredentialStore(),
            clientIdProvider = { clientId },
            httpClient = http,
        )
        val events = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        try {
            // ---- REAL_SEARCH：正式 Provider 真实搜索 ----
            // Jamendo 公开 API 偶发返回空结果（K0/J2 已记录同一关键词有时 count=0），有限重试并
            // 轮换关键词以区分"端点不可用"与"偶发空结果"；不改变请求真实性、不用 Mock。
            var track: org.feeluown.mobile.MusicTrack? = null
            var lastFailure: Any? = null
            val keywords = listOf("ambient", "music", "love", "rock", "piano", "loop")
            for (attempt in 1..6) {
                val kw = keywords[(attempt - 1) % keywords.size]
                val r = provider.search(kw)
                lastFailure = r.failure
                logEvidence("K1_REAL_SEARCH_ATTEMPT kw=$kw count=${r.tracks.size} failure=${r.failure}")
                track = r.tracks.firstOrNull()
                if (track != null) break
                delay(400)
            }
            val chosen = track ?: throw AssertionError("K1 REAL_SEARCH 失败：6 次真实搜索均无结果（lastFailure=$lastFailure）")
            logEvidence("K1_REAL_SEARCH providerId=${chosen.providerId} originalId=${chosen.originalId} id=${chosen.id} title=${chosen.title} artist=${chosen.artists}")

            // ---- REAL_URL_RESOLVE：正式 Provider resolve → trackById 重取时效 URL ----
            // Jamendo 的 /tracks/?id= 端点对部分请求偶发返回空（K0 ProductionPathTest 也重试 3 次），
            // 这里对正式 resolve 做有限重试以拿到时效 URL；每次都是真实网络请求，不用 Mock。
            // 注意：生产 resolve 本身只尝试一次——该偶发失败是产品风险，报告中如实记录。
            var payload: PlaybackPayload? = null
            for (attempt in 1..6) {
                payload = provider.resolve(chosen, "")
                logEvidence("K1_REAL_URL_RESOLVE_ATTEMPT attempt=$attempt ok=${payload != null}")
                if (payload != null) break
                delay(400)
            }
            val fresh = payload ?: throw AssertionError("K1 REAL_URL_RESOLVE 失败：6 次 resolve 均返回 null（trackById 端点偶发空结果）")
            assertTrue(fresh.url.startsWith("https://"), "播放 URL 必须为 https")
            val uri = URI(fresh.url)
            val host = uri.host
            val urlTrackId = Regex("[?&]trackid=([^&]+)").find(fresh.url)?.groupValues?.get(1)
            logEvidence("K1_REAL_URL_RESOLVE host=$host trackid=$urlTrackId（URL 脱敏：仅 host+trackid）")

            // ---- 身份关联（K1.2）：Provider 身份 / MusicTrack ID / 重取 URL trackid 一致 ----
            // Jamendo 的 track() helper 生成 id="jamendo:<原始id>"；originalId 仅在智能替换场景填充，
            // 普通搜索不一定有，因此以 id 的冒号后段作为原始来源 ID（K0 已确认模型如此）。
            assertTrue(chosen.id.startsWith("jamendo:"), "MusicTrack id 必须带 jamendo 前缀：${chosen.id}")
            val originalId = chosen.originalId?.takeIf { it.isNotBlank() }
                ?: chosen.id.substringAfter("jamendo:", "")
            assertTrue(originalId.isNotBlank(), "必须能从 MusicTrack 解析出 Jamendo 原始来源 ID")
            assertEquals(originalId, urlTrackId, "搜索原始 ID 必须等于重取 URL 的 trackid")
            assertEquals("jamendo", chosen.providerId?.substringBefore(":") ?: chosen.source, "Provider 身份必须为 jamendo")

            // 用带事件记录的 listener 包装生产后端工厂（事件不改变后端行为），
            // 再用正式 DesktopMpvPlaybackEngine 驱动，记录 load/playing/end/error 真实事件与状态。
            val recordedEngine = DesktopMpvPlaybackEngine { listener ->
                FfmMpvBackend({ ev ->
                    if (ev !is DesktopMpvBackendEvent.Property) events.add(describeEvent(ev))
                    listener(ev)
                }, createDesktopFfmMpvNativeApi())
            }
            val collect = launch {
                recordedEngine.state.collect { st ->
                    if (statuses.lastOrNull() != st.status.name) statuses.add(st.status.name)
                }
            }
            try {
                recordedEngine.prepareLoading(chosen)
                // 正式引擎接受"逻辑曲目 + 已解析曲目 + payload"，与生产 coordinator 调用一致。
                recordedEngine.playResolved(chosen, chosen, fresh)

                // 等待进入 Playing。
                val reachedPlaying = withTimeoutOrNull(30_000L) {
                    while (recordedEngine.state.first().status != PlayerStatus.Playing) delay(200)
                    true
                } == true
                assertTrue(reachedPlaying, "K1 REAL_PLAYBACK 失败：30s 内未进入 Playing（statuses=$statuses events=$events error=${recordedEngine.state.first().errorMessage}）")

                // ---- 连续播放至少 30 秒，位置持续推进 ----
                val start = recordedEngine.state.first().positionMs
                var maxPos = start
                val deadline = System.currentTimeMillis() + overallTimeoutMs
                while (System.currentTimeMillis() < deadline && maxPos - start < playMs) {
                    delay(500)
                    val st = recordedEngine.state.first()
                    if (st.status == PlayerStatus.Error) break
                    if (st.positionMs > maxPos) maxPos = st.positionMs
                }
                val advanced = maxPos - start
                logEvidence("K1_REAL_PLAYBACK status=${recordedEngine.state.first().status} start=$start max=$maxPos advanced=${advanced}ms")
                assertTrue(advanced >= playMs, "K1 REAL_PLAYBACK 失败：位置仅推进 ${advanced}ms，要求 >= ${playMs}ms")
                assertTrue(recordedEngine.state.first().status != PlayerStatus.Error, "播放期间进入 Error：${recordedEngine.state.first().errorMessage}")

                // ---- 暂停/继续/停止 ----
                recordedEngine.pause()
                val pausedPos = recordedEngine.state.first().positionMs
                delay(400)
                val stillPaused = recordedEngine.state.first().positionMs
                assertEquals(PlayerStatus.Paused, recordedEngine.state.first().status, "pause 后应为 Paused")
                assertTrue(kotlin.math.abs(stillPaused - pausedPos) < 600, "暂停期间位置不应明显推进：$pausedPos -> $stillPaused")
                recordedEngine.resume()
                assertTrue(withTimeoutOrNull(10_000L) {
                    while (recordedEngine.state.first().status != PlayerStatus.Playing) delay(200); true
                } == true, "resume 后应回到 Playing")
                recordedEngine.stop()
                logEvidence("K1_CONTROL pause/resume/stop OK；events=$events statuses=$statuses")

                val evidence = Evidence(
                    providerId = chosen.providerId ?: chosen.source,
                    originalId = originalId,
                    trackId = chosen.id,
                    title = chosen.title,
                    artist = chosen.artists,
                    urlHostAndTrackId = "$host?trackid=$urlTrackId",
                    events = events.toList(),
                    statuses = statuses.toList(),
                    maxPositionMs = maxPos,
                    identityOk = true,
                )
                logEvidence("K1_EVIDENCE=${evidence}")
                logEvidence("K1_IDENTITY=PASS（播放会话与 Jamendo 曲目 ID 关联一致；非声学指纹核验）")
                logEvidence("K1_ACOUSTIC_IDENTITY=NOT_RUN（未实现音频指纹交叉验证）")
                logEvidence("K1_HUMAN_LISTENING=NOT_RUN（ao=null 无声解码，环境无扬声器/耳机）")
            } finally {
                collect.cancel()
                recordedEngine.close()
            }
        } finally {
            http.close()
        }
    }

    private fun describeEvent(ev: DesktopMpvBackendEvent): String = when (ev) {
        is DesktopMpvBackendEvent.StartFile -> "StartFile(entry=${ev.playlistEntryId})"
        is DesktopMpvBackendEvent.FileLoaded -> "FileLoaded(path=${ev.path.substringBefore('?')})"
        is DesktopMpvBackendEvent.PlaybackRestart -> "PlaybackRestart"
        is DesktopMpvBackendEvent.EndFile -> "EndFile(reason=${ev.reason})"
        is DesktopMpvBackendEvent.Failure -> "Failure(${ev.throwable.message})"
        is DesktopMpvBackendEvent.Property -> "Property(${ev.name}=${ev.value})"
    }
}
