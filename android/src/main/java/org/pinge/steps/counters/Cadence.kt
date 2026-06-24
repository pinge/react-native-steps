package org.pinge.steps.counters

// Represents walking cadence in steps per second. JavaScript provides a value in [MIN, MAX] steps
// per second to enable a walking cadence cap, or DISABLED (0) to emit raw sensor step counting events.
object Cadence {
  // Represents a no cap walking cadence (0 steps per second), so all events coming from the sensor
  // step counters are passed through without any filtering applied.
  const val DISABLED = 0.0

  // Inclusive bounds in steps/second. The ceiling represents brisk walking, running is out of scope.
  const val MIN = 1.0
  const val MAX = 2.5

  private const val NANOS_PER_SECOND = 1_000_000_000.0

  // Sanitizes a JavaScript value by clamping it to either a valid value between MIN and MAX or DISABLED.
  fun sanitize(value: Double): Double = if (isEnabled(value)) value else DISABLED

  // Whether an already-sanitized or raw value enables the walking cadence cap.
  fun isEnabled(value: Double): Boolean = value in MIN..MAX

  // Derived minimum step interval in nanoseconds from cadence (steps/second).
  fun minStepInterval(cadence: Double): Long = (NANOS_PER_SECOND / cadence).toLong()
}
