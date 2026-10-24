package com.zeoffice.einkreader.presentation.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zeoffice.einkreader.domain.model.ReadingSettings
import com.zeoffice.einkreader.presentation.ui.theme.READER_THEMES
import com.zeoffice.einkreader.presentation.viewmodel.MainViewModel

private val PREVIEW_TEXT = "夜幕低垂，江面泛起细碎的银光。读书的人合上书本，听见远处传来悠长的钟声——那是时间走过耳畔的声音。文字里藏着的山河与悲欢，都在这一刻静静沉淀。"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val theme = READER_THEMES[settings.theme] ?: READER_THEMES["day"]!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 实时预览
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = theme.background)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "预览效果",
                        color = theme.onBackground.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        PREVIEW_TEXT,
                        color = theme.onBackground,
                        fontSize = with(LocalDensity.current) {
                            (settings.fontSizeSp).sp
                        },
                        lineHeight = with(LocalDensity.current) {
                            (settings.fontSizeSp * settings.lineSpacing).sp
                        },
                        textAlign = if (settings.textAlign == "justify") TextAlign.Justify else TextAlign.Start,
                        fontFamily = when (settings.fontFamily) {
                            "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
                            "song" -> androidx.compose.ui.text.font.FontFamily.Serif
                            else -> androidx.compose.ui.text.font.FontFamily.SansSerif
                        }
                    )
                }
            }

            // 主题
            Text("背景主题", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                READER_THEMES.forEach { (key, c) ->
                    Box(
                        Modifier.size(48.dp)
                            .background(c.background, RoundedCornerShape(8.dp))
                            .border(
                                if (settings.theme == key) 3.dp else 1.dp,
                                if (settings.theme == key) MaterialTheme.colorScheme.primary
                                else Color.Gray,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { vm.saveSettings(settings.copy(theme = key)) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(16.dp)
                                .background(c.onBackground, RoundedCornerShape(4.dp))
                        )
                    }
                }
            }

            Divider()

            // 字号
            StepperRow(
                label = "字号",
                valueText = "${settings.fontSizeSp.toInt()} pt",
                onMinus = {
                    vm.saveSettings(settings.copy(fontSizeSp = (settings.fontSizeSp - 1f).coerceIn(12f, 30f)))
                },
                onPlus = {
                    vm.saveSettings(settings.copy(fontSizeSp = (settings.fontSizeSp + 1f).coerceIn(12f, 30f)))
                }
            )

            // 行距
            StepperRow(
                label = "行距",
                valueText = String.format("%.1f", settings.lineSpacing),
                onMinus = {
                    vm.saveSettings(settings.copy(lineSpacing = (settings.lineSpacing - 0.1f).coerceIn(1.2f, 2.4f)))
                },
                onPlus = {
                    vm.saveSettings(settings.copy(lineSpacing = (settings.lineSpacing + 0.1f).coerceIn(1.2f, 2.4f)))
                }
            )

            // 边距
            StepperRow(
                label = "页边距",
                valueText = "${settings.marginHorizontalDp} dp",
                onMinus = {
                    vm.saveSettings(settings.copy(marginHorizontalDp = (settings.marginHorizontalDp - 2).coerceIn(8, 48)))
                },
                onPlus = {
                    vm.saveSettings(settings.copy(marginHorizontalDp = (settings.marginHorizontalDp + 2).coerceIn(8, 48)))
                }
            )

            Divider()

            // 字体
            Text("字体", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sans" to "黑体", "serif" to "宋体", "song" to "楷体").forEach { (key, label) ->
                    FilterChip(
                        selected = settings.fontFamily == key,
                        onClick = { vm.saveSettings(settings.copy(fontFamily = key)) },
                        label = { Text(label) }
                    )
                }
            }

            // 对齐
            Text("对齐", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("justify" to "两端对齐", "left" to "左对齐").forEach { (key, label) ->
                    FilterChip(
                        selected = settings.textAlign == key,
                        onClick = { vm.saveSettings(settings.copy(textAlign = key)) },
                        label = { Text(label) }
                    )
                }
            }

            Divider()

            // 亮度 (应用内调暗)
            Text("亮度（应用内调暗）", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = settings.brightness,
                onValueChange = { vm.saveSettings(settings.copy(brightness = it)) },
                valueRange = 0.3f..1f
            )
            Text(
                "当前亮度：${String.format("%.0f", settings.brightness * 100)}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 保持屏幕常亮
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("保持屏幕常亮", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = settings.keepScreenOn,
                    onCheckedChange = { vm.saveSettings(settings.copy(keepScreenOn = it)) }
                )
            }

            Divider()

            // ===== 电纸书（墨水屏）适配 =====
            Text("电纸书适配", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("电纸书模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭动画/水波纹、纯黑白高对比、支持物理翻页键",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.einkMode,
                    onCheckedChange = { vm.saveSettings(settings.copy(einkMode = it)) }
                )
            }

            // 物理翻页键映射
            Text("物理翻页键", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "off" to "关闭",
                    "prev_next" to "上页·下页",
                    "next_prev" to "下页·上页"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = settings.pageKeyAction == key,
                        onClick = { vm.saveSettings(settings.copy(pageKeyAction = key)) },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "将设备实体上下翻页键映射为内容翻页。若方向反了，请选「下页·上页」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMinus) {
                Text("－", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                valueText,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onPlus) {
                Text("＋", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
