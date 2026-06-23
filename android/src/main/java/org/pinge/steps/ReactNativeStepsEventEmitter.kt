package org.pinge.steps

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
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

  override fun emitStep(payload: WritableMap) {
    emitEvent("step", payload)
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
