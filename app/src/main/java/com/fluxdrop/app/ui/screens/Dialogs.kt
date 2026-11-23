package com.fluxdrop.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.core.Fmt
import com.fluxdrop.app.core.IncomingRequest
import com.fluxdrop.app.ui.components.DeviceAvatar
import com.fluxdrop.app.ui.components.FileBadge
import com.fluxdrop.app.ui.components.GhostButton
import com.fluxdrop.app.ui.components.GlassCard
import com.fluxdrop.app.ui.components.GradientButton
import com.fluxdrop.app.ui.components.GradientProgress
import com.fluxdrop.app.ui.components.SectionLabel
import com.fluxdrop.app.ui.theme.Flux

/** 有人发来文件：带波纹头像、文件预览、60 秒倒计时 */
@Composable
fun IncomingDialog(request: IncomingRequest, onAccept: () -> Unit, onReject: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val appear = remember(request.id) { Animatable(0.85f) }
    val left = remember(request.id) { Animatable(1f) }
    LaunchedEffect(request.id) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(request.id) {
        val elapsed = System.currentTimeMillis() - request.createdAt
        val start = (1f - elapsed / 60_000f).coerceIn(0f, 1f)
        left.snapTo(start)
        left.animateTo(0f, tween((start * 60_000).toInt().coerceAtLeast(1), easing = LinearEasing))
    }
    val tr = rememberInfiniteTransition(label = "incoming")
    val wave by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "wave")

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false, usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(30.dp)
        Column(
            Modifier
                .padding(22.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = appear.value
                    scaleY = appear.value
                }
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF18204A), Color(0xFF0D1330))))
                .border(1.dp, Flux.GlassBorder, shape)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (k in 0 until 3) {
                        val p = (wave + k / 3f) % 1f
                        drawCircle(
                            Flux.Cyan.copy(alpha = (1f - p) * 0.45f),
                            radius = size.minDimension / 2f * (0.45f + 0.55f * p),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                DeviceAvatar(request.senderKind, request.senderName, 66.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(request.senderName, color = Flux.TextHi, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                "想发送 ${request.files.size} 个文件给你 · ${Fmt.bytes(request.totalBytes)}",
                color = Flux.TextMid,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            GlassCard(Modifier.fillMaxWidth(), corner = 18.dp, padding = 12.dp) {
                request.files.take(4).forEachIndexed { i, f ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(f.name, f.mime, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            f.name,
                            color = Flux.TextHi,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(Fmt.bytes(f.size), color = Flux.TextLow, fontSize = 12.sp)
                    }
                }
                if (request.files.size > 4) {
                    Spacer(Modifier.height(8.dp))
                    Text("…还有 ${request.files.size - 4} 个文件", color = Flux.TextLow, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            GradientProgress(left.value, active = false, brush = Flux.coolH, modifier = Modifier.height(4.dp))
            Spacer(Modifier.height(6.dp))
            Text("${(left.value * 60).toInt()} 秒后自动拒绝", color = Flux.TextLow, fontSize = 11.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("拒绝", onClick = onReject, icon = Icons.Rounded.Close, modifier = Modifier.weight(1f), height = 52.dp)
                GradientButton("接收", onClick = onAccept, icon = Icons.Rounded.Check, modifier = Modifier.weight(1f), height = 52.dp)
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Flux.Cyan,
    unfocusedBorderColor = Flux.GlassBorder,
    focusedTextColor = Flux.TextHi,
    unfocusedTextColor = Flux.TextHi,
    cursorColor = Flux.Cyan,
    focusedLabelColor = Flux.Cyan,
    unfocusedLabelColor = Flux.TextLow,
    focusedPlaceholderColor = Flux.TextLow,
    unfocusedPlaceholderColor = Flux.TextLow,
)

/** 第一步的核心能力：输入对方 IP 直连 */
@Composable
fun ManualIpDialog(onDismiss: () -> Unit, onConnect: (String, (Boolean) -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val submit = {
        if (text.isNotBlank() && !busy) {
            busy = true
            onConnect(text) { ok ->
                busy = false
                if (ok) onDismiss()
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131B3B),
        shape = RoundedCornerShape(26.dp),
        title = { Text("输入对方 IP 直连", color = Flux.TextHi, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "雷达没扫到时可手动连接。对方的 IP 显示在其「雷达」页顶部。",
                    color = Flux.TextMid,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    singleLine = true,
                    label = { Text("IP 地址") },
                    placeholder = { Text("例如 192.168.1.23") },
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !busy && text.isNotBlank()) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Flux.Cyan)
                } else {
                    Text("连接", color = Flux.Cyan, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Flux.TextMid) }
        },
    )
}

/** 设置：设备名、自动接收 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val name by vm.deviceName.collectAsStateWithLifecycle()
    val auto by vm.autoAccept.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(name) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color(0xFF10172F),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text("设置", color = Flux.TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            SectionLabel("设备名称")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = editing,
                onValueChange = { editing = it.take(32) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = Flux.TextLow) },
                trailingIcon = {
                    if (editing.isNotBlank() && editing != name) {
                        TextButton(onClick = { vm.setName(editing) }) { Text("保存", color = Flux.Cyan) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.setName(editing) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("其他设备的雷达上会显示这个名字", color = Flux.TextLow, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

            Spacer(Modifier.height(22.dp))
            GlassCard(Modifier.fillMaxWidth(), corner = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动接收", color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("开启后无需确认，直接接收所有文件", color = Flux.TextLow, fontSize = 12.sp)
                    }
                    Switch(
                        checked = auto,
                        onCheckedChange = vm::setAutoAccept,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Flux.Violet,
                            uncheckedThumbColor = Flux.TextMid,
                            uncheckedTrackColor = Color(0x22FFFFFF),
                            uncheckedBorderColor = Flux.GlassBorder,
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("保存位置", color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("手机存储 / Download / FluxDrop", color = Flux.TextLow, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "闪渡 FluxDrop 1.0 · 局域网直连，文件不经过任何服务器",
                color = Flux.TextLow,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
