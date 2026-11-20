package org.feeluown.mobile.feature.community

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.feeluown.mobile.sourceupdate.DiscoveredCandidate

/**
 * 社区音源发现与审核中心（阶段 D）。
 *
 * 只读展示 + 人工审核动作；本页绝不"批准"候选，也不执行/安装任何脚本。
 * DISCOVERED/CLEAN 一律显示为"待审核"（见 [CommunityDiscoveryPresentation]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityDiscoveryScreen(
    controller: CommunityDiscoveryController,
    onBack: () -> Unit,
) {
    val state by controller.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(state.feedback) {
        state.feedback?.let { snackbar.showSnackbar(it); controller.clearFeedback() }
    }
    LaunchedEffect(Unit) { controller.refresh() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("社区音源发现") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { controller.refresh() }, enabled = !state.isRefreshing) {
                        Text(if (state.isRefreshing) "刷新中…" else "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "以下为社区清单发现的候选，仅供审查；不会自动安装或播放。\n" +
                    "所有音质/可用性结论需真实验证后才成立。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.typography.bodySmall.color.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.candidates.isEmpty() && !state.isRefreshing) {
                Text(
                    "暂无候选。点击右上角刷新以读取社区清单。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.candidates, key = { it.id }) { candidate ->
                    CandidateRow(candidate, onClick = { controller.select(candidate.id) })
                }
            }
        }
    }
    state.selectedId?.let { id ->
        state.candidates.firstOrNull { it.id == id }?.let { selected ->
            CandidateDetailDialog(
                candidate = selected,
                onDismiss = { controller.select(null) },
                onQuarantine = { reason -> controller.quarantine(id, reason) },
                onReject = { reason -> controller.reject(id, reason) },
                onReopen = { controller.reopen(id) },
                onDelete = { controller.delete(id) },
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: DiscoveredCandidate, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(candidate.name + "  ·  " + CommunityDiscoveryPresentation.reviewLabel(candidate),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text("版本 ${candidate.version ?: "未知"} · ${candidate.sourceRepo.ifBlank { "来源未知" }}")
                Text(CommunityDiscoveryPresentation.verdictLabel(candidate) +
                    " · ${CommunityDiscoveryPresentation.verificationLabel(candidate)}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = Modifier.clickable(onClick = onClick).fillMaxWidth(),
    )
}

/**
 * 时间格式化（epoch 毫秒 → UTC 日期），供详情展示。
 * 用纯 Kotlin 算术实现，避免 java.time/SimpleDateFormat 这类 JVM-only API 破坏 iOS 编译；
 * 候选审查时间精确到分钟即可，UTC 对审核证据已足够（不依赖设备时区）。
 */
internal fun formatCandidateTime(millis: Long): String {
    if (millis <= 0) return "未记录"
    val totalMinutes = millis / 60_000L
    val days = totalMinutes / 1440L
    val minuteOfDay = (totalMinutes % 1440L).toInt()
    // 从 1970-01-01 起按 400 年周期展开（跳过闰年规则用逐日推进，代码简单且够用）。
    var year = 1970
    var remaining = days
    while (true) {
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val yd = if (leap) 366L else 365L
        if (remaining < yd) break
        remaining -= yd
        year++
    }
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val monthDays = if (leap) intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    else intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 1
    var day = remaining.toInt()
    while (month <= 12 && day >= monthDays[month - 1]) {
        day -= monthDays[month - 1]
        month++
    }
    val hh = minuteOfDay / 60
    val mm = minuteOfDay % 60
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} " +
        "${hh.toString().padStart(2, '0')}:${mm.toString().padStart(2, '0')} UTC"
}

/**
 * 详情弹窗：显示全部审查字段、静态原因、域名列表、审核历史与人工动作。
 * 审核动作全部显式调用 controller，绝不自动批准。
 */
@Composable
fun CandidateDetailDialog(
    candidate: DiscoveredCandidate,
    onDismiss: () -> Unit,
    onQuarantine: (String) -> Unit,
    onReject: (String) -> Unit,
    onReopen: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("候选详情：${candidate.name}") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                DetailLine("ID", candidate.id)
                DetailLine("审核状态", CommunityDiscoveryPresentation.reviewLabel(candidate))
                DetailLine("验证等级", CommunityDiscoveryPresentation.verificationLabel(candidate))
                DetailLine("静态结论", CommunityDiscoveryPresentation.verdictLabel(candidate))
                DetailLine("静态风险", candidate.risk)
                DetailLine("版本", candidate.version ?: "未知")
                DetailLine("来源仓库", candidate.sourceRepo.ifBlank { "未知" })
                DetailLine("SHA256", candidate.sha256 ?: "未记录")
                DetailLine("文件大小", candidate.sizeBytes?.let { "$it 字节" } ?: "未知")
                DetailLine("发现时间", formatCandidateTime(candidate.discoveredAtMillis))
                DetailLine("最近扫描", formatCandidateTime(candidate.scannedAtMillis))
                if (candidate.rejectReason != null) DetailLine("拒绝/隔离原因", candidate.rejectReason!!)
                Spacer(Modifier.height(8.dp))
                Text("请求域名（仅供人工审核，未加入白名单）：", style = MaterialTheme.typography.labelMedium)
                if (candidate.discoveredDomains.isEmpty()) {
                    Text("无（域名静态不可见，需专项逆向）", style = MaterialTheme.typography.bodySmall)
                } else {
                    candidate.discoveredDomains.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (candidate.staticBlockReasons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("静态审查原因：", style = MaterialTheme.typography.labelMedium)
                    candidate.staticBlockReasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(8.dp))
                Text("镜像 ID：${candidate.aliasIds.joinToString(", ").ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onReopen() }) { Text("重新审核") }
                OutlinedButton(onClick = { onQuarantine("人工隔离") }) { Text("隔离") }
                OutlinedButton(onClick = { onReject("人工拒绝") }) { Text("拒绝") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label + "：", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.typography.bodySmall.color.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
