package com.fluxdrop.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.core.HTTP_PORT
import com.fluxdrop.app.core.NetUtils
import com.fluxdrop.app.hotspot.HostState
import com.fluxdrop.app.hotspot.HotspotInfo
import com.fluxdrop.app.hotspot.JoinState
import com.fluxdrop.app.ui.components.GhostButton
import com.fluxdrop.app.ui.components.GlassCard
import com.fluxdrop.app.ui.components.GlowingQrCard
import com.fluxdrop.app.ui.components.GradientButton
import com.fluxdrop.app.ui.components.InfoRow
import com.fluxdrop.app.ui.components.ScreenHeader
import com.fluxdrop.app.ui.theme.Flux
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun HotspotScreen(vm: MainViewModel, contentPadding: PaddingValues, onGoRadar: () -> Unit) {
    val host by vm.hostState.collectAsStateWithLifecycle()
    val join by vm.joinState.collectAsStateWithLifecycle()
    val addrs by vm.addresses.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current

    fun doStart() {
        if (Build.VERSION.SDK_INT < 33) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                Toast.makeText(ctx, "Android 12 及以下开热点需要先打开系统「定位」开关", Toast.LENGTH_LONG).show()
                try {
                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                }
                return
            }
        }
        vm.startHotspot()
    }

    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) doStart()
        else Toast.makeText(ctx, "需要「附近设备 / 位置」权限才能开启热点", Toast.LENGTH_LONG).show()
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r -> vm.onScanResult(r.contents) }

    val onStart = {
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) doStart() else permLauncher.launch(perms)
    }
    val onScan = {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准对方屏幕上的二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
    ) {
        ScreenHeader("热点直连", "没有 Wi-Fi、不耗流量，也能高速互传")
        SegmentedToggle(
            options = listOf("我来开热点", "扫码加入"),
            selected = mode,
            onSelect = { mode = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = mode,
            transitionSpec = { (fadeIn(tween(250)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(150)) },
            label = "hotspotMode",
        ) { m ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (m == 0) HostPanel(host, addrs, onStart = onStart, onStop = vm::stopHotspot)
                else JoinPanel(join, onScan = onScan, onLeave = vm::leaveHotspot, onGoRadar = onGoRadar)
            }
        }
    }
}

@Composable
private fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(Color(0x12FFFFFF))
            .border(1.dp, Flux.GlassBorder, shape)
            .padding(4.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, spring(0.75f, Spring.StiffnessMediumLow), label = "seg")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Flux.accentH),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (i == selected) Color.White else Flux.TextMid,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/** 发起方：开热点 → 出二维码 */
@Composable
private fun HostPanel(host: HostState, addrs: List<NetUtils.LocalAddr>, onStart: () -> Unit, onStop: () -> Unit) {
    when (host) {
        is HostState.Running -> RunningHost(host.info, addrs, onStop)
        HostState.Starting -> GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Flux.Cyan, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("正在开启免流量热点…", color = Flux.TextHi, fontSize = 15.sp)
            }
        }
        else -> GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                WaveIcon(Icons.Rounded.WifiTethering)
                Spacer(Modifier.height(12.dp))
                Text("一键开启免流量热点", color = Flux.TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "本机临时创建一个专用热点，不共享移动数据、不消耗流量。\n对方扫码即可连接，适合户外、没有路由器的场景。",
                    color = Flux.TextMid,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
                )
                if (host is HostState.Error) {
                    Spacer(Modifier.height(12.dp))
                    ErrorLine(host.msg)
                }
                Spacer(Modifier.height(20.dp))
                GradientButton("开启热点", onClick = onStart, icon = Icons.Rounded.WifiTethering, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RunningHost(info: HotspotInfo, addrs: List<NetUtils.LocalAddr>, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val hostIp = addrs.firstOrNull { it.isHotspot }?.ip ?: addrs.lastOrNull()?.ip
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GlowingQrCard(info.qrPayload(), size = 264.dp)
        Spacer(Modifier.height(16.dp))
        Text("让对方用闪渡「扫码加入」", color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("也可以用系统相机 / 微信扫一扫连接 Wi-Fi", color = Flux.TextLow, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            InfoRow("网络名称", info.ssid) { CopyButton(ctx, info.ssid) }
            if (info.password.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                InfoRow("密码", info.password) { CopyButton(ctx, info.password) }
            }
            if (hostIp != null) {
                Spacer(Modifier.height(12.dp))
                val url = "http://$hostIp:$HTTP_PORT"
                InfoRow("电脑连上此 Wi-Fi 后，浏览器打开", url) { CopyButton(ctx, url) }
            }
        }
        Spacer(Modifier.height(14.dp))
        GhostButton("关闭热点", onClick = onStop, icon = Icons.Rounded.PowerSettingsNew, tint = Flux.Red, modifier = Modifier.fillMaxWidth())
    }
}

/** 接收方：扫码 → 自动连接热点 */
@Composable
private fun JoinPanel(join: JoinState, onScan: () -> Unit, onLeave: () -> Unit, onGoRadar: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            WaveIcon(Icons.Rounded.QrCodeScanner)
            Spacer(Modifier.height(12.dp))
            Text("扫码连接对方热点", color = Flux.TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "扫描对方「我来开热点」页面上的二维码，自动连接，\n连上后回到雷达就能看到对方。",
                color = Flux.TextMid,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            when (join) {
                is JoinState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Flux.Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在连接「${join.ssid}」，请在系统弹窗中确认", color = Flux.TextMid, fontSize = 13.sp)
                }
                is JoinState.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Flux.Mint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("已连接「${join.ssid}」", color = Flux.Mint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                is JoinState.Error -> ErrorLine(join.msg)
                JoinState.Idle -> Unit
            }
            Spacer(Modifier.height(16.dp))
            if (join is JoinState.Connected) {
                GradientButton("去雷达找对方", onClick = onGoRadar, icon = Icons.Rounded.TrackChanges, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                GhostButton("断开", onClick = onLeave, icon = Icons.Rounded.LinkOff, modifier = Modifier.fillMaxWidth())
            } else {
                GradientButton("扫一扫", onClick = onScan, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun WaveIcon(icon: ImageVector) {
    val tr = rememberInfiniteTransition(label = "wave")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0 until 3) {
                val f = (p + k / 3f) % 1f
                drawCircle(
                    Flux.Violet.copy(alpha = (1f - f) * 0.5f),
                    radius = size.minDimension / 2f * (0.5f + 0.5f * f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Flux.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun ErrorLine(msg: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Flux.Red.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Flux.Red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(msg, color = Flux.Red, fontSize = 13.sp)
    }
}

@Composable
fun CopyButton(ctx: Context, text: String) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .clickable {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("FluxDrop", text))
                Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.ContentCopy, "复制", tint = Flux.TextMid, modifier = Modifier.size(17.dp)) }
}
