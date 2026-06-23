package org.pinge.steps.counters

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.facebook.react.bridge.WritableMap

/**
 * The base class for hardware sensor-based step counters.
 *
 * see: https://developer.android.com/reference/android/hardware/SensorManager
 *      https://developer.android.com/reference/android/hardware/SensorEventListener
 *      https://developer.android.com/reference/android/hardware/Sensor#TYPE_STEP_COUNTER
 */
abstract class SensorStepCounter(
  private val sink: StepsEventSink,
  private val sensorManager: SensorManager,
) : SensorEventListener {
  // The sensor type Android constant (Sensor.TYPE_STEP_COUNTER: 19, Sensor.TYPE_ACCELEROMETER: 1).
  abstract val sensorType: Int

  /**
   * Represents the sampling 'rate' that android.hardware.SensorEvent are delivered at. This is
   * only a hint, as events may be received faster (the usual) or slower than the specified rate.
   *
   * Can be one of the following:
   * - SensorManager.SENSOR_DELAY_NORMAL  => 200000
   * - SensorManager.SENSOR_DELAY_UI      => 66667
   * - SensorManager.SENSOR_DELAY_GAME    => 20000
   * - SensorManager.SENSOR_DELAY_FASTEST => 0
   *
   * or an explicit delay between events in microseconds (supported since API level 9).
   */
  private val samplingPeriod
    get() =
      when (sensorType) {
        Sensor.TYPE_ACCELEROMETER -> SensorManager.SENSOR_DELAY_GAME
        Sensor.TYPE_STEP_COUNTER -> SensorManager.SENSOR_DELAY_NORMAL
        else -> SensorManager.SENSOR_DELAY_UI
      }

  // The normalized sensor type exposed to JavaScript (PEDOMETER or ACCELEROMETER)
  abstract val sensorTypeString: String

  // The default sensor backing this counter, or null if the device has no sensor for the counter.
  abstract val detectedSensor: Sensor?

  // The 'step' event payload for the current session state.
  val stepsParamsMap: WritableMap
    get() = StepEvent.build(currentSteps, startDate, endDate, sensorTypeString)

  // The total number of steps counted since the service started.
  abstract var currentSteps: Double

  /**
   * The raw sensor checkpoint to persist for restart, meaning the last raw cumulative-counter value
   * seen, which together with the accumulated total lets a StepsForegroundService reconstruct state
   * after the process is killed. Negative when there is no raw checkpoint: before the first sensor
   * event or if we're using the accelerometer fallback, which has no cumulative counter.
   */
  open val rawCheckpoint: Double
    get() = -1.0

  // Resets the in-memory session state before a new step counting session starts.
  protected open fun resetSessionState() {
  }

  /**
   * Re-seeds the in-memory session state from values persisted by a StepsForegroundService so
   * a session can resume seamlessly after the process is killed.
   */
  protected open fun restoreSessionState(rawCheckpoint: Double, accumulated: Double) {
  }

  // Start date of the step counting in UTC milliseconds.
  private var startDate: Long = System.currentTimeMillis()

  // End date of the step counting in UTC milliseconds.
  private val endDate: Long
    get() = System.currentTimeMillis()

  fun startService(start: Long) {
    startDate = start.takeIf { it > 0 } ?: System.currentTimeMillis()
    resetSessionState()
    registerSensor()
  }

  /**
   * Resumes a step counting session that was previously persisted by StepsForegroundService],
   * re-seeding the state so the running total continues uninterrupted.
   */
  fun restoreService(start: Long, baseline: Double, accumulated: Double) {
    startDate = start.takeIf { it > 0 } ?: System.currentTimeMillis()
    restoreSessionState(baseline, accumulated)
    registerSensor()
  }

  private fun registerSensor() {
    val sensor = detectedSensor
    // If there is no sensor of this type on the device because getDefaultSensor() returned null,
    // we bail safely instead of crashing, and the session becomes a no-op.
    if (sensor == null) {
      Log.w(TAG_NAME, "No $sensorTypeString sensor available; not registering a listener")
      sink.emitError("No $sensorTypeString sensor available")
      return
    }
    sensorManager.registerListener(this, sensor, samplingPeriod)
  }

  fun stopService() {
    sensorManager.unregisterListener(this)
  }

  /**
   * Called when there is a new sensor event.  Note that "on changed" is somewhat of a misnomer,
   * as this will also be called if we have a new reading from a sensor with the exact same sensor
   * values (but a newer timestamp).
   * Check the following links for details on available sensor types and events:
   * - https://developer.android.com/reference/android/hardware/SensorManager
   * - https://developer.android.com/reference/android/hardware/SensorEvent
   * - https://developer.android.com/reference/android/hardware/Sensor#REPORTING_MODE_ON_CHANGE
   *
   * NOTE: The application does not own the android.hardware.SensorEvent object passed as a
   *       parameter and therefore cannot hold on to it. The object may be part of an internal pool
   *       and may be reused by the framework. We need to make a copy to hold on to the event.
   *
   * NOTE: Since the timestamp delivered from JavaScript is millisecond based, mistakes can occur
   *       if the timestamp of sensor events recorded every moment in nanoseconds is delivered as is.
   *       So we convert the timestamp (nanoseconds recorded in sensor events) using
   *       java.util.concurrent.TimeUnit.toMillis() or call System.currentTimeMillis().
   */
  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor == null ||
      event.sensor != detectedSensor ||
      event.sensor.type != sensorType ||
      detectedSensor?.type != event.sensor.type
    ) {
      return
    }
    if (hasDetectedStep(event.values)) {
      sink.emitStep(stepsParamsMap)
    }
  }

  /**
   * Processes one raw sensor reading and returns whether it completed a step or not. Implemented
   * in PedometerStepCounter and AccelerometerStepCounter with different motion sensor handling
   * algorithms. Both implementations update their internal step count as a side effect.
   * @return `true` when this reading completes a new step (and a step event should be emitted).
   */
  abstract fun hasDetectedStep(eventData: FloatArray): Boolean

  /**
   * Called when the accuracy of the registered sensor has changed. Unlike onSensorChanged(),
   * this is only called when the accuracy value changes. Check the SENSOR_STATUS_* constants
   * in the android.hardware.SensorManager class.
   * See https://developer.android.com/reference/android/hardware/SensorManager
   */
  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
  }

  companion object {
    val TAG_NAME: String = SensorStepCounter::class.java.name
  }
}
