package org.feeluown.mobile.sourceupdate

/**
 * 平台文件存储的创建入口。
 *
 * 只暴露 [SourceFileStore] 这一个抽象：版本语义、原子性、回滚都在 commonMain 实现，
 * 平台侧只负责"按相对路径读写文本"，方便用内存实现做完整测试。
 */
expect fun createPlatformSourceFileStore(): SourceFileStore

/** 拒绝绝对路径与路径穿越：存储键只允许是相对路径。 */
internal fun sanitizeStoragePath(path: String): String {
    require(path.isNotBlank()) { "存储路径不能为空" }
    require(!path.startsWith("/") && !path.startsWith("\\")) { "存储路径不能是绝对路径: $path" }
    require(!path.contains("..")) { "存储路径不能包含 ..: $path" }
    require(!path.contains("\\")) { "存储路径请使用 / 分隔: $path" }
    return path
}

/** 取父目录（不含结尾斜杠）；没有父目录时返回空串。 */
internal fun parentOf(path: String): String = path.substringBeforeLast('/', "")

internal fun nameOf(path: String): String = path.substringAfterLast('/')
