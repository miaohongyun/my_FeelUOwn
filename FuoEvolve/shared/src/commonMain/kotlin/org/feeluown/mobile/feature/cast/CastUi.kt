package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The cast controller the player UI talks to. */
val LocalCastControllerPort = staticCompositionLocalOf<CastControllerPort> { NoOpCastControllerPort }

/**
 * The "设备投放" entry in the player header.
 *
 * It opens the device picker; picking a device starts casting the current track to it.
 */
@Composable
fun RuntimeCastHeaderButton() {
    val cast = LocalCastControllerPort.current
    val state by cast.state.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val device = state.activeDevice

    IconButton(onClick = { showSheet = true }) {
        Icon(
            imageVector = if (device != null) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = if (device != null) "设备投放，投屏中" else "设备投放",
            tint = if (device != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
    if (showSheet) {
        CastDeviceSheet(onDismiss = { showSheet = false })
    }
}

/** Lists the renderers on the local network and controls the running cast session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(onDismiss: () -> Unit) {
    val cast = LocalCastControllerPort.current
    val state by cast.state.collectAsStateWithLifecycle()

    // The device list is empty on a fresh sheet, so start searching right away.
    LaunchedEffect(Unit) {
        if (state.devices.isEmpty() && !state.discovering) cast.discover()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "设备投放",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "把当前的播放队列推送到同一局域网里的电视或音箱，一首播完会自动接着下一首",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.activeDevice?.let { device ->
                HorizontalDivider()
                Text(
                    text = "正在投屏到 ${device.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { cast.togglePlayPause() }) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" 暂停/播放")
                    }
                    TextButton(onClick = { cast.next() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" 下一首")
                    }
                    TextButton(onClick = { cast.stopCast() }) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" 停止")
                    }
                }
            }

            HorizontalDivider()
            Text(
                text = "可用设备",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )

            when {
                state.discovering -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在搜索局域网设备…", style = MaterialTheme.typography.bodyMedium)
                }

                state.devices.isEmpty() -> Text(
                    text = "没有找到可投放的设备，请确认电视已开机并与手机连接同一个 Wi-Fi。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> state.devices.forEach { device ->
                    CastDeviceRow(
                        device = device,
                        active = state.activeDevice?.id == device.id,
                        onClick = { cast.castTo(device) },
                    )
                }
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(
                onClick = { cast.discover() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 重新搜索设备")
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDevice,
    active: Boolean,
    onClick: () -> Unit,
) {
    val subtitle = listOf(device.manufacturer, device.modelName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { device.host }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) {
            Text(
                text = "投放中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
