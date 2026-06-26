package org.pinge.steps.services

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin SharedPreferences wrapper that persists the current step counting session so it
 * survives the React Native process being killed (e.g. app is swiped away from recents)
 * while StepsForegroundService keeps running in the background. We can also resume a
 * session on device reboot since SharedPreferences are persisted across reboot. The
 * step counting implementation re-baselines from rawCheckpoint when the cumulative
 * hardware counter (pedometer) reset, so a resumed session continues from accumulatedSteps
 * rather than freezing. Android devices do not track steps when off or during boot.
 */
class StepsSessionStore(context: Context) {
  private val prefs: SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // Whether a session is currently in progress.
  val isActive: Boolean
    get() = prefs.getBoolean(KEY_ACTIVE, false)

  // Start of the current session in UTC milliseconds.
  val sessionStart: Long
    get() = prefs.getLong(KEY_SESSION_START, 0L)

  // Last raw cumulative step counter value seen (or -1.0 until the first event).
  // On restart, this is used with accumulatedSteps to reconstruct state.
  val rawCheckpoint: Double
    get() = prefs.getDouble(KEY_RAW_CHECKPOINT, RAW_UNSET)

  // Latest emitted session total steps since sessionStart.
  val accumulatedSteps: Double
    get() = prefs.getDouble(KEY_ACCUMULATED_STEPS, 0.0)

  // The sensor strategy backing the session (a SensorTypes value).
  val sensorType: String
    get() = prefs.getString(KEY_SENSOR_TYPE, "") ?: ""

  // The maximum-cadence cap for the session (steps/second), or Cadence.DISABLED (0) for no cap.
  // Persisted so a sticky restart resumes counting with the same cap the session started with.
  val cadence: Double
    get() = prefs.getDouble(KEY_CADENCE, DEFAULT_CADENCE)

  // We persist the foreground notification options, so the notification UI can be
  // re-rendered after a process restart with no React context attached.
  val notificationTitle: String?
    get() = prefs.getString(KEY_NOTIFICATION_TITLE, null)

  val notificationText: String?
    get() = prefs.getString(KEY_NOTIFICATION_TEXT, null)

  val notificationChannel: String?
    get() = prefs.getString(KEY_NOTIFICATION_CHANNEL, null)

  // The small icon drawable resource name for the foreground notification, or null if none persisted.
  val notificationIcon: String?
    get() = prefs.getString(KEY_NOTIFICATION_ICON, null)

  // The deep link URL opened when the user taps the foreground notification, or null to just open the app.
  val notificationUrl: String?
    get() = prefs.getString(KEY_NOTIFICATION_URL, null)

  // We persist the goal options, so the goal achieved notification UI can be
  // re-rendered after a process restart with no React context attached.
  val goalTitle: String?
    get() = prefs.getString(KEY_GOAL_TITLE, null)

  val goalText: String?
    get() = prefs.getString(KEY_GOAL_TEXT, null)

  val goalChannel: String?
    get() = prefs.getString(KEY_GOAL_CHANNEL, null)

  // The small icon drawable resource name for the goal achieved notification, or null if none persisted.
  val goalIcon: String?
    get() = prefs.getString(KEY_GOAL_ICON, null)

  // The deep link URL opened when the user taps the goal achieved notification, or null to just open the app.
  val goalUrl: String?
    get() = prefs.getString(KEY_GOAL_URL, null)

  // The goal step target, or 0 (Goal.DISABLED) when no goal is set.
  val goalSteps: Double
    get() = prefs.getDouble(KEY_GOAL_STEPS, 0.0)

  // The goal recurrence window (a Goal.PERIOD_* value), or null if none persisted.
  val goalPeriod: String?
    get() = prefs.getString(KEY_GOAL_PERIOD, null)

  // Key of the current period window the goal is tracking (see Goal.periodKey). 0 = unset.
  val goalPeriodKey: Long
    get() = prefs.getLong(KEY_GOAL_PERIOD_KEY, 0L)

  // The total accumulated steps for a session since when the current period window began.
  // "Total steps during this period" is accumulatedSteps - goalPeriodBaseline.
  val goalPeriodBaseline: Double
    get() = prefs.getDouble(KEY_GOAL_BASELINE, 0.0)

  // Whether the goal notification has already fired in the current period window.
  val goalNotified: Boolean
    get() = prefs.getBoolean(KEY_GOAL_NOTIFIED, false)

  // Starts a fresh session, discarding any previous state.
  fun startSession(
    start: Long,
    sensorType: String,
    notificationTitle: String,
    notificationText: String,
    notificationChannel: String,
    notificationIcon: String,
    notificationUrl: String?,
    cadence: Double,
  ) {
    prefs
      .edit()
      .putBoolean(KEY_ACTIVE, true)
      .putLong(KEY_SESSION_START, start)
      .putString(KEY_SENSOR_TYPE, sensorType)
      .putDouble(KEY_RAW_CHECKPOINT, RAW_UNSET)
      .putDouble(KEY_ACCUMULATED_STEPS, 0.0)
      .putString(KEY_NOTIFICATION_TITLE, notificationTitle)
      .putString(KEY_NOTIFICATION_TEXT, notificationText)
      .putString(KEY_NOTIFICATION_CHANNEL, notificationChannel)
      .putString(KEY_NOTIFICATION_ICON, notificationIcon)
      .putString(KEY_NOTIFICATION_URL, notificationUrl)
      .putDouble(KEY_CADENCE, cadence)
      .apply()
  }

  // Updates the persisted notification + cadence for the active session, without touching the running
  // total/baseline/start. Used on a resume that adopts updated options, so a later sticky restart renders
  // the latest notification strings and counts steps with the latest cadence cap.
  fun saveConfig(
    notificationTitle: String,
    notificationText: String,
    notificationChannel: String,
    notificationIcon: String,
    notificationUrl: String?,
    cadence: Double,
  ) {
    prefs
      .edit()
      .putString(KEY_NOTIFICATION_TITLE, notificationTitle)
      .putString(KEY_NOTIFICATION_TEXT, notificationText)
      .putString(KEY_NOTIFICATION_CHANNEL, notificationChannel)
      .putString(KEY_NOTIFICATION_ICON, notificationIcon)
      .putString(KEY_NOTIFICATION_URL, notificationUrl)
      .putDouble(KEY_CADENCE, cadence)
      .apply()
  }

  // Persists the latest raw cumulative-counter checkpoint (updated on every emit).
  fun saveRawCheckpoint(rawCheckpoint: Double) {
    prefs.edit().putDouble(KEY_RAW_CHECKPOINT, rawCheckpoint).apply()
  }

  // Persists the latest session total so it can be replayed after a process restart.
  fun saveProgress(accumulatedSteps: Double) {
    prefs.edit().putDouble(KEY_ACCUMULATED_STEPS, accumulatedSteps).apply()
  }

  // Persists the goal configuration for the active session, so a sticky restart with no React
  // context can keep evaluating the if the goal has been achieved and render its notification.
  // A non positive 'steps' (Goal.DISABLED) means no goal is set. This is called on a fresh
  // start and on a resume that adopts updated goal options.
  fun saveGoalConfig(
    steps: Double,
    period: String,
    title: String,
    text: String,
    channel: String,
    icon: String,
    url: String?,
  ) {
    prefs
      .edit()
      .putDouble(KEY_GOAL_STEPS, steps)
      .putString(KEY_GOAL_PERIOD, period)
      .putString(KEY_GOAL_TITLE, title)
      .putString(KEY_GOAL_TEXT, text)
      .putString(KEY_GOAL_CHANNEL, channel)
      .putString(KEY_GOAL_ICON, icon)
      .putString(KEY_GOAL_URL, url)
      .apply()
  }

  // Wipes the entire persisted step counting session (flags, checkpoints, notifications, cadence config,
  // and goal config + runtime). Useful when logging out a user session or a reset session button.
  fun clear() {
    prefs.edit().clear().apply()
  }

  // Persists the goal runtime state, which is updated when the period window rolls over (new
  // baseline, notified flag reset) and when the goal achieved notification fires, so a sticky
  // restart does not re-fire the notification or lose the current period window's baseline.
  fun saveGoalState(periodKey: Long, baseline: Double, notified: Boolean) {
    prefs
      .edit()
      .putLong(KEY_GOAL_PERIOD_KEY, periodKey)
      .putDouble(KEY_GOAL_BASELINE, baseline)
      .putBoolean(KEY_GOAL_NOTIFIED, notified)
      .apply()
  }

  companion object {
    private const val PREFS_NAME = "org.pinge.steps.session"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SESSION_START = "session_start"
    private const val KEY_RAW_CHECKPOINT = "raw_checkpoint"
    private const val KEY_ACCUMULATED_STEPS = "accumulated_steps"
    private const val KEY_SENSOR_TYPE = "sensor_type"
    private const val KEY_NOTIFICATION_TITLE = "notification_title"
    private const val KEY_NOTIFICATION_TEXT = "notification_text"
    private const val KEY_NOTIFICATION_CHANNEL = "notification_channel"
    private const val KEY_NOTIFICATION_ICON = "notification_icon"
    private const val KEY_NOTIFICATION_URL = "notification_url"
    private const val KEY_CADENCE = "cadence"
    private const val KEY_GOAL_STEPS = "goal_steps"
    private const val KEY_GOAL_PERIOD = "goal_period"
    private const val KEY_GOAL_TITLE = "goal_title"
    private const val KEY_GOAL_TEXT = "goal_text"
    private const val KEY_GOAL_CHANNEL = "goal_channel"
    private const val KEY_GOAL_ICON = "goal_icon"
    private const val KEY_GOAL_URL = "goal_url"
    private const val KEY_GOAL_PERIOD_KEY = "goal_period_key"
    private const val KEY_GOAL_BASELINE = "goal_baseline"
    private const val KEY_GOAL_NOTIFIED = "goal_notified"
    const val RAW_UNSET = -1.0
    // Default Cadence.DISABLED when a stored session predates the cadence key.
    private const val DEFAULT_CADENCE = 0.0
  }
}

// SharedPreferences has no native Double accessors, so we store the raw IEEE-754 bits as a Long.
private fun SharedPreferences.getDouble(key: String, default: Double): Double =
  if (contains(key)) {
    Double.fromBits(getLong(key, java.lang.Double.doubleToRawLongBits(default)))
  } else {
    default
  }

private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor =
  putLong(key, java.lang.Double.doubleToRawLongBits(value))
