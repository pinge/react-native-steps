package org.pinge.steps.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.WritableMap
import org.pinge.steps.capabilities.AndroidCapabilities
import org.pinge.steps.counters.Cadence
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.StepCounterFactory
import org.pinge.steps.counters.StepEvent
import org.pinge.steps.counters.StepsEventSink

/**
 * Foreground service that owns the step counting sensor independently of the React Native
 * process, so counting continues while the app is backgrounded or swiped away from recents.
 *
 * Lifecycle:
 * - The StepCounterModule starts this service via startSession() and binds to it. While bound,
 *   live updates are forwarded to JavaScript through the module's StepsEventSink; on connect,
 *   the module receives an immediate replayCurrent of the accumulated session total.
 * - When the app closes, the module unbinds but leaves the service started, so it keeps counting
 *   and persisting progress via StepsSessionStore. On a sticky restart (process killed under
 *   memory pressure) the persisted session is resumed.
 * - stopSession() ends the session, clears persistence, and removes the notification.
 *
 * We gracefully degrade down to API level/minSdk 24. Notification channels, the
 * startForegroundService() entry point, the POST_NOTIFICATIONS / 'health' type runtime
 * gate, and foreground service types are all guarded through AndroidCapabilities.
 */
class StepsForegroundService : Service(), StepsEventSink {
  private val binder = LocalBinder()
  private lateinit var store: StepsSessionStore
  private var sensorManager: SensorManager? = null
  private var listener: SensorStepCounter? = null

  // The bound module's event sink (null while no React context is connected).
  private var liveCallback: StepsEventSink? = null

  // Resolved notification strings (defaults filled in by the JS layer). Kept in memory for the
  // current process and persisted via the store so a no-JS sticky restart can re-render them.
  private var notificationTitle: String = DEFAULT_TITLE
  private var notificationText: String = DEFAULT_TEXT
  private var notificationChannel: String = DEFAULT_CHANNEL

  // Resolved max cadence (steps per second), or Cadence.DISABLED for no cap. Resolved from the start
  // intent on a fresh start, or from the store on a sticky restart, like the notification strings.
  private var cadence: Double = Cadence.DISABLED

  // Wall clock time of the last posted notification update, used to throttle per-step re-posts to
  // at most one per NOTIFICATION_THROTTLE_MS (see updateNotification). The initial startForeground
  // post does not go through the throttle, so the notification is always shown promptly on start.
  private var lastNotificationUpdateMs: Long = 0L

  // Binder handed to a bound StepCounterModule so it can attach a live
  // forwarding sink and pull the current accumulated total on (re)connect.
  inner class LocalBinder : Binder() {
    fun setLiveCallback(callback: StepsEventSink) {
      liveCallback = callback
      replayCurrent()
    }

    fun clearLiveCallback() {
      liveCallback = null
    }

    fun replayCurrent() = this@StepsForegroundService.replayCurrent()
  }

  override fun onCreate() {
    super.onCreate()
    store = StepsSessionStore(this)
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Resolve the notification strings and max cadence before going foreground.
    // From the intent on a fresh start, or from the store on a sticky restart.
    resolveNotificationConfig(intent)
    resolveCadenceConfig(intent)

    // On API level 26+ a foreground service must call startForeground promptly after being started.
    startForeground()

    if (intent?.action == ACTION_STOP) {
      // stop() acts as a session pause, not a terminate. It stops the sensor and leaves the foreground,
      // but does not clear the persisted session. A later start() with the same 'since' resumes the
      // running total from it (see handleExplicitStart()). A start() with a different 'since' (e.g. the
      // first one after midnight) overwrites it with a fresh session. So the reset is driven by 'since'
      // changing, and there is no separate terminate path.
      stopCounting()
      // We use stopSelf(startId) here and not stopSelf(). During a quick stop()->start() restart a newer
      // ACTION_START has already advanced the start id, so this becomes a no-op and the service instance
      // is not destroyed. That keeps the bound instance (and the live callback attached to it) alive
      // across the restart, so onStep keeps firing. The unconditional stopSelf() would instead tore the
      // counting instance down, and JavaScript only recovers dozens of seconds later once START_STICKY
      // restarted and re-bounded the instance. When this really is a terminate (no start racing condition),
      // startId is the latest, so the service stops.
      stopSelf(startId)
      return START_NOT_STICKY
    }

    // A null intent is a sticky restart after the process was killed with no session to resume:
    // nothing to do, so stop instead of lingering as an empty foreground service.
    if (intent == null && !store.isActive) {
      stopCounting()
      stopSelf()
      return START_NOT_STICKY
    }

    if (intent?.action == ACTION_START) {
      handleExplicitStart(intent.getLongExtra(EXTRA_SESSION_START, System.currentTimeMillis()))
    } else {
      // A null intent with a persisted active session means a sticky restart after a process kill.
      // Rebuilds the counter from the persisted session as is (config already loaded from the
      // store by resolve*()).
      restoreCounter(store.sessionStart, store.rawCheckpoint, store.accumulatedSteps)
    }
    return START_STICKY
  }

  // Handles an explicit start() for the requested session start. Resumes the running
  // total when it matches the active session, otherwise begins a fresh session at 0.
  private fun handleExplicitStart(requestedStart: Long) {
    if (store.isActive && store.sessionStart == requestedStart) {
      resumeSession()
    } else {
      startFreshSession(requestedStart)
    }
  }

  // Resumes the active session. Keeps the running total and the original session 'start', but
  // adopts the new notification/cadence. Cadence is intrinsic to a counter (the accelerometer
  // derives an absolute step-interval floor from it, the pedometer caps per reading), so a
  // cadence change requires rebuilding the counter. We do this via restoreCounter() so the
  // running total carries across untouched.
  private fun resumeSession() {
    // store.cadence still holds the cap the running counter was built with (updated only here
    // or on a fresh start()), so we compare them before persisting the new value.
    val cadenceChanged = store.cadence != cadence
    persistConfig()
    val current = listener
    when {
      current == null -> restoreCounter(store.sessionStart, store.rawCheckpoint, store.accumulatedSteps)
      cadenceChanged -> restoreCounter(store.sessionStart, current.rawCheckpoint, current.currentSteps)
      // If we're already counting with the same cadence, just replay the current total.
      else -> replayCurrent()
    }
  }

  // Begins a brand new session at 'start', resetting the running total to 0 and adopting the new config.
  private fun startFreshSession(start: Long) {
    stopListener()
    val service = createCounter() ?: return
    store.startSession(start, service.sensorTypeString, notificationTitle, notificationText, notificationChannel, cadence)
    service.startService(start)
  }

  // Builds or rebuilds the step counter and seeds it to a running total without resetting it.
  // This is used to resume sessions and for sticky restarts. Stops any existing counter first
  // so the new one (built with the current cadence) owns the sensor. The total/baseline carry
  // across via restoreService().
  private fun restoreCounter(start: Long, checkpoint: Double, accumulated: Double) {
    stopListener()
    val service = createCounter() ?: return
    service.restoreService(start, checkpoint, accumulated)
  }

  private fun createCounter(): SensorStepCounter? {
    val manager = sensorManager ?: return null
    return StepCounterFactory.create(this, manager, this, cadence).also { listener = it }
  }

  private fun stopListener() {
    listener?.stopService()
    listener = null
  }

  // Persists the (possibly updated) notification + cadence so a later sticky restart uses the
  // latest values rather than the ones the session originally started with.
  private fun persistConfig() {
    store.saveConfig(notificationTitle, notificationText, notificationChannel, cadence)
  }

  // Populates the in-memory notification strings from the intent extras on a fresh start, otherwise
  // from the persisted store (sticky restart), falling back to the built-in safety-net defaults.
  private fun resolveNotificationConfig(intent: Intent?) {
    if (intent?.hasExtra(EXTRA_NOTIFICATION_TITLE) == true) {
      notificationTitle = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE) ?: DEFAULT_TITLE
      notificationText = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: DEFAULT_TEXT
      notificationChannel = intent.getStringExtra(EXTRA_NOTIFICATION_CHANNEL) ?: DEFAULT_CHANNEL
    } else {
      notificationTitle = store.notificationTitle ?: DEFAULT_TITLE
      notificationText = store.notificationText ?: DEFAULT_TEXT
      notificationChannel = store.notificationChannel ?: DEFAULT_CHANNEL
    }
  }

  // Populates the in-memory cadence from the intent extra on a fresh start, otherwise from the
  // persisted store (sticky restart). Sanitized again here for integrity.
  private fun resolveCadenceConfig(intent: Intent?) {
    val raw =
      if (intent?.hasExtra(EXTRA_CADENCE) == true) {
        intent.getDoubleExtra(EXTRA_CADENCE, Cadence.DISABLED)
      } else {
        store.cadence
      }
    cadence = Cadence.sanitize(raw)
  }

  override fun emitStep(data: WritableMap) {
    val total = listener?.currentSteps ?: store.accumulatedSteps
    store.saveProgress(total)
    val checkpoint = listener?.rawCheckpoint ?: -1.0
    if (checkpoint >= 0.0) {
      store.saveRawCheckpoint(checkpoint)
    }
    updateNotification(total)
    // Forward the event to JavaScript only when a React context is connected,
    // otherwise we just persist progress.
    liveCallback?.emitStep(data)
  }

  override fun emitError(message: String) {
    liveCallback?.emitError(message)
  }

  // Re-emit the current accumulated total to the bound module so JavaScript sees a continuous
  // count immediately on (re)connect, including any steps gathered while the app was closed.
  private fun replayCurrent() {
    val callback = liveCallback ?: return
    val active = listener
    when {
      active != null -> callback.emitStep(active.stepPayload)
      store.isActive ->
        callback.emitStep(
          StepEvent.build(
            store.accumulatedSteps,
            store.sessionStart,
            System.currentTimeMillis(),
            store.sensorType,
          ),
        )
      else -> Unit
    }
  }

  private fun startForeground() {
    createChannelIfSupported()
    val total = listener?.currentSteps ?: store.accumulatedSteps
    try {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        buildNotification(total),
        AndroidCapabilities.foregroundServiceType(),
      )
    } catch (e: IllegalStateException) {
      // ForegroundServiceStartNotAllowedException (API 31+, an IllegalStateException): the OS refused
      // a foreground-service start from the background (background-start restrictions). Caught via the
      // stable supertype so the API-31-only class is never referenced on older devices.
      onStartForegroundFailed(e)
    } catch (e: SecurityException) {
      // API 34+: the `health` FGS type's runtime permission is missing. This library satisfies that
      // gate with ACTIVITY_RECOGNITION (the step-counter permission); it never reads body sensors, so
      // BODY_SENSORS / BODY_SENSORS_BACKGROUND are deliberately not declared. The module gates on
      // ACTIVITY_RECOGNITION before starting us, so this catch is defense-in-depth.
      onStartForegroundFailed(e)
    }
  }

  // Shared error handler for the possible set of foreground start failures.
  // We log and stop the foreground service, avoiding crashing the host.
  private fun onStartForegroundFailed(e: RuntimeException) {
    Log.e(TAG, "startForeground failed; stopping service", e)
    stopSelf()
  }

  private fun createChannelIfSupported() {
    if (AndroidCapabilities.supportsNotificationChannels()) {
      createChannelApi26()
    }
  }

  @RequiresApi(android.os.Build.VERSION_CODES.O)
  private fun createChannelApi26() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    // createNotificationChannel() is idempotent and also updates the user visible name of an
    // existing channel, so re-running it with a newly localized name keeps the channel in sync.
    val channel =
      NotificationChannel(CHANNEL_ID, notificationChannel, NotificationManager.IMPORTANCE_LOW).apply {
        setShowBadge(false)
      }
    manager.createNotificationChannel(channel)
  }

  // Render the body, substituting the {{steps}} placeholder with the live count (static if absent).
  private fun renderBody(steps: Double): String = notificationText.replace("{{steps}}", steps.toLong().toString())

  private fun buildNotification(steps: Double): Notification {
    val builder =
      NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setContentTitle(notificationTitle)
        .setContentText(renderBody(steps))
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
      builder.setContentIntent(
        PendingIntent.getActivity(this, 0, launch, PENDING_INTENT_FLAGS),
      )
    }
    return builder.build()
  }

  // Re-post the ongoing notification with the latest count, throttled to at most one update per
  // NOTIFICATION_THROTTLE_MS. Step persistence (saveProgress) is unthrottled, so a coalesced update
  // only delays the *displayed* count by under a second; the next step re-posts the current total.
  // This keeps us well under Android's notification rate limit and avoids a notify() IPC per step.
  private fun updateNotification(steps: Double) {
    val now = System.currentTimeMillis()
    if (now - lastNotificationUpdateMs < NOTIFICATION_THROTTLE_MS) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    lastNotificationUpdateMs = now
    manager.notify(NOTIFICATION_ID, buildNotification(steps))
  }

  // Stops the sensor and leaves the foreground, but never clears the persisted session, since
  // stop() is pauses a session (the running total survives for a same 'since' resume) and a
  // fresh session is started by startFreshSession() overwriting the store.
  private fun stopCounting() {
    listener?.stopService()
    listener = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    /**
     * stopForeground() does not reliably remove a notification that was last (re)posted
     * via NotificationManager.notify(), which updateNotification() does on every step.
     * Since the ongoing notification can linger after stopForeground(), we cancel it
     * explicitly by id to guarantee its removal.
     */
    getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    // Reset the throttle so the first step of any subsequent session re-posts immediately
    // rather than being coalesced against this session's last update timestamp.
    lastNotificationUpdateMs = 0L
  }

  // When the app task is swiped away from recents, a started foreground service
  // is detached from the task, so we deliberately do not stop here to keep counting.
  override fun onTaskRemoved(rootIntent: Intent?) {
    // Intentionally do not stop, keep background step counting alive after task removal.
  }

  override fun onDestroy() {
    listener?.stopService()
    listener = null
    super.onDestroy()
  }

  companion object {
    private val TAG: String = StepsForegroundService::class.java.name
    private const val CHANNEL_ID = "step_counter_background"
    private const val NOTIFICATION_ID = 0x57E95 // arbitrary, non-zero

    // Minimum interval between per-step notification re-posts (see updateNotification). One second
    // keeps the displayed count near-live while staying well under Android's notification rate limit.
    private const val NOTIFICATION_THROTTLE_MS = 1_000L
    private const val ACTION_START = "org.pinge.steps.action.START"
    private const val ACTION_STOP = "org.pinge.steps.action.STOP"
    private const val EXTRA_SESSION_START = "session_start"
    private const val EXTRA_NOTIFICATION_TITLE = "notification_title"
    private const val EXTRA_NOTIFICATION_TEXT = "notification_text"
    private const val EXTRA_NOTIFICATION_CHANNEL = "notification_channel"
    private const val EXTRA_CADENCE = "cadence"

    private const val DEFAULT_TITLE = StepsNotificationOptions.DEFAULT_TITLE
    private const val DEFAULT_TEXT = StepsNotificationOptions.DEFAULT_TEXT
    private const val DEFAULT_CHANNEL = StepsNotificationOptions.DEFAULT_CHANNEL

    // FLAG_IMMUTABLE exists since API 23. We support API level 24, so it is always safe to use.
    private val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // Starts (or resumes) a background step counting session.
    fun startSession(
      context: Context,
      start: Long,
      notificationTitle: String,
      notificationText: String,
      notificationChannel: String,
      cadence: Double,
    ) {
      val intent =
        Intent(context, StepsForegroundService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_SESSION_START, start)
          putExtra(EXTRA_NOTIFICATION_TITLE, notificationTitle)
          putExtra(EXTRA_NOTIFICATION_TEXT, notificationText)
          putExtra(EXTRA_NOTIFICATION_CHANNEL, notificationChannel)
          putExtra(EXTRA_CADENCE, cadence)
        }
      ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    // Stops the step background step counting session and clears persisted state with ACTION_STOP.
    fun stopSession(context: Context) {
      val intent =
        Intent(context, StepsForegroundService::class.java).apply {
          action = ACTION_STOP
        }
      ContextCompat.startForegroundService(context.applicationContext, intent)
    }
  }
}
