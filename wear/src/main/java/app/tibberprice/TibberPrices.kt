package app.tibberprice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** One price slot: [start, start + 1h), total price incl. tax in EUR/kWh. */
data class PriceSlot(val start: Instant, val total: Double)

/**
 * Fetches today's and tomorrow's prices from the Tibber API and caches them,
 * so the network is used roughly twice a day.
 */
class TibberPrices(private val context: Context) {

    private val prefs = context.getSharedPreferences("tibber", Context.MODE_PRIVATE)

    suspend fun slots(now: Instant = Instant.now()): List<PriceSlot> {
        val cached = readCache()
        if (!needsRefresh(cached, now)) return cached
        return try {
            val fresh = fetch()
            writeCache(fresh, now)
            fresh
        } catch (e: Exception) {
            cached // offline or API error: keep showing what we have
        }
    }

    private fun needsRefresh(slots: List<PriceSlot>, now: Instant): Boolean {
        if (slots.none { it.start <= now && now < it.start.plusSeconds(3600) }) return true
        // Tomorrow's prices are published around 13:00 CET; pick them up once available.
        val local = now.atZone(ZONE)
        val hasTomorrow = slots.any { it.start.atZone(ZONE).toLocalDate() == local.toLocalDate().plusDays(1) }
        val lastFetch = Instant.ofEpochMilli(prefs.getLong(KEY_FETCHED_AT, 0))
        return !hasTomorrow && local.hour >= 13 && lastFetch.isBefore(now.minusSeconds(30 * 60))
    }

    private suspend fun fetch(): List<PriceSlot> = withContext(Dispatchers.IO) {
        val token = BuildConfig.TIBBER_TOKEN
        require(token.isNotBlank()) { "No Tibber token configured" }
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("query", QUERY).toString().toByteArray()) }
            if (conn.responseCode != 200) error("Tibber HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(root: JSONObject): List<PriceSlot> {
        val homes = root.getJSONObject("data").getJSONObject("viewer").getJSONArray("homes")
        for (i in 0 until homes.length()) {
            val sub = homes.getJSONObject(i).optJSONObject("currentSubscription") ?: continue
            val info = sub.getJSONObject("priceInfo")
            val out = mutableListOf<PriceSlot>()
            for (day in listOf("today", "tomorrow")) {
                val arr = info.optJSONArray(day) ?: continue
                for (j in 0 until arr.length()) {
                    val p = arr.getJSONObject(j)
                    out += PriceSlot(OffsetDateTime.parse(p.getString("startsAt")).toInstant(), p.getDouble("total"))
                }
            }
            if (out.isNotEmpty()) return out.sortedBy { it.start }
        }
        error("No home with an active subscription")
    }

    private fun readCache(): List<PriceSlot> {
        val raw = prefs.getString(KEY_SLOTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PriceSlot(Instant.ofEpochSecond(o.getLong("s")), o.getDouble("t"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeCache(slots: List<PriceSlot>, now: Instant) {
        val arr = JSONArray()
        slots.forEach { arr.put(JSONObject().put("s", it.start.epochSecond).put("t", it.total)) }
        prefs.edit().putString(KEY_SLOTS, arr.toString()).putLong(KEY_FETCHED_AT, now.toEpochMilli()).apply()
    }

    companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
        private const val API = "https://api.tibber.com/v1-beta/gql"
        private const val KEY_SLOTS = "slots"
        private const val KEY_FETCHED_AT = "fetchedAt"
        private const val QUERY =
            "{ viewer { homes { currentSubscription { priceInfo { " +
                "today { total startsAt } tomorrow { total startsAt } } } } } }"
    }
}
