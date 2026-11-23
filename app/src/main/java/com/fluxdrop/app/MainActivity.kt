package com.fluxdrop.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.fluxdrop.app.service.TransferService
import com.fluxdrop.app.ui.FluxRoot
import com.fluxdrop.app.ui.theme.FluxTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        FluxApp.engine.start()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            FluxTheme {
                FluxRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
        FluxApp.engine.start()
        TransferService.start(this)
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.foreground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) TransferService.stopWhenIdle()
    }

    /** 从其他应用「分享」到闪渡 */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            vm.addUris(uris)
            vm.nav.tryEmit(0)
        }
    }
}
