package org.pinge.steps.counters.accelerometer

import android.hardware.Sensor
import android.hardware.SensorManager
import org.pinge.steps.counters.Cadence
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.SensorTypes
import org.pinge.steps.counters.StepsEventSink
import kotlin.math.sqrt

/**
 * This class is responsible for listening to the accelerometer sensor.
 *
 * We implement the algorithm from Lee, Choi & Lee, "Step Detection Robust against the Dynamics
 * of Smartphones", Sensors 2015, 15, 27230–27250 (see docs/sensors-15-27230.pdf).
 *
 * The detector works on the orientation-independent magnitude of acceleration:
 *
 * |a| = sqrt(x²+y²+z²)
 *
 * and models a step as a peak followed by an adjacent valley. Two adaptive thresholds make it
 * robust to changing step mode (walk/run) and device pose (hand/pocket/bag):
 *
 * - Adaptive magnitude threshold: a sample is a peak/valley candidate only if it is a strict
 *   local extremum that also clears the band "μ_a ± σ_a/α", where the step average
 *   "μ_a = (|a_p| + |a_v|)/2" is the midpoint of the most recent peak/valley pair (Eq. 1–2).
 *   Because "μ_a" is the midpoint of the latest cycle rather than a long-window mean, it
 *   re-centers within one step of a mode/pose change, so extrema are not missed during transitions
 *
 * - Adaptive temporal threshold: a candidate is confirmed only if its interval since the last
 *   same-type extremum exceeds "Th = μ_int − σ_int/β" (Eq. 3–4), computed from the running
 *   statistics of recent inter-peak / inter-valley intervals. This suppresses pseudo-extrema
 *   that cluster closer than the cadence allows, and widens automatically during transitions
 *   so real steps survive.
 *
 * Intervals are measured in sample indices: at the fixed SENSOR_DELAY_GAME (~50 Hz, matching the
 * paper) the magnitude/temporal thresholds share units, so the algorithm is self-consistent.
 */
class AccelerometerStepCounter(
  sink: StepsEventSink,
  sensorManager: SensorManager,
  cadence: Double,
) : SensorStepCounter(sink, sensorManager) {
  // Absolute minimum interval between two counted steps, derived from the configured cadence cap.
  // The cap is intrinsic to this detector: the paper's relative temporal threshold adapts to (and
  // accepts) fast shaking, so this absolute floor is what actually rejects shake bursts. When the
  // caller leaves cadence disabled we still apply the built-in Cadence.MAX (2.5 steps/s = 400 ms)
  // safety floor; a configured cap (<= MAX) only ever tightens it.
  private val minStepInterval: Long =
    Cadence.minStepInterval(if (Cadence.isEnabled(cadence)) cadence else Cadence.MAX)

  // The normalized sensor type exposed to JavaScript, always SensorTypes.ACCELEROMETER.
  override val sensorTypeString = SensorTypes.ACCELEROMETER
  // The sensor type Android constant, always Sensor.TYPE_ACCELEROMETER.
  override val sensorType = Sensor.TYPE_ACCELEROMETER
  // The accelerometer sensor backing this counter, or null if the device has no accelerometer.
  override val detectedSensor: Sensor? = sensorManager.getDefaultSensor(sensorType)
  // The total number of steps counted since the service started.
  override var currentSteps: Double = 0.0

  private enum class State { INIT, PEAK, VALLEY }

  // Detection state machine: tracks whether the last confirmed extremum was a peak or a valley so
  // an incoming candidate can be classified as a new extremum, a replacement within a cluster, or
  // ignored.
  private var state = State.INIT

  // Most recent confirmed peak/valley: magnitude and sample index.
  private var peakMag = 0f
  private var valleyMag = 0f
  private var peakIndex = 0L
  private var valleyIndex = 0L
  private var havePeak = false
  private var haveValley = false

  // Step average "μ_a = (|a_p| + |a_v|)/2", the midpoint of the most recent peak/valley pair. Until the
  // first full pair exists it is unset and the running magnitude mean is used as the band centre instead.
  private var stepAverage = 0f
  private var stepAverageInitialized = false

  // Running statistics. magStats backs the step deviation σ_a over the last K magnitudes; the interval
  // stats back the adaptive temporal thresholds over the last M peak / valley intervals.
  private val magStats = RingStats(K)
  private val peakIntervalStats = RingStats(M)
  private val valleyIntervalStats = RingStats(M)

  // One-sample look-ahead: the middle sample a[n] is classified once its right neighbour a[n+1] arrives,
  // using the buffered left a[n-1] (prevPrevMag) and middle a[n] (prevMag).
  private var prevMag = 0f
  private var prevPrevMag = 0f
  private var sampleIndex = 0L

  // Wall clock time of the last counted step, for the absolute max-cadence cap (see classifyMiddleSample()).
  // Kept in nanoseconds because it encodes a physical limit on human step rate, independent of sample rate.
  private var lastStepAt = 0L

  override fun resetSessionState() {
    currentSteps = 0.0
    state = State.INIT
    peakMag = 0f
    valleyMag = 0f
    peakIndex = 0L
    valleyIndex = 0L
    havePeak = false
    haveValley = false
    stepAverage = 0f
    stepAverageInitialized = false
    prevMag = 0f
    prevPrevMag = 0f
    sampleIndex = 0L
    lastStepAt = 0L
    magStats.clear()
    peakIntervalStats.clear()
    valleyIntervalStats.clear()
  }

  override fun restoreSessionState(rawCheckpoint: Double, accumulated: Double) {
    // The accelerometer has no cumulative hardware counter, so there is no raw checkpoint to honor; the
    // detector restarts fresh (a brief warm-up after a process-restart resume is acceptable) and the
    // running total continues additively from the persisted value.
    resetSessionState()
    currentSteps = accumulated
  }

  /**
   * Feed one accelerometer sample. The magnitude is pushed into the σ_a window, then, once the window
   * has warmed up and a one-sample look-ahead is available, the previous sample (the middle of the
   * "a[n-1], a[n], a[n+1]" triple) is classified and run through the step state machine.
   */
  override fun hasDetectedStep(eventData: FloatArray, eventAt: Long): Boolean {
    // This detector times its absolute cadence cap with System.nanoTime() (see classifyMiddleSample());
    // eventAt is unused here. The accelerometer is sampled at a fixed rate without the batching
    // the pedometer can exhibit, so processing-time and event-time are equivalent for the cap.
    val mag = norm(eventData)
    magStats.push(mag)
    sampleIndex++

    var stepDetected = false
    // Suppress detection until the σ_a window is full (~0.5 s): this is the warm-up that keeps the early,
    // unsettled statistics from producing false steps, and guarantees the look-ahead neighbours are valid.
    if (magStats.isFull) {
      val middleIndex = sampleIndex - 1
      stepDetected = classifyMiddleSample(prevPrevMag, prevMag, mag, middleIndex, System.nanoTime())
    }

    prevPrevMag = prevMag
    prevMag = mag
    return stepDetected
  }

  /**
   * Classify the middle sample of the 'left, middle, right' triple as a peak/valley candidate (Eq. 2)
   * and drive the state machine (Algorithm 1). Returns 'true' if this completes a step.
   */
  private fun classifyMiddleSample(
    left: Float,
    middle: Float,
    right: Float,
    n: Long,
    time: Long,
  ): Boolean {
    val sigmaA = magStats.stddev
    // Stationary guard (beyond the paper, which only evaluated while stepping): at rest σ_a collapses to
    // sensor noise and, before the step average is seeded, the band centre sits on the resting mean so
    // noise straddles it and yields tiny alternating extrema. Gate detection on a minimum step deviation
    // so a still device produces zero steps; real walking easily clears it.
    if (sigmaA < MIN_STEP_DEVIATION) return false

    val band = sigmaA / ALPHA
    val centre = if (stepAverageInitialized) stepAverage else magStats.mean
    val isPeak = middle > left && middle > right && middle > centre + band
    val isValley = middle < left && middle < right && middle < centre - band

    if (isPeak) {
      when {
        // Startup: the first peak seeds the machine; valleys before it are discarded.
        state == State.INIT -> registerPeak(middle, n, recordInterval = false)
        // New peak after a valley, far enough from the last peak: a genuine new cycle.
        state == State.VALLEY && intervalSince(peakIndex, n) > peakThreshold() -> {
          registerPeak(middle, n, recordInterval = true)
          updateStepAverage()
        }
        // A higher peak clustered close behind the last one: replace it (the real apex of the cluster).
        state == State.PEAK && intervalSince(peakIndex, n) <= peakThreshold() && middle > peakMag ->
          registerPeak(middle, n, recordInterval = false)
      }
    } else if (isValley) {
      when {
        // New valley after a peak, far enough from the last valley: this completes a step. The state
        // machine always advances (so detection continues), but the step is only counted when at least
        // minStepInterval has elapsed since the last counted step. That absolute cap on cadence is
        // what the paper's relative threshold lacks: without it, a fast shake registers many valid
        // peak/valley pairs per second, and the relative threshold simply adapts to that rate. Walking
        // never exceeds the configured cap (<= 2.5 steps/s), so this rejects shake bursts without
        // dropping real steps.
        state == State.PEAK && intervalSince(valleyIndex, n) > valleyThreshold() -> {
          registerValley(middle, n, recordInterval = true)
          updateStepAverage()
          if (time - lastStepAt >= minStepInterval) {
            currentSteps += 1
            lastStepAt = time
            return true
          }
        }
        // A lower valley clustered close behind the last one: replace it (the real nadir of the cluster).
        state == State.VALLEY && intervalSince(valleyIndex, n) <= valleyThreshold() && middle < valleyMag ->
          registerValley(middle, n, recordInterval = false)
      }
    }
    return false
  }

  // Sample interval since a previously recorded extremum index, as a float for threshold comparison.
  private fun intervalSince(lastIndex: Long, n: Long): Float = (n - lastIndex).toFloat()

  // Adaptive peak temporal threshold "Th_p = μ_p − σ_p/β". Permissive (0) until M intervals have
  // been collected, so the first few steps are not over-suppressed.
  private fun peakThreshold(): Float =
    if (peakIntervalStats.isFull) peakIntervalStats.mean - peakIntervalStats.stddev / BETA else 0f

  private fun valleyThreshold(): Float =
    if (valleyIntervalStats.isFull) valleyIntervalStats.mean - valleyIntervalStats.stddev / BETA else 0f

  /**
   * Record a confirmed peak. On a genuine new peak the inter-peak interval feeds the temporal-threshold
   * statistics; cluster replacements update position/magnitude only, so the interval stats keep reflecting
   * true step-cycle spacing rather than intra-cluster gaps.
   */
  private fun registerPeak(mag: Float, n: Long, recordInterval: Boolean) {
    if (recordInterval && havePeak) {
      peakIntervalStats.push(intervalSince(peakIndex, n))
    }
    peakIndex = n
    peakMag = mag
    havePeak = true
    state = State.PEAK
  }

  private fun registerValley(mag: Float, n: Long, recordInterval: Boolean) {
    if (recordInterval && haveValley) {
      valleyIntervalStats.push(intervalSince(valleyIndex, n))
    }
    valleyIndex = n
    valleyMag = mag
    haveValley = true
    state = State.VALLEY
  }

  // Recomputes the step average μ_a from the most recent peak/valley pair (Eq. 1).
  private fun updateStepAverage() {
    if (havePeak && haveValley) {
      stepAverage = (peakMag + valleyMag) / 2f
      stepAverageInitialized = true
    }
  }

  // Fixed-capacity ring of Float samples maintaining a running mean and population standard deviation
  // in O(1) per push. Backs both the magnitude window (σ_a) and the inter-extremum interval windows.
  private class RingStats(
    private val capacity: Int,
  ) {
    private val buffer = FloatArray(capacity)
    private var size = 0
    private var head = 0
    private var sum = 0f
    private var sumSquares = 0f

    fun push(value: Float) {
      if (size == capacity) {
        val evicted = buffer[head]
        sum -= evicted
        sumSquares -= evicted * evicted
      } else {
        size++
      }
      buffer[head] = value
      sum += value
      sumSquares += value * value
      head = (head + 1) % capacity
    }

    val isFull: Boolean get() = size == capacity

    val mean: Float get() = if (size == 0) 0f else sum / size

    val stddev: Float
      get() {
        if (size == 0) return 0f
        val m = sum / size
        val variance = sumSquares / size - m * m
        return if (variance > 0f) sqrt(variance) else 0f
      }

    fun clear() {
      size = 0
      head = 0
      sum = 0f
      sumSquares = 0f
    }
  }

  companion object {
    // Magnitude-band constant: a candidate must clear "μ_a ± σ_a/α". Paper-tuned saturation value.
    private const val ALPHA = 4f

    // Temporal-threshold constant: "Th = μ_int − σ_int/β". Paper uses "β = 1/3" (so "σ/β = 3σ").
    private const val BETA = 1f / 3f

    // σ_a window length in samples (~one walking step cycle at 50 Hz).
    private const val K = 25

    // Number of recent peak / valley intervals backing the adaptive temporal thresholds.
    private const val M = 10

    /**
     * Minimum step deviation σ_a (m/s²) required to attempt detection. A stationary device sits well
     * below this (noise ~0.02-0.1); walking is comfortably above (~1–3). Guards against cold-start
     * stationary false positives. See classifyMiddleSample().
     */
    private const val MIN_STEP_DEVIATION = 0.5f

    // Euclidean norm |a| = sqrt(x²+y²+z²) of an acceleration vector.
    private fun norm(vector: FloatArray): Float {
      var sumSquares = 0f
      for (v in vector) {
        sumSquares += v * v
      }
      return sqrt(sumSquares)
    }
  }
}
