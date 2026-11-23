package com.fluxdrop.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.core.HTTP_PORT
import com.fluxdrop.app.ui.components.DeviceAvatar
import com.fluxdrop.app.ui.components.GlassCard
import com.fluxdrop.app.ui.components.PulseDot
import com.fluxdrop.app.ui.components.QrCode
import com.fluxdrop.app.ui.components.ScreenHeader
import com.fluxdrop.app.ui.components.SectionLabel
import com.fluxdrop.app.ui.theme.Flux

/** 安卓 ↔ 电脑：电脑浏览器打开手机地址即可双向传输，无需装软件 */
@Composable
fun PcScreen(vm: MainViewModel, contentPadding: PaddingValues) {
    val addrs by vm.addresses.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val serverOn by vm.serverOn.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var pick by remember { mutableIntStateOf(0) }
    val addr = addrs.getOrNull(pick) ?: addrs.firstOrNull()
    val url = addr?.let { "http://${it.ip}:$HTTP_PORT" }
    val browsers = devices.filter { it.viaWeb }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
    ) {
        ScreenHeader("电脑互传", "电脑无需安装任何软件，浏览器即可收发")

        GlassCard(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            padding = 20.dp,
        ) {
            if (!serverOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Flux.Red, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("传输服务未启动（端口 $HTTP_PORT 被占用？）", color = Flux.Red, fontSize = 13.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            SectionLabel("在电脑浏览器地址栏输入")
            Spacer(Modifier.height(8.dp))
            if (url == null) {
                Text("未连接网络", color = Flux.Amber, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("请连接 Wi-Fi，或在「热点」页开启免流量热点", color = Flux.TextLow, fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        url.removePrefix("http://"),
                        style = TextStyle(brush = Flux.accentH, fontSize = 26.sp, fontWeight = FontWeight.Black),
                        modifier = Modifier.weight(1f),
                    )
                    CopyButton(ctx, url)
                }
                if (addrs.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        addrs.forEachIndexed { i, a ->
                            val sel = a.ip == addr?.ip
                            Text(
                                (if (a.isHotspot) "热点 " else "") + a.ip,
                                color = if (sel) Color.White else Flux.TextMid,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (sel) Flux.Violet.copy(alpha = 0.45f) else Color(0x14FFFFFF))
                                    .clickable { pick = i }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(184.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White)
                            .padding(14.dp),
                    ) { QrCode(url, Modifier.fillMaxSize()) }
                    Spacer(Modifier.height(8.dp))
                    Text("平板 / 没装闪渡的手机，扫码即可打开网页版", color = Flux.TextLow, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            Text("三步完成", color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Step(1, "电脑和手机连接同一个 Wi-Fi，或让电脑连上本机的免流量热点")
            Step(2, "在电脑浏览器中打开上面的地址")
            Step(3, "把文件拖进网页即可发到手机；在手机「雷达」里点选这台电脑即可推送到电脑")
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已连接的浏览器", color = Flux.TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (browsers.isNotEmpty()) PulseDot()
            }
            Spacer(Modifier.height(10.dp))
            if (browsers.isEmpty()) {
                Text("暂无。电脑打开网页后会自动出现在这里和雷达上", color = Flux.TextLow, fontSize = 13.sp)
            } else {
                browsers.forEachIndexed { i, d ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeviceAvatar(d.kind, d.id, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, color = Flux.TextHi, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(d.ip, color = Flux.TextLow, fontSize = 12.sp)
                        }
                        Text("在线", color = Flux.Mint, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Flux.accent)
                .border(1.dp, Color(0x40FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Flux.TextMid, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
