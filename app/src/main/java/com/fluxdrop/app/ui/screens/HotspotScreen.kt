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
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.core.Device
import com.fluxdrop.app.core.HTTP_PORT
import com.fluxdrop.app.core.NetUtils
import com.fluxdrop.app.hotspot.FixAction
import com.fluxdrop.app.hotspot.HostState
import com.fluxdrop.app.hotspot.HotspotInfo
import com.fluxdrop.app.hotspot.JoinState
import com.fluxdrop.app.ui.components.GhostButton
import com.fluxdrop.app.ui.components.GlassCard
import com.fluxdrop.app.ui.components.GlowingQrCard
import com.fluxdrop.app.ui.components.GradientButton
import com.fluxdrop.app.ui.components.InfoRow
import com.fluxdrop.app.ui.components.PulseDot
import com.fluxdrop.app.ui.components.ScreenHeader
import com.fluxdrop.app.ui.theme.Flux
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

@Composable
fun HotspotScreen(vm: MainViewModel, contentPadding: PaddingValues, onGoRadar: () -> Unit) {
    val host by vm.hostState.collectAsStateWithLifecycle()
    val join by vm.joinState.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current

    fun doStart() {
        if (Build.VERSION.SDK_INT < 33 && !locationOn(ctx)) {
            toast(ctx, "Android 12 及以下开热点需要先打开系统「定位」开关")
            openSafely(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        vm.startHotspot()
    }

    val perms = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            doStart()
        } else {
            toast(ctx, "需要「附近设备 / 位置信息」权限才能开启热点，请在系统设置中允许")
            openSafely(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
        }
    }
    val permOnlyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            toast(ctx, "权限已授予")
        } else {
            toast(ctx, "权限被拒绝，请在系统设置 → 权限 中手动允许")
            openSafely(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
        }
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r -> vm.onScanResult(r.contents) }
    // Android 11+ 的「系统方式连接」必须用 startActivityForResult 启动，系统才知道是哪个应用在添加网络
    val addNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val onStart: () -> Unit = {
        val missing = perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) doStart() else permLauncher.launch(perms)
    }
    val onScan: () -> Unit = {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准对方屏幕上的二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
    val connectViaSystem: (HotspotInfo) -> Unit = { info ->
        if (info.password.isNotEmpty()) copy(ctx, info.password, "密码已复制，系统要求输入时直接粘贴")
        val intent = vm.systemConnectIntent(info)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) addNetworkLauncher.launch(intent) else ctx.startActivity(intent)
            vm.markSystemConnecting(info)
        } catch (e: Exception) {
            openSafely(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))
            vm.markSystemConnecting(info)
        }
    }
    val runFix: (FixAction, HotspotInfo?) -> Unit = { fix, info ->
        when (fix) {
            FixAction.LOCATION -> openSafely(ctx, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            FixAction.WIFI -> openSafely(
                ctx,
                Intent(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS),
            )
            FixAction.SYSTEM_HOTSPOT -> {
                val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
                if (!openSafely(ctx, tether, silent = true)) openSafely(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            FixAction.SYSTEM_CONNECT -> info?.let(connectViaSystem)
            FixAction.NONE -> Unit
        }
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
                if (m == 0) {
                    HostPanel(
                        host = host,
                        devices = devices,
                        onStart = onStart,
                        onStop = vm::stopHotspot,
                        onRestart = vm::restartHotspot,
                        onFix = { runFix(it, null) },
                    )
                } else {
                    JoinPanel(
                        join = join,
                        onScan = onScan,
                        onLeave = vm::leaveHotspot,
                        onGoRadar = onGoRadar,
                        onRetry = vm::retryJoin,
                        onSystem = connectViaSystem,
                        onFix = runFix,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        DiagnosticsCard(
            vm = vm,
            perms = perms,
            onRequestPerms = { permOnlyLauncher.launch(perms) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// =====================================================================
// 权限与诊断
// =====================================================================

@Composable
private fun DiagnosticsCard(
    vm: MainViewModel,
    perms: Array<String>,
    onRequestPerms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val logLines by vm.engine.hotspot.log.collectAsStateWithLifecycle()
    // 每 2 秒刷新一次开关状态
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }
    // 读取 tick，让下面的状态每 2 秒重新计算一次
    val refreshKey = tick
    if (refreshKey < 0) return

    val permOk = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val permName = if (Build.VERSION.SDK_INT >= 33) "「附近设备」权限" else "「位置信息」权限"
    val locOn = locationOn(ctx)
    val wifiOn = vm.engine.hotspot.wifiEnabled()
    val sysAp = vm.engine.hotspot.systemHotspotState()
    val ifaces = NetUtils.localIpv4().joinToString("，") { "${it.iface} ${it.ip}" }.ifEmpty { "无" }

    GlassCard(modifier.fillMaxWidth(), corner = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("权限与诊断", color = Flux.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "复制诊断信息",
                color = Flux.Cyan,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val text = buildString {
                            appendLine("闪渡诊断 · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine("$permName=${if (permOk) "已授予" else "未授予"}，定位=${if (locOn) "开" else "关"}，Wi-Fi=${if (wifiOn) "开" else "关"}，系统热点=${sysAp ?: "未知"}")
                            appendLine("网卡：$ifaces")
                            logLines.forEach { appendLine(it) }
                        }
                        copy(ctx, text, "诊断信息已复制，可以直接粘贴发送")
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        DiagRow(permName, if (permOk) "已授予" else "未授予", permOk)
        if (!permOk) {
            Spacer(Modifier.height(6.dp))
            GhostButton("去授予权限", onClick = onRequestPerms, icon = Icons.Rounded.Build, modifier = Modifier.fillMaxWidth(), height = 40.dp)
            Spacer(Modifier.height(6.dp))
        }
        DiagRow(
            "定位开关",
            if (locOn) "开" else "关" + if (Build.VERSION.SDK_INT < 33) "（本机开热点必须打开）" else "",
            locOn || Build.VERSION.SDK_INT >= 33,
        )
        DiagRow("Wi-Fi 开关", if (wifiOn) "开" else "关", true)
        DiagRow(
            "系统「个人热点」",
            when (sysAp) {
                true -> "开着（建议关闭）"
                false -> "关闭"
                null -> "无法读取"
            },
            sysAp != true,
        )
        DiagRow("网卡", ifaces, true)
        Spacer(Modifier.height(8.dp))
        Text(
            "说明：闪渡开的是「本地专用热点」，和系统设置里的「个人热点」是两回事，" +
                "所以系统热点开关保持灰色是正常的。判断是否成功，请看上方是否显示「热点已开启」，" +
                "或在另一台手机的 Wi-Fi 列表里找上方显示的网络名称。",
            color = Flux.TextLow,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
        if (logLines.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("系统返回记录", color = Flux.TextMid, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            logLines.takeLast(10).forEach {
                Text(it, color = Flux.TextLow, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(if (ok) "✓" else "✗", color = if (ok) Flux.Mint else Flux.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(label, color = Flux.TextMid, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = if (ok) Flux.TextHi else Flux.Red, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

// =====================================================================
// 发起方
// =====================================================================

@Composable
private fun HostPanel(
    host: HostState,
    devices: List<Device>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onFix: (FixAction) -> Unit,
) {
    when (host) {
        is HostState.Running -> RunningHost(host, devices, onStop, onRestart)
        is HostState.Starting -> GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Flux.Cyan, strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("正在开启免流量热点…", color = Flux.TextHi, fontSize = 15.sp)
                if (host.attempt > 1) {
                    Text("系统暂时没开成功，正在自动重试（第 ${host.attempt} 次）", color = Flux.TextLow, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        else -> Column {
            GlassCard(Modifier.fillMaxWidth()) {
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
                        Spacer(Modifier.height(14.dp))
                        ErrorLine(host.msg)
                        if (host.fix != FixAction.NONE) {
                            Spacer(Modifier.height(10.dp))
                            GhostButton(fixLabel(host.fix), onClick = { onFix(host.fix) }, icon = Icons.Rounded.Build, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    GradientButton(
                        if (host is HostState.Error) "重新开启" else "开启热点",
                        onClick = onStart,
                        icon = Icons.Rounded.WifiTethering,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            TipsCard(
                "开启失败怎么办",
                listOf(
                    "先关闭系统自带的「个人热点 / 网络共享」",
                    "打开手机的 Wi-Fi 开关（不需要连接网络）",
                    "Android 12 及以下需要打开「定位」开关",
                    "在系统设置中把闪渡的「省电策略」设为「无限制」",
                ),
            )
        }
    }
}

@Composable
private fun RunningHost(host: HostState.Running, devices: List<Device>, onStop: () -> Unit, onRestart: () -> Unit) {
    val ctx = LocalContext.current
    val info = host.info

    // 展示二维码期间保持亮屏：很多手机熄屏后会把热点关掉
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val prefix = host.ip?.substringBeforeLast('.')
    val joined = if (prefix == null) 0 else devices.count { it.ip.startsWith("$prefix.") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GlowingQrCard(info.qrPayload(), size = 264.dp)
        Spacer(Modifier.height(16.dp))

        // 真实状态
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0x14FFFFFF))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                host.verifying -> {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Flux.Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在确认热点已就绪…", color = Flux.TextMid, fontSize = 13.sp)
                }
                host.ip == null -> {
                    PulseDot(color = Flux.Amber)
                    Spacer(Modifier.width(8.dp))
                    Text("未检测到热点网卡，若对方搜不到请点「重新开启」", color = Flux.Amber, fontSize = 13.sp)
                }
                else -> {
                    PulseDot(color = Flux.Mint)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (joined > 0) "热点已开启 · 已有 $joined 台设备连入" else "热点已开启 · 等待对方扫码",
                        color = Flux.Mint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("让对方用闪渡「扫码加入」", color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("也可以用系统相机 / 微信扫一扫连接 Wi-Fi", color = Flux.TextLow, fontSize = 12.sp)
        Text(
            "注意：这是独立的临时热点，系统设置里的「个人热点」开关不会亮，属于正常现象",
            color = Flux.TextLow,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            InfoRow("网络名称", info.ssid) { CopyButton(ctx, info.ssid) }
            if (info.password.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                InfoRow("密码", info.password) { CopyButton(ctx, info.password) }
            }
            if (host.ip != null) {
                Spacer(Modifier.height(12.dp))
                val url = "http://${host.ip}:$HTTP_PORT"
                InfoRow("电脑连上此 Wi-Fi 后，浏览器打开", url) { CopyButton(ctx, url) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("重新开启", onClick = onRestart, icon = Icons.Rounded.Refresh, modifier = Modifier.weight(1f))
            GhostButton("关闭热点", onClick = onStop, icon = Icons.Rounded.PowerSettingsNew, tint = Flux.Red, modifier = Modifier.weight(1f))
        }
    }
}

// =====================================================================
// 加入方
// =====================================================================

@Composable
private fun JoinPanel(
    join: JoinState,
    onScan: () -> Unit,
    onLeave: () -> Unit,
    onGoRadar: () -> Unit,
    onRetry: (HotspotInfo) -> Unit,
    onSystem: (HotspotInfo) -> Unit,
    onFix: (FixAction, HotspotInfo?) -> Unit,
) {
    Column {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(8.dp))
                WaveIcon(Icons.Rounded.QrCodeScanner)
                Spacer(Modifier.height(12.dp))
                Text("扫码连接对方热点", color = Flux.TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "扫描对方「我来开热点」页面上的二维码，自动连接，\n连上后会自动找到对方。",
                    color = Flux.TextMid,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(16.dp))

                when (join) {
                    is JoinState.Connecting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Flux.Cyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (join.viaSystem) "等待系统连上「${join.info.ssid}」…" else "正在连接「${join.info.ssid}」",
                                color = Flux.TextMid,
                                fontSize = 13.sp,
                            )
                        }
                        if (!join.viaSystem) {
                            Text(
                                "如果弹出系统窗口，请选择该网络并点「连接」",
                                color = Flux.TextLow,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        if (!join.viaSystem) {
                            GhostButton("一直连不上？用系统方式连接", onClick = { onSystem(join.info) }, icon = Icons.Rounded.Settings, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                        }
                        GhostButton("取消", onClick = onLeave, icon = Icons.Rounded.LinkOff, modifier = Modifier.fillMaxWidth())
                    }

                    is JoinState.Connected -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Flux.Mint, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("已连接「${join.ssid}」", color = Flux.Mint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            if (join.hostIp != null) "对方地址 ${join.hostIp}，正在自动添加到雷达" else "回到雷达即可看到对方",
                            color = Flux.TextLow,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        GradientButton("去雷达找对方", onClick = onGoRadar, icon = Icons.Rounded.TrackChanges, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        GhostButton("断开", onClick = onLeave, icon = Icons.Rounded.LinkOff, modifier = Modifier.fillMaxWidth())
                    }

                    is JoinState.Error -> {
                        ErrorLine(join.msg)
                        Spacer(Modifier.height(12.dp))
                        if (join.fix != FixAction.NONE) {
                            GhostButton(fixLabel(join.fix), onClick = { onFix(join.fix, join.info) }, icon = Icons.Rounded.Build, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                        }
                        val info = join.info
                        if (info != null) {
                            GradientButton("重试连接「${info.ssid}」", onClick = { onRetry(info) }, icon = Icons.Rounded.Refresh, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                        }
                        GhostButton("重新扫码", onClick = onScan, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.fillMaxWidth())
                    }

                    JoinState.Idle -> {
                        GradientButton("扫一扫", onClick = onScan, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        TipsCard(
            "连不上怎么办",
            listOf(
                "确认对方页面显示「热点已开启」，两台手机靠近一些",
                "本机 Wi-Fi 开关要打开；Android 10～12 还要打开「定位」",
                "弹出系统连接窗口时，要点选网络并确认",
                "仍然不行就点「用系统方式连接」，或直接在系统 Wi-Fi 列表里连接",
            ),
        )
    }
}

private fun fixLabel(fix: FixAction) = when (fix) {
    FixAction.LOCATION -> "打开定位开关"
    FixAction.WIFI -> "打开 Wi-Fi 开关"
    FixAction.SYSTEM_HOTSPOT -> "去关闭系统热点"
    FixAction.SYSTEM_CONNECT -> "用系统方式连接"
    FixAction.NONE -> ""
}

// =====================================================================
// 通用小组件
// =====================================================================

@Composable
private fun TipsCard(title: String, tips: List<String>) {
    GlassCard(Modifier.fillMaxWidth(), corner = 20.dp) {
        Text(title, color = Flux.TextHi, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        tips.forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}.", color = Flux.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                Text(t, color = Flux.TextMid, fontSize = 12.sp, lineHeight = 18.sp)
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
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Flux.Red.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Flux.Red, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(msg, color = Flux.Red, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
fun CopyButton(ctx: Context, text: String) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x14FFFFFF))
            .clickable { copy(ctx, text, "已复制") },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.ContentCopy, "复制", tint = Flux.TextMid, modifier = Modifier.size(17.dp)) }
}

private fun copy(ctx: Context, text: String, tip: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("FluxDrop", text))
    toast(ctx, tip)
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

private fun locationOn(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return LocationManagerCompat.isLocationEnabled(lm)
}

private fun openSafely(ctx: Context, intent: Intent, silent: Boolean = false): Boolean = try {
    ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    if (!silent) toast(ctx, "无法打开系统设置，请手动前往")
    false
}
