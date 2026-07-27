package org.pinge.steps.counters

import android.os.Bundle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * The step event payload crosses a process boundary, where step counting runs in the :steps process,
 * which does not initialize React Native, so payloads are produced there as a plain Bundle to avoid
 * using React Native types and only converted to a WritableMap in the main process, right before
 * emitting to JavaScript. We keep the WritableMap / Arguments out of the :steps process so step
 * counting logic never depends on React Native libraries in a process that never loads them.
 */
object StepEvent {
  private const val KEY_STEPS = "steps"
  private const val KEY_START = "start"
  private const val KEY_END = "end"
  private const val KEY_SENSOR = "sensor"

  // Builds the neutral Bundle payload produced by the counters and the foreground service. Start and
  // end are stored as doubles (Unix milliseconds) to match the shape JavaScript receives.
  fun bundle(steps: Double, startDate: Long, endDate: Long, sensor: String): Bundle =
    Bundle().apply {
      putDouble(KEY_STEPS, steps)
      putDouble(KEY_START, startDate.toDouble())
      putDouble(KEY_END, endDate.toDouble())
      putString(KEY_SENSOR, sensor)
    }

  // Converts the neutral Bundle payload into the WritableMap emitted to JavaScript. Only called in
  // the main (React Native) process, where Arguments / WritableNativeMap are available.
  fun toWritableMap(data: Bundle): WritableMap =
    Arguments.createMap().apply {
      putDouble(KEY_STEPS, data.getDouble(KEY_STEPS))
      putDouble(KEY_START, data.getDouble(KEY_START))
      putDouble(KEY_END, data.getDouble(KEY_END))
      putString(KEY_SENSOR, data.getString(KEY_SENSOR))
    }
}
