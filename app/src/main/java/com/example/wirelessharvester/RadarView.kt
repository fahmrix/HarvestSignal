package com.example.wirelessharvester

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.example.wirelessharvester.data.SignalType
import com.example.wirelessharvester.data.WirelessSignal
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar animasi ringan untuk memvisualisasikan sinyal Wi-Fi, BLE, dan BTS.
 *
 * Sudut dibuat deterministik dari ID perangkat agar blip tidak berpindah secara acak,
 * sedangkan jarak dari pusat mengikuti kekuatan sinyal: sinyal kuat berada lebih dekat.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class RadarBlip(
        val id: String,
        val type: SignalType,
        val angleDegrees: Float,
        var radiusFraction: Float,
        var rssi: Int,
        var lastSeenMs: Long,
    )

    companion object {
        private const val SWEEP_DURATION_MS = 3_200L
        private const val BLIP_TTL_MS = 20_000L
        private const val MAX_BLIPS = 72
    }

    private val density = resources.displayMetrics.density
    private val blips = LinkedHashMap<String, RadarBlip>()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(3, 15, 28)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 34, 211, 238)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val faintGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 34, 211, 238)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 212, 191)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val sweepTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var scanning = false

    fun setScanning(active: Boolean) {
        scanning = active
        if (active) {
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    fun submitSignal(signal: WirelessSignal) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { submitSignal(signal) }
            return
        }

        val effectiveRssi = when (signal.type) {
            SignalType.CELLULAR -> signal.rsrp ?: signal.ssRsrp ?: signal.rssi
            else -> signal.rssi
        }
        val normalizedStrength = ((effectiveRssi + 110f) / 80f).coerceIn(0f, 1f)
        val radiusFraction = 0.18f + (1f - normalizedStrength) * 0.76f
        val angle = ((signal.bssid.hashCode().toLong() and 0xffffffffL) % 360L).toFloat()
        val now = SystemClock.elapsedRealtime()

        val existing = blips[signal.bssid]
        if (existing != null) {
            existing.radiusFraction = radiusFraction
            existing.rssi = effectiveRssi
            existing.lastSeenMs = now
        } else {
            blips[signal.bssid] = RadarBlip(
                id = signal.bssid,
                type = signal.type,
                angleDegrees = angle,
                radiusFraction = radiusFraction,
                rssi = effectiveRssi,
                lastSeenMs = now,
            )
        }

        while (blips.size > MAX_BLIPS) {
            val oldestKey = blips.minByOrNull { it.value.lastSeenMs }?.key ?: break
            blips.remove(oldestKey)
        }

        contentDescription = "Radar sinyal aktif, ${blips.size} perangkat terdeteksi"
        postInvalidateOnAnimation()
    }

    fun clearSignals() {
        blips.clear()
        contentDescription = "Radar sinyal, belum ada perangkat terdeteksi"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (230f * density).toInt()
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 2f - 14f * density
        if (radius <= 0f) return

        canvas.drawCircle(centerX, centerY, radius, backgroundPaint)

        for (ring in 1..4) {
            val ringRadius = radius * ring / 4f
            canvas.drawCircle(
                centerX,
                centerY,
                ringRadius,
                if (ring == 4) gridPaint else faintGridPaint,
            )
        }

        for (angle in 0 until 360 step 45) {
            val radians = Math.toRadians(angle.toDouble())
            canvas.drawLine(
                centerX,
                centerY,
                centerX + cos(radians).toFloat() * radius,
                centerY + sin(radians).toFloat() * radius,
                faintGridPaint,
            )
        }

        val now = SystemClock.elapsedRealtime()
        val sweepAngle = if (scanning) {
            (now % SWEEP_DURATION_MS).toFloat() / SWEEP_DURATION_MS * 360f - 90f
        } else {
            -90f
        }

        drawSweepTrail(canvas, centerX, centerY, radius, sweepAngle)

        val sweepRadians = Math.toRadians(sweepAngle.toDouble())
        canvas.drawLine(
            centerX,
            centerY,
            centerX + cos(sweepRadians).toFloat() * radius,
            centerY + sin(sweepRadians).toFloat() * radius,
            sweepPaint,
        )

        val iterator = blips.values.iterator()
        while (iterator.hasNext()) {
            val blip = iterator.next()
            val age = now - blip.lastSeenMs
            if (age > BLIP_TTL_MS) {
                iterator.remove()
                continue
            }

            val radians = Math.toRadians((blip.angleDegrees - 90f).toDouble())
            val distance = radius * blip.radiusFraction
            val x = centerX + cos(radians).toFloat() * distance
            val y = centerY + sin(radians).toFloat() * distance
            val ageAlpha = (255f * (1f - age.toFloat() / BLIP_TTL_MS)).toInt().coerceIn(45, 255)
            val angularDistance = smallestAngleDistance(sweepAngle + 90f, blip.angleDegrees)
            val isSwept = scanning && angularDistance < 22f
            val baseColor = colorForType(blip.type)

            blipPaint.color = withAlpha(baseColor, ageAlpha)
            blipRingPaint.color = withAlpha(baseColor, (ageAlpha * 0.65f).toInt())
            val dotRadius = (if (isSwept) 5.5f else 3.5f) * density
            canvas.drawCircle(x, y, dotRadius, blipPaint)
            if (isSwept) {
                val pulse = 9f + ((now / 55L) % 8L).toFloat()
                canvas.drawCircle(x, y, pulse * density, blipRingPaint)
            }
        }

        canvas.drawCircle(centerX, centerY, 4f * density, centerPaint)
        canvas.drawCircle(centerX, centerY, 8f * density, gridPaint)

        if (scanning) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawSweepTrail(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        sweepAngle: Float,
    ) {
        val bounds = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val path = Path().apply {
            moveTo(centerX, centerY)
            arcTo(bounds, sweepAngle - 42f, 42f)
            close()
        }
        sweepTrailPaint.color = Color.argb(if (scanning) 48 else 20, 45, 212, 191)
        canvas.drawPath(path, sweepTrailPaint)
    }

    private fun colorForType(type: SignalType): Int = when (type) {
        SignalType.WIFI -> Color.rgb(34, 211, 238)
        SignalType.BLE -> Color.rgb(167, 139, 250)
        SignalType.CELLULAR -> Color.rgb(251, 191, 36)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun smallestAngleDistance(first: Float, second: Float): Float {
        val difference = ((first - second + 540f) % 360f) - 180f
        return kotlin.math.abs(difference)
    }
}
