package com.radarviewer.ld2450.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radarviewer.ld2450.Link
import com.radarviewer.ld2450.RadarTarget
import com.radarviewer.ld2450.UiState
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_RANGE_MM = 6000f
private const val FOV_DEG = 60f
private const val STALE_MS = 3000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(state: UiState, onOpenSettings: () -> Unit) {
    // Re-evaluates "no data" every half second even when no frames arrive.
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(500)
            value = SystemClock.elapsedRealtime()
        }
    }
    val live = state.frame != null && now - state.lastFrameAt < STALE_MS &&
        (state.link == Link.Home || state.link == Link.Remote)
    val targets = if (live) state.frame!!.targets else emptyList()

    Scaffold(
        containerColor = RadarColors.background,
        topBar = {
            TopAppBar(
                title = { Text("LD2450 Radar", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RadarColors.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            ConnectionRow(state)
            Spacer(Modifier.size(8.dp))
            Summary(state, live, targets.size)
            Spacer(Modifier.size(12.dp))
            RadarView(
                targets = targets,
                trails = if (live) state.trails else emptyMap(),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.7f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RadarColors.surface),
            )
            Spacer(Modifier.size(12.dp))
            for (id in 1..3) TargetRow(id, targets.firstOrNull { it.id == id })
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ConnectionRow(state: UiState) {
    val (color, label) = when (state.link) {
        Link.Home -> RadarColors.home to "Home Wi-Fi · ${state.detail}"
        Link.Remote -> RadarColors.remote to "Remote · EMQX"
        Link.Searching -> RadarColors.searching to "Connecting…"
        Link.Offline -> RadarColors.offline to "Not connected"
        Link.Idle -> RadarColors.muted to "Paused"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
    if (state.link == Link.Searching || state.link == Link.Offline) {
        Text(state.detail, color = RadarColors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
    val device = listOf(state.deviceId, state.firmware.takeIf { it.isNotBlank() }?.let { "radar $it" })
        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
    if (device.isNotBlank()) {
        Text(device, color = RadarColors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Summary(state: UiState, live: Boolean, count: Int) {
    val connected = state.link == Link.Home || state.link == Link.Remote
    val (text, color) = when {
        !connected -> "—" to RadarColors.muted
        state.deviceOnline == false -> "Radar offline" to RadarColors.offline
        !live -> (if (state.link == Link.Remote) "Waiting for the radar…" else "No radar data") to RadarColors.searching
        count == 0 -> "Nobody detected" to Color.White
        count == 1 -> "1 person" to Color.White
        else -> "$count people" to Color.White
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (live) Text("%.0f Hz".format(state.hz), color = RadarColors.muted, fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun TargetRow(id: Int, t: RadarTarget?) {
    val color = RadarColors.targets[id - 1]
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (t != null) color else color.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Text("$id", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Spacer(Modifier.width(12.dp))
        if (t == null) {
            Text("—", color = RadarColors.muted, fontSize = 16.sp)
        } else {
            Text(
                "%.2f m     %+.0f°     %+.2f m/s".format(t.distanceM, t.angleDeg, t.speedMs),
                color = Color.White, fontSize = 16.sp,
            )
        }
    }
}

/** Top-down view: radar at the bottom centre, +/-60 degrees, rings every metre to 6 m. */
@Composable
fun RadarView(
    targets: List<RadarTarget>,
    trails: Map<Int, List<Pair<Int, Int>>>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val origin = Offset(size.width / 2f, size.height - 14.dp.toPx())
        val maxR = minOf(
            (size.width / 2f - 8.dp.toPx()) / sin(Math.toRadians(FOV_DEG.toDouble())).toFloat(),
            origin.y - 8.dp.toPx(),
        )
        val pxPerMm = maxR / MAX_RANGE_MM
        fun toScreen(x: Int, y: Int) = Offset(origin.x + x * pxPerMm, origin.y - y * pxPerMm)
        fun polar(deg: Float, r: Float): Offset {
            val a = Math.toRadians(deg.toDouble())
            return Offset(origin.x + r * sin(a).toFloat(), origin.y - r * cos(a).toFloat())
        }

        // Fan and grid (Compose angles: 0 = 3 o'clock, clockwise; straight up is -90).
        drawArc(RadarColors.fan, -90f - FOV_DEG, 2 * FOV_DEG, useCenter = true,
            topLeft = origin - Offset(maxR, maxR), size = Size(2 * maxR, 2 * maxR))
        for (m in 1..6) {
            val r = m * 1000 * pxPerMm
            drawArc(RadarColors.grid, -90f - FOV_DEG, 2 * FOV_DEG, useCenter = false,
                topLeft = origin - Offset(r, r), size = Size(2 * r, 2 * r), style = Stroke(1.dp.toPx()))
            if (m % 2 == 0) {
                drawText(measurer, "${m}m", topLeft = Offset(origin.x + 4.dp.toPx(), origin.y - r + 2.dp.toPx()),
                    style = TextStyle(color = RadarColors.muted, fontSize = 11.sp))
            }
        }
        for (a in listOf(-60f, -30f, 0f, 30f, 60f)) {
            drawLine(RadarColors.grid, origin, polar(a, maxR), strokeWidth = 1.dp.toPx())
        }
        drawRadarIcon(origin)

        // Trails, then people.
        for (t in targets) {
            val color = RadarColors.targets[t.id - 1]
            val trail = trails[t.id].orEmpty()
            trail.dropLast(1).forEachIndexed { i, (x, y) ->
                drawCircle(color.copy(alpha = 0.15f + 0.6f * (i + 1) / trail.size), 3.dp.toPx(), toScreen(x, y))
            }
            val p = toScreen(t.xMm, t.yMm)
            drawCircle(color, 10.dp.toPx(), p)
            drawCircle(Color.White, 10.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            val label = measurer.measure("${t.id}", TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            drawText(label, topLeft = p - Offset(label.size.width / 2f, label.size.height / 2f))
        }
    }
}

private fun DrawScope.drawRadarIcon(origin: Offset) {
    val s = 7.dp.toPx()
    val path = Path().apply {
        moveTo(origin.x, origin.y - s)
        lineTo(origin.x - s, origin.y + s * 0.8f)
        lineTo(origin.x + s, origin.y + s * 0.8f)
        close()
    }
    drawPath(path, Color.White)
}
