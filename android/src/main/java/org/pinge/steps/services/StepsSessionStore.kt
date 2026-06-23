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
    get() = prefs.getDouble(KEY_ACCUMULATED, 0.0)

  // The sensor strategy backing the session (a SensorTypes value).
  val sensorType: String
    get() = prefs.getString(KEY_SENSOR_TYPE, "") ?: ""

  // We persist the notification options, so the notification UI can be
  // re-rendered after a process restart with no React context attached.
  val notificationTitle: String?
    get() = prefs.getString(KEY_NOTIF_TITLE, null)

  val notificationText: String?
    get() = prefs.getString(KEY_NOTIF_TEXT, null)

  val notificationChannel: String?
    get() = prefs.getString(KEY_NOTIF_CHANNEL, null)

  // Starts a fresh session, discarding any previous state.
  fun startSession(
    start: Long,
    sensorType: String,
    notificationTitle: String,
    notificationText: String,
    notificationChannel: String,
  ) {
    prefs
      .edit()
      .putBoolean(KEY_ACTIVE, true)
      .putLong(KEY_SESSION_START, start)
      .putString(KEY_SENSOR_TYPE, sensorType)
      .putDouble(KEY_RAW_CHECKPOINT, RAW_UNSET)
      .putDouble(KEY_ACCUMULATED, 0.0)
      .putString(KEY_NOTIF_TITLE, notificationTitle)
      .putString(KEY_NOTIF_TEXT, notificationText)
      .putString(KEY_NOTIF_CHANNEL, notificationChannel)
      .apply()
  }

  // Persists the latest raw cumulative-counter checkpoint (updated on every emit).
  fun saveRawCheckpoint(rawCheckpoint: Double) {
    prefs.edit().putDouble(KEY_RAW_CHECKPOINT, rawCheckpoint).apply()
  }

  // Persists the latest session total so it can be replayed after a process restart.
  fun saveProgress(accumulatedSteps: Double) {
    prefs.edit().putDouble(KEY_ACCUMULATED, accumulatedSteps).apply()
  }

  // Clears all session state (called on explicit stop).
  fun clearSession() {
    prefs.edit().clear().apply()
  }

  companion object {
    private const val PREFS_NAME = "steps_bg"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SESSION_START = "session_start"
    private const val KEY_RAW_CHECKPOINT = "raw_checkpoint"
    private const val KEY_ACCUMULATED = "accumulated_steps"
    private const val KEY_SENSOR_TYPE = "sensor_type"
    private const val KEY_NOTIF_TITLE = "notif_title"
    private const val KEY_NOTIF_TEXT = "notif_text"
    private const val KEY_NOTIF_CHANNEL = "notif_channel"
    const val RAW_UNSET = -1.0
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
