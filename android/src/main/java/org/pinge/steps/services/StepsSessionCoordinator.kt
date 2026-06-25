package org.pinge.steps.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import org.pinge.steps.capabilities.AndroidCapabilities
import org.pinge.steps.capabilities.Permissions
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.StepCounterFactory
import org.pinge.steps.counters.StepsEventSink

/**
 * Coordinates the step counting session lifecycle with three responsibilities: choosing between
 * the StepsForegroundService and the in-process fallback, binding/unbinding the service, and
 * replaying the accumulated total on (re)connect.
 *
 * Step counting runs inside the background service so the app can be backgrounded or swiped away
 * from recents. On API 34+ a 'health' foreground service requires the ACTIVITY_RECOGNITION.
 * permission. When this permission is not granted, we fall back to in-process counting, which
 * only counts steps when the app process is alive.
 *
 * This class does not handle event emitting, sink receives every event.
 */
class StepsSessionCoordinator(
  private val context: ReactApplicationContext,
  private val sink: StepsEventSink,
) {
  private companion object {
    val TAG_NAME: String = StepsSessionCoordinator::class.java.name
  }

  private val sensorManager: SensorManager =
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  /**
   * Guards all mutable session state (serviceBinder, bindRequested, inProcessListener). The binding
   * is touched from the ServiceConnection callbacks (main thread) and from the start/stop/dispose
   * paths (module queue / thread teardown), so every access is serialized here. The session lock
   * makes sure that emits that arrive while holding the lock (replay => sink) never re-enter it
   * to avoid deadlock.
   */
  private val sessionLock = Any()

  // Application context used for bind/unbind, so teardown time unbind can never touch a
  // half-invalidated React context, and so bind and unbind always use the same Context.
  private val bindContext: Context = context.applicationContext

  // Binder of the bound foreground service, or null while no service is connected.
  // Guarded by sessionLock.
  private var serviceBinder: StepsForegroundService.LocalBinder? = null

  // Whether bindContext has an outstanding binding request.
  // Guarded by sessionLock.
  private var bindRequested = false

  // Fallback listener used when a background service cannot be started.
  // Guarded by sessionLock.
  private var inProcessListener: SensorStepCounter? = null

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? StepsForegroundService.LocalBinder ?: return
        synchronized(sessionLock) {
          serviceBinder = binder
          // Attaching the callback immediately replays/emits the accumulated total to JavaScript.
          // We hold this invocation under the session lock to avoid having connect interleaving
          // with stop() or dispose().
          binder.setLiveCallback(sink)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        synchronized(sessionLock) { serviceBinder = null }
      }
    }

  /**
   * Starts a step counting session covering start. Prefers a foreground service, but falls back to
   * in-process counting when a 'health' foreground service can't be started (see canRunBackgroundService).
   */
  fun start(start: Long, notification: StepsNotificationOptions, cadence: Double) {
    if (canRunBackgroundService()) {
      startBackgroundSession(start, notification, cadence)
    } else {
      startInProcessSession(start, cadence)
    }
  }

  // Stops step counting, ends in-process fallback (if any), unbinds, and stops the foreground service.
  fun stop() {
    stopInProcessSession()
    unbindFromService()
    StepsForegroundService.stopSession(context)
  }

  // Releases the coordinator when the React context is torn down. Unbind but leave the foreground
  // service running so step counting can continue. A running in-process fallback is stopped.
  fun dispose() {
    stopInProcessSession()
    unbindFromService()
  }

  // Foreground service

  // Whether a foreground service can be started in the background. Below API level 34 there is no
  // foreground service type. On API 34+ the 'health' type requires ACTIVITY_RECOGNITION at runtime.
  private fun canRunBackgroundService(): Boolean =
    !AndroidCapabilities.requiresHealthForegroundServiceGate() ||
      Permissions.isActivityRecognitionGranted(context)

  private fun startBackgroundSession(start: Long, notification: StepsNotificationOptions, cadence: Double) {
    synchronized(sessionLock) {
      stopInProcessSession()
      StepsForegroundService.startSession(
        context,
        start,
        notification.title,
        notification.text,
        notification.channel,
        notification.url,
        cadence,
      )
      if (!bindRequested) {
        val intent = Intent(bindContext, StepsForegroundService::class.java)
        bindContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bindRequested = true
      } else {
        // Already bound in this process; resume and replay the accumulated total.
        serviceBinder?.replayCurrent()
      }
    }
  }

  // Caller must hold sessionLock.
  private fun unbindServiceSafely() {
    try {
      bindContext.unbindService(connection)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG_NAME, "unbindService called without an active binding", e)
    }
    bindRequested = false
  }

  /**
   * Detachs the live callback and unbind from the background service, leaving the service running.
   * Shared by stop(), which then also stops the service, and by dispose(), which leaves it running
   * so background step counting can continue on a React context teardown.
   */
  private fun unbindFromService() {
    synchronized(sessionLock) {
      if (bindRequested) {
        serviceBinder?.clearLiveCallback()
        unbindServiceSafely()
      }
      serviceBinder = null
    }
  }

  // in-process fallback
  private fun startInProcessSession(start: Long, cadence: Double) {
    synchronized(sessionLock) {
      stopInProcessSession()
      val service = StepCounterFactory.create(context, sensorManager, sink, cadence)
      inProcessListener = service
      service.startService(start)
    }
  }

  private fun stopInProcessSession() {
    synchronized(sessionLock) {
      inProcessListener?.stopService()
      inProcessListener = null
    }
  }
}
