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
 * 社区音源清单的每日发现（阶段 D）。
 *
 * 与 [AndroidSourceUpdateWorker]（受控目录更新）分开：本 worker 只做"发现候选"，
 * 调用 discovery.refresh()，绝不执行/安装脚本、绝不自动批准。
 * - 网络约束 CONNECTED；24h 周期 + KEEP 幂等（每次进程启动 enqueue 不重复排队）；
 * - 失败一律 Result.success()：节奏由 WorkManager 的周期控制，不与业务退避叠加；
 * - 清单失败不清空本地候选（框架保证）。
 */
internal class AndroidCommunityDiscoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? FuoEvolveApplication ?: return Result.failure()
        runCatching { application.communityDiscoveryController.refresh() }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC_NAME = "fuoevolve-community-discovery-daily"
        private const val PERIOD_HOURS = 24L

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
