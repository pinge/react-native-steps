package org.pinge.steps.counters

import java.util.Calendar

/**
 * A Goal represents the effort required in terms of steps during a certain period of time.
 * This is used as a gamification UX concept for users to acquire a healthy habit of walking a
 * certain amount of steps on a recurring basis.
 *
 * These helpers are used during validation and per period window key for daily rollovers. When the
 * window key changes, the goal baseline and its once per period notification flag are reset.
 *
 * Only 'daily' is supported for now; 'weekly' is reserved and falls back to daily until implemented.
 */
object Goal {
  // Represents a "no goal" set, so all events coming from the sensor step counters are passed
  // through without any filtering applied.
  const val DISABLED = 0.0

  const val PERIOD_DAILY = "daily"

  // Sanitizes a JavaScript value into a goal enabling value or DISABLED.
  fun sanitize(steps: Double): Double = if (isEnabled(steps)) steps else DISABLED

  // Whether a step target enables a daily steps goal.
  fun isEnabled(steps: Double): Boolean = steps > DISABLED

  /**
   * The key identifying the current period window for the given wall clock time. A change in this
   * key between two readings signals a period rollover. For 'daily' this is the local calendar day.
   * Uses the device's default time zone/calendar, so the daily reset happens around local midnight.
   */
  fun periodKey(epochMs: Long, period: String): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = epochMs
    return when (period) {
      // PERIOD_WEEKLY -> weekKey(calendar)
      else -> dailyKey(calendar)
    }
  }

  // year * 1000 + dayOfYear is unique per local day and changes on a day or year rollover. The key
  // is only compared for equality, so we can use a collision free Long.
  private fun dailyKey(calendar: Calendar): Long {
    val year = calendar.get(Calendar.YEAR)
    val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
    return year * 1000L + dayOfYear
  }
}
