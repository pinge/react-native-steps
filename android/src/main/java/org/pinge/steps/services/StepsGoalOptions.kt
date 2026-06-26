package org.pinge.steps.services

import com.facebook.react.bridge.ReadableMap
import org.pinge.steps.counters.Goal

/**
 * Configuration options for setting a goal over a period time with a step target. When from() is
 * invoked with null or with a non positive step target, it returns null and nothing downstream
 * evaluates a goal (no-op).
 */
data class StepsGoalOptions(
  val steps: Double,
  val period: String,
  val notification: StepsNotificationOptions,
) {
  companion object {
    fun from(map: ReadableMap?): StepsGoalOptions? {
      if (map == null) return null
      val steps = if (map.hasKey("steps") && !map.isNull("steps")) map.getDouble("steps") else Goal.DISABLED
      // JavaScript validates if steps > 0, so a non positive `steps` disables the goal.
      if (!Goal.isEnabled(steps)) return null
      val period =
        if (map.hasKey("period") && !map.isNull("period")) {
          map.getString("period") ?: Goal.PERIOD_DAILY
        } else {
          Goal.PERIOD_DAILY
        }
      val notificationMap =
        if (map.hasKey("notification") && !map.isNull("notification")) map.getMap("notification") else null
      return StepsGoalOptions(steps, period, StepsNotificationOptions.fromGoal(notificationMap))
    }
  }
}
