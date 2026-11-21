package com.fieldcam.timestamp

import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor
import kotlin.math.max

/**
 * Draws the complete watermark (timestamp/location text block, compass, mini map
 * and logo) onto an upright canvas. Used for the live preview, photos and video
 * frames alike, so what you see is exactly what gets saved.
 */
class TelemetryOverlayRenderer(
    private val state: TelemetryState,
    private val tiles: MapTileCache,
    private val settingsProvider: () -> CameraSettings,
    private val logoProvider: () -> Bitmap?,
) {
    private var cachedFormatIndex = -1
    private var dateFormat = SimpleDateFormat(TimeFormats.PATTERNS[0], Locale.getDefault())

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(215, 80, 84, 88) }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.rgb(0, 172, 220) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 228, 222) }
    private val mapBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
    private val path = Path()
    private val rect = RectF()
    private val tileSrc = Rect()
    private val tileDst = RectF()

    private interface Item {
        val w: Float
        val h: Float
        fun draw(c: Canvas, left: Float, top: Float)
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val s = settingsProvider()
        val minDim = minOf(width, height).toFloat()
        val margin = minDim * 0.03f
        val gap = minDim * 0.015f

        val groups = LinkedHashMap<StampPosition, MutableList<Item>>()
        fun add(pos: StampPosition, item: Item?) { if (item != null) groups.getOrPut(pos) { mutableListOf() }.add(item) }

        add(s.position, textItem(s, minDim))
        if (s.showMap) add(s.mapPosition, mapItem(s, minDim))
        if (s.showCompass) add(s.compassPosition, compassItem(s, minDim))
        if (s.showLogo) logoProvider()?.let { add(s.logoPosition, logoItem(s, minDim, it)) }

        // Place the timestamp group first; later elements are pushed away from
        // anything already placed so nothing overlaps.
        placed.clear()
        for ((pos, items) in groups) {
            fun xFor(item: Item) = when {
                pos.isLeft -> margin
                pos.isRight -> width - margin - item.w
                else -> (width - item.w) / 2f
            }
            when {
                pos.isBottom -> {
                    var bottom = height - margin
                    for (it in items) {
                        val x = xFor(it)
                        var top = bottom - it.h
                        var hit = collide(x, top, it)
                        while (hit != null) { top = hit.top - gap - it.h; hit = collide(x, top, it) }
                        place(canvas, it, x, top)
                        bottom = top - gap
                    }
                }
                else -> {
                    val total = items.sumOf { it.h.toDouble() }.toFloat() + gap * (items.size - 1)
                    var top = if (pos.isTop) margin else (height - total) / 2f
                    for (it in items) {
                        val x = xFor(it)
                        var hit = collide(x, top, it)
                        while (hit != null) { top = hit.bottom + gap; hit = collide(x, top, it) }
                        place(canvas, it, x, top)
                        top += it.h + gap
                    }
                }
            }
        }
    }

    private val placed = ArrayList<RectF>()

    private fun collide(x: Float, y: Float, item: Item): RectF? =
        placed.firstOrNull { it.intersects(x, y, x + item.w, y + item.h) }

    private fun place(c: Canvas, item: Item, x: Float, y: Float) {
        placed.add(RectF(x, y, x + item.w, y + item.h))
        item.draw(c, x, y)
    }

    // ---------------------------------------------------------------- text

    private fun buildLines(s: CameraSettings): List<String> {
        val lines = ArrayList<String>(8)
        if (s.showIndex) lines.add(String.format(Locale.US, "#%04d", s.photoIndex))
        if (s.showTime) {
            if (cachedFormatIndex != s.timeFormatIndex) {
                cachedFormatIndex = s.timeFormatIndex
                dateFormat = SimpleDateFormat(TimeFormats.PATTERNS[s.timeFormatIndex], Locale.getDefault())
            }
            lines.add(dateFormat.format(Date()))
        }
        if (s.showGps) lines.add(state.formatCoordinates(s.coordFormat))
        if (s.showHeading) lines.add(state.formatBearing())
        if (s.showAltitude || s.showSpeed) {
            val parts = ArrayList<String>(2)
            if (s.showAltitude) {
                val alt = state.altitudeMeters
                parts.add(
                    if (alt == null) "Alt --" else if (s.imperialUnits)
                        String.format(Locale.US, "Alt %.1f ft", alt * 3.28084) else String.format(Locale.US, "Alt %.1f m", alt)
                )
            }
            if (s.showSpeed) {
                val sp = state.speedMps ?: 0f
                parts.add(
                    if (s.imperialUnits) String.format(Locale.US, "%.1f mph", sp * 2.23694f)
                    else String.format(Locale.US, "%.1f km/h", sp * 3.6f)
                )
            }
            lines.add(parts.joinToString("  "))
        }
        if (s.showAddress) {
            val loc = s.customLocation.ifBlank { state.formatAddress(s.addressFormat) ?: "" }
            if (loc.isNotBlank()) lines.add(loc)
        }
        if (s.customText.isNotBlank()) lines.addAll(s.customText.lines().filter { it.isNotBlank() })
        return lines
    }

    private fun textItem(s: CameraSettings, minDim: Float): Item? {
        val lines = buildLines(s)
        if (lines.isEmpty()) return null
        val fontSize = minDim * 0.032f * s.textSizePercent / 100f
        textPaint.typeface = typefaceFor(s.font)
        textPaint.textSize = fontSize
        textPaint.color = s.fontColor
        textPaint.alpha = 255 * s.textOpacityPercent / 100
        textPaint.clearShadowLayer()
        val bgAlpha = 255 * s.backgroundOpacityPercent / 100
        if (s.backgroundStyle == BackgroundStyle.SHADOW) {
            textPaint.setShadowLayer(fontSize * 0.12f, fontSize * 0.05f, fontSize * 0.05f,
                (s.backgroundColor and 0x00FFFFFF) or (max(bgAlpha, 120) shl 24))
        }
        val align = when {
            s.position.isLeft -> Paint.Align.LEFT
            s.position.isRight -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        textPaint.textAlign = align
        val lineH = fontSize * 1.22f
        val pad = if (s.backgroundStyle == BackgroundStyle.BOX) fontSize * 0.35f else 0f
        val widths = lines.map { textPaint.measureText(it) }
        val maxW = widths.maxOrNull() ?: 0f

        return object : Item {
            override val w = maxW + pad * 2
            override val h = lines.size * lineH + pad * 2 - (lineH - fontSize)
            override fun draw(c: Canvas, left: Float, top: Float) {
                if (s.backgroundStyle == BackgroundStyle.BOX && bgAlpha > 0) {
                    bgPaint.color = s.backgroundColor; bgPaint.alpha = bgAlpha
                    rect.set(left, top, left + w, top + h)
                    c.drawRoundRect(rect, fontSize * 0.25f, fontSize * 0.25f, bgPaint)
                }
                val x = when (align) {
                    Paint.Align.LEFT -> left + pad
                    Paint.Align.RIGHT -> left + w - pad
                    else -> left + w / 2f
                }
                var baseline = top + pad - textPaint.ascent() * 0.92f
                for (line in lines) {
                    if (s.backgroundStyle == BackgroundStyle.OUTLINE) {
                        strokePaint.typeface = textPaint.typeface
                        strokePaint.textSize = fontSize
                        strokePaint.textAlign = align
                        strokePaint.strokeWidth = fontSize * 0.14f
                        strokePaint.color = s.backgroundColor
                        strokePaint.alpha = max(bgAlpha, 60) * s.textOpacityPercent / 100
                        c.drawText(line, x, baseline, strokePaint)
                    }
                    c.drawText(line, x, baseline, textPaint)
                    baseline += lineH
                }
            }
        }
    }

    // ------------------------------------------------------------- compass

    private fun compassItem(s: CameraSettings, minDim: Float): Item {
        val size = minDim * 0.19f * s.compassSizePercent / 100f
        return object : Item {
            override val w = size
            override val h = size
            override fun draw(c: Canvas, left: Float, top: Float) {
                val r = size / 2f
                val cx = left + r
                val cy = top + r
                val ringW = r * 0.2f
                ringPaint.strokeWidth = ringW
                c.drawCircle(cx, cy, r - ringW / 2f, ringPaint)
                cardinalPaint.textSize = ringW * 0.95f
                val off = r - ringW / 2f
                val tOff = (cardinalPaint.descent() + cardinalPaint.ascent()) / 2f
                c.drawText("N", cx, cy - off - tOff, cardinalPaint)
                c.drawText("S", cx, cy + off - tOff, cardinalPaint)
                c.drawText("E", cx + off, cy - tOff, cardinalPaint)
                c.drawText("W", cx - off, cy - tOff, cardinalPaint)

                c.save()
                c.rotate(state.azimuthDeg, cx, cy)
                val len = r - ringW * 1.15f
                path.reset()
                path.moveTo(cx, cy - len)                // tip -> heading
                path.lineTo(cx + r * 0.1f, cy + len * 0.95f)
                path.lineTo(cx - r * 0.1f, cy + len * 0.95f)
                path.close()
                c.drawPath(path, needlePaint)
                c.restore()
            }
        }
    }

    // ----------------------------------------------------------------- map

    private fun mapItem(s: CameraSettings, minDim: Float): Item {
        val size = minDim * s.mapSizePercent / 100f
        val alpha = 255 * s.mapOpacityPercent / 100
        return object : Item {
            override val w = size
            override val h = size
            override fun draw(c: Canvas, left: Float, top: Float) {
                rect.set(left, top, left + size, top + size)
                val corner = size * 0.06f
                c.save()
                path.reset()
                path.addRoundRect(rect, corner, corner, Path.Direction.CW)
                c.clipPath(path)
                mapBgPaint.alpha = alpha
                c.drawRect(rect, mapBgPaint)

                val lat = state.latitude
                val lon = state.longitude
                if (lat != null && lon != null) {
                    val z = s.mapZoom.coerceIn(3, 19)
                    val (fx, fy) = MapTileCache.tileXY(lat, lon, z)
                    val tilePx = size / 1.6f // ~1.6 tiles across the box
                    val cx = left + size / 2f
                    val cy = top + size / 2f
                    val x0 = floor(fx - (size / 2f) / tilePx).toInt()
                    val x1 = floor(fx + (size / 2f) / tilePx).toInt()
                    val y0 = floor(fy - (size / 2f) / tilePx).toInt()
                    val y1 = floor(fy + (size / 2f) / tilePx).toInt()
                    bitmapPaint.alpha = alpha
                    for (tx in x0..x1) for (ty in y0..y1) {
                        val bmp = tiles.tile(z, tx, ty) ?: continue
                        val dl = cx + ((tx - fx) * tilePx).toFloat()
                        val dt = cy + ((ty - fy) * tilePx).toFloat()
                        tileSrc.set(0, 0, bmp.width, bmp.height)
                        tileDst.set(dl, dt, dl + tilePx, dt + tilePx)
                        c.drawBitmap(bmp, tileSrc, tileDst, bitmapPaint)
                    }
                    markerPaint.alpha = 255
                    mapBorderPaint.strokeWidth = size * 0.012f
                    c.drawCircle(cx, cy, size * 0.035f, markerPaint)
                    c.drawCircle(cx, cy, size * 0.035f, mapBorderPaint)
                    // heading wedge
                    c.save()
                    c.rotate(state.azimuthDeg, cx, cy)
                    path.reset()
                    path.moveTo(cx, cy - size * 0.1f)
                    path.lineTo(cx + size * 0.03f, cy - size * 0.045f)
                    path.lineTo(cx - size * 0.03f, cy - size * 0.045f)
                    path.close()
                    c.drawPath(path, markerPaint)
                    c.restore()
                } else {
                    smallPaint.textSize = size * 0.08f
                    smallPaint.textAlign = Paint.Align.CENTER
                    c.drawText("Waiting for GPS…", left + size / 2f, top + size / 2f, smallPaint)
                }
                smallPaint.textSize = size * 0.055f
                smallPaint.textAlign = Paint.Align.RIGHT
                smallPaint.alpha = 200
                c.drawText("© OpenStreetMap", left + size - size * 0.03f, top + size - size * 0.03f, smallPaint)
                smallPaint.alpha = 255
                c.restore()
                mapBorderPaint.strokeWidth = size * 0.012f
                mapBorderPaint.alpha = alpha
                c.drawRoundRect(rect, corner, corner, mapBorderPaint)
                mapBorderPaint.alpha = 255
            }
        }
    }

    // ---------------------------------------------------------------- logo

    private fun logoItem(s: CameraSettings, minDim: Float, bmp: Bitmap): Item {
        val w = minDim * s.logoSizePercent / 100f
        val h = w * bmp.height / max(1, bmp.width)
        val alpha = 255 * s.logoOpacityPercent / 100
        return object : Item {
            override val w = w
            override val h = h
            override fun draw(c: Canvas, left: Float, top: Float) {
                bitmapPaint.alpha = alpha
                tileSrc.set(0, 0, bmp.width, bmp.height)
                tileDst.set(left, top, left + w, top + h)
                c.drawBitmap(bmp, tileSrc, tileDst, bitmapPaint)
            }
        }
    }

    companion object {
        private val typefaces = HashMap<String, Typeface>()

        fun typefaceFor(key: String): Typeface = typefaces.getOrPut(key) {
            when (key) {
                "sans_bold" -> Typeface.create("sans-serif", Typeface.BOLD)
                "sans_medium" -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                "sans_light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                "sans_thin" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
                "sans_black" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
                "condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                "condensed_bold" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                "serif" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                "serif_bold" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                "serif_italic" -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                "mono" -> Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                "mono_bold" -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                "casual" -> Typeface.create("casual", Typeface.NORMAL)
                "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
                "smallcaps" -> Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }
    }
}
