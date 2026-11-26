package com.radarviewer.ld2450

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.radarviewer.ld2450.ui.RadarScreen
import com.radarviewer.ld2450.ui.RadarTheme
import com.radarviewer.ld2450.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders each screen in a phone-sized window and saves it to app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF0D1117.toInt())
            view.draw(Canvas(bmp))
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun liveState(link: Link, detail: String) = UiState(
        link = link,
        detail = detail,
        deviceId = "ld2450-35f4",
        firmware = "V2.04.23101915",
        deviceOnline = true,
        frame = RadarFrame(42, listOf(RadarTarget(1, -782, 1713, -16), RadarTarget(3, 1500, 3200, 30))),
        lastFrameAt = android.os.SystemClock.elapsedRealtime(),
        hz = 10f,
        trails = mapOf(
            1 to (0..8).map { -1300 + it * 65 to 1713 },
            3 to (0..8).map { 1500 to 3600 - it * 50 },
        ),
        cloudConfigured = true,
    )

    @Test fun homeView() {
        compose.setContent { RadarTheme { RadarScreen(liveState(Link.Home, "192.168.1.243")) {} } }
        save("home")
    }

    @Test fun remoteView() {
        compose.setContent { RadarTheme { RadarScreen(liveState(Link.Remote, "EMQX")) {} } }
        save("remote")
    }

    @Test fun notConfiguredAway() {
        compose.setContent {
            RadarTheme {
                RadarScreen(UiState(link = Link.Offline,
                    detail = "Not on Wi-Fi. Add your MQTT broker in Settings to watch from anywhere.")) {}
            }
        }
        save("offline")
    }

    @Test fun settings() {
        compose.setContent {
            RadarTheme {
                SettingsScreen(AppSettings(mqttHost = "xxxxxxxx.ala.us-east-1.emqxsl.com", mqttUser = "radar-phone-app",
                    mqttPassword = "secret", deviceId = "ld2450-35f4"), {}, {})
            }
        }
        save("settings")
    }
}
