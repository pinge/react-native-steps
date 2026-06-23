package org.pinge.steps.counters

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * Both the step counting (SensorStepCounter) and the foreground service's persisted state step
 * event replay (StepsForegroundService) build the event payload map here, so the emitted shape
 * is consistent between the two emit paths.
 */
object StepEvent {
  fun build(steps: Double, startDate: Long, endDate: Long, sensor: String): WritableMap =
    Arguments.createMap().apply {
      putDouble("steps", steps)
      putDouble("start", startDate.toDouble())
      putDouble("end", endDate.toDouble())
      putString("sensor", sensor)
    }
}
