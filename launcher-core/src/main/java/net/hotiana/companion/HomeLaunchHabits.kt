package net.hotiana.companion

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * Records when the person opens each app, from every entry point (Home icon, dock, Apps drawer, search).
 *
 * Every successful [launchHomeApp] records the hour it happened in, split into weekdays and weekends: 48 small
 * counters per app, kept in this launcher's own private preferences, and feed the "Suggested now" ranking. It does not use
 * the Usage Access permission, and the code here does not transmit the counters; only the app's launcher component
 * and its counters are stored.
 */
internal object HomeLaunchHabits {
    private const val PREFS = "hotiana_launch_habits"
    const val BUCKETS = 48
    /** An app's busiest hour stops at this; reaching it halves every hour, so old habits fade behind new ones. */
    const val CEILING = 240
    /** Apps remembered; the least used beyond this are forgotten first. */
    const val KEEP = 300

    fun record(context: Context, component: String, now: Long = System.currentTimeMillis()) = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = homeLaunchRecorded(homeLaunchParse(prefs.getString(component, null)), homeLaunchBucket(now))
        prefs.edit().putString(component, next.joinToString(",")).apply()
        if (prefs.all.size > KEEP) {
            val forget = prefs.all.entries.sortedByDescending { homeLaunchParse(it.value as? String).sum() }
                .drop(KEEP).map { it.key }
            prefs.edit().apply { forget.forEach { remove(it) } }.apply()
        }
    }.isSuccess

    /** Every remembered app's hours, keyed like the search history ("app:" + component). */
    fun hours(context: Context): Map<String, IntArray> = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            (value as? String)?.let { "app:$key" to homeLaunchParse(it) }
        }.toMap()
    }.getOrDefault(emptyMap())
}

/** The hour of the day, plus 24 on a Saturday or Sunday. */
internal fun homeLaunchBucket(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Int {
    val time = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }
    val weekend = time.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
    return time.get(Calendar.HOUR_OF_DAY) + if (weekend) 24 else 0
}

internal fun homeLaunchParse(stored: String?): IntArray {
    val parts = stored?.split(',').orEmpty()
    return IntArray(HomeLaunchHabits.BUCKETS) { parts.getOrNull(it)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0 }
}

internal fun homeLaunchRecorded(hours: IntArray, bucket: Int): IntArray {
    val next = IntArray(HomeLaunchHabits.BUCKETS) { hours.getOrElse(it) { 0 }.coerceAtLeast(0) }
    next[bucket.coerceIn(0, HomeLaunchHabits.BUCKETS - 1)]++
    return if (next.max() >= HomeLaunchHabits.CEILING) IntArray(next.size) { next[it] / 2 } else next
}

/** How much this app belongs to [bucket]: its own hour counts double, the hour either side once, same kind of day. */
internal fun homeLaunchNowScore(hours: IntArray, bucket: Int): Int {
    val day = (bucket / 24) * 24
    val hour = bucket % 24
    fun at(h: Int) = hours.getOrElse(day + (h + 24) % 24) { 0 }
    return at(hour) * 2 + at(hour - 1) + at(hour + 1)
}

/**
 * The counts the Apps drawer ranks by: the larger of the search sheet's count and the all-door total (an app opened
 * from search is also a launch, so the two are never added), plus how strongly it belongs to this hour.
 */
internal fun homeAppUsageCounts(search: Map<String, Int>, hours: Map<String, IntArray>, bucket: Int): Map<String, Int> =
    (search.keys + hours.keys).associateWith { id ->
        val habit = hours[id]
        maxOf(search[id] ?: 0, habit?.sum() ?: 0) + (habit?.let { homeLaunchNowScore(it, bucket) } ?: 0)
    }
