package com.fluxdrop.app.ui.screens

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.core.Direction
import com.fluxdrop.app.core.Fmt
import com.fluxdrop.app.core.TaskSnapshot
import com.fluxdrop.app.core.TaskState
import com.fluxdrop.app.ui.components.DeviceAvatar
import com.fluxdrop.app.ui.components.GlassCard
import com.fluxdrop.app.ui.components.GlassIconButton
import com.fluxdrop.app.ui.components.GradientProgress
import com.fluxdrop.app.ui.components.ScreenHeader
import com.fluxdrop.app.ui.theme.Flux

@Composable
fun TransfersScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val active = tasks.filter { it.state.active }
    val speed = active.sumOf { it.speed }
    val subtitle = when {
        active.isNotEmpty() -> "${active.size} 个进行中 · 总速度 ${Fmt.speed(speed)}"
        tasks.isEmpty() -> "发送与接收记录会显示在这里"
        else -> "共 ${tasks.size} 条记录"
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("传输", subtitle) {
            if (tasks.any { !it.state.active }) GlassIconButton(Icons.Rounded.DeleteSweep, onClick = vm::clearFinished)
        }
        if (tasks.isEmpty()) {
            EmptyTransfers(Modifier.weight(1f).padding(bottom = contentPadding.calculateBottomPadding()))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = contentPadding.calculateBottomPadding() + 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tasks, key = { it.id }) { t ->
                    TaskCard(
                        t = t,
                        onCancel = { vm.cancel(t.id) },
                        onOpen = { openTask(ctx, t) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTransfers(modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(
            Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Flux.Violet.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(Flux.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.SwapVert, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Text("还没有传输记录", color = Flux.TextHi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "去「雷达」选文件和设备，一次发给多台",
            color = Flux.TextLow,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 40.dp, end = 40.dp),
        )
    }
}

@Composable
private fun TaskCard(t: TaskSnapshot, onCancel: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(t.progress, tween(300), label = "progress")
    val out = t.direction == Direction.OUT
    val stateColor = when (t.state) {
        TaskState.WAITING -> Flux.Amber
        TaskState.TRANSFERRING -> Flux.Cyan
        TaskState.DONE -> Flux.Mint
        TaskState.REJECTED, TaskState.FAILED -> Flux.Red
        TaskState.CANCELED -> Flux.TextLow
    }
    val barBrush = when (t.state) {
        TaskState.DONE -> Flux.mintH
        TaskState.FAILED, TaskState.REJECTED -> Brush.horizontalGradient(listOf(Flux.Red, Flux.Pink))
        TaskState.CANCELED -> Brush.horizontalGradient(listOf(Flux.TextLow, Flux.TextLow))
        else -> Flux.accentH
    }
    val canOpen = !out && t.state == TaskState.DONE && t.savedUris.isNotEmpty()

    GlassCard(
        modifier
            .fillMaxWidth()
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                DeviceAvatar(t.peerKind, t.peerName, 46.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (out) Flux.Cyan else Flux.Mint)
                        .border(2.dp, Flux.Bg1, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (out) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                        null,
                        tint = Flux.Bg0,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (out) "发送给 ${t.peerName}" else "来自 ${t.peerName}",
                    color = Flux.TextHi,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val fileLine = when {
                    t.fileCount == 1 -> t.firstName
                    t.state == TaskState.TRANSFERRING -> "(${t.currentIndex + 1}/${t.fileCount}) ${t.currentName}"
                    else -> "${t.firstName} 等 ${t.fileCount} 个文件"
                }
                Text(fileLine, color = Flux.TextMid, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            when {
                t.state.active -> Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0x14FFFFFF))
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "取消", tint = Flux.TextMid, modifier = Modifier.size(18.dp)) }
                canOpen -> Text(
                    "打开",
                    color = Flux.Bg0,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Flux.mintH)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        GradientProgress(progress, active = t.state == TaskState.TRANSFERRING, brush = barBrush)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val status = when (t.state) {
                TaskState.WAITING -> t.message ?: "等待中…"
                TaskState.TRANSFERRING -> {
                    val eta = if (t.speed > 1) ((t.totalBytes - t.doneBytes) / t.speed).toLong() else -1L
                    "${Fmt.bytes(t.doneBytes)} / ${Fmt.bytes(t.totalBytes)} · ${Fmt.speed(t.speed)} · 剩余 ${Fmt.eta(eta)}"
                }
                TaskState.DONE -> "已完成 · ${Fmt.bytes(t.totalBytes)}"
                TaskState.REJECTED -> t.message ?: "对方已拒绝"
                TaskState.FAILED -> "失败：" + (t.message ?: "传输中断")
                TaskState.CANCELED -> "已取消"
            }
            Text(
                status,
                color = if (t.state == TaskState.TRANSFERRING) Flux.TextMid else stateColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(progress * 100).toInt()}%",
                color = stateColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun openTask(ctx: Context, t: TaskSnapshot) {
    try {
        if (t.savedUris.size == 1) {
            val uri = t.savedUris.first()
            val mime = ctx.contentResolver.getType(uri) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(view, "打开文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ctx.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "没有可打开此文件的应用，文件在 Download/FluxDrop", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "文件在 Download/FluxDrop", Toast.LENGTH_LONG).show()
    }
}
