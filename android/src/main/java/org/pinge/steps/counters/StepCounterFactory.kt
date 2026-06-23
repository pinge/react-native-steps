package org.pinge.steps.counters

import android.content.Context
import android.hardware.SensorManager
import org.pinge.steps.capabilities.HardwareCapabilities
import org.pinge.steps.capabilities.Permissions
import org.pinge.steps.counters.accelerometer.AccelerometerStepCounter
import org.pinge.steps.counters.pedometer.PedometerStepCounter

/**
 * Factory for step counting implementations using different hardware sensors.
 * When available and with permission granted, choose pedometer over accelerometer.
 */
object StepCounterFactory {

  fun create(context: Context, sensorManager: SensorManager, sink: StepsEventSink): SensorStepCounter =
    if (canUsePedometer(context)) {
      PedometerStepCounter(sink, sensorManager)
    } else {
      AccelerometerStepCounter(sink, sensorManager)
    }

  // The ACTIVITY_RECOGNITION permission is required for the pedometer events on API level 29+.
  // The accelerometer does not require any permission to be granted.
  private fun canUsePedometer(context: Context): Boolean =
    HardwareCapabilities.hasPedometer(context) &&
      Permissions.isActivityRecognitionGranted(context)
}
