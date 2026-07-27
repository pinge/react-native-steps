package org.pinge.steps.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import java.util.concurrent.atomic.AtomicLong
import org.pinge.steps.capabilities.AndroidCapabilities
import org.pinge.steps.capabilities.Permissions
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.StepCounterFactory
import org.pinge.steps.counters.StepsEventSink

/**
 * Coordinates the step counting session lifecycle from the main (React Native) process.
 *
 * Step counting runs in the StepsForegroundService, which lives in a separate :steps process so the
 * whole app process is not pinned alive by the foreground service (e.g. the UI / React Native process
 * can be killed when swiping the app away in recents and cold started cleanly on reopen. Because the
 * service is in another process, this coordinator talks to it over a Messenger. Issued commands to
 * start/stop counting are issued as intents (only an intent confers the foreground service start grant),
 * and all other feedback (step/error events, start/stop confirmations, and the listening state) comes
 * back over the Messenger reply channel.
 *
 * There is deliberately no ContentProvider and no cross process SharedPreferences read: those are
 * the mechanisms/libraries that would pin the main process alive. A Messenger bind pins the service
 * process (:steps, already kept alive by the foreground service), and never this client.
 *
 * All Messenger/binding state is confined to the main looper (the IncomingHandler and every setup
 * block post here), so it requires no locking. Only the in process accelerometer fallback (used when
 * ACTIVITY_RECOGNITION is not granted and a 'health' foreground service therefore cannot start) runs
 * in this process and is guarded by sessionLock.
 */
class StepsSessionCoordinator(
  private val context: ReactApplicationContext,
  private val sink: StepsEventSink,
) {
  private companion object {
    val TAG_NAME: String = StepsSessionCoordinator::class.java.name

    // How long start()/stop()/isCounting() wait for the service's Messenger reply before resolving
    // best effort. A foreground service normally registers in well under a second.
    const val START_CONFIRM_TIMEOUT_MS = 5_000L
    const val STOP_CONFIRM_TIMEOUT_MS = 3_000L
    const val STATE_QUERY_TIMEOUT_MS = 2_000L
  }

  // The single main looper handler that serializes all foreground service IPC state and timeouts.
  private val main = Handler(Looper.getMainLooper())

  private val sensorManager: SensorManager =
    context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  // Used only to wipe persisted state on a stop(true) taken by the in process fallback path (the
  // background service owns and clears its own store). Shares the same SharedPreferences file.
  private val store: StepsSessionStore = StepsSessionStore(context)

  // Application context used for bind/unbind and starting the service, so teardown time unbind never
  // touches a half invalidated React context and bind/unbind always use the same Context.
  private val bindContext: Context = context.applicationContext

  // Monotonic token correlating each start() to the service's MSG_START_RESULT (was a process-global
  // static; now local since results come back over the Messenger, not a shared static).
  private val tokenSeq = AtomicLong(0)

  // IPC state
  private var serviceMessenger: Messenger? = null
  private var bindRequested = false
  private var pendingStart: PendingStart? = null
  private val pendingState = mutableListOf<(Boolean) -> Unit>()
  private var pendingStateTimeout: Runnable? = null
  private var pendingStop: (() -> Unit)? = null
  private var pendingStopTimeout: Runnable? = null

  private class PendingStart(val token: Long, val callback: (String?) -> Unit) {
    var timeout: Runnable? = null
  }

  // Our reply Messenger: the service sends MSG_STEP/MSG_ERROR/MSG_START_RESULT/MSG_STATE/MSG_STOPPED here.
  private val incomingMessenger = Messenger(IncomingHandler())

  // sessionLock is only used in the in process fallback
  private val sessionLock = Any()
  private var inProcessListener: SensorStepCounter? = null

  private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        StepsForegroundService.MSG_STEP -> sink.emitStep(msg.data)
        StepsForegroundService.MSG_ERROR ->
          sink.emitError(msg.data.getString(StepsForegroundService.KEY_MESSAGE) ?: "")
        StepsForegroundService.MSG_START_RESULT ->
          resolveStart(
            msg.data.getLong(StepsForegroundService.KEY_TOKEN),
            msg.data.getBoolean(StepsForegroundService.KEY_REGISTERED),
          )
        StepsForegroundService.MSG_STATE ->
          resolveState(msg.data.getBoolean(StepsForegroundService.KEY_LISTENING))
        StepsForegroundService.MSG_STOPPED -> resolveStop()
        else -> super.handleMessage(msg)
      }
    }
  }

  private val connection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service ?: return
        serviceMessenger = Messenger(binder)
        // Register our reply Messenger. The service replies with MSG_STATE and replays the running total.
        sendRegister()
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        serviceMessenger = null
      }
    }

  /**
   * Starts a step counting session covering start. Prefers the background foreground service, but
   * falls back to in process counting when a 'health' foreground service can't be started (see
   * canRunBackgroundService).
   *
   * Invokes onResult() once: null when a session is active (the sensor registered), or an error
   * message when it could not start (no usable sensor, the service could not be launched, or the
   * confirmation timed out).
   */
  fun start(
    start: Long,
    notification: StepsNotificationOptions,
    cadence: Double,
    goal: StepsGoalOptions?,
    onResult: (String?) -> Unit,
  ) {
    if (!canRunBackgroundService()) {
      // In process fallback (no ACTIVITY_RECOGNITION). In-memory only, counts only while the app
      // process is alive, and does not evaluate goals. Registration is synchronous on this thread.
      startInProcessSession(start, cadence, onResult)
      return
    }
    val token = tokenSeq.incrementAndGet()
    main.post {
      synchronized(sessionLock) { stopInProcessSession() }
      // Supersede any previous unresolved start, only the latest token is awaited.
      pendingStart?.timeout?.let { main.removeCallbacks(it) }
      val p = PendingStart(token, onResult)
      pendingStart = p

      val error: String? =
        try {
          StepsForegroundService.startSession(
            bindContext,
            start,
            token,
            notification.title,
            notification.text,
            notification.channel,
            notification.icon,
            notification.url,
            cadence,
            goal,
          )
          null
        } catch (e: IllegalStateException) {
          // e.g. ForegroundServiceStartNotAllowedException when started from the background on API 31+.
          Log.w(TAG_NAME, "Could not start the foreground service", e)
          "could not start the step counting foreground service"
        } catch (e: SecurityException) {
          Log.w(TAG_NAME, "Missing permission to start the foreground service", e)
          "missing permission to start the step counting foreground service"
        }

      if (error != null) {
        if (pendingStart === p) pendingStart = null
        onResult(error)
        return@post
      }

      // Bind so we receive the MSG_START_RESULT (and subsequent live events). The service records the
      // start result and flushes it on our registration, so the bind/intent ordering doesn't race.
      ensureBound()
      // Guard the main process so if the app is swept away it force quits the UI and on reopen it has
      // a clean cold start (the :steps counting process is separate and keeps running). Only while
      // background counting.
      startProcessGuard()
      val timeout =
        Runnable {
          if (pendingStart === p) {
            pendingStart = null
            onResult("step counting did not start in time")
          }
        }
      p.timeout = timeout
      main.postDelayed(timeout, START_CONFIRM_TIMEOUT_MS)
    }
  }

  /**
   * Stops step counting. By default this is a pause: the service stops its sensor but keeps the
   * persisted session (a later start() with the same 'since' resumes the running total). When
   * 'clear' is true it also wipes the persisted session. Invokes onDone() once counting has stopped
   * (on the service's MSG_STOPPED confirmation, or best effort on timeout).
   */
  fun stop(clear: Boolean, onDone: () -> Unit) {
    synchronized(sessionLock) { stopInProcessSession() }
    val background = canRunBackgroundService()
    // The background service clears its own store on an ACTION_STOP with clear, only the in process
    // path (no service) needs us to wipe its store here.
    if (clear && !background) store.clear()
    main.post {
      StepsForegroundService.stopSession(bindContext, clear)
      // Counting is stopping, so the main process no longer needs to force quit on swipe.
      stopProcessGuard()
      if (!background) {
        // In process fallback: there is no foreground service session to confirm over the Messenger.
        onDone()
        return@post
      }
      pendingStop = onDone
      val timeout =
        Runnable {
          Log.w(TAG_NAME, "Stop confirmation timed out; resolving best effort")
          resolveStop()
        }
      pendingStopTimeout = timeout
      main.postDelayed(timeout, STOP_CONFIRM_TIMEOUT_MS)
    }
  }

  /**
   * Whether step events are actively being produced right now. This is resolved asynchronously
   * because the background service lives in another process. Reports true when the in process
   * fallback is registered, or when the :steps foreground service replies that its sensor listener
   * is live, including on a fresh JavaScript context after a recents swipe away, where binding
   * connects to the surviving service. Resolves false (and releases a query-only binding) when
   * nothing is counting.
   */
  fun isCounting(onResult: (Boolean) -> Unit) {
    val fallbackActive = synchronized(sessionLock) { inProcessListener?.isRegistered() == true }
    if (fallbackActive) {
      onResult(true)
      return
    }
    main.post {
      pendingState.add(onResult)
      ensureBound()
      val target = serviceMessenger
      if (target != null) {
        // Already connected, ask for a one off state reply without re-registering as event client.
        trySend(target, StepsForegroundService.MSG_QUERY_STATE) { it.replyTo = incomingMessenger }
      }
      // Otherwise onServiceConnected -> sendRegister() -> the service replies MSG_STATE.
      if (pendingStateTimeout == null) {
        val timeout =
          Runnable {
            pendingStateTimeout = null
            drainPendingState(false)
          }
        pendingStateTimeout = timeout
        main.postDelayed(timeout, STATE_QUERY_TIMEOUT_MS)
      }
    }
  }

  // Releases the coordinator when the React context is torn down. Unbind but leave the foreground
  // service running so background step counting continues. A running in process fallback is stopped.
  fun dispose() {
    synchronized(sessionLock) { stopInProcessSession() }
    main.post {
      unregisterAndUnbind()
      pendingStart?.timeout?.let { main.removeCallbacks(it) }
      pendingStart = null
      pendingStopTimeout?.let { main.removeCallbacks(it) }
      pendingStopTimeout = null
      pendingStop = null
      pendingStateTimeout?.let { main.removeCallbacks(it) }
      pendingStateTimeout = null
      pendingState.clear()
    }
  }

  // ----- Messenger helpers (main looper) -----

  private fun resolveStart(token: Long, registered: Boolean) {
    val p = pendingStart ?: return
    if (p.token != token) return
    p.timeout?.let { main.removeCallbacks(it) }
    pendingStart = null
    p.callback(if (registered) null else "no usable step counting sensor")
  }

  private fun resolveStop() {
    val cb = pendingStop ?: return
    pendingStop = null
    pendingStopTimeout?.let { main.removeCallbacks(it) }
    pendingStopTimeout = null
    // A stop is a pause; release the binding so an idle :steps process can be torn down. A later
    // start() re-binds.
    unregisterAndUnbind()
    cb()
  }

  private fun resolveState(listening: Boolean) {
    pendingStateTimeout?.let { main.removeCallbacks(it) }
    pendingStateTimeout = null
    drainPendingState(listening)
    // If nothing is running and no start is in flight, drop a query-only binding so an idle :steps
    // process is not kept alive just because isCounting() was called.
    if (!listening && pendingStart == null) unregisterAndUnbind()
  }

  private fun drainPendingState(listening: Boolean) {
    val waiters = pendingState.toList()
    pendingState.clear()
    waiters.forEach { it(listening) }
  }

  private fun ensureBound() {
    if (bindRequested) return
    val intent = Intent(bindContext, StepsForegroundService::class.java)
    bindRequested =
      try {
        bindContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      } catch (e: SecurityException) {
        Log.w(TAG_NAME, "bindService failed", e)
        false
      }
  }

  private fun sendRegister() {
    val target = serviceMessenger ?: return
    trySend(target, StepsForegroundService.MSG_REGISTER_CLIENT) { it.replyTo = incomingMessenger }
  }

  private fun unregisterAndUnbind() {
    if (!bindRequested) {
      serviceMessenger = null
      return
    }
    serviceMessenger?.let { trySend(it, StepsForegroundService.MSG_UNREGISTER_CLIENT) }
    try {
      bindContext.unbindService(connection)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG_NAME, "unbindService called without an active binding", e)
    }
    bindRequested = false
    serviceMessenger = null
  }

  private fun trySend(target: Messenger, what: Int, configure: (Message) -> Unit = {}) {
    try {
      target.send(Message.obtain(null, what).also(configure))
    } catch (e: RemoteException) {
      Log.w(TAG_NAME, "service Messenger is dead; dropping the binding", e)
      serviceMessenger = null
    }
  }

  // Starts the main process guard so when the app is swept away in recents, it force quits the UI
  // process (see StepsProcessGuardService), guaranteeing a cold start on reopen. The :steps counting
  // process is separate and keeps running. Started only while a background session is active. The app
  // is in the foreground when start() is called (a user action), so the plain startService is allowed.
  private fun startProcessGuard() {
    try {
      bindContext.startService(Intent(bindContext, StepsProcessGuardService::class.java))
    } catch (e: RuntimeException) {
      Log.w(TAG_NAME, "could not start the process guard", e)
    }
  }

  private fun stopProcessGuard() {
    try {
      bindContext.stopService(Intent(bindContext, StepsProcessGuardService::class.java))
    } catch (e: RuntimeException) {
      Log.w(TAG_NAME, "could not stop the process guard", e)
    }
  }

  // Whether a foreground service can be started in the background. Below API 34 there is no
  // foreground service type gate. On API 34+ the 'health' type requires ACTIVITY_RECOGNITION at runtime.
  private fun canRunBackgroundService(): Boolean =
    !AndroidCapabilities.requiresHealthForegroundServiceGate() ||
      Permissions.isActivityRecognitionGranted(context)

  private fun startInProcessSession(start: Long, cadence: Double, onResult: (String?) -> Unit) {
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
