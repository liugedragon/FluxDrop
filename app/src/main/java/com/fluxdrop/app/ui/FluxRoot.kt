package com.fluxdrop.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdrop.app.MainViewModel
import com.fluxdrop.app.ui.components.AuroraBackground
import com.fluxdrop.app.ui.components.FloatingNavBar
import com.fluxdrop.app.ui.screens.HotspotScreen
import com.fluxdrop.app.ui.screens.IncomingDialog
import com.fluxdrop.app.ui.screens.PcScreen
import com.fluxdrop.app.ui.screens.RadarScreen
import com.fluxdrop.app.ui.screens.TransfersScreen
import kotlinx.coroutines.flow.merge

/** Android 17 (API 37) 新增的局域网权限；用字符串避免依赖新版 SDK 才有的常量 */
private const val PERM_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
fun FluxRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val incoming by vm.incoming.collectAsStateWithLifecycle()
    val active by vm.activeCount.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) { vm.nav.collect { tab = it } }
    LaunchedEffect(Unit) {
        merge(vm.toasts, vm.events).collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    }

    // 启动时申请：通知（Android 13+）、存储（Android 9 及以下）、局域网（Android 17+ 且 targetSdk ≥ 37）
    val startupPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[PERM_LOCAL_NETWORK] == false) {
            Toast.makeText(ctx, "未授予「局域网」权限，将无法发现和连接附近设备", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 37 && ctx.applicationInfo.targetSdkVersion >= 37) add(PERM_LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) startupPerms.launch(need.toTypedArray())
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPad = PaddingValues(bottom = navBottom + 96.dp)

    AuroraBackground {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (
                    fadeIn(tween(260)) +
                        slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { w -> dir * w / 6 }
                    ) togetherWith (
                    fadeOut(tween(180)) +
                        slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { w -> -dir * w / 6 }
                    )
            },
            modifier = Modifier.fillMaxSize(),
            label = "tabs",
        ) { t ->
            when (t) {
                0 -> RadarScreen(vm, contentPad)
                1 -> TransfersScreen(vm, contentPad)
                2 -> HotspotScreen(vm, contentPad, onGoRadar = { tab = 0 })
                else -> PcScreen(vm, contentPad)
            }
        }

        FloatingNavBar(
            selected = tab,
            onSelect = { tab = it },
            badge = active > 0,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        )

        incoming.firstOrNull()?.let { r ->
            IncomingDialog(
                request = r,
                onAccept = { vm.decide(r.id, true) },
                onReject = { vm.decide(r.id, false) },
            )
        }
    }
}
