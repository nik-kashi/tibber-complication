package app.tibberprice

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant
import java.util.Locale

/**
 * Power price gauge: the arc runs from today's cheapest to today's most expensive
 * Tibber price, the value is the current hour's price, and the text shows it in cents.
 */
class PriceComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, current = 0.27, min = 0.11, max = 0.40)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val now = Instant.now()
        val slots = TibberPrices(this).slots(now)
        val current = slots.firstOrNull { it.start <= now && now < it.start.plusSeconds(3600) }
            ?: return NoDataComplicationData()
        val today = now.atZone(TibberPrices.ZONE).toLocalDate()
        val todays = slots.filter { it.start.atZone(TibberPrices.ZONE).toLocalDate() == today }.map { it.total }
        return build(request.complicationType, current.total, todays.min(), todays.max())
    }

    private fun build(type: ComplicationType, current: Double, min: Double, max: Double): ComplicationData? {
        val text = PlainComplicationText.Builder("${Math.round(current * 100)}¢").build()
        val desc = PlainComplicationText.Builder(
            String.format(Locale.US, "Power price %.0f cents per kWh, today %.0f to %.0f", current * 100, min * 100, max * 100)
        ).build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_bolt)).build()
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, PriceGraphActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                // Relative scale: empty arc = today's cheapest hour, full = most expensive.
                // Guard against a flat day (min == max), which faces may reject.
                val hi = if (max > min) max else min + 0.01
                RangedValueComplicationData.Builder(
                    value = current.toFloat().coerceIn(min.toFloat(), hi.toFloat()),
                    min = min.toFloat(),
                    max = hi.toFloat(),
                    contentDescription = desc,
                ).setText(text)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .apply {
                        if (Build.VERSION.SDK_INT >= 33) {
                            setColorRamp(ColorRamp(intArrayOf(GREEN, YELLOW, RED), interpolated = true))
                        }
                    }
                    .build()
            }
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, desc).setMonochromaticImage(icon).setTapAction(tap).build()
            else -> null
        }
    }

    private companion object {
        val GREEN = Color.rgb(60, 200, 120)
        val YELLOW = Color.rgb(240, 200, 70)
        val RED = Color.rgb(240, 80, 80)
    }
}
