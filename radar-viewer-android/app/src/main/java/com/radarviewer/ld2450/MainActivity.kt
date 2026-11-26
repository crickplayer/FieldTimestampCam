package com.radarviewer.ld2450

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radarviewer.ld2450.ui.RadarScreen
import com.radarviewer.ld2450.ui.RadarTheme
import com.radarviewer.ld2450.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // live view stays on
        setContent {
            RadarTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        current = settings,
                        onSave = {
                            vm.saveSettings(it)
                            showSettings = false
                        },
                        onBack = { showSettings = false },
                    )
                } else {
                    RadarScreen(state, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    // Connected only while the app is on screen, so the ESP32 stops streaming to EMQX
    // (and using your data quota) when you close or leave the app.
    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }
}
