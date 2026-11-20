package org.feeluown.mobile.feature.community

import io.ktor.client.HttpClient
import org.feeluown.mobile.provider.core.network.createSharedProviderHttpClient
import org.feeluown.mobile.sourceupdate.CommunityCatalogDiscovery
import org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository
import org.feeluown.mobile.sourceupdate.KtorCatalogFetcher
import org.feeluown.mobile.sourceupdate.createPlatformSourceFileStore

/**
 * 社区发现 Repository 的装配（阶段 D）。
 *
 * 清单地址硬编码为**可信来源**（shellsec 仓库原始地址 + 已知公共代理作为下载通道），
 * 不接受 UI 或远端注入——社区清单不是信任根，内容仍要过静态扫描与人工审核。
 * 与受控目录（SourceUpdateDefaults）分开：这里只读 candidates，不验签、不安装。
 */
object CommunityDiscoveryFactory {

    /** 社区清单主地址与镜像；只作候选线索，绝不作为安装信任根。 */
    val COMMUNITY_LIST_URLS: List<String> = listOf(
        "https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json",
        "https://gh-proxy.com/raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/shellsec/lx-music-source/master/sources.json",
    )

    fun createRepository(httpClient: HttpClient = createSharedProviderHttpClient()): CommunityDiscoveryRepository {
        // 关掉重定向：跨域 302 会把请求引到 allowlist 之外，与受控目录同一防护。
        val fetchClient = httpClient.config { followRedirects = false }
        val fetcher = KtorCatalogFetcher(fetchClient)
        val store = createPlatformSourceFileStore()
        val discovery = CommunityCatalogDiscovery(fetcher, store)
        return CommunityDiscoveryRepository(discovery, store, COMMUNITY_LIST_URLS)
    }
}
