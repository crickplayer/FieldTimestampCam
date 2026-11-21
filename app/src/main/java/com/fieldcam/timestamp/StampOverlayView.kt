package com.fieldcam.timestamp

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * Draws the timestamp/compass stamp on top of the camera preview as a normal
 * Android view, so it is always visible on screen regardless of whether the
 * phone supports CameraX effects. Redraws twice a second so the clock,
 * compass and GPS values stay live.
 */
class StampOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var renderer: TelemetryOverlayRenderer? = null

    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, REFRESH_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer?.draw(canvas, width, height)
    }

    private companion object {
        const val REFRESH_MS = 500L
    }
}
