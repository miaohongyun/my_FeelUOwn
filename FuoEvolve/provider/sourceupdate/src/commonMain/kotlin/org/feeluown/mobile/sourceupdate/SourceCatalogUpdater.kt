package org.feeluown.mobile.sourceupdate

import org.feeluown.mobile.sourceupdate.crypto.Sha256
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 网络失败分类：只用于诊断，不参与信任判断。 */
enum class CatalogFetchError { TIMEOUT, DNS, TLS, HTTP_STATUS, TOO_LARGE, EMPTY, OTHER }

data class CatalogFetchResult(
    val url: String,
    val body: String? = null,
    val etag: String? = null,
    val notModified: Boolean = false,
    val error: CatalogFetchError? = null,
    val detail: String? = null,
) {
    val ok: Boolean get() = error == null
}

interface CatalogFetcher {
    /**
     * @param maxBytes 本次读取的体积上限；目录/脚本下载各自传入独立常量。
     *   null 表示用实现默认值。mock 实现可忽略。
     */
    suspend fun fetch(url: String, etag: String? = null, maxBytes: Int? = null): CatalogFetchResult
}

/**
 * Ktor 实现。
 *
 * 约束：
 * - 15 秒总超时（设计文档 4.3 的"单文件读取 15 秒"）；
 * - 1 MiB 上限，超了就按 [CatalogFetchError.TOO_LARGE] 失败，不把超大响应读进内存；
 * - 不跟随重定向（跨域重定向会被当成失败，避免被引到 allowlist 之外的地址）。
 *   这要求调用方传入的 HttpClient 配置了 `followRedirects = false`。
 */
class KtorCatalogFetcher(
    private val client: HttpClient,
    private val maxBytes: Int = HttpBodyLimits.CATALOG_JSON_BYTES,
    private val timeoutMillis: Long = 15_000L,
) : CatalogFetcher {

    override suspend fun fetch(url: String, etag: String?, maxBytes: Int?): CatalogFetchResult {
        val byteLimit = maxBytes ?: this.maxBytes
        val response = try {
            withTimeoutOrNull(timeoutMillis) {
                client.get(url) {
                    if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
                }
            } ?: return CatalogFetchResult(url, error = CatalogFetchError.TIMEOUT, detail = "请求超时 ${timeoutMillis}ms")
        } catch (e: HttpRequestTimeoutException) {
            return CatalogFetchResult(url, error = CatalogFetchError.TIMEOUT, detail = "请求超时: ${e.message}")
        } catch (e: ConnectTimeoutException) {
            return CatalogFetchResult(url, error = CatalogFetchError.TIMEOUT, detail = "连接超时: ${e.message}")
        } catch (e: SocketTimeoutException) {
            return CatalogFetchResult(url, error = CatalogFetchError.TIMEOUT, detail = "读取超时: ${e.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CatalogFetchResult(url, error = classify(e), detail = "网络失败: ${e.message}")
        }

        if (response.status == HttpStatusCode.NotModified) {
            return CatalogFetchResult(url, notModified = true, etag = etag)
        }
        if (response.status.value !in 200..299) {
            return CatalogFetchResult(
                url,
                error = CatalogFetchError.HTTP_STATUS,
                detail = "HTTP ${response.status.value}",
            )
        }

        // 流式读取：Content-Length 提前拒绝 + 无/伪造 CL 时逐块累计，超限立即中断，
        // 不把超大响应整体读进内存再 substring。读取也纳入总超时。
        val read = try {
            withTimeoutOrNull(timeoutMillis) { readBodyBounded(response, byteLimit) }
                ?: return CatalogFetchResult(url, error = CatalogFetchError.TIMEOUT, detail = "读取响应超时 ${timeoutMillis}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CatalogFetchResult(url, error = CatalogFetchError.OTHER, detail = "读取响应失败: ${e.message}")
        }
        when (read) {
            is BoundedBodyRead.TooLarge -> return CatalogFetchResult(
                url,
                error = CatalogFetchError.TOO_LARGE,
                detail = if (read.fromContentLength) {
                    "Content-Length ${read.observed} 字节，超过上限 ${read.limit}"
                } else {
                    "流式读取累计 ${read.observed} 字节，超过上限 ${read.limit}（已中断）"
                },
            )
            is BoundedBodyRead.Ok -> Unit
        }
        val text = (read as BoundedBodyRead.Ok).text
        if (text.isBlank()) {
            return CatalogFetchResult(url, error = CatalogFetchError.EMPTY, detail = "响应为空")
        }
        return CatalogFetchResult(url, body = text, etag = response.headers[HttpHeaders.ETag])
    }

    private fun classify(error: Exception): CatalogFetchError {
        val message = (error.message.orEmpty() + " " + (error.cause?.message.orEmpty())).lowercase()
        return when {
            "unresolved" in message || "unknownhost" in message || "nodename" in message -> CatalogFetchError.DNS
            "ssl" in message || "tls" in message || "certificate" in message -> CatalogFetchError.TLS
            "timeout" in message -> CatalogFetchError.TIMEOUT
            else -> CatalogFetchError.OTHER
        }
    }
}

sealed interface CatalogLoad {
    data class Loaded(
        val catalog: SourceCatalog,
        val warnings: List<String>,
        val sourceUrl: String,
        val etag: String?,
        /** 已通过签名校验的目录原始字节 SHA-256，用于同版本内容变化检测。 */
        val catalogSha256: String,
    ) : CatalogLoad

    data class NotModified(val etag: String?) : CatalogLoad

    data class Failed(val reason: String, val attempts: List<String>) : CatalogLoad
}

/**
 * 目录拉取：按镜像顺序尝试 → 校验签名 → 解析结构。
 *
 * 签名文件是 `.sig` 独立文件，与目录同源拉取。任何一步失败都换下一个镜像，
 * 全部失败时返回 [CatalogLoad.Failed]，调用方保留上次成功的目录与已安装脚本。
 */
class SourceCatalogUpdater(
    private val fetcher: CatalogFetcher,
    private val mirrors: SourceMirrorPolicy,
    private val signatureVerifier: CatalogSignatureVerifier,
    private val clock: () -> Long = { 0L },
) {

    /**
     * 拉取并校验目录。
     *
     * @param minCatalogVersion 本地已接受的目录版本；任何签名有效的候选若版本低于它，
     *   一律拒绝（反重放）。同时本方法会遍历所有允许镜像，在全部签名有效的候选中
     *   选**版本最高**者返回——避免"主地址返回旧版本就立即接受、错过镜像上的新版本"。
     *   首次安装传 0。
     */
    suspend fun load(
        catalogUrl: String,
        etag: String? = null,
        minCatalogVersion: Long = 0L,
    ): CatalogLoad {
        val candidates = (listOf(catalogUrl) + mirrors.orderedMirrors(clock())).distinct()
        val attempts = mutableListOf<String>()
        var best: CatalogLoad.Loaded? = null
        var anyNotModified: CatalogLoad.NotModified? = null

        for (url in candidates) {
            if (!mirrors.isAllowed(url)) {
                attempts += "$url: 不在允许的镜像列表内"
                continue
            }
            val result = fetcher.fetch(url, etag)
            if (!result.ok) {
                mirrors.recordFailure(url, clock(), result.detail)
                attempts += "$url: ${result.error} ${result.detail.orEmpty()}"
                continue
            }

            val body = result.body
            if (result.notModified && body == null) {
                mirrors.recordSuccess(url, clock())
                if (anyNotModified == null) anyNotModified = CatalogLoad.NotModified(result.etag ?: etag)
                continue
            }
            if (body == null) {
                mirrors.recordFailure(url, clock(), "响应为空")
                attempts += "$url: 响应为空"
                continue
            }

            val parsed = SourceCatalogParser.parse(body)
            if (parsed is CatalogParseResult.Invalid) {
                // 结构非法属于发布事故，不消耗镜像信誉，但也绝不接受。
                attempts += "$url: ${parsed.reason}"
                continue
            }
            val valid = parsed as CatalogParseResult.Valid

            val signature = loadSignature(url)
            when (val verification = signatureVerifier.verify(
                rawCatalog = body.encodeToByteArray(),
                signatureBase64 = signature,
                algorithm = valid.catalog.signatureAlgorithm,
                keyId = valid.catalog.keyId,
            )) {
                is SignatureVerification.Rejected -> {
                    attempts += "$url: ${verification.reason}"
                    continue
                }
                SignatureVerification.Accepted, SignatureVerification.NotRequired -> Unit
            }

            // 反重放：签名有效但版本低于本地已接受版本 → 绝不接受（即使来自"可信"镜像）。
            if (valid.catalog.catalogVersion < minCatalogVersion) {
                mirrors.recordSuccess(url, clock())
                attempts += "$url: 目录版本 ${valid.catalog.catalogVersion} 低于本地已接受版本 $minCatalogVersion（疑似重放）"
                continue
            }

            mirrors.recordSuccess(url, clock())
            val loaded = CatalogLoad.Loaded(
                valid.catalog, valid.warnings, url, result.etag ?: etag, Sha256.hex(body.encodeToByteArray()),
            )
            if (best == null || loaded.catalog.catalogVersion > best!!.catalog.catalogVersion) {
                best = loaded
            }
        }

        // 有签名有效的新目录 → 取版本最高者；否则若拿到 304 → 视为未修改；再否则失败。
        best?.let { return it }
        anyNotModified?.let { return it }
        return CatalogLoad.Failed(
            reason = if (attempts.any { "重放" in it }) "所有目录地址均不可用或为旧版本重放" else "所有目录地址均不可用",
            attempts = attempts,
        )
    }

    private suspend fun loadSignature(catalogUrl: String): String? {
        val sigUrl = "$catalogUrl.sig"
        if (!mirrors.isAllowed(sigUrl)) return null
        val result = fetcher.fetch(sigUrl, null)
        return if (result.ok) result.body?.trim() else null
    }
}
