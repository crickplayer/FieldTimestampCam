package com.fieldcam.timestamp

import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*

class TelemetryOverlayRenderer(
    private val settings: CameraSettings,
    private val state: TelemetryState
) {
    private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(190, 85, 95, 105)
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 188, 212)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val minDim = minOf(width, height).toFloat()
        val margin = minDim * 0.035f

        if (settings.showCompass) {
            drawCompass(canvas, margin, minDim)
        }

        val lines = mutableListOf<String>()
        if (settings.showIndexNumber) {
            lines.add("#${String.format("%04d", settings.photoIndex)}")
        }
        lines.add(dateFormat.format(Date()))
        lines.add(state.formatCoordinates())
        lines.add(state.formatBearing())
        if (state.locationName.isNotBlank()) {
            lines.add(state.locationName)
        }
        if (settings.showAltitudeSpeed) {
            val mph = state.speedMps * 2.23694f
            lines.add(String.format("Alt: %.1fm  Spd: %.1f mph", state.altitudeMeters, mph))
        }
        if (settings.customText.isNotBlank()) {
            lines.addAll(settings.customText.lines())
        }

        drawTextBlock(canvas, width, height, margin, minDim, lines)
    }

    private fun drawCompass(canvas: Canvas, margin: Float, minDim: Float) {
        val radius = minDim * 0.075f
        val cx = margin + radius
        val cy = margin + radius

        ringPaint.strokeWidth = radius * 0.22f
        canvas.drawCircle(cx, cy, radius * 0.85f, ringPaint)

        cardinalPaint.textSize = radius * 0.24f
        val offset = radius * 0.85f
        val textOffset = (cardinalPaint.descent() + cardinalPaint.ascent()) / 2f
        canvas.drawText("N", cx, cy - offset - textOffset, cardinalPaint)
        canvas.drawText("S", cx, cy + offset - textOffset, cardinalPaint)
        canvas.drawText("E", cx + offset, cy - textOffset, cardinalPaint)
        canvas.drawText("W", cx - offset, cy - textOffset, cardinalPaint)

        canvas.save()
        canvas.rotate(-state.azimuthDeg.toFloat(), cx, cy)
        val path = Path().apply {
            moveTo(cx, cy - radius * 0.65f)
            lineTo(cx + radius * 0.14f, cy + radius * 0.55f)
            lineTo(cx - radius * 0.14f, cy + radius * 0.55f)
            close()
        }
        canvas.drawPath(path, needlePaint)
        canvas.restore()
    }

    private fun drawTextBlock(
        canvas: Canvas,
        width: Int,
        height: Int,
        margin: Float,
        minDim: Float,
        lines: List<String>
    ) {
        val fontSize = (minDim * 0.028f) * settings.textSizeScale
        textPaint.textSize = fontSize
        textPaint.color = settings.fontColor
        textPaint.alpha = settings.textOpacity
        textPaint.setShadowLayer(4f, 2f, 2f, Color.argb(200, 0, 0, 0))

        val isRightAligned = settings.position == StampPosition.BOTTOM_RIGHT ||
                settings.position == StampPosition.TOP_RIGHT
        textPaint.textAlign = if (isRightAligned) Paint.Align.RIGHT else Paint.Align.LEFT

        val lineHeight = fontSize * 1.25f
        val totalHeight = lines.size * lineHeight
        val maxWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f

        val startX = if (isRightAligned) width - margin else margin
        var startY = when (settings.position) {
            StampPosition.BOTTOM_RIGHT, StampPosition.BOTTOM_LEFT -> height - margin - totalHeight + fontSize
            StampPosition.TOP_RIGHT, StampPosition.TOP_LEFT -> margin + fontSize
        }

        if (settings.backgroundOpacity > 0) {
            bgPaint.color = settings.backgroundColor
            bgPaint.alpha = settings.backgroundOpacity
            val pad = fontSize * 0.3f
            val left = if (isRightAligned) startX - maxWidth - pad else startX - pad
            val right = if (isRightAligned) startX + pad else startX + maxWidth + pad
            val top = startY - fontSize - pad * 0.5f
            val bottom = top + totalHeight + pad
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, bgPaint)
        }

        for (line in lines) {
            canvas.drawText(line, startX, startY, textPaint)
            startY += lineHeight
        }
    }
}
