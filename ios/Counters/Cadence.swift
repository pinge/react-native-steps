import Foundation

// Represents walking cadence in steps per second. JavaScript provides a value between 'min' and 'max' steps
// per second to enable a walking cadence cap, or disabled (0) to emit raw sensor step counting events.
@objc(RNStepsCadence)
public final class Cadence: NSObject {
  // Represents a no cap walking cadence (0 steps per second), so all events coming from the sensor
  // step counter are passed through without any filtering applied.
  public static let disabled: Double = 0.0

  // Inclusive bounds in steps per second. The ceiling represents brisk walking, running is out of scope.
  public static let min: Double = 1.0
  public static let max: Double = 2.5

  // Sanitizes a JavaScript value by clamping it to either a valid value between 'minx' and 'max' or 'disabled'.
  @objc public static func sanitize(_ value: Double) -> Double {
    isEnabled(value) ? value : disabled
  }

  // Whether an already-sanitized or raw value enables the walking cadence cap.
  private static func isEnabled(_ value: Double) -> Bool {
    value >= min && value <= max
  }

  // CMPedometer never emits individual steps, it delivers a cumulative step count in batches without
  // any step timestamps. We use a maximum accepted steps per 'elapsed' time interval (in seconds),
  // and we treat a negative 'elapsed' value as a zero/no interval. The maximum number of steps is
  // calculated as "floor(elapsed * cadence)" per reading in emitStep().
  @objc public static func maxSteps(over elapsed: TimeInterval, cadence: Double) -> Int {
    Int(floor(Swift.max(0, elapsed) * cadence))
  }
}
