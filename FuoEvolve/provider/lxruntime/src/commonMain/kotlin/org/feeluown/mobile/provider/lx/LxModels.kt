package org.feeluown.mobile.provider.lx

/**
 * LX Music 自定义音源脚本的协议模型。
 *
 * 字段与取值严格对齐官方文档（lxmusic.toside.cn/mobile/custom-source）的移动端约束，
 * 而不是社区脚本的经验猜测：
 * - 平台 key 只有 kw / kg / tx / wy / mg / local；
 * - 音质只允许 128k / 320k / flac / flac24bit；
 * - 除 local 外，actions 固定为 ["musicUrl"]。
 */
object LxProtocol {
    const val PROTOCOL_VERSION = "2.0.0"

    /** 宿主环境标识。移动端固定为 mobile（桌面端是 desktop），脚本会据此分支。 */
    const val ENV = "mobile"

    const val EVENT_INITED = "inited"
    const val EVENT_REQUEST = "request"
    const val EVENT_UPDATE_ALERT = "updateAlert"

    val PLATFORMS: Set<String> = linkedSetOf("kw", "kg", "tx", "wy", "mg", "local")

    const val ACTION_MUSIC_URL = "musicUrl"
    const val ACTION_LYRIC = "lyric"
    const val ACTION_PIC = "pic"

    val QUALITIES: List<String> = listOf("128k", "320k", "flac", "flac24bit")

    /** 单脚本体积上限：与设计文档一致，先给 2 MiB。 */
    const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024

    /** 单次求值默认超时。QuickJS 侧到达该时长会被强制中断，防止脚本死循环卡住播放流程。 */
    const val DEFAULT_EVALUATION_TIMEOUT_MS = 8_000L

    /** 初始化阶段单独给更短的上限：初始化本不该做重活。 */
    const val DEFAULT_INIT_TIMEOUT_MS = 5_000L
}

data class LxSourceInfo(
    val platform: String,
    val name: String,
    val actions: List<String>,
    val qualitys: List<String>,
)

data class LxScriptMeta(
    val scriptName: String,
    val sources: Map<String, LxSourceInfo>,
) {
    val platforms: Set<String> get() = sources.keys

    fun supports(platform: String): Boolean = sources.containsKey(platform)

    /** 某平台声明的音质，按官方顺序归一（脚本可能乱序或重复）。 */
    fun qualitiesOf(platform: String): List<String> {
        val declared = sources[platform]?.qualitys ?: return emptyList()
        val normalized = declared.map { it.trim().lowercase() }.filter { it in LxProtocol.QUALITIES }
        return LxProtocol.QUALITIES.filter { it in normalized }
    }
}

/** 一次 musicUrl 请求的结果。 */
data class LxResolvedUrl(
    val url: String,
    val platform: String,
    val quality: String,
    val scriptName: String,
)

class LxScriptException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LxScriptTimeoutException(message: String) : RuntimeException(message)

class LxProtocolException(message: String) : RuntimeException(message)
