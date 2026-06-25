import CoreMotion
import Foundation

// iOS API-level helper methods to query the device's hardware sensors and their step counting capabilities.
// At most a single CMPedometer is exposed on iOS but we still return a list for type consistency.
@objc(RNHardwareCapabilities)
public final class HardwareCapabilities: NSObject {
  // Whether the device can count steps.
  @objc public static func canCountSteps() -> Bool {
    CMPedometer.isStepCountingAvailable()
  }

  // The device's available step counting hardware sensors (list with Pedometer sensor).
  @objc public static func availableSensors() -> [[String: Any]] {
    guard CMPedometer.isStepCountingAvailable() else { return [] }
    return [[
      "os": "ios",
      "type": "PEDOMETER",
      "name": "CMPedometer",
      "stepCounting": CMPedometer.isStepCountingAvailable(),
      "pace": CMPedometer.isPaceAvailable(),
      "cadence": CMPedometer.isCadenceAvailable(),
      "distance": CMPedometer.isDistanceAvailable(),
      "floorCounting": CMPedometer.isFloorCountingAvailable(),
    ]]
  }
}
