package org.feeluown.mobile.provider.lx

import kotlinx.coroutines.CancellationException

/**
 * 多平台搜索注册表：每个平台一个独立的 [LxSongSearcher] 适配器。
 *
 * 为什么必须有这一层：LX 协议本身没有搜索，搜索是客户端按平台做的。
 * "脚本在 inited 里声明支持 kg/tx/wy/mg"只代表它能**解析**这些平台的 ID，
 * 不代表播放器已经具备这些平台的搜索能力。注册表明确回答"哪些平台真能搜"，
 * 未注册的平台返回空结果并在 [searchablePlatforms] 中缺席，UI 据此标记"不可搜索"。
 *
 * 平台键严格用 LX 官方值：kw / kg / tx / wy / mg（local 是本地曲库，不在 LX 搜索范围）。
 */
class LxSongSearchRegistry(
    searchers: Collection<LxSongSearcher> = emptyList(),
) {
    private val byPlatform = linkedMapOf<String, LxSongSearcher>()

    init {
        for (searcher in searchers) {
            for (platform in searcher.supportedPlatforms) {
                // 同一平台后注册者覆盖前者，便于测试注入与将来替换适配器。
                byPlatform[platform] = searcher
            }
        }
    }

    /** 真正具备搜索能力的平台集合；脚本声明了但这里没有的，就是"不可搜索"。 */
    val searchablePlatforms: Set<String> get() = byPlatform.keys.toSet()

    fun canSearch(platform: String): Boolean = platform in byPlatform

    suspend fun search(keyword: String, platform: String, limit: Int = 20): List<LxSongHit> {
        val searcher = byPlatform[platform] ?: return emptyList()
        return try {
            searcher.search(keyword, platform, limit)
        } catch (e: CancellationException) {
            throw e // 取消必须上抛，不能被当成"这个平台搜不到"
        } catch (e: Exception) {
            // 单个平台失败不影响其它平台：Provider 会继续遍历下一个平台。
            emptyList()
        }
    }

    companion object {
        /**
         * 默认注册表：kw + kg。
         *
         * kg 于 2026-11-19 做了真实接口探测：公开搜索端点 `song_search_v2` 返回 HTTP 200
         * 与真实《晴天》数据（FileHash/SongName/SingerName/Duration 字段齐备），因此从
         * "仅模拟"提升为默认注册。真实接口探测只覆盖**搜索**，`REAL_SEARCH` 与脚本的
         * `REAL_URL_RESOLVE`/`REAL_AUDIO_RESPONSE` 是不同层级，不得混为一谈。
         *
         * tx/wy/mg 仍只有真实响应结构的模拟解析测试，未做真实接口验证，默认不注册；
         * 要启用由部署配置显式注册并注明验证等级。
         */
        fun default(client: io.ktor.client.HttpClient): LxSongSearchRegistry =
            LxSongSearchRegistry(listOf(KuwoSongSearcher(client), KugouSongSearcher(client)))
    }
}
