package com.fluxdrop.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.core.Fmt
import com.fluxdrop.app.core.PickedFile
import com.fluxdrop.app.ui.components.FileBadge
import com.fluxdrop.app.ui.components.GhostButton
import com.fluxdrop.app.ui.components.GlassCard
import com.fluxdrop.app.ui.components.GlassIconButton
import com.fluxdrop.app.ui.components.GradientButton
import com.fluxdrop.app.ui.components.PulseDot
import com.fluxdrop.app.ui.components.RadarView
import com.fluxdrop.app.ui.theme.Flux

@Composable
fun RadarScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val addrs by vm.addresses.collectAsStateWithLifecycle()
    val serverOn by vm.serverOn.collectAsStateWithLifecycle()
    var showManual by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addUris(it) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addUris(it) }

    val selCount = devices.count { it.id in selected }
    val ip = addrs.firstOrNull()?.ip

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        // 顶部
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("闪渡", style = TextStyle(brush = Flux.accentH, fontSize = 30.sp, fontWeight = FontWeight.Black))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(color = if (ip != null && serverOn) Flux.Mint else Flux.Amber)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$name · ${ip ?: "未连接网络"}",
                        color = Flux.TextMid,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            GlassIconButton(Icons.Rounded.Link, onClick = { showManual = true })
            Spacer(Modifier.width(10.dp))
            GlassIconButton(Icons.Rounded.Settings, onClick = { showSettings = true })
        }

        // 雷达
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            RadarView(
                devices = devices,
                selected = selected,
                onToggle = vm::toggle,
                onCenterClick = vm::refreshRadar,
            )
            SearchingHint(visible = devices.isEmpty(), modifier = Modifier.align(Alignment.BottomCenter))
        }

        // 待发送文件
        FilesTray(
            files = picked,
            onMedia = {
                mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onDocs = { docPicker.launch(arrayOf("*/*")) },
            onRemove = vm::removeFile,
            onClear = vm::clearFiles,
        )

        val ready = selCount > 0 && picked.isNotEmpty()
        val label = when {
            picked.isEmpty() && selCount == 0 -> "选择文件，再点选雷达上的设备"
            picked.isEmpty() -> "已选 $selCount 台设备，请选择文件"
            selCount == 0 -> "点选雷达上的设备"
            else -> "发送给 $selCount 台设备"
        }
        GradientButton(
            text = label,
            onClick = vm::send,
            icon = Icons.AutoMirrored.Rounded.Send,
            enabled = ready,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        )
    }

    if (showManual) {
        ManualIpDialog(
            onDismiss = { showManual = false },
            onConnect = { input, done -> vm.probe(input) { ok -> done(ok) } },
        )
    }
    if (showSettings) {
        SettingsSheet(vm = vm, onDismiss = { showSettings = false })
    }
}

@Composable
private fun SearchingHint(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在搜寻附近设备…", color = Flux.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "请让对方也打开闪渡，并连接同一 Wi-Fi 或热点",
                color = Flux.TextLow,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FilesTray(
    files: List<PickedFile>,
    onMedia: () -> Unit,
    onDocs: () -> Unit,
    onRemove: (PickedFile) -> Unit,
    onClear: () -> Unit,
) {
    GlassCard(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        padding = 14.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (files.isEmpty()) "选择要发送的内容" else "已选 ${files.size} 个文件",
                    color = Flux.TextHi,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Text(
                    if (files.isEmpty()) "照片、视频、安装包、任意文件，不限大小"
                    else "共 ${Fmt.bytes(files.sumOf { it.size })}",
                    color = Flux.TextLow,
                    fontSize = 12.sp,
                )
            }
            if (files.isNotEmpty()) {
                Text(
                    "清空",
                    color = Flux.TextMid,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        AnimatedVisibility(visible = files.isNotEmpty()) {
            LazyRow(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.uri.toString() }) { f ->
                    FileChip(f, onRemove = { onRemove(f) }, modifier = Modifier.animateItem())
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("相册", onClick = onMedia, icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.weight(1f), height = 44.dp)
            GhostButton("文件", onClick = onDocs, icon = Icons.Rounded.FolderOpen, modifier = Modifier.weight(1f), height = 44.dp)
        }
    }
}

@Composable
private fun FileChip(f: PickedFile, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x14FFFFFF))
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(f.name, f.mime, size = 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 120.dp)) {
            Text(f.name, color = Flux.TextHi, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Fmt.bytes(f.size), color = Flux.TextLow, fontSize = 10.sp)
        }
        Box(
            Modifier
                .padding(start = 4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "移除", tint = Flux.TextLow, modifier = Modifier.size(14.dp))
        }
    }
}
