package org.feeluown.mobile.provider.lx

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.sourceupdate.crypto.Sha256
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G1：声称实现 `search` 的候选 —— 静态审查 + 隔离 QuickJS 加载验证。
 *
 * 严格边界：
 * - 只加载 E2 静态无 BLOCK 命中、且来源/许可已登记的候选；feichangdao 为 QUARANTINE，禁止加载执行；
 * - 空 allowedHosts（fail-closed），全程 MockEngine，不发任何真实网络请求；
 * - 验证"脚本 inited 是否声明 search"（DECLARED）与"能否 load+initialize"（LOADED）；
 * - search 的真实调用结果属于 G3，本测试不把 MockEngine 结果升级为 REAL_SEARCH_PASSED；
 * - 脚本原文 SHA 与取证台账一致，防止"审的是 A、跑的是 B"。
 */
class PhaseG1SearchCandidateTest {

    private val scriptDir = File("src/desktopTest/resources/third-party")

    private data class Candidate(
        val id: String,
        val file: String,
        val sha256: String,
        val license: String,
        val declaresSearch: Boolean,
        val expectedPlatforms: Set<String>,
    )

    private val candidates = listOf(
        Candidate(
            "suyin", "g1-suyin.js", "d10c9c28823b5c2ce4e3fef3b25b8ff16d2d165bd0883e2b866900ef7c0dc0ee",
            "NONE(pdone 无 LICENSE)", true, setOf("qq", "wy", "kw", "mg"),
        ),
        Candidate(
            "huanyin", "g1-huanyin.js", "4a81129240ecae3ebf6b42f28c73e2d37bbcb6fccac9a73a401d90cdd36bee5f",
            "NONE(pdone 无 LICENSE)", true, setOf("mg"),
        ),
        Candidate(
            "ciallo", "g1-ciallo.js", "fd16333bcf630f2fe92142e2cb6c8d7480331180ea5c4249a3e4080f5b95ac83",
            "Apache-2.0(guoyue2010)", true, setOf("wy"),
        ),
    )

    private fun offlineClient(): HttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    @Test
    fun g1_script_sha_matches_forensics(): Unit = runBlocking {
        candidates.forEach { c ->
            val source = File(scriptDir, c.file).readText()
            assertEquals(c.sha256, Sha256.hex(source.encodeToByteArray()), "${c.id} SHA 与 G1 取证一致")
        }
    }

    @Test
    fun g1_declared_and_loaded_in_isolation(): Unit = runBlocking {
        val results = mutableListOf<String>()
        candidates.forEach { c ->
            val source = File(scriptDir, c.file).readText()
            val client = offlineClient()
            val runtime = LxScriptRuntime(client)
            try {
                runtime.load(c.id, source, allowedHosts = emptySet()) // fail-closed
                val meta = runtime.initialize()
                val declares = meta.sources.values.any { "search" in it.actions }
                results.add("${c.id}: LOADED platforms=${meta.platforms} declaresSearch=$declares license=${c.license}")
                assertEquals(c.declaresSearch, declares, "${c.id} 声明 search 的能力矩阵应如实")
            } catch (e: Exception) {
                // 如实记录加载失败，不掩盖、不升级。
                results.add("${c.id}: LOAD_FAILED ${e.message?.take(80)} license=${c.license}")
            } finally {
                runtime.close()
                client.close()
            }
        }
        File("build/phase-g1-isolated-load.txt").apply {
            parentFile.mkdirs()
            writeText(results.joinToString(System.lineSeparator()))
        }
        // 至少两个候选应能在隔离环境完成 load+initialize（DECLARED+LOADED）。
        assertTrue(results.count { it.contains("LOADED") } >= 2, "隔离加载结果：${results.joinToString(" | ")}")
    }

    @Test
    fun g1_quarantined_feichangdao_never_executed(): Unit = runBlocking {
        // 非常刀声明了 search（actions 含 'musicUrl','search','lyric'），但为 QUARANTINE
        // （裸IP 175.27.166.236 + t.me + 多个第三方 API），禁止进入任何可执行 harness。
        val names = scriptDir.list()?.toSet() ?: emptySet()
        assertTrue(names.none { it == "g1-feichangdao.js" }, "QUARANTINE 脚本不得作为 G1 可执行资源")
        // 其 SHA 与 E2 台账一致这一事实已在阶段 E 记录，本阶段不重复执行。
    }
}
