package com.pocketbedrock.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketbedrock.app.data.AppSettings
import com.pocketbedrock.app.ui.MainScaffold
import com.pocketbedrock.app.ui.setup.SetupWizardScreen
import com.pocketbedrock.app.ui.theme.PocketBedrockTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PocketBedrockApp).graph

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent {
            val settings by graph.settings.flow.collectAsStateWithLifecycle(
                initialValue = AppSettings.Settings(),
            )
            if (settings.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            PocketBedrockTheme(settings.themeMode) {
                if (settings.setupCompleted) {
                    MainScaffold(graph)
                } else {
                    SetupWizardScreen(graph)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val graph = (application as PocketBedrockApp).graph
        graph.configureSamplerForeground()
    }

    override fun onStop() {
        super.onStop()
        val graph = (application as PocketBedrockApp).graph
        graph.configureSamplerBackground()
    }
}
