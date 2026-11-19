package org.feeluown.mobile.provider.jamendo

/**
 * Jamendo 开放曲库的最小模型。
 *
 * 只保留"能搜到、能播、能显示"所需的字段。Jamendo 的曲目是 CC 授权音乐，
 * 与商业流行曲库不重叠——UI 必须明确这是"替代曲目"，不能假装成原曲。
 */
data class JamendoTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int?,
    val audioUrl: String,
    val downloadUrl: String,
    val imageUrl: String,
    val shareUrl: String,
)

data class JamendoSearchResult(
    val tracks: List<JamendoTrack>,
    val totalCount: Int,
)
