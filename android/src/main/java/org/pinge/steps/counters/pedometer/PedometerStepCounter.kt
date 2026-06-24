package org.pinge.steps.counters.pedometer

import android.hardware.Sensor
import android.hardware.SensorManager
import org.pinge.steps.counters.Cadence
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.SensorTypes
import org.pinge.steps.counters.StepsEventSink
import kotlin.math.floor


/**
 * This class is responsible for listening to the pedometer (step counter) sensor.
 *
 * Maximum plausible walking cadence (steps per second), or Cadence.DISABLED for no cap.
 * When enabled, a reading may credit at most "floor(elapsedSinceLastReading * cadence)" steps.
 * The excess is dropped as a burst false positive (over count). Because the cap is measured
 * against the time since the previous reading, a legitimately batched delivery (many steps
 * reported at once over a long window) is credited in full, while a fast spike over a short
 * window is trimmed.
 */
class PedometerStepCounter(
  sink: StepsEventSink,
  sensorManager: SensorManager,
  private val cadence: Double,
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
  // Timestamp in nanoseconds of the previous reading, used for the cadence cap. -1 until the first
  // reading. Reset on session start/restore so the first reading after a (re)start is never capped.
  private var lastEventAt: Long = -1L
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
    lastEventAt = -1L
    currentSteps = 0.0
  }

  override fun restoreSessionState(
    rawCheckpoint: Double,
    accumulated: Double,
  ) {
    // Seed from the persisted checkpoint so deltas continue to be calculated correctly across
    // restarts. A negative checkpoint means no event was seen before the process was killed,
    // so fall back to a fresh session behavior (the next event will re-establish the checkpoint).
    lastEventAt = -1L
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

  override fun hasDetectedStep(eventData: FloatArray, eventAt: Long): Boolean {
    val raw = eventData[0].toDouble()
    // First reading just establishes the checkpoint, there is no delta to add yet.
    if (!rawInitialized) {
      rawInitialized = true
      lastRaw = raw
      lastEventAt = eventAt
      return false
    }
    val delta = raw - lastRaw
    lastRaw = raw
    if (delta <= 0.0) {
      // delta <= 0 means a cumulative counter reset (device reboot) or no change. 'lastRaw' is
      // already re-baselined above, so the accumulated total is preserved and counting continues.
      lastEventAt = eventAt
      return false
    }

    // Cap the credited steps to the configured cadence. The allowance scales with the time since the
    // previous reading, so steady walking (and batched delivery) is credited in full while a spike
    // over a short window is trimmed. Excess is dropped permanently (no carry forward) matching the
    // accelerometer's absolute cap and avoiding phantom steps after a burst stops.
    val credited =
      if (Cadence.isEnabled(cadence) && lastEventAt >= 0L) {
        val elapsedSeconds = (eventAt - lastEventAt).coerceAtLeast(0L) / 1_000_000_000.0
        minOf(delta, floor(elapsedSeconds * cadence))
      } else {
        // If cadence is disabled or there is no prior event to measure against (e.g. first reading
        // after a (re)start), then we credit the full delta, including steps the counter kept while
        // the app process was not running.
        delta
      }
    lastEventAt = eventAt

    return if (credited > 0.0) {
      currentSteps += credited
      true
    } else {
      // This path means thee whole delta was an over-cadence burst, so we drop it.
      false
    }
  }
}
