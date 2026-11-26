package com.radarviewer.ld2450

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.net.ssl.SSLSocketFactory

/** How the app is currently connected to the radar. */
enum class Link { Idle, Searching, Home, Remote, Offline }

data class UiState(
    val link: Link = Link.Idle,
    /** Address on the home network, "EMQX", or why it isn't connected. */
    val detail: String = "",
    val deviceId: String = "",
    val firmware: String = "",
    /** From the ESP32's status message; null = not known yet. */
    val deviceOnline: Boolean? = null,
    val frame: RadarFrame? = null,
    /** SystemClock.elapsedRealtime() of the last frame. */
    val lastFrameAt: Long = 0,
    val hz: Float = 0f,
    /** Recent positions (x, y in mm) per target id, oldest first. */
    val trails: Map<Int, List<Pair<Int, Int>>> = emptyMap(),
    val cloudConfigured: Boolean = false,
)

/**
 * Connection manager. On Wi-Fi it looks for the ESP32 on the home network first (fast, no
 * internet needed); otherwise, or if the radar isn't found, it uses EMQX. While remote it
 * checks every 30 s whether the home network has become reachable and switches back.
 *
 * All state changes happen on the main thread; network callbacks are posted to it.
 */
class RadarViewModel(app: Application) : AndroidViewModel(app), LanClient.Listener, CloudClient.Listener {

    private val store = SettingsStore(app)
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val lan = LanClient(this)
    private val discovery = LanDiscovery(app) { id, host -> main.post { onDiscovered(id, host) } }
    private var cloud: CloudClient? = null
    private val tls: SSLSocketFactory by lazy {
        Tls.socketFactory(app.resources.openRawResource(R.raw.radar_root_cas))
    }

    private var active = false
    private var frameTimes = ArrayDeque<Long>()
    private var lastNetwork: Network? = null

    // ---- Lifecycle ---------------------------------------------------------

    /** Called when the screen becomes visible. */
    fun start() {
        if (active) return
        active = true
        lastNetwork = connectivity.activeNetwork
        connectivity.registerDefaultNetworkCallback(networkCallback)
        connect()
    }

    /** Called when the screen is hidden: disconnects so the ESP32 stops streaming to EMQX. */
    fun stop() {
        if (!active) return
        active = false
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        teardown()
        _state.update { it.copy(link = Link.Idle, detail = "") }
    }

    fun saveSettings(new: AppSettings) {
        store.save(new)
        _settings.value = new
        if (new.deviceId != _state.value.deviceId) {
            _state.update { it.copy(deviceId = new.deviceId, firmware = "", deviceOnline = null) }
        }
        if (active) connect()
    }

    override fun onCleared() {
        stop()
    }

    // ---- Choosing a connection ---------------------------------------------

    private fun connect() {
        teardown()
        val s = _settings.value
        _state.update {
            it.copy(cloudConfigured = s.cloudConfigured, deviceId = it.deviceId.ifBlank { s.deviceId })
        }
        if (onLocalNetwork()) {
            _state.update { it.copy(link = Link.Searching, detail = "Looking for the radar on this Wi-Fi…") }
            val first = s.homeAddress.ifBlank { s.lastHomeAddress }
            if (first.isNotBlank()) lan.connect(first)
            discovery.start()
            main.postDelayed(lanGiveUp, LAN_SEARCH_MS)
        } else {
            goRemote("Not on Wi-Fi")
        }
    }

    private val lanGiveUp = Runnable {
        if (active && _state.value.link == Link.Searching && cloud == null) {
            goRemote("Radar not found on this Wi-Fi")
        }
    }

    private fun goRemote(reason: String) {
        main.removeCallbacks(lanGiveUp)
        discovery.stop()
        lan.close()
        scheduleLanProbe()
        val s = _settings.value
        if (!s.cloudConfigured) {
            _state.update {
                it.copy(link = Link.Offline, detail = "$reason. Add your MQTT broker in Settings to watch from anywhere.")
            }
            return
        }
        _state.update { it.copy(link = Link.Searching, detail = "Connecting to EMQX…") }
        cloud?.close()
        cloud = CloudClient(s, tls, this).also { it.connect() }
    }

    private fun teardown() {
        main.removeCallbacks(lanGiveUp)
        main.removeCallbacks(lanProbe)
        main.removeCallbacks(stopProbeDiscovery)
        main.removeCallbacks(watchTick)
        main.removeCallbacks(cloudRetry)
        lan.close()
        discovery.stop()
        cloud?.close()
        cloud = null
    }

    private fun onLocalNetwork(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = main.post { onNetworkChanged(network) }.let { }
        override fun onLost(network: Network) = main.post { onNetworkChanged(null) }.let { }
    }

    private fun onNetworkChanged(network: Network?) {
        if (!active || network == lastNetwork) return
        lastNetwork = network
        main.removeCallbacks(reconnectAfterNetworkChange)
        main.postDelayed(reconnectAfterNetworkChange, 1500)  // let the new network settle
    }

    private val reconnectAfterNetworkChange = Runnable { if (active) connect() }

    // While remote (or offline) on Wi-Fi, check now and then whether the radar is reachable at home.
    private val lanProbe: Runnable = Runnable {
        if (!active) return@Runnable
        val link = _state.value.link
        if ((link == Link.Remote || link == Link.Offline) && onLocalNetwork()) {
            val s = _settings.value
            s.homeAddress.ifBlank { s.lastHomeAddress }.takeIf { it.isNotBlank() }?.let { lan.connect(it) }
            discovery.start()
            main.postDelayed(stopProbeDiscovery, LAN_SEARCH_MS)
        }
        main.postDelayed(lanProbe, LAN_PROBE_MS)
    }

    private val stopProbeDiscovery = Runnable { if (_state.value.link != Link.Home) discovery.stop() }

    private fun scheduleLanProbe() {
        main.removeCallbacks(lanProbe)
        main.postDelayed(lanProbe, LAN_PROBE_MS)
    }

    // ---- Home network --------------------------------------------------------

    private fun onDiscovered(deviceId: String, host: String) {
        if (!active || _state.value.link == Link.Home || lan.host == host) return
        val wanted = _state.value.deviceId
        if (wanted.isNotBlank() && deviceId != wanted) return  // another radar on the same Wi-Fi
        lan.connect(host)
    }

    override fun onLanOpen(host: String) {
        main.post {
            if (!active) return@post
            main.removeCallbacks(lanGiveUp)
            main.removeCallbacks(lanProbe)
            main.removeCallbacks(watchTick)
            main.removeCallbacks(cloudRetry)
            discovery.stop()
            cloud?.close()
            cloud = null
            _state.update { it.copy(link = Link.Home, detail = host, deviceOnline = true) }
            val s = _settings.value
            if (s.lastHomeAddress != host) saveQuietly(s.copy(lastHomeAddress = host))
        }
    }

    override fun onLanMessage(host: String, text: String) {
        main.post { if (active) handleMessage(text) }
    }

    override fun onLanClosed(host: String, reason: String) {
        main.post {
            // Failed attempts while searching or probing are expected; only a lost live link matters.
            if (active && _state.value.link == Link.Home) goRemote("Lost the home-network connection")
        }
    }

    // ---- EMQX ----------------------------------------------------------------

    override fun onCloudConnected() {
        main.post {
            val c = cloud ?: return@post
            if (!active) return@post
            _state.update { it.copy(link = Link.Remote, detail = "EMQX") }
            val id = _state.value.deviceId
            if (id.isNotBlank()) {
                c.subscribe(Topics.status(id))
                c.subscribe(Topics.targets(id))
            } else {
                c.subscribe(Topics.ALL_STATUS)  // retained: the radar's status arrives straight away
            }
            main.removeCallbacks(watchTick)
            main.post(watchTick)
        }
    }

    override fun onCloudMessage(topic: String, payload: String) {
        main.post {
            val c = cloud ?: return@post
            if (!active) return@post
            val topicDevice = Topics.deviceIdOf(topic) ?: return@post
            if (_state.value.deviceId.isBlank() && topic.endsWith("/status")) {
                adoptDevice(topicDevice)
                c.unsubscribe(Topics.ALL_STATUS)
                c.subscribe(Topics.status(topicDevice))
                c.subscribe(Topics.targets(topicDevice))
                main.removeCallbacks(watchTick)
                main.post(watchTick)
            }
            if (topicDevice == _state.value.deviceId) handleMessage(payload)
        }
    }

    override fun onCloudLost(reason: String) {
        main.post {
            if (!active || cloud == null) return@post
            main.removeCallbacks(watchTick)
            _state.update { it.copy(link = Link.Offline, detail = "EMQX: $reason. Retrying…") }
            main.removeCallbacks(cloudRetry)
            main.postDelayed(cloudRetry, CLOUD_RETRY_MS)
        }
    }

    private val cloudRetry = Runnable {
        if (active && _state.value.link != Link.Home && _settings.value.cloudConfigured) {
            goRemote("Retrying")
        }
    }

    // Tells the ESP32 someone is watching; it streams to EMQX for 60 s after each one.
    private val watchTick: Runnable = Runnable {
        val c = cloud
        val id = _state.value.deviceId
        if (active && c != null && id.isNotBlank() && _state.value.link == Link.Remote) {
            c.publish(Topics.watch(id), "1")
        }
        if (active && cloud != null) main.postDelayed(watchTick, WATCH_INTERVAL_MS)
    }

    // ---- Messages ------------------------------------------------------------

    private fun handleMessage(text: String) {
        when (val msg = RadarMessages.parse(text)) {
            is RadarMessage.Status -> {
                val st = msg.status
                if (_state.value.deviceId.isBlank() && st.deviceId.isNotBlank()) adoptDevice(st.deviceId)
                _state.update {
                    it.copy(
                        firmware = st.firmware.ifBlank { it.firmware },
                        deviceOnline = st.online,
                    )
                }
            }
            is RadarMessage.Frame -> onFrame(msg.frame)
            null -> {}
        }
    }

    private fun onFrame(frame: RadarFrame) {
        val now = SystemClock.elapsedRealtime()
        frameTimes.addLast(now)
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 1000) frameTimes.removeFirst()
        _state.update { s ->
            val trails = HashMap<Int, List<Pair<Int, Int>>>()
            for (t in frame.targets) {
                trails[t.id] = ((s.trails[t.id] ?: emptyList()) + (t.xMm to t.yMm)).takeLast(TRAIL_LENGTH)
            }
            s.copy(frame = frame, lastFrameAt = now, hz = frameTimes.size.toFloat(), trails = trails, deviceOnline = true)
        }
    }

    private fun adoptDevice(id: String) {
        _state.update { it.copy(deviceId = id) }
        val s = _settings.value
        if (s.deviceId != id) saveQuietly(s.copy(deviceId = id))
    }

    private fun saveQuietly(s: AppSettings) {
        store.save(s)
        _settings.value = s
    }

    companion object {
        private const val LAN_SEARCH_MS = 4000L
        private const val LAN_PROBE_MS = 30_000L
        private const val WATCH_INTERVAL_MS = 30_000L
        private const val CLOUD_RETRY_MS = 10_000L
        private const val TRAIL_LENGTH = 12
    }
}
