package org.feeluown.mobile.lossless

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 可选的完整 FLAC 解码校验。若系统未安装 FFmpeg，则返回 null，调用方仍可执行容器校验，
 * 但不能把结果描述为“完整解码通过”。
 */
internal object DesktopFlacDecodeVerifier {
    private const val TIMEOUT_SECONDS = 30L

    /** true=完整解码成功，false=解码器明确报错，null=解码器不可用/启动失败。 */
    fun verify(file: File): Boolean? {
        if (!file.isFile) return false
        val executable = System.getProperty("fuoevolve.ffmpeg.path")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUOEVOLVE_FFMPEG_PATH")?.takeIf { it.isNotBlank() }
            ?: "ffmpeg"
        val process = try {
            ProcessBuilder(
                executable,
                "-nostdin",
                "-v", "error",
                "-xerror",
                "-err_detect", "explode",
                "-i", file.absolutePath,
                "-map", "0:a:0",
                "-f", "null",
                "-",
            ).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (_: Exception) {
            return null
        }

        return try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            false
        }
    }
}
