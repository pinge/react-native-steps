package org.pinge.steps.counters

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
   * Represents the sampling rate that android.hardware.SensorEvent are delivered at. This is
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
  val stepPayload: WritableMap
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

  // Background thread that SensorManager delivers sensor events on, so onSensorChanged() (and the
  // step detection it drives, up to ~50 Hz for the accelerometer fallback) never runs on the main
  // thread. Created in registerSensor() and torn down in stopService(), null while not registered.
  private var sensorThread: HandlerThread? = null

  // Detection runs on sensorThread, but the resulting step is delivered to the sink on the main
  // thread. This keeps the downstream emit/persist/notify pipeline (StepsForegroundService and its
  // fields) single-threaded as it was before sensor delivery moved off the main thread, so only the
  // hot detection path is off main and no cross thread field synchronization is needed downstream.
  private val mainThreadHandler = Handler(Looper.getMainLooper())

  // Whether this counter is registered and should still deliver steps. Cleared in stopService() and
  // checked inside the main thread emit runnable, so a step the sensor thread posts after teardown is
  // dropped rather than re-firing the pipeline (e.g. re-posting a cancelled notification). We use
  // Volatile here because start/stop may run off the main thread (the in-process fallback is driven
  // from the module's @ReactMethod calls) while the guard is read on the main thread.
  @Volatile private var registered = false

  // Whether the sensor listener is currently registered and delivering steps. This is false before
  // startService()/restoreService(), when no sensor of this type exists (registerSensor() bails),
  // and after stopService(). Used by the session coordinator in isCounting() for the in-process path.
  fun isRegistered(): Boolean = registered

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
    // If there is no sensor of this type on the device because getDefaultSensor() returned
    // null, we bail safely instead of crashing, and the session becomes a no-op.
    if (sensor == null) {
      Log.w(TAG_NAME, "No $sensorTypeString sensor available; not registering a listener")
      return
    }
    // Deliver sensor events on a dedicated background thread instead of the main thread.
    val thread = HandlerThread("StepCounterSensor").also { it.start() }
    sensorThread = thread
    registered = true
    sensorManager.registerListener(this, sensor, samplingPeriod, Handler(thread.looper))
  }

  fun stopService() {
    registered = false
    sensorManager.unregisterListener(this)
    // Quit the delivery thread safely after unregistering so no further events are dispatched to it.
    sensorThread?.quitSafely()
    sensorThread = null
    // Drop any emitted step already posted to the main thread. Events that the sensor thread posts
    // after this point are still dropped by the 'registered' guard in the onSensorChanged() emit runnable.
    mainThreadHandler.removeCallbacksAndMessages(null)
  }

  /**
   * Called when there is a new sensor event. Note that "on changed" is somewhat of a misnomer,
   * as this method will also be called if we have a new reading from a sensor with the exact
   * same sensor values (but a newer timestamp).
   *
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
    // SensorEvent.timestamp is nanoseconds since boot (an "uptime" clock), not since the Unix epoch,
    // and the exact base is not guaranteed across devices, so it can't be turned into a wall clock
    // time; only differences between two timestamps are meaningful. That is all the walking cadence
    // cap needs, and it reflects when the reading occurred, so it is more accurate than wall clock at
    // processing time when the OS batches sensor deliveries.
    if (hasDetectedStep(event.values, event.timestamp)) {
      // We build the payload here on the sensor thread since it reads the counter state that is mutated
      // only on this thread. Then we hand over the immutable map to the main thread for emitting. The
      // 'registered' guard drops a step that lands after stopService() so the pipeline is not touched
      // after teardown.
      val payload = stepPayload
      mainThreadHandler.post { if (registered) sink.emitStep(payload) }
    }
  }

  /**
   * Processes one raw sensor reading and returns whether it completed a step or not. Implemented
   * in PedometerStepCounter and AccelerometerStepCounter with different motion sensor handling
   * algorithms. Both implementations update their internal step count as a side effect.
   */
  abstract fun hasDetectedStep(eventData: FloatArray, eventAt: Long): Boolean

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
