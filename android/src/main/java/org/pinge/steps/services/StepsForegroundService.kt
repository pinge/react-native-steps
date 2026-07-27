package org.pinge.steps.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.pinge.steps.capabilities.AndroidCapabilities
import org.pinge.steps.counters.Cadence
import org.pinge.steps.counters.Goal
import org.pinge.steps.counters.SensorStepCounter
import org.pinge.steps.counters.StepCounterFactory
import org.pinge.steps.counters.StepEvent
import org.pinge.steps.counters.StepsEventSink

/**
 * Foreground service that owns the step counting sensor. It runs in a separate :steps process
 * (see AndroidManifest `android:process`), so counting continues while the app is running in the
 * background or is swiped away from recents, and crucially only this process is pinned alive by
 * the foreground service, leaving the main React Native process free to be killed and cold start
 * cleanly on reopen. This process does NOT initialize React Native, so nothing here touches React
 * Native types (we use plain Bundle instances in step event payloads).
 *
 * Lifecycle and IPC:
 * - The main process StepsSessionCoordinator starts this service with an intent (startSession())
 *   and binds to it over a Messenger. While bound, step/error events, start/stop confirmations,
 *   and the listening state are sent back to the coordinator's reply Messenger. On registration,
 *   the client gets an immediate MSG_STATE + a replay of the accumulated session total.
 * - When the app closes, the coordinator unbinds but leaves the service started, so it keeps counting
 *   steps and persisting progress via StepsSessionStore. On a sticky restart (e.g. process killed
 *   under memory pressure) the persisted session is resumed.
 * - stopSession() (ACTION_STOP) pauses/ends the session, and confirms via MSG_STOPPED.
 *
 * We gracefully degrade down to API level/minSdk 24. Notification channels, the
 * startForegroundService() entry point, the POST_NOTIFICATIONS / 'health' type runtime
 * gate, and foreground service types are all guarded through AndroidCapabilities.
 */
class StepsForegroundService : Service(), StepsEventSink {
  // This service runs in a separate :steps process (see AndroidManifest). The main (React Native)
  // process binds to it and talks over this Messenger. A plain in process callback cannot cross the
  // process boundary. onBind hands back messenger.binder, the client registers a reply Messenger.
  private val messenger = Messenger(IncomingHandler())

  // The registered main process reply Messenger (null while no client is connected). Step/error
  // events and start/stop confirmations are sent to it.
  private var client: Messenger? = null

  // The most recent ACTION_START confirmation (token + whether the sensor registered). Stashed so a
  // client that registers after the start was processed still receives the result.
  // See IncomingHandler.MSG_REGISTER_CLIENT and sendStartResult).
  private var lastStartToken: Long = Long.MIN_VALUE
  private var lastStartRegistered: Boolean = false

  // Whether the sensor listener is currently registered and emitting. Lives in this :steps process.
  // The main process learns it over the Messenger (MSG_STATE), never by reading a static.
  private var listening: Boolean = false

  private lateinit var store: StepsSessionStore
  private var sensorManager: SensorManager? = null
  private var listener: SensorStepCounter? = null

  // Resolved notification strings (defaults filled in by the JS layer). Kept in memory for the
  // current process and persisted via the store so a no-JS sticky restart can re-render them.
  private var notificationTitle: String = DEFAULT_TITLE
  private var notificationText: String = DEFAULT_TEXT
  private var notificationChannel: String = DEFAULT_CHANNEL

  // Small icon drawable resource name for the foreground notification (resolved to a resource id at
  // build time via iconResId, falling back to a system icon when the host app has no such drawable).
  private var notificationIcon: String = DEFAULT_ICON

  // Optional deep link URL. When set and and the notification is tapped, it will follow the URL.
  // Otherwise it will just open the app as the default behavior.
  private var notificationUrl: String? = null

  // Resolved max cadence (steps per second), or Cadence.DISABLED for no cap. Resolved from the start
  // intent on a fresh start, or from the store on a sticky restart, like the notification strings.
  private var cadence: Double = Cadence.DISABLED

  // Wall clock time of the last posted notification update, used to throttle per-step re-posts to
  // at most one per NOTIFICATION_THROTTLE_MS (see updateNotification). The initial startForeground
  // post does not go through the throttle, so the notification is always shown promptly on start.
  private var lastNotificationUpdateMs: Long = 0L

  // Optional goal achieved notification settings. If 'steps' is set to Goal.DISABLED, we do not
  // count steps towards a goal (and no goal achieved notification is displayed). Resolved from the
  // start intent on a fresh start() (or resume), or from the store on a sticky restart.
  private var goalSteps: Double = Goal.DISABLED
  private var goalPeriod: String = Goal.PERIOD_DAILY
  private var goalTitle: String = DEFAULT_GOAL_TITLE
  private var goalText: String = DEFAULT_GOAL_TEXT
  private var goalChannel: String = DEFAULT_GOAL_CHANNEL
  private var goalIcon: String = DEFAULT_ICON
  private var goalUrl: String? = null

  // Goal runtime state (the current period window key, the accumulated total steps in this period),
  // and whether we already fired a goal achieve notification during this period. We persist the state
  // using the store so a sticky restart neither re-fires nor loses the baseline, see evaluateGoal().
  private var goalPeriodKey: Long = 0L
  private var goalBaseline: Double = 0.0
  private var goalNotified: Boolean = false

  // Receives messages from the bound main process client. Runs on the main looper of the :steps
  // process, the same thread the sensor pipeline delivers on, so no cross thread synchronization of
  // the service fields is needed.
  private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
    override fun handleMessage(msg: Message) {
      when (msg.what) {
        MSG_REGISTER_CLIENT -> {
          client = msg.replyTo
          sendState()
          replayCurrent()
          // Flush a start result the client missed by registering after the start was processed.
          if (lastStartToken != Long.MIN_VALUE) sendStartResult(lastStartToken, lastStartRegistered)
        }
        MSG_UNREGISTER_CLIENT -> if (msg.replyTo == null || msg.replyTo == client) client = null
        // A stateless probe (used by isCounting on a fresh reopen): reply with the listening state to
        // the sender without registering it as the live event client.
        MSG_QUERY_STATE -> msg.replyTo?.let { sendStateTo(it) }
        else -> super.handleMessage(msg)
      }
    }
  }

  // Sends a message + optional data Bundle to the registered client, dropping the client if its
  // process has died (RemoteException).
  private fun sendToClient(what: Int, data: Bundle? = null) {
    val target = client ?: return
    if (!trySend(target, what, data)) client = null
  }

  private fun trySend(target: Messenger, what: Int, data: Bundle?): Boolean =
    try {
      target.send(Message.obtain(null, what).apply { data?.let { this.data = it } })
      true
    } catch (e: RemoteException) {
      Log.w(TAG, "client Messenger is dead; dropping", e)
      false
    }

  private fun sendState() = sendToClient(MSG_STATE, Bundle().apply { putBoolean(KEY_LISTENING, listening) })

  private fun sendStateTo(target: Messenger) =
    trySend(target, MSG_STATE, Bundle().apply { putBoolean(KEY_LISTENING, listening) })

  private fun sendStartResult(token: Long, registered: Boolean) =
    sendToClient(
      MSG_START_RESULT,
      Bundle().apply {
        putLong(KEY_TOKEN, token)
        putBoolean(KEY_REGISTERED, registered)
      },
    )

  override fun onCreate() {
    super.onCreate()
    store = StepsSessionStore(this)
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
  }

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Resolve the notification strings, max cadence and goal config before going foreground.
    // From the intent on a fresh start, or from the store on a sticky restart.
    resolveNotificationConfig(intent)
    resolveCadenceConfig(intent)
    resolveGoalConfig(intent)

    // On API level 26+ a foreground service must call startForeground promptly after being started.
    startForeground()

    if (intent?.action == ACTION_STOP) {
      // By default stop() is a session pause, not a terminate. It stops the sensor and leaves the
      // foreground, but does not clear the persisted session. A later start() with the same 'since'
      // resumes the running total from it (see handleExplicitStart()). A start() with a different
      // 'since' (e.g. the first one after midnight) overwrites it with a fresh session. So the reset
      // is normally driven by 'since' changing.
      // A clearing stop (EXTRA_CLEAR, e.g. on logout) is an explicit terminate. It also wipes the
      // persisted session and removes the goal notification, so a later start() always as a fresh
      // session regardless of 'since'.
      stopCounting(intent.getBooleanExtra(EXTRA_CLEAR, false))
      // Confirm the stop to the bound client (resolves stopCounting()'s promise). Sent before
      // stopSelf so the client hears it even if the service instance is then destroyed.
      sendToClient(MSG_STOPPED)
      // We use stopSelf(startId) here and not stopSelf(). During a quick stop()->start() restart a newer
      // ACTION_START has already advanced the start id, so this becomes a no-op and the service instance
      // is not destroyed. That keeps the bound instance (and the registered client Messenger) alive
      // across the restart, so onStep keeps firing. The unconditional stopSelf() would instead tear the
      // counting instance down, and JavaScript only recovers dozens of seconds later once START_STICKY
      // restarted and re-bound the instance. When this really is a terminate (no start racing condition),
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
      // Report whether the listener actually registered so the coordinator's start() promise can
      // resolve (registered) or reject (not). EXTRA_START_TOKEN, always set by startSession(),
      // correlates this result to the awaiting start. Stash it so a client that registers slightly
      // later still gets it (see IncomingHandler.MSG_REGISTER_CLIENT).
      val token = intent.getLongExtra(EXTRA_START_TOKEN, Long.MIN_VALUE)
      lastStartToken = token
      lastStartRegistered = listening
      sendStartResult(token, listening)
    } else {
      // A null intent with a persisted active session means a sticky restart after a process kill.
      // We rebuild the counter from the persisted session as is (config already loaded from the
      // store by resolve*()), and reloads the goal runtime state so it continues uninterrupted.
      loadGoalRuntime()
      restoreCounter(store.sessionStart, store.rawCheckpoint, store.accumulatedSteps)
    }
    return START_STICKY
  }

  // Handles an explicit start() for the requested session start. Resumes the running
  // total when it matches the active session, otherwise begins a fresh session (at 0,
  // or seeded with the current goal window's steps — see startFreshSession()).
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
    // The goal's running window/baseline/notified state continues across a stop() -> start() resume,
    // so we reload it from the store before persisting the (possibly updated) goal config below.
    loadGoalRuntime()
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

  // Begins a brand new session at 'start', adopting the new config. The running total normally
  // resets to 0, with one exception: when a goal is set and 'start' falls inside the goal period
  // window the previous session was already tracking (e.g. the first start() after a midnight
  // rollover the service counted through), the steps already counted in that window seed the new
  // session's total, and the once per period notified flag is preserved. Without this, a fresh
  // start() with the new day's 'since' would silently discard the steps the foreground service
  // counted since midnight (the hardware counter cannot backfill them) and could re-fire an
  // already delivered goal notification. This mirrors iOS, where a fresh start() backfills the
  // new window via CMPedometer.
  private fun startFreshSession(start: Long) {
    // Snapshot the carry before the listener and the persisted session are torn down.
    val carry = goalWindowCarry(start)
    stopListener()
    val service = createCounter() ?: return
    store.startSession(start, service.sensorTypeString, notificationTitle, notificationText, notificationChannel, notificationIcon, notificationUrl, cadence)
    store.saveGoalConfig(goalSteps, goalPeriod, goalTitle, goalText, goalChannel, goalIcon, goalUrl)
    if (carry != null) {
      // The carried steps become the new session's starting total, so the goal window (total minus
      // a 0 baseline) continues seamlessly and stays consistent with what JavaScript receives.
      goalPeriodKey = Goal.periodKey(start, goalPeriod)
      goalBaseline = 0.0
      goalNotified = carry.notified
      store.saveGoalState(goalPeriodKey, goalBaseline, goalNotified)
      store.saveProgress(carry.steps)
      if (carry.rawCheckpoint >= 0.0) {
        store.saveRawCheckpoint(carry.rawCheckpoint)
      }
      service.restoreService(start, carry.rawCheckpoint, carry.steps)
    } else {
      resetGoalRuntime(start)
      service.startService(start)
    }
    listening = service.isRegistered()
  }

  // The goal window progress a fresh session starting at 'start' should carry over, or null for a
  // plain fresh start at 0. Carrying applies only when a goal is enabled for the new session, a
  // previous session is active, and the goal window it was tracking is the same period window that
  // contains 'start' (so steps from a previous day never leak into the new one). The total and raw
  // checkpoint come from the live listener when one is running (most precise), else from the store.
  private fun goalWindowCarry(start: Long): GoalWindowCarry? {
    if (!Goal.isEnabled(goalSteps) || !store.isActive) return null
    val trackedKey = store.goalPeriodKey
    if (trackedKey == 0L || trackedKey != Goal.periodKey(start, goalPeriod)) return null
    val current = listener
    val total = current?.currentSteps ?: store.accumulatedSteps
    val checkpoint = current?.rawCheckpoint ?: store.rawCheckpoint
    val windowSteps = (total - store.goalPeriodBaseline).coerceAtLeast(0.0)
    if (windowSteps <= 0.0) return null
    return GoalWindowCarry(windowSteps, checkpoint, store.goalNotified)
  }

  // Steps already counted in the current goal period window, the raw cumulative counter checkpoint
  // they were measured at, and whether the goal notification already fired in this window.
  private data class GoalWindowCarry(
    val steps: Double,
    val rawCheckpoint: Double,
    val notified: Boolean,
  )

  // Builds or rebuilds the step counter and seeds it to a running total without resetting it.
  // This is used to resume sessions and for sticky restarts. Stops any existing counter first
  // so the new one (built with the current cadence) owns the sensor. The total/baseline carry
  // across via restoreService().
  private fun restoreCounter(start: Long, checkpoint: Double, accumulated: Double) {
    stopListener()
    val service = createCounter() ?: return
    service.restoreService(start, checkpoint, accumulated)
    listening = service.isRegistered()
  }

  private fun createCounter(): SensorStepCounter? {
    val manager = sensorManager ?: return null
    return StepCounterFactory.create(this, manager, this, cadence).also { listener = it }
  }

  private fun stopListener() {
    listener?.stopService()
    listener = null
    listening = false
  }

  // Persists the (possibly updated) notification + cadence + goal config so a later sticky restart
  // uses the latest values rather than the ones the session originally started with.
  private fun persistConfig() {
    store.saveConfig(notificationTitle, notificationText, notificationChannel, notificationIcon, notificationUrl, cadence)
    store.saveGoalConfig(goalSteps, goalPeriod, goalTitle, goalText, goalChannel, goalIcon, goalUrl)
  }

  // Populates the in-memory notification strings from the intent extras on a fresh start, otherwise
  // from the persisted store (sticky restart), falling back to the built-in safety-net defaults.
  private fun resolveNotificationConfig(intent: Intent?) {
    if (intent?.hasExtra(EXTRA_NOTIFICATION_TITLE) == true) {
      notificationTitle = intent.getStringExtra(EXTRA_NOTIFICATION_TITLE) ?: DEFAULT_TITLE
      notificationText = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: DEFAULT_TEXT
      notificationChannel = intent.getStringExtra(EXTRA_NOTIFICATION_CHANNEL) ?: DEFAULT_CHANNEL
      notificationIcon = intent.getStringExtra(EXTRA_NOTIFICATION_ICON) ?: DEFAULT_ICON
      notificationUrl = intent.getStringExtra(EXTRA_NOTIFICATION_URL)?.takeIf { it.isNotBlank() }
    } else {
      notificationTitle = store.notificationTitle ?: DEFAULT_TITLE
      notificationText = store.notificationText ?: DEFAULT_TEXT
      notificationChannel = store.notificationChannel ?: DEFAULT_CHANNEL
      notificationIcon = store.notificationIcon ?: DEFAULT_ICON
      notificationUrl = store.notificationUrl
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

  // Populates the in-memory goal config. On an explicit ACTION_START the config comes from the intent
  // and absent goal extras mean "no goal" (this is how removing the goal on a resume disables it). On
  // a sticky restart (null intent) or on a stop() it comes from the persisted store. A non-positive
  // 'steps' target (Goal.DISABLED) disables any goal tracking or notifications associated with it.
  private fun resolveGoalConfig(intent: Intent?) {
    if (intent?.action == ACTION_START) {
      goalSteps = Goal.sanitize(intent.getDoubleExtra(EXTRA_GOAL_STEPS, Goal.DISABLED))
      goalPeriod = intent.getStringExtra(EXTRA_GOAL_PERIOD) ?: Goal.PERIOD_DAILY
      goalTitle = intent.getStringExtra(EXTRA_GOAL_NOTIFICATION_TITLE) ?: DEFAULT_GOAL_TITLE
      goalText = intent.getStringExtra(EXTRA_GOAL_NOTIFICATION_TEXT) ?: DEFAULT_GOAL_TEXT
      goalChannel = intent.getStringExtra(EXTRA_GOAL_NOTIFICATION_CHANNEL) ?: DEFAULT_GOAL_CHANNEL
      goalIcon = intent.getStringExtra(EXTRA_GOAL_NOTIFICATION_ICON) ?: DEFAULT_ICON
      goalUrl = intent.getStringExtra(EXTRA_GOAL_NOTIFICATION_URL)?.takeIf { it.isNotBlank() }
    } else {
      goalSteps = Goal.sanitize(store.goalSteps)
      goalPeriod = store.goalPeriod ?: Goal.PERIOD_DAILY
      goalTitle = store.goalTitle ?: DEFAULT_GOAL_TITLE
      goalText = store.goalText ?: DEFAULT_GOAL_TEXT
      goalChannel = store.goalChannel ?: DEFAULT_GOAL_CHANNEL
      goalIcon = store.goalIcon ?: DEFAULT_ICON
      goalUrl = store.goalUrl
    }
  }

  // Resets the goal runtime state for a brand new session: the current per period window is the one
  // containing 'start', the baseline is 0 (the running total starts at 0), and nothing has fired yet.
  // Persisted so a sticky restart picks up the same window.
  private fun resetGoalRuntime(start: Long) {
    goalPeriodKey = Goal.periodKey(start, goalPeriod)
    goalBaseline = 0.0
    goalNotified = false
    store.saveGoalState(goalPeriodKey, goalBaseline, goalNotified)
  }

  // Loads the persisted goal runtime state so a resume or sticky restart continues the same period
  // window (baseline + notified flag) rather than re-evaluating from scratch.
  private fun loadGoalRuntime() {
    goalPeriodKey = store.goalPeriodKey
    goalBaseline = store.goalPeriodBaseline
    goalNotified = store.goalNotified
  }

  // Evaluates the step target against the latest running total and fires the goal notification once
  // per period window. The window rolls over when the local calendar day changes (for a 'daily' goal):
  // we snapshot the current total as the new baseline and clear the fired flag, so the goal resets at
  // (around) local midnight even within a session that spans days. "Steps during this period" is the
  // total steps since that baseline. Because the foreground service only ever emits for right now,
  // the window we evaluate is always the current one (no past period backfill handling, unlike iOS).
  private fun evaluateGoal(total: Double) {
    if (!Goal.isEnabled(goalSteps)) return
    var changed = false
    val key = Goal.periodKey(System.currentTimeMillis(), goalPeriod)
    if (key != goalPeriodKey) {
      // When goalPeriodKey is 0 it means we haven't tracked a window yet, so this is the first one,
      // and not an actual day change: the baseline stays 0 and we count the whole period. On a real
      // day change we instead save the current total as the baseline, so the next day starts counting
      // from there.
      goalBaseline = if (goalPeriodKey == 0L) 0.0 else total
      goalPeriodKey = key
      goalNotified = false
      changed = true
    }
    val windowSteps = total - goalBaseline
    if (!goalNotified && windowSteps >= goalSteps) {
      goalNotified = true
      changed = true
      postGoalNotification(windowSteps)
    }
    if (changed) {
      store.saveGoalState(goalPeriodKey, goalBaseline, goalNotified)
    }
  }

  // The step count shown in the ongoing foreground notification. When a goal is set this is the
  // steps within the current goal window (total - goalBaseline), so the notification resets along
  // with the goal at local midnight. The rollover itself is owned by evaluateGoal(). If no goal is
  // set this is the session cumulative total number of steps. This is used for display only.
  private fun notificationSteps(total: Double): Double =
    if (Goal.isEnabled(goalSteps)) total - goalBaseline else total

  override fun emitStep(data: Bundle) {
    val total = listener?.currentSteps ?: store.accumulatedSteps
    store.saveProgress(total)
    val checkpoint = listener?.rawCheckpoint ?: StepsSessionStore.RAW_UNSET
    if (checkpoint >= 0.0) {
      store.saveRawCheckpoint(checkpoint)
    }
    // Evaluate the goal first so a midnight rollover updates goalBaseline before we render,
    // then display the possibly goal windowed count.
    evaluateGoal(total)
    updateNotification(notificationSteps(total))
    // Forward the event across the process boundary only when a client is connected. Otherwise we
    // just persist progress and let the client replay it on (re)connect.
    sendToClient(MSG_STEP, data)
  }

  override fun emitError(message: String) {
    sendToClient(MSG_ERROR, Bundle().apply { putString(KEY_MESSAGE, message) })
  }

  // Re-emit the current accumulated total to the bound client so JavaScript sees a continuous
  // count immediately on (re)connect, including any steps gathered while the app was closed.
  private fun replayCurrent() {
    if (client == null) return
    val active = listener
    val payload =
      when {
        active != null -> active.stepPayload
        store.isActive ->
          StepEvent.bundle(
            store.accumulatedSteps,
            store.sessionStart,
            System.currentTimeMillis(),
            store.sensorType,
          )
        else -> return
      }
    sendToClient(MSG_STEP, payload)
  }

  private fun startForeground() {
    createChannelIfSupported()
    val total = listener?.currentSteps ?: store.accumulatedSteps
    try {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        buildNotification(notificationSteps(total)),
        AndroidCapabilities.foregroundServiceType(),
      )
    } catch (e: IllegalStateException) {
      // ForegroundServiceStartNotAllowedException (API 31+, an IllegalStateException): the OS refused
      // a foreground service start from the background (background-start restrictions). Caught via the
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

  // Render a body template, substituting the {{steps}} placeholder with the count (static if absent).
  private fun renderBody(template: String, steps: Double): String = template.replace("{{steps}}", steps.toLong().toString())

  // Resolves a small icon drawable resource name (e.g. "ic_menu_compass") to a resource id,
  // checking drawable then mipmap. Falls back to the matching Android system icon when the host
  // app provides no such resource, so a missing or typo'ed icon never crashes startForeground()
  // and notify(), because id 0 is invalid.
  private fun iconResId(name: String): Int {
    val drawableId = resources.getIdentifier(name, "drawable", packageName)
    if (drawableId != 0) return drawableId
    val mipmapId = resources.getIdentifier(name, "mipmap", packageName)
    if (mipmapId != 0) return mipmapId
    Log.w(TAG, "notification icon '$name' not found in drawable/mipmap; using fallback")
    return android.R.drawable.ic_menu_compass
  }

  // Builds the tap PendingIntent for a notification. When a deep link URL is set, we turn the
  // launcher intent into an explicit ACTION_VIEW that includes the URL in its data (the launch
  // intent is already resolved, so it needs no manifest intent filter, and React Native's
  // Linking.getInitialURL() reads it on a cold start). When the URL is null, the launcher intent
  // just opens the app. The foreground and goal achieved notifications use distinct request codes
  // so their PendingIntents (and URLs) never clobber each other under FLAG_UPDATE_CURRENT.
  private fun contentIntent(url: String?, requestCode: Int): PendingIntent? {
    val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
    url?.let {
      launch.action = Intent.ACTION_VIEW
      launch.data = Uri.parse(it)
    }
    return PendingIntent.getActivity(this, requestCode, launch, PENDING_INTENT_FLAGS)
  }

  private fun buildNotification(steps: Double): Notification {
    val builder =
      NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setContentTitle(notificationTitle)
        .setContentText(renderBody(notificationText, steps))
        .setSmallIcon(iconResId(notificationIcon))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    contentIntent(notificationUrl, FOREGROUND_REQUEST_CODE)?.let { builder.setContentIntent(it) }
    return builder.build()
  }

  // Posts the once per period goal achieved notification: a separate, alerting, dismissable
  // notification (distinct id + channel from the ongoing foreground one). The tag {{steps}}
  // renders the steps taken during this period (windowSteps). Uses the same POST_NOTIFICATIONS
  // grant as the foreground service notification.
  private fun postGoalNotification(windowSteps: Double) {
    createGoalChannelIfSupported()
    val manager = getSystemService(NotificationManager::class.java) ?: return
    val builder =
      NotificationCompat
        .Builder(this, GOAL_CHANNEL_ID)
        .setContentTitle(goalTitle)
        .setContentText(renderBody(goalText, windowSteps))
        .setSmallIcon(iconResId(goalIcon))
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    contentIntent(goalUrl, GOAL_REQUEST_CODE)?.let { builder.setContentIntent(it) }
    manager.notify(GOAL_NOTIFICATION_ID, builder.build())
  }

  private fun createGoalChannelIfSupported() {
    if (AndroidCapabilities.supportsNotificationChannels()) {
      createGoalChannelApi26()
    }
  }

  @RequiresApi(android.os.Build.VERSION_CODES.O)
  private fun createGoalChannelApi26() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    // IMPORTANCE_HIGH (vs the foreground channel's LOW) so the goal notification shows everywhere,
    // makes noise and peeks. createNotificationChannel() is idempotent.
    val channel = NotificationChannel(GOAL_CHANNEL_ID, goalChannel, NotificationManager.IMPORTANCE_HIGH)
    manager.createNotificationChannel(channel)
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

  // Stops the sensor and leaves the foreground. By default it does not clear the persisted session,
  // since a plain stop() pauses a session (the running total resumes when 'since' is the same as
  // in the previous start()) and a fresh session is started by startFreshSession() overwriting the
  // store. When 'clear' is true it additionally wipes the persisted session and removes the goal
  // notification, so nothing is left to resume. The sensor is stopped before clearing, so no
  // in-flight event can re-populate the store after it has been wiped.
  private fun stopCounting(clear: Boolean = false) {
    listener?.stopService()
    listener = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    /**
     * stopForeground() does not reliably remove a notification that was last (re)posted
     * via NotificationManager.notify(), which updateNotification() does on every step.
     * Since the ongoing notification can linger after stopForeground(), we cancel it
     * explicitly by id to guarantee its removal.
     */
    val manager = getSystemService(NotificationManager::class.java)
    manager?.cancel(NOTIFICATION_ID)
    // Reset the throttle so the first step of any subsequent session re-posts immediately
    // rather than being coalesced against this session's last update timestamp.
    lastNotificationUpdateMs = 0L
    if (clear) {
      manager?.cancel(GOAL_NOTIFICATION_ID)
      store.clear()
    }
  }

  // When the app task is swiped away from recents, a started foreground service
  // is detached from the task, so we deliberately do not stop here to keep counting.
  override fun onTaskRemoved(rootIntent: Intent?) {
    // Intentionally do not stop, keep background step counting alive after task removal.
  }

  override fun onDestroy() {
    listener?.stopService()
    listener = null
    listening = false
    client = null
    super.onDestroy()
  }

  companion object {
    private val TAG: String = StepsForegroundService::class.java.name

    // Messenger protocol between the main process StepsSessionCoordinator (client) and this service
    // (running in the :steps process). Commands to start/stop still travel as intents (only an intent
    // confers the foreground service start grant), while all feedback (events, start/stop confirmations,
    // listening state) flows back over the Messenger.
    // main -> service:
    const val MSG_REGISTER_CLIENT = 1 // msg.replyTo = the client's reply Messenger
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_QUERY_STATE = 3 // msg.replyTo = where to send the one-off MSG_STATE reply
    // service -> client:
    const val MSG_STEP = 10 // msg.data = the StepEvent Bundle
    const val MSG_ERROR = 11 // msg.data = { KEY_MESSAGE }
    const val MSG_START_RESULT = 12 // msg.data = { KEY_TOKEN, KEY_REGISTERED }
    const val MSG_STATE = 13 // msg.data = { KEY_LISTENING }
    const val MSG_STOPPED = 14 // ACTION_STOP has been processed

    const val KEY_LISTENING = "listening"
    const val KEY_TOKEN = "token"
    const val KEY_REGISTERED = "registered"
    const val KEY_MESSAGE = "message"

    private const val CHANNEL_ID = "step_counter_background"
    private const val NOTIFICATION_ID = 0x57E95 // arbitrary, non-zero

    // Distinct channel + id for the once per period goal achieved notification, so it is
    // independent of the foreground service notification (different importance, dismissable,
    // and never coalesced).
    private const val GOAL_CHANNEL_ID = "step_counter_goal"
    private const val GOAL_NOTIFICATION_ID = 0x57E96 // must be different than NOTIFICATION_ID

    // Distinct PendingIntent request codes so the foreground and goal notifications tap intents
    // (and their deep linked URLs) do not clobber each other under FLAG_UPDATE_CURRENT.
    private const val FOREGROUND_REQUEST_CODE = 0
    private const val GOAL_REQUEST_CODE = 1

    // Minimum interval between per-step notification re-posts (see updateNotification). One second
    // keeps the displayed count near-live while staying well under Android's notification rate limit.
    private const val NOTIFICATION_THROTTLE_MS = 1_000L
    private const val ACTION_START = "org.pinge.steps.action.START"
    private const val ACTION_STOP = "org.pinge.steps.action.STOP"
    private const val EXTRA_SESSION_START = "session_start"
    private const val EXTRA_START_TOKEN = "start_token"
    private const val EXTRA_CLEAR = "clear"
    private const val EXTRA_NOTIFICATION_TITLE = "notification_title"
    private const val EXTRA_NOTIFICATION_TEXT = "notification_text"
    private const val EXTRA_NOTIFICATION_CHANNEL = "notification_channel"
    private const val EXTRA_NOTIFICATION_ICON = "notification_icon"
    private const val EXTRA_NOTIFICATION_URL = "notification_url"
    private const val EXTRA_CADENCE = "cadence"
    private const val EXTRA_GOAL_STEPS = "goal_steps"
    private const val EXTRA_GOAL_PERIOD = "goal_period"
    private const val EXTRA_GOAL_NOTIFICATION_TITLE = "goal_notification_title"
    private const val EXTRA_GOAL_NOTIFICATION_TEXT = "goal_notification_text"
    private const val EXTRA_GOAL_NOTIFICATION_CHANNEL = "goal_notification_channel"
    private const val EXTRA_GOAL_NOTIFICATION_ICON = "goal_notification_icon"
    private const val EXTRA_GOAL_NOTIFICATION_URL = "goal_notification_url"

    private const val DEFAULT_TITLE = StepsNotificationOptions.DEFAULT_TITLE
    private const val DEFAULT_TEXT = StepsNotificationOptions.DEFAULT_TEXT
    private const val DEFAULT_CHANNEL = StepsNotificationOptions.DEFAULT_CHANNEL
    private const val DEFAULT_ICON = StepsNotificationOptions.DEFAULT_ICON
    private const val DEFAULT_GOAL_TITLE = StepsNotificationOptions.DEFAULT_GOAL_TITLE
    private const val DEFAULT_GOAL_TEXT = StepsNotificationOptions.DEFAULT_GOAL_TEXT
    private const val DEFAULT_GOAL_CHANNEL = StepsNotificationOptions.DEFAULT_GOAL_CHANNEL

    // FLAG_IMMUTABLE exists since API 23. We support API level 24, so it is always safe to use.
    private val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // Starts (or resumes) a background step counting session. A null 'goal' means no goal is set, so
    // no goal extras are added and the service does not evaluate any number of steps or display any
    // goal achieved notification.
    fun startSession(
      context: Context,
      start: Long,
      startToken: Long,
      notificationTitle: String,
      notificationText: String,
      notificationChannel: String,
      notificationIcon: String,
      notificationUrl: String?,
      cadence: Double,
      goal: StepsGoalOptions?,
    ) {
      val intent =
        Intent(context, StepsForegroundService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_SESSION_START, start)
          putExtra(EXTRA_START_TOKEN, startToken)
          putExtra(EXTRA_NOTIFICATION_TITLE, notificationTitle)
          putExtra(EXTRA_NOTIFICATION_TEXT, notificationText)
          putExtra(EXTRA_NOTIFICATION_CHANNEL, notificationChannel)
          putExtra(EXTRA_NOTIFICATION_ICON, notificationIcon)
          notificationUrl?.let { putExtra(EXTRA_NOTIFICATION_URL, it) }
          putExtra(EXTRA_CADENCE, cadence)
          goal?.let {
            putExtra(EXTRA_GOAL_STEPS, it.steps)
            putExtra(EXTRA_GOAL_PERIOD, it.period)
            putExtra(EXTRA_GOAL_NOTIFICATION_TITLE, it.notification.title)
            putExtra(EXTRA_GOAL_NOTIFICATION_TEXT, it.notification.text)
            putExtra(EXTRA_GOAL_NOTIFICATION_CHANNEL, it.notification.channel)
            putExtra(EXTRA_GOAL_NOTIFICATION_ICON, it.notification.icon)
            it.notification.url?.let { url -> putExtra(EXTRA_GOAL_NOTIFICATION_URL, url) }
          }
        }
      ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    // Stops the background step counting session via ACTION_STOP. When 'clear' is true, it also
    // wipes the persisted step counting session and removes the goal notification. When 'clear'
    // is false, we pause the current counting session() and update the persisted state.
    fun stopSession(context: Context, clear: Boolean) {
      val intent =
        Intent(context, StepsForegroundService::class.java).apply {
          action = ACTION_STOP
          putExtra(EXTRA_CLEAR, clear)
        }
      ContextCompat.startForegroundService(context.applicationContext, intent)
    }
  }
}
