package org.feeluown.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 社区音源清单的每日发现（阶段 D / 修复 P1-1）。
 *
 * 与 [AndroidSourceUpdateWorker]（受控目录更新）分开：只做"发现候选"，绝不执行/安装/批准。
 * - **真正等待** Repository.suspend refresh() 完成后才返回结果（不再经 UI Controller 的
 *   scope.launch 立即返回）；网络失败返回 retry，配置/数据错误返回 failure，取消信号重抛；
 * - UI 手动刷新与后台刷新共用 Application 里的同一 Repository 实例；
 * - 24h 周期 + CONNECTED 网络约束 + KEEP 幂等；清单失败不清空本地候选（框架保证）。
 */
internal class AndroidCommunityDiscoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? FuoEvolveApplication ?: return Result.failure()
        val result = try {
            // 直接 await Repository 的挂起刷新：发现、解析、保存全部完成后才走到这里。
            application.communityDiscoveryRepository.refresh()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须重新抛出，不能吞掉。
        } catch (e: Exception) {
            // 非预期的运行时异常（如存储写入失败）视为可重试。
            return Result.retry()
        }
        // 清单读取失败（网络/DNS/超时）可重试；结构非法/空清单属数据错误，不无限重试。
        return when (application.communityDiscoveryRepository.workerDisposition(result)) {
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.SUCCESS -> Result.success()
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.RETRY -> Result.retry()
            org.feeluown.mobile.sourceupdate.CommunityDiscoveryRepository.RefreshDisposition.FAILURE -> Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_NAME = "fuoevolve-community-discovery-daily"
        private const val PERIOD_HOURS = 24L

        /** 幂等：每次进程启动都调用也不会重复排队（KEEP 语义）。 */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AndroidCommunityDiscoveryWorker>(
                PERIOD_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
