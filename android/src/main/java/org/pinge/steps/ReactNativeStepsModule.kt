package org.pinge.steps

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import org.pinge.steps.capabilities.HardwareCapabilities
import org.pinge.steps.counters.Cadence
import org.pinge.steps.services.StepsGoalOptions
import org.pinge.steps.services.StepsNotificationOptions
import org.pinge.steps.services.StepsSessionCoordinator

class ReactNativeStepsModule(reactContext: ReactApplicationContext) : NativeReactNativeStepsSpec(reactContext) {
  companion object {
    const val NAME: String = NativeReactNativeStepsSpec.NAME
  }

  private val coordinator = StepsSessionCoordinator(reactContext, ReactNativeStepsEventEmitter(reactContext))

  override fun getName(): String = NAME

  override fun invalidate() {
    // When the React context is torn down (e.g. the app is closing) we release the coordinator
    // to unbind from the foreground service that keeps running to count steps in the background.
    coordinator.dispose()
    super.invalidate()
  }

  @ReactMethod
  override fun canCountSteps(promise: Promise) {
    promise.resolve(HardwareCapabilities.canCountSteps(reactApplicationContext))
  }

  @ReactMethod
  override fun start(since: Double, notification: ReadableMap, cadence: Double, goal: ReadableMap?) {
    coordinator.start(
      since.toLong(),
      StepsNotificationOptions.from(notification),
      Cadence.sanitize(cadence),
      StepsGoalOptions.from(goal),
    )
  }

  @ReactMethod
  override fun stop(clear: Boolean) {
    coordinator.stop(clear)
  }

  @ReactMethod
  override fun getSensors(promise: Promise) {
    promise.resolve(HardwareCapabilities.availableSensors(reactApplicationContext))
  }

  @ReactMethod
  override fun addListener(eventName: String) {
  }

  @ReactMethod
  override fun removeListeners(count: Double) {
  }
}
