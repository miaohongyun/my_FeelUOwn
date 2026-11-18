package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One selectable download-quality tier.
 *
 * [policy] is the provider-facing quality policy string. The dialect matches the desktop player
 * (">>>" / "hq<>" / "sq<>" / "lq<>"), so the same providers interpret it the same way.
 * A null policy means "follow the app-wide audio quality setting".
 */
data class DownloadQualityOption(
    val policy: String?,
    val label: String,
    val detail: String,
    /**
     * A reference bitrate used only for a rough size estimate in the picker.
     *
     * Different providers map the same tier to different bitrates, so this is never presented as
     * a guaranteed value; `null` means the tier is unbounded (for example "highest available").
     */
    val referenceKbps: Int? = null,
)

/** A pending download request that waits for the user to pick a quality tier. */
data class DownloadQualitySelection(
    val track: MusicTrack,
    val options: List<DownloadQualityOption>,
    /** True while the provider is being asked which tiers this track supports. */
    val probing: Boolean = false,
    /**
     * Why the tier list differs from the generic one.
     *
     * Providers report account-scoped availability, so the picker can be empty (nothing
     * playable) or shortened (membership tiers hidden). Saying so avoids a silent failure
     * after the user taps a tier that the account cannot actually fetch.
     */
    val notice: String? = null,
)

/**
 * Feature port that owns the "pick a download quality" interaction.
 *
 * The port is deliberately separate from [DownloadActionPort]: the picker owns its own transient
 * state, while the download action port keeps owning the queue itself.
 */
interface DownloadQualitySelectionPort {
    val pendingSelection: StateFlow<DownloadQualitySelection?>

    /** Opens the picker for [track]. Downloads immediately when no picker is available. */
    fun requestSelection(track: MusicTrack)

    /** Confirms the pending selection and starts the download with [policy]. */
    fun confirmSelection(policy: String?)

    /** Cancels the pending selection without downloading. */
    fun dismissSelection()
}

/** Resolves a track's media with an explicit provider quality policy. */
interface DownloadQualityResolver {
    suspend fun resolveWithQualityPolicy(track: MusicTrack, qualityPolicy: String): PlaybackPayload?

    /**
     * Quality policies this track actually supports, or `null` when the provider cannot tell.
     *
     * `null` keeps the generic tier list in the picker, so a provider without support does not
     * break the download flow.
     */
    suspend fun availableQualities(track: MusicTrack): List<String>? = null
}

/**
 * The download surface consumed by the UI: the download queue actions plus the quality picker
 * that guards them.
 */
interface DownloadFeaturePorts : DownloadActionPort, DownloadQualitySelectionPort

/** No-op port used when the host cannot resolve tracks by an explicit quality policy. */
object NoOpDownloadQualitySelectionPort : DownloadQualitySelectionPort {
    private val mutablePending = MutableStateFlow<DownloadQualitySelection?>(null)
    override val pendingSelection: StateFlow<DownloadQualitySelection?> = mutablePending.asStateFlow()

    override fun requestSelection(track: MusicTrack) = Unit

    override fun confirmSelection(policy: String?) = Unit

    override fun dismissSelection() {
        mutablePending.value = null
    }
}

/**
 * Estimates the download size from the track duration and a reference bitrate.
 *
 * Returns `null` for unbounded tiers or when the track has no duration, because a wrong estimate
 * is worse than no estimate.
 */
fun estimateDownloadSizeMb(durationMs: Long?, referenceKbps: Int?): String? {
    if (durationMs == null || durationMs <= 0L) return null
    val kbps = referenceKbps?.takeIf { it > 0 } ?: return null
    val megabytes = durationMs.toDouble() / 1_000.0 * kbps / 8.0 / 1_024.0
    return if (megabytes >= 10.0) {
        "约 %.0f MB".format(megabytes)
    } else {
        "约 %.1f MB".format(megabytes)
    }
}

/**
 * The offered tiers, ordered from the highest quality to the lowest.
 *
 * The order is intentional: the picker highlights the first entry so the default action is the
 * best available quality.
 */
fun defaultDownloadQualityOptions(): List<DownloadQualityOption> = listOf(
    DownloadQualityOption(
        policy = AudioQualityPolicy.Highest.policy,
        label = "无损 / 最高可用",
        detail = "请求音源最高档；歌曲没有无损时会自动回落",
    ),
    DownloadQualityOption(
        policy = AudioQualityPolicy.High.policy,
        label = "高音质",
        detail = "优先高码率（常见 320kbps）",
        referenceKbps = 320,
    ),
    DownloadQualityOption(
        policy = AudioQualityPolicy.Standard.policy,
        label = "标准音质",
        detail = "标准码率；网易云约 192kbps",
        referenceKbps = 192,
    ),
    DownloadQualityOption(
        policy = AudioQualityPolicy.Low.policy,
        label = "省流量",
        detail = "低码率（常见 128kbps）",
        referenceKbps = 128,
    ),
    DownloadQualityOption(
        policy = null,
        label = "跟随设置",
        detail = "使用「设置」里的默认音质",
    ),
)

/**
 * Default [DownloadQualitySelectionPort] implementation.
 *
 * It only stores the pending request; the download itself is delegated to [owner] so the queue,
 * feedback and error handling stay in one place.
 */
class DefaultDownloadQualitySelectionPort(
    private val owner: DownloadFeatureOwner<MusicTrack, DownloadTask, DownloadState>,
    private val resolver: DownloadQualityResolver?,
    private val options: List<DownloadQualityOption> = defaultDownloadQualityOptions(),
    private val scope: CoroutineScope? = null,
) : DownloadQualitySelectionPort {
    private val mutablePending = MutableStateFlow<DownloadQualitySelection?>(null)
    override val pendingSelection: StateFlow<DownloadQualitySelection?> = mutablePending.asStateFlow()

    /** Stale probes must not overwrite a newer picker request. */
    private var requestGeneration = 0

    override fun requestSelection(track: MusicTrack) {
        if (resolver == null) {
            // Without a quality-aware resolver there is nothing to choose from.
            owner.download(track)
            return
        }
        val probeScope = scope
        if (probeScope == null) {
            mutablePending.value = DownloadQualitySelection(track = track, options = options)
            return
        }

        val generation = ++requestGeneration
        mutablePending.value = DownloadQualitySelection(
            track = track,
            options = emptyList(),
            probing = true,
        )
        probeScope.launch {
            val policies = runCatching { resolver.availableQualities(track) }.getOrNull()
            if (generation != requestGeneration) return@launch
            // `null` means "provider cannot tell", `emptyList` means "nothing is playable".
            val available = policies?.toSet()
            val visible = when {
                available == null -> options
                available.isEmpty() -> emptyList()
                // "跟随设置" stays available even when a provider reports a restricted set.
                else -> options.filter { option -> option.policy == null || option.policy in available }
            }
            val notice = when {
                available == null -> null
                available.isEmpty() -> "当前账户没有探测到可用音质，请换一个音源或稍后重试"
                available.size < options.count { option -> option.policy != null } ->
                    "已按当前账户的实际权限隐藏不可用档位"
                else -> null
            }
            mutablePending.value = DownloadQualitySelection(
                track = track,
                options = visible,
                notice = notice,
            )
        }
    }

    override fun confirmSelection(policy: String?) {
        requestGeneration++
        val selection = mutablePending.value ?: return
        mutablePending.value = null
        owner.download(selection.track, policy)
    }

    override fun dismissSelection() {
        requestGeneration++
        mutablePending.value = null
    }
}
