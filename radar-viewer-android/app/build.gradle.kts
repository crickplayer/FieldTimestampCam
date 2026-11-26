plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.radarviewer.ld2450"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radarviewer.ld2450"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true  // Robolectric screen-rendering tests
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // Home network: WebSocket to the ESP32.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Remote: MQTT over TLS to EMQX (plain Java client; sends SNI and verifies the hostname).
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // Stores the MQTT password encrypted on the phone.
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")  // real org.json for JVM tests
    // Renders the Compose screens on the JVM (no emulator) and saves PNGs in app/build/screenshots.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric normally downloads its Android runtime while tests run; fetch it through Gradle
// instead (cached, works behind proxies) and run Robolectric offline.
val robolectricRuntime: Configuration by configurations.creating
dependencies {
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
}
val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")
val copyRobolectricJars by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricJarsDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")  // needed for Compose captureToImage
    systemProperty("robolectric.dependency.dir", robolectricJarsDir.get().asFile.absolutePath)
}
