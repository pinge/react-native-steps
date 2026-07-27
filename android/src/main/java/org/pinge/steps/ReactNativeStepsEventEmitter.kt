package org.pinge.steps

import android.os.Bundle
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import org.pinge.steps.counters.StepEvent
import org.pinge.steps.counters.StepsEventSink

/**
 * This is a StepsEventSink implementation that forwards step counting events to JavaScript via the
 * React Native device event emitter. It shapes the 'error' payload and keys every event as
 * ReactNativeStepsModule.NAME.<type>. ReactNativeStepsModule creates an instance of this emitter
 * and hands it off to the StepsSessionCoordinator instead of acting as the event sink itself.
 */
internal class ReactNativeStepsEventEmitter(private val appContext: ReactApplicationContext) : StepsEventSink {
  private companion object {
    val TAG_NAME: String = ReactNativeStepsEventEmitter::class.java.name
  }

  override fun emitStep(data: Bundle) {
    // The payload arrives as a process neutral Bundle (from the :steps process over the Messenger, or
    // from the in process fallback counter). We convert to a WritableMap here, in the main process,
    // where React Native's Arguments/WritableNativeMap are available, before emitting to JavaScript.
    emitEvent("step", StepEvent.toWritableMap(data))
  }

  override fun emitError(message: String) {
    emitEvent(
      "error",
      Arguments.createMap().apply { putString("message", message) },
    )
  }

  private fun emitEvent(eventType: String, eventPayload: Any) {
    try {
      appContext
        .getJSModule(RCTDeviceEventEmitter::class.java)
        .emit(eventName = "${ReactNativeStepsModule.NAME}.$eventType", data = eventPayload)
    } catch (e: RuntimeException) {
      e.message?.let { Log.e(TAG_NAME, it) }
      Log.e(TAG_NAME, eventType, e)
    }
  }
}
