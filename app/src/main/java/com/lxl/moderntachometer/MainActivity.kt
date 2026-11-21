package com.lxl.moderntachometer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lxl.moderntachometer.viewmodel.TachometerUiState
import com.lxl.moderntachometer.viewmodel.TachometerViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) {
                darkColorScheme(
                    primary = Color(0xFF82B1FF),
                    secondary = Color(0xFF80CBC4),
                    surface = Color(0xFF121417),
                    surfaceVariant = Color(0xFF1E2228)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF1565C0),
                    secondary = Color(0xFF00796B),
                    surface = Color(0xFFF5F7FA),
                    surfaceVariant = Color(0xFFFFFFFF)
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    TachometerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TachometerScreen(viewModel: TachometerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showBaudDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "数字光电转速计",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isConnected) Color(0xFF00C853) else Color(0xFFFF5252))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.statusMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showBaudDialog = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "波特率设置")
                    }
                    IconButton(
                        onClick = {
                            if (state.isConnected) viewModel.disconnect() else viewModel.connectOrScan()
                        }
                    ) {
                        Icon(
                            imageVector = if (state.isConnected) Icons.Rounded.UsbOff else Icons.Rounded.Usb,
                            contentDescription = "连接/断开",
                            tint = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 核心转速与极值统计看板
            RpmDashboardCard(
                state = state,
                onResetStats = { viewModel.resetRpmStats() }
            )

            // 2. 校准与采样控制卡片
            CalibrationCard(
                state = state,
                onFactorChange = { viewModel.updateCalibrationFactor(it) },
                onBladesChange = { viewModel.updateBladesPerRev(it) },
                onIgnoreZeroChange = { viewModel.toggleIgnoreZero(it) }
            )

            // 3. 脉冲计数卡片
            PulseCounterCard(
                state = state,
                onDirectionChange = { viewModel.setCountDirection(it) },
                onResetCount = { viewModel.resetHardwarePulseCount() }
            )

            // 4. 线速度计算卡片
            LineSpeedCard(
                state = state,
                onDiameterChange = { viewModel.updateDiameter(it) },
                onUnitChange = { viewModel.setLineSpeedUnit(it) }
            )

            // 5. 近期读数流水
            RecentLogCard(logs = state.recentLogs)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showBaudDialog) {
        val baudRates = listOf(9600, 19200, 38400, 57600, 115200, 230400)
        AlertDialog(
            onDismissRequest = { showBaudDialog = false },
            title = { Text("串口波特率设置") },
            text = {
                Column {
                    baudRates.forEach { baud ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.baudRate == baud,
                                onClick = {
                                    viewModel.updateBaudRate(baud)
                                    showBaudDialog = false
                                }
                            )
                            Text(
                                text = "$baud bps" + if (baud == 115200) " (默认)" else "",
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBaudDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
fun RpmDashboardCard(
    state: TachometerUiState,
    onResetStats: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "实时转速 (RPM)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                FilledTonalButton(
                    onClick = onResetStats,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清零统计", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = String.format(Locale.US, "%d", state.currentRpm),
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "转/分",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatPill(label = "最大值", value = state.maxRpm.toString(), color = Color(0xFFE53935))
                StatPill(label = "最小值", value = state.minRpm.toString(), color = Color(0xFF1E88E5))
                StatPill(label = "平均值", value = state.avgRpm.toString(), color = Color(0xFF00897B))
                StatPill(label = "有效采样", value = state.sampleCount.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun StatPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = color
        )
    }
}

@Composable
fun CalibrationCard(
    state: TachometerUiState,
    onFactorChange: (String) -> Unit,
    onBladesChange: (Int) -> Unit,
    onIgnoreZeroChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "校准与采样设置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.calibrationFactorText,
                    onValueChange = onFactorChange,
                    label = { Text("倍率系数 K", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("反光贴/叶片数", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onBladesChange(state.bladesPerRev - 1) }) {
                            Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = "减少")
                        }
                        Text(
                            text = "${state.bladesPerRev}",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp
                        )
                        IconButton(onClick = { onBladesChange(state.bladesPerRev + 1) }) {
                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = "增加")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "忽略 0 转速（防止停转拉低均值与最小值）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = state.ignoreZeroRpm,
                    onCheckedChange = onIgnoreZeroChange
                )
            }
        }
    }
}

@Composable
fun PulseCounterCard(
    state: TachometerUiState,
    onDirectionChange: (Boolean) -> Unit,
    onResetCount: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "硬件累计计数 (次)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = state.isCountUp,
                        onClick = { onDirectionChange(true) },
                        label = { Text("加计数", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = !state.isCountUp,
                        onClick = { onDirectionChange(false) },
                        label = { Text("减计数", fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.pulseCount.toString(),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedButton(onClick = onResetCount) {
                    Text("硬件清零")
                }
            }
        }
    }
}

@Composable
fun LineSpeedCard(
    state: TachometerUiState,
    onDiameterChange: (String) -> Unit,
    onUnitChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "线速度换算",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row {
                    FilterChip(
                        selected = state.isMetersPerSecond,
                        onClick = { onUnitChange(true) },
                        label = { Text("m/s", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = !state.isMetersPerSecond,
                        onClick = { onUnitChange(false) },
                        label = { Text("km/h", fontSize = 12.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.diameterMmText,
                    onValueChange = onDiameterChange,
                    label = { Text("旋转体直径 (mm)", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = String.format(Locale.US, "%.2f", state.lineSpeed),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (state.isMetersPerSecond) "米/秒 (m/s)" else "千米/小时 (km/h)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RecentLogCard(logs: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "最近采样记录 (${logs.size}/30)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (logs.isEmpty()) {
                Text(
                    text = "暂无有效采样数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                logs.take(8).forEach { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
