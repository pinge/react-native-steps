package org.pinge.steps.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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

    // Polling timeouts used to confirm the async foreground service start/stop. The service start
    // is an intent posted to the main looper, so we poll the process global result it records
    // instead of blocking. A foreground service normally registers in under a second.
    const val POLL_INTERVAL_MS = 50L
    const val START_CONFIRM_TIMEOUT_MS = 5_000L
    const val STOP_CONFIRM_TIMEOUT_MS = 3_000L
  }

  // Handles the short start/stop confirmation polling. The main looper avoids spinning up a new
  // thread and makes resolving the React promise from a consistent place trivial.
  private val pollHandler = Handler(Looper.getMainLooper())

  private val sensorManager: SensorManager =
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  // Used only to wipe persisted state on a stop(true). Shares the same SharedPreferences as the
  // foreground service's own store.
  private val store: StepsSessionStore = StepsSessionStore(context)

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
   *
   * Invokes onResult() once: to null when a step counting session is active (the sensor listener
   * is registered), or an error message when it could not start (no usable sensor, the foreground
   * service could not be launched, or the confirmation timed out).
   */
  fun start(
    start: Long,
    notification: StepsNotificationOptions,
    cadence: Double,
    goal: StepsGoalOptions?,
    onResult: (String?) -> Unit,
  ) {
    if (canRunBackgroundService()) {
      startBackgroundSession(start, notification, cadence, goal, onResult)
    } else {
      // The in-process fallback (no ACTIVITY_RECOGNITION) does not evaluate goals, it is in-memory
      // only and counts steps only while the app process is alive. Goal notifications are
      // background service only (for now?).
      // TODO explore goal notifications with in-process fallback
      startInProcessSession(start, cadence, onResult)
    }
  }

  /*
   * Stops step counting, ends in-process fallback (if any), unbinds, and stops the foreground
   * service. If 'clear' is true, the persisted state is wiped in addition to stopping. In background
   * service mode the service clears the persisted state after stopping its sensor. In in-process
   * fallback mode there is no foreground service to process clearing the persisted state, so we
   * wipe it here (the in-process counter does not touch the store, so no race conditions).
   */
  fun stop(clear: Boolean, onDone: () -> Unit) {
    stopInProcessSession()
    unbindFromService()
    if (clear && !canRunBackgroundService()) {
      store.clear()
    }
    StepsForegroundService.stopSession(context, clear)
    // Resolve once the listener is actually torn down. For the in-process path, isListening() is
    // false and this resolves on the first poll. For the the foreground service, it stops once the
    // service processes ACTION_STOP.
    pollStopResult(onDone)
  }

  // Releases the coordinator when the React context is torn down. Unbind but leave the foreground
  // service running so step counting can continue. A running in-process fallback is stopped.
  fun dispose() {
    pollHandler.removeCallbacksAndMessages(null)
    stopInProcessSession()
    unbindFromService()
  }

  /**
   * Whether step events are actively being produced right now, via either the foreground service
   * or the in-process fallback. On Android we perform no permission check since a registered
   * fallback listener using accelerometer (ACTIVITY_RECOGNITION denied) is still emitting.
   */
  fun isCounting(): Boolean =
    StepsForegroundService.isListening() ||
      synchronized(sessionLock) { inProcessListener?.isRegistered() == true }

  // Foreground service

  // Whether a foreground service can be started in the background. Below API level 34 there is no
  // foreground service type. On API 34+ the 'health' type requires ACTIVITY_RECOGNITION at runtime.
  private fun canRunBackgroundService(): Boolean =
    !AndroidCapabilities.requiresHealthForegroundServiceGate() ||
      Permissions.isActivityRecognitionGranted(context)

  private fun startBackgroundSession(
    start: Long,
    notification: StepsNotificationOptions,
    cadence: Double,
    goal: StepsGoalOptions?,
    onResult: (String?) -> Unit,
  ) {
    // This globally monotonic token ties this start to the result the service records when it
    // processes the ACTION_START intent. See recordStartResult() and consumeStartResult() in
    // StepsForegroundService.
    val startToken = StepsForegroundService.nextStartToken()
    synchronized(sessionLock) {
      stopInProcessSession()
      try {
        StepsForegroundService.startSession(
          context,
          start,
          startToken,
          notification.title,
          notification.text,
          notification.channel,
          notification.icon,
          notification.url,
          cadence,
          goal,
        )
      } catch (e: IllegalStateException) {
        // e.g. ForegroundServiceStartNotAllowedException when started from the background on API 31+.
        Log.w(TAG_NAME, "Could not start the foreground service", e)
        onResult("could not start the step counting foreground service")
        return
      } catch (e: SecurityException) {
        Log.w(TAG_NAME, "Missing permission to start the foreground service", e)
        onResult("missing permission to start the step counting foreground service")
        return
      }
      if (!bindRequested) {
        val intent = Intent(bindContext, StepsForegroundService::class.java)
        bindContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bindRequested = true
      } else {
        // Already bound in this process; resume and replay the accumulated total.
        serviceBinder?.replayCurrent()
      }
    }
    pollStartResult(startToken, onResult)
  }

  // Polls the result the service records for start token until it is known or the timeout expires,
  // then invokes onResult() once. Runs on the main looper so it never blocks the React method queue.
  private fun pollStartResult(token: Long, onResult: (String?) -> Unit) {
    val deadline = SystemClock.uptimeMillis() + START_CONFIRM_TIMEOUT_MS
    pollHandler.post(
      object : Runnable {
        override fun run() {
          when (StepsForegroundService.consumeStartResult(token)) {
            true -> onResult(null)
            false -> onResult("no usable step counting sensor")
            null ->
              if (SystemClock.uptimeMillis() >= deadline) {
                onResult("step counting did not start in time")
              } else {
                pollHandler.postDelayed(this, POLL_INTERVAL_MS)
              }
          }
        }
      },
    )
  }

  // Polls until the foreground service listener is torn down or the timeout expires, then invokes
  // onDone() once. This is a best effort approach, since stop() is a pause that always succeeds,
  // so a timeout still resolves.
  private fun pollStopResult(onDone: () -> Unit) {
    val deadline = SystemClock.uptimeMillis() + STOP_CONFIRM_TIMEOUT_MS
    pollHandler.post(
      object : Runnable {
        override fun run() {
          when {
            !StepsForegroundService.isListening() -> onDone()
            SystemClock.uptimeMillis() >= deadline -> {
              Log.w(TAG_NAME, "Stop confirmation timed out; resolving best-effort")
              onDone()
            }
            else -> pollHandler.postDelayed(this, POLL_INTERVAL_MS)
          }
        }
      },
    )
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
  private fun startInProcessSession(start: Long, cadence: Double, onResult: (String?) -> Unit) {
    // Registration (and its result) is synchronous on this thread.
    val registered =
      synchronized(sessionLock) {
        stopInProcessSession()
        val service = StepCounterFactory.create(context, sensorManager, sink, cadence)
        inProcessListener = service
        service.startService(start)
        service.isRegistered()
      }
    onResult(if (registered) null else "no usable step counting sensor")
  }

  private fun stopInProcessSession() {
    synchronized(sessionLock) {
      inProcessListener?.stopService()
      inProcessListener = null
    }
  }
}
