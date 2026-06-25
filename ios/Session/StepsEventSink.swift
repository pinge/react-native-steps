import Foundation

// The channel for step and error events emitted through the library, decoupling the producer from
// the consumer. On iOS the producer is StepsCore (its reconcile and live paths) and the consumer is
// the ObjectiveC++ module shim, which forwards events to JavaScript keyed as `ReactNativeSteps.<type>`.
//
// `AnyObject` bound so a consumer can hold it weakly (see StepsCore's `sink`).
@objc(RNStepsEventSink)
public protocol StepsEventSink: AnyObject {
  // Called for every accepted step count update.
  func emitStep(_ body: [String: Any])

  // Called when a counting session cannot proceed (e.g. a CMPedometer error).
  // Emitted as an 'error' event with a '{ message }' payload.
  func emitError(_ message: String)
}
