package org.pinge.steps.counters.pedometer

import android.hardware.Sensor
import android.hardware.SensorManager
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.SensorTypes
import org.pinge.steps.counters.StepsEventSink

// This class is responsible for listening to the pedometer (step counter) sensor.
class PedometerStepCounter(
  sink: StepsEventSink,
  sensorManager: SensorManager
) : SensorStepCounter(sink, sensorManager) {

  // The normalized sensor type exposed to JavaScript, always SensorTypes.PEDOMETER.
  override val sensorTypeString = SensorTypes.PEDOMETER
  // The sensor type Android constant, always Sensor.TYPE_STEP_COUNTER.
  override val sensorType = Sensor.TYPE_STEP_COUNTER
  // The pedometer sensor backing this counter, or null if the device has no pedometer.
  override val detectedSensor: Sensor? = sensorManager.getDefaultSensor(sensorType)
  // Distinguishes "no reading yet" from a genuine raw value of 0 (right after boot).
  private var rawInitialized: Boolean = false
  // The most recent raw cumulative since boot counter value. Step deltas are measured against it,
  // and it is re-baselined when the counter resets (e.g. device reboot).
  private var lastRaw: Double = 0.0
  // The total number of steps counted since the service started.
  override var currentSteps: Double = 0.0

  /**
   * The hardware counter is cumulative since boot. We accumulate positive deltas between consecutive
   * raw readings, so a reboot (counter resets low => negative delta) is absorbed rather than freezing
   * the count. 'lastRaw' is persisted so the running total continues across a process restart, and
   * 'rawInitialized' distinguishes "no reading yet" from a genuine raw value of 0 (right after boot).
   */
  override val rawCheckpoint: Double
    get() = if (rawInitialized) lastRaw else -1.0

  override fun resetSessionState() {
    rawInitialized = false
    lastRaw = 0.0
    currentSteps = 0.0
  }

  override fun restoreSessionState(
    rawCheckpoint: Double,
    accumulated: Double,
  ) {
    // Seed from the persisted checkpoint so deltas continue to be calculated correctly across
    // restarts. A negative checkpoint means no event was seen before the process was killed,
    // so fall back to a fresh session behavior (the next event will re-establish the checkpoint).
    if (rawCheckpoint >= 0.0) {
      rawInitialized = true
      lastRaw = rawCheckpoint
      currentSteps = accumulated
    } else {
      rawInitialized = false
      lastRaw = 0.0
      currentSteps = 0.0
    }
  }

  override fun hasDetectedStep(eventData: FloatArray): Boolean {
    val raw = eventData[0].toDouble()
    // First reading just establishes the checkpoint; there is no delta to add yet.
    if (!rawInitialized) {
      rawInitialized = true
      lastRaw = raw
      return false
    }
    val delta = raw - lastRaw
    lastRaw = raw
    return if (delta > 0.0) {
      // Normal forward progress: we add the steps taken since the previous reading. We can also
      // recover steps counted while the process was dead but the device stayed booted and the
      // step counter kept going.
      currentSteps += delta
      true
    } else {
      // delta <= 0 means a cumulative counter reset (device reboot) or no change. 'lastRaw' is
      // already re-baselined above, so the accumulated total is preserved and counting continues.
      false
    }
  }
}
