package org.pinge.steps.counters

import com.facebook.react.bridge.WritableMap

/**
 * The channel for step and error events being emitted through the library, decoupling event producers
 * from event consumers.
 *
 * Producers: a SensorStepCounter (AccelerometerStepCounter, PedometerStepCounter) emits a step on each
 * detected update, and the StepsForegroundService re-emits the accumulated total on (re)connect (the
 * replay path). The counting implementations only know how to detect steps, they are agnostic to where
 * the events go.
 *
 * Consumers: ReactNativeStepsEventEmitter forwards events to JavaScript while the React context is
 * alive, and the StepsForegroundService persists progress when the app process exits. It is both a
 * producer (replay) and a consumer (persistence). Routing every event through this interface lets
 * the same counting logic serve either path.
 */
interface StepsEventSink {
  // Called for every accepted step count update.
  fun emitStep(data: WritableMap)

  // Called when a counting session cannot proceed (e.g. no usable sensor).
  // Emitted as an 'error' event with a '{ message }' payload.
  fun emitError(message: String)
}
