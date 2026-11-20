package org.feeluown.mobile.provider.musicfree

import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.provider.musicfree.MusicFreePluginPolicy.PolicyDecision

/**
 * 阶段 G2：MusicFree → FuoEvolve 协议适配层（纯函数，不执行插件、不发网络请求）。
 *
 * 职责边界：
 * - 把 MusicFree 的 `{isEnd,data[]}` / `IMediaSourceResult` 数据形状映射成内部模型；
 * - 每次映射都经过 [MusicFreePluginPolicy] 授权门禁与 URL 策略校验；
 * - 全程**不执行任何 CommonJS 插件代码、不下载音频、不写凭据**；
 * - 结果以 [AdaptResult] 显式记录等级与拒绝原因，便于 UI/测试诚实区分"能解析"与"能播放"。
 *
 * 这不是一个已注册的 Provider：它不挂到 ProviderComposition，不出现在默认音源，
 * 不产生自动安装。真正的端到端执行（G3）需另行人工审查 + 受控端点授权。
 */
object MusicFreeAdapter {

    const val PROVIDER_ID = "musicfree-adapter"
    const val TAG = "mf-adapter"

    /** 搜索结果映射；每条音乐条目的 artwork 若非授权域名则置空（不因此丢弃整条）。 */
    fun adaptSearch(
        record: MusicFreePluginRecord,
        decision: PolicyDecision,
        json: String,
        type: MusicFreeSearchType,
    ): AdaptResult<List<MusicFreeMusicItem>> {
        val precheck = gate(record, decision, MusicFreeCapability.SEARCH)
        if (precheck != null) return AdaptResult.Denied(precheck)
        val parsed = parseMusicFreeSearch(json, type)
            .getOrElse { return AdaptResult.Denied((it as? MusicFreeParseError)?.reason ?: "搜索解析失败") }
        // 说明：适配层只做数据映射与门禁；真正的搜索网络调用属于 G3，本阶段不发起。
        return AdaptResult.Mapped(parsed.items, "DECLARED+MAPPED（未做真实搜索调用，未验证真实播放）")
    }

    /** 播放地址映射：URL 必须通过候选级网络策略，否则拒绝，绝不返回未授权地址。 */
    fun adaptMediaSource(
        record: MusicFreePluginRecord,
        decision: PolicyDecision,
        json: String,
        title: String,
        artists: String,
        album: String,
    ): AdaptResult<PlaybackPayload> {
        val precheck = gate(record, decision, MusicFreeCapability.MEDIA_SOURCE)
        if (precheck != null) return AdaptResult.Denied(precheck)
        val source = parseMusicFreeMediaSource(json)
            .getOrElse { return AdaptResult.Denied((it as? MusicFreeParseError)?.reason ?: "URL 解析失败") }
        val reject = MusicFreePluginPolicy.rejectionReason(decision, source.url)
        if (reject != null) return AdaptResult.Denied("播放 URL 被候选级网络策略拒绝：$reject")
        val payload = PlaybackPayload(
            url = source.url,
            title = title,
            artists = artists,
            album = album,
            source = PROVIDER_ID,
            headers = source.headers,
            coverUrl = null,
            audioQuality = source.quality?.wire,
            providerName = record.name,
        )
        return AdaptResult.Mapped(payload, "REAL_URL_RESOLVE 候选（仅数据映射 + URL 策略校验；未验证音频响应/真实播放）")
    }

    /** 歌词映射（无网络）。 */
    fun adaptLyric(json: String): AdaptResult<MusicFreeLyric> {
        val lyric = parseMusicFreeLyric(json)
            .getOrElse { return AdaptResult.Denied((it as? MusicFreeParseError)?.reason ?: "歌词解析失败") }
        return AdaptResult.Mapped(lyric, "MAPPED（歌词文本，无网络）")
    }

    private fun gate(
        record: MusicFreePluginRecord,
        decision: PolicyDecision,
        capability: MusicFreeCapability,
    ): String? {
        if (capability !in record.capabilities) return "插件未声明 ${capability.name} 能力"
        if (!record.isStaticallyClean) return "插件静态审查有风险命中，拒绝适配"
        return when (decision) {
            is PolicyDecision.Denied -> decision.reason
            is PolicyDecision.Authorized -> null
        }
    }

    sealed class AdaptResult<T> {
        data class Mapped<T>(val value: T, val level: String) : AdaptResult<T>()
        data class Denied<T>(val reason: String) : AdaptResult<T>()
    }
}
