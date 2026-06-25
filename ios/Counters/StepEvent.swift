import CoreMotion
import Foundation

@objc(RNStepEvent)
public final class StepEvent: NSObject {
  @objc public static func build(
    steps: Int,
    start: Date,
    end: Date,
    data: CMPedometerData
  ) -> [String: Any] {
    var body: [String: Any] = [
      "sensor": "PEDOMETER",
      "steps": steps,
      "start": Int64(start.timeIntervalSince1970 * 1000.0),
      "end": Int64(end.timeIntervalSince1970 * 1000.0),
    ]

    // The optional fields are mapped off CMPedometerData and omitted entirely when CoreMotion
    // provides no value for them, so we have an undefined type on JavaScript instead of null.
    if let distance = data.distance { body["distance"] = distance }
    if let floorsAscended = data.floorsAscended { body["floorsAscended"] = floorsAscended }
    if let floorsDescended = data.floorsDescended { body["floorsDescended"] = floorsDescended }

    return body
  }
}
