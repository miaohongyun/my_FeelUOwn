package org.feeluown.mobile.sourceupdate

/**
 * 一次自动检查的结果，直接对应设置页要显示的状态文案。
 */
sealed interface UpdateReport {
    data class UpToDate(val catalogVersion: Long) : UpdateReport

    data class Installed(
        val scriptIds: List<String>,
        val errors: List<String>,
        val catalogVersion: Long,
    ) : UpdateReport

    data class Skipped(val reason: String) : UpdateReport

    data class Failed(val reason: String, val attempts: List<String> = emptyList()) : UpdateReport
}

/**
 * 自动更新编排。
 *
 * 职责边界：
 * - 只下载目录里标记为自动安装的条目，交给 [SourceInstallTransaction] 做全部审核；
 * - 冒烟测试与"先验证后激活"由事务内部完成，协调器不再碰 active 指针；
 * - 任何网络/校验失败都只影响"这一次更新"，不清空已安装脚本，不改动 active 指针。
 */
class SourceUpdateCoordinator(
    private val catalogUrls: List<String>,
    private val updater: SourceCatalogUpdater,
    private val fetcher: CatalogFetcher,
    private val store: SourceVersionStore,
    private val transaction: SourceInstallTransaction,
    private val policy: SourceUpdatePolicy,
    private val clock: () -> Long,
    private val appVersion: String = "0.0.0",
) {

    suspend fun checkForUpdates(force: Boolean = false): UpdateReport {
        val index = store.loadIndex()
        val now = clock()
        if (!policy.shouldCheck(index, now, force)) {
            return UpdateReport.Skipped("距上次成功检查不足 ${policy.intervalHours} 小时")
        }
        if (catalogUrls.isEmpty()) {
            return UpdateReport.Skipped("未配置音源目录地址")
        }

        // 反重放：以本地已接受版本为下限；任何更旧的签名目录都会被 updater 拒绝。
        val load = updater.load(catalogUrls.first(), index.etag, minCatalogVersion = index.catalogVersion)
        return when (load) {
            is CatalogLoad.Failed -> {
                // 必须重新读一次索引：这一轮可能已经有脚本装完，用旧快照会把它抹掉。
                store.saveIndex(
                    store.loadIndex().copy(
                        lastCheckMillis = now,
                        consecutiveFailures = index.consecutiveFailures + 1,
                    ),
                )
                UpdateReport.Failed(load.reason, load.attempts)
            }

            is CatalogLoad.NotModified -> {
                store.saveIndex(
                    store.loadIndex().copy(lastCheckMillis = now, lastSuccessMillis = now, consecutiveFailures = 0),
                )
                UpdateReport.UpToDate(index.catalogVersion)
            }

            is CatalogLoad.Loaded -> {
                val catalog = load.catalog
                // 双保险：即使 updater 有 bug 或其他调用方传入了更旧目录，这里也绝不接受。
                if (catalog.catalogVersion < index.catalogVersion) {
                    return UpdateReport.Failed(
                        "拒绝旧目录重放：本地已接受版本 ${index.catalogVersion}，收到 ${catalog.catalogVersion}",
                    )
                }
                // 同版本内容变化：版本策略要求"内容变了必须提升 catalogVersion"。
                // 首次安装（无已接受版本）不受此约束。
                val catalogHash = load.catalogSha256
                if (!index.isFirstInstall &&
                    catalog.catalogVersion == index.catalogVersion &&
                    index.acceptedCatalogSha256 != null &&
                    !index.acceptedCatalogSha256.equals(catalogHash, ignoreCase = true)
                ) {
                    return UpdateReport.Failed(
                        "拒绝同版本内容变化的目录：catalogVersion=${catalog.catalogVersion} 但内容哈希与已接受版本不一致",
                    )
                }
                val minVersion = catalog.minAppVersion
                if (!minVersion.isNullOrBlank() && compareVersions(appVersion, minVersion) < 0) {
                    store.saveIndex(store.loadIndex().copy(lastCheckMillis = now, consecutiveFailures = 0))
                    return UpdateReport.Skipped("目录要求应用版本 ≥ $minVersion，当前 $appVersion")
                }

                val installed = mutableListOf<String>()
                val errors = mutableListOf<String>()
                for (entry in catalog.sources) {
                    if (!entry.autoInstall || !entry.review.installable) continue
                    val current = index.sources[entry.id]
                    if (current != null &&
                        current.activeVersion == entry.version &&
                        current.sha256.equals(entry.sha256, ignoreCase = true)
                    ) {
                        continue
                    }

                    // 脚本下载与目录 JSON 用各自独立的上限常量，不共用。
                    val download = fetcher.fetch(entry.scriptUrl, maxBytes = HttpBodyLimits.SCRIPT_DOWNLOAD_BYTES)
                    val body = download.body
                    if (!download.ok || body == null) {
                        errors += "${entry.id}: 下载失败 ${download.error} ${download.detail.orEmpty()}".trim()
                        continue
                    }

                    when (val outcome = transaction.install(entry, body, enable = entry.autoEnable)) {
                        is InstallOutcome.Rejected -> errors += "${entry.id}: ${outcome.reason}"
                        is InstallOutcome.AlreadyInstalled -> Unit
                        is InstallOutcome.SmokeTestFailed ->
                            errors += "${entry.id}: 冒烟测试失败，未激活（${outcome.reason}）"
                        is InstallOutcome.Installed -> installed += entry.id
                    }
                }

                store.saveIndex(
                    store.loadIndex().copy(
                        catalogVersion = catalog.catalogVersion,
                        lastCheckMillis = now,
                        lastSuccessMillis = now,
                        consecutiveFailures = 0,
                        etag = load.etag,
                        acceptedCatalogSha256 = catalogHash,
                    ),
                )
                return if (installed.isEmpty() && errors.isEmpty()) {
                    UpdateReport.UpToDate(catalog.catalogVersion)
                } else {
                    UpdateReport.Installed(installed, errors, catalog.catalogVersion)
                }
            }
        }
    }
}

/** 语义化版本比较：只比较数字段，缺省补 0，忽略预发布后缀。 */
fun compareVersions(left: String, right: String): Int {
    val leftParts = left.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val rightParts = right.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val a = leftParts.getOrElse(index) { 0 }
        val b = rightParts.getOrElse(index) { 0 }
        if (a != b) return if (a < b) -1 else 1
    }
    return 0
}
