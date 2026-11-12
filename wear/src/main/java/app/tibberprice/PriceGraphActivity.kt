package app.tibberprice

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Opened by tapping the complication: bar chart of prices from the current hour onward. */
class PriceGraphActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = PriceGraphView(this)
        setContentView(view)
        scope.launch {
            val now = Instant.now()
            val slots = TibberPrices(this@PriceGraphActivity).slots(now)
            view.show(slots.filter { it.start.plusSeconds(3600) > now })
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

private class PriceGraphView(context: Context) : View(context) {

    private var slots: List<PriceSlot>? = null
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hourFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    init { setBackgroundColor(Color.BLACK) }

    fun show(data: List<PriceSlot>) {
        slots = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val data = slots
        if (data == null || data.isEmpty()) {
            label(canvas, if (data == null) "Loading prices…" else "No prices yet.\nCheck Wi-Fi.", w / 2, h / 2, 15f, MUTED)
            return
        }

        val now = data.first()
        val cheapest = data.minBy { it.total }
        val lo = data.minOf { it.total }
        val hi = data.maxOf { it.total }

        // Header
        label(canvas, "Now ${cents(now.total)}", w / 2, h * 0.19f, 20f, Color.WHITE, bold = true)
        val cheapText = if (cheapest == now) "Cheapest is now"
        else "Cheapest ${local(cheapest).format(hourFmt)} · ${cents(cheapest.total)}"
        label(canvas, cheapText, w / 2, h * 0.27f, 13f, color(0f))

        // Bars, baseline at €0 so heights stay honest; colour carries the cheap/expensive signal.
        val left = w * 0.11f
        val right = w * 0.89f
        val top = h * 0.36f
        val bottom = h * 0.70f
        val slotW = (right - left) / data.size
        val gap = if (slotW > 4 * dp) 1.2f * dp else 0.5f * dp
        val scaleMax = maxOf(hi, 0.01)
        data.forEachIndexed { i, s ->
            val x0 = left + i * slotW
            val barTop = bottom - ((s.total.coerceAtLeast(0.0) / scaleMax) * (bottom - top)).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = color(if (hi > lo) ((s.total - lo) / (hi - lo)).toFloat() else 0f)
            canvas.drawRoundRect(RectF(x0 + gap / 2, barTop, x0 + slotW - gap / 2, bottom), dp, dp, paint)

            val t = local(s)
            if (i > 0 && t.hour == 0) { // day boundary
                paint.color = GRID
                paint.strokeWidth = dp
                canvas.drawLine(x0, top - 6 * dp, x0, bottom, paint)
                text.textAlign = Paint.Align.LEFT
                label(canvas, t.format(dayFmt), x0 + 3 * dp, top - 1 * dp, 10f, MUTED)
                text.textAlign = Paint.Align.CENTER
            }
            // keep hour labels clear of the "now" label under the first bar
            if (i * slotW > 26 * dp && t.hour % 6 == 0) label(canvas, "%02d".format(t.hour), x0 + slotW / 2, bottom + 13 * dp, 10f, MUTED)
            if (s == cheapest) {
                paint.color = color(0f)
                canvas.drawCircle(x0 + slotW / 2, barTop - 5 * dp, 2.5f * dp, paint)
            }
        }
        // "now" marker under the first bar
        paint.color = Color.WHITE
        canvas.drawRect(left, bottom + 2 * dp, left + slotW, bottom + 4 * dp, paint)
        label(canvas, "now", left + slotW / 2 + 6 * dp, bottom + 13 * dp, 10f, Color.WHITE)

        label(canvas, "Range ${cents(lo)}–${cents(hi)} per kWh", w / 2, h * 0.84f, 11f, MUTED)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, sizeSp: Float, col: Int, bold: Boolean = false) {
        text.textSize = sizeSp * dp
        text.color = col
        text.isFakeBoldText = bold
        s.split('\n').forEachIndexed { i, line -> c.drawText(line, x, y + i * text.textSize * 1.25f, text) }
    }

    private fun local(s: PriceSlot) = s.start.atZone(TibberPrices.ZONE)
    private fun cents(v: Double) = "${(v * 100).roundToInt()}¢"

    /** 0 = cheapest (green) → 1 = most expensive (red). */
    private fun color(t: Float): Int {
        val (a, b, f) = if (t < 0.5f) Triple(GREEN, YELLOW, t * 2) else Triple(YELLOW, RED, (t - 0.5f) * 2)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toInt()
        return Color.rgb(ch(16), ch(8), ch(0))
    }

    private companion object {
        val GREEN = Color.rgb(60, 200, 120)
        val YELLOW = Color.rgb(240, 200, 70)
        val RED = Color.rgb(240, 80, 80)
        val MUTED = Color.rgb(140, 145, 155)
        val GRID = Color.rgb(60, 63, 70)
    }
}
