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
 * 受控音源目录的每日检查。
 *
 * 设计取舍：
 * - **不做系统通知、不弹 UI**：脚本更新是静默的后台维护，失败只在设置页留状态；
 * - 网络约束 CONNECTED，允许系统延迟调度（设计文档 4.2）；
 * - 真正的"要不要检查"由 `SourceUpdatePolicy`（24h + 1/4/12/24h 退避）判定，
 *   因此 worker 即使被提前唤醒也只是一次空转，不会频繁打上游；
 * - 异常一律 `Result.success()`：失败节奏由我们自己的退避控制，
 *   再叠加 WorkManager 的 retry 会让两个退避互相干扰。
 */
internal class AndroidSourceUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? FuoEvolveApplication ?: return Result.failure()
        runCatching { application.sourceUpdateHost.checkIfDue() }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_PERIODIC_NAME = "fuoevolve-source-catalog-daily"
        private const val PERIOD_HOURS = 24L

        /** 幂等：每次进程启动都调用也不会重复排队（KEEP 语义）。 */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AndroidSourceUpdateWorker>(
                PERIOD_HOURS,
                TimeUnit.HOURS,
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
