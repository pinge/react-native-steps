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
  private var notifTitle: String = DEFAULT_TITLE
  private var notifText: String = DEFAULT_TEXT
  private var notifChannel: String = DEFAULT_CHANNEL

  // Wall-clock time of the last posted notification update, used to throttle per-step re-posts to
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
    // Resolve the notification strings before going foreground.
    // From the intent on a fresh start, or from the store on a sticky restart.
    resolveNotificationConfig(intent)

    // On API level 26+ a foreground service must call startForeground promptly after being started.
    startForeground()

    if (intent?.action == ACTION_STOP) {
      stopCounting(clear = true)
      stopSelf()
      return START_NOT_STICKY
    }

    // A null intent is a sticky restart after the process was killed: resume any persisted session.
    if (intent == null && !store.isActive) {
      stopCounting(clear = false)
      stopSelf()
      return START_NOT_STICKY
    }

    // For an explicit start with no active session, begin a fresh one at the requested timestamp.
    // Otherwise (active session, or sticky restart) resume the persisted session as is.
    val freshStart =
      if (intent?.action == ACTION_START && !store.isActive) {
        intent.getLongExtra(EXTRA_SESSION_START, System.currentTimeMillis())
      } else {
        null
      }

    ensureCounting(freshStart)
    return START_STICKY
  }

  // Lazily create the counting strategy and register the sensor. This is idempotent, meaning
  // that if counting is already running in this process it only replays the current total.
  private fun ensureCounting(freshStart: Long?) {
    if (listener != null) {
      replayCurrent()
      return
    }
    val manager = sensorManager ?: return

    val service = StepCounterFactory.create(this, manager, this)
    listener = service

    if (store.isActive) {
      service.restoreService(store.sessionStart, store.rawCheckpoint, store.accumulatedSteps)
    } else {
      val start = freshStart ?: System.currentTimeMillis()
      store.startSession(start, service.sensorTypeString, notifTitle, notifText, notifChannel)
      service.startService(start)
    }
  }

  // Populate the in-memory notification strings: from the intent extras on a fresh start, otherwise
  // from the persisted store (sticky restart), falling back to the built-in safety-net defaults.
  private fun resolveNotificationConfig(intent: Intent?) {
    if (intent?.hasExtra(EXTRA_NOTIF_TITLE) == true) {
      notifTitle = intent.getStringExtra(EXTRA_NOTIF_TITLE) ?: DEFAULT_TITLE
      notifText = intent.getStringExtra(EXTRA_NOTIF_TEXT) ?: DEFAULT_TEXT
      notifChannel = intent.getStringExtra(EXTRA_NOTIF_CHANNEL) ?: DEFAULT_CHANNEL
    } else {
      notifTitle = store.notificationTitle ?: DEFAULT_TITLE
      notifText = store.notificationText ?: DEFAULT_TEXT
      notifChannel = store.notificationChannel ?: DEFAULT_CHANNEL
    }
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
      active != null -> callback.emitStep(active.stepsParamsMap)
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
      NotificationChannel(CHANNEL_ID, notifChannel, NotificationManager.IMPORTANCE_LOW).apply {
        setShowBadge(false)
      }
    manager.createNotificationChannel(channel)
  }

  // Render the body, substituting the {{steps}} placeholder with the live count (static if absent).
  private fun renderBody(steps: Double): String = notifText.replace("{{steps}}", steps.toLong().toString())

  private fun buildNotification(steps: Double): Notification {
    val builder =
      NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setContentTitle(notifTitle)
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

  private fun stopCounting(clear: Boolean) {
    listener?.stopService()
    listener = null
    if (clear) {
      store.clearSession()
    }
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
    private const val EXTRA_SESSION_START = "session_start_millis"
    private const val EXTRA_NOTIF_TITLE = "notification_title"
    private const val EXTRA_NOTIF_TEXT = "notification_text"
    private const val EXTRA_NOTIF_CHANNEL = "notification_channel"

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
    ) {
      val intent =
        Intent(context, StepsForegroundService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_SESSION_START, start)
          putExtra(EXTRA_NOTIF_TITLE, notificationTitle)
          putExtra(EXTRA_NOTIF_TEXT, notificationText)
          putExtra(EXTRA_NOTIF_CHANNEL, notificationChannel)
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
