package org.pinge.steps.counters

import android.os.Bundle

/**
 * The channel for step and error events being emitted through the library, decoupling event producers
 * from event consumers.
 *
 * Producers: a SensorStepCounter (AccelerometerStepCounter, PedometerStepCounter) emits a step on each
 * detected update, and the StepsForegroundService re-emits the accumulated total on (re)connect (the
 * replay path). The counting implementations only know how to detect steps, they are agnostic to where
 * the events go.
 *
 * Consumers: in the main process ReactNativeStepsEventEmitter forwards events to JavaScript while the
 * React context is alive. In the :steps process the StepsForegroundService persists progress and
 * relays events across the process boundary. The payload is a plain Bundle (not a WritableMap) so a
 * producer or consumer running in the :steps process never touches React Native native types. The main
 * process emitter converts the Bundle to a WritableMap right before handing it to JavaScript.
 */
interface StepsEventSink {
  // Called for every accepted step count update.
  fun emitStep(data: Bundle)

  // Called when a counting session cannot proceed (e.g. no usable sensor).
  // Emitted as an 'error' event with a '{ message }' payload.
  fun emitError(message: String)
}
