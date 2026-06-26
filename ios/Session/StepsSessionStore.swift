import Foundation

// This class persists the current step counting session so a later start() with the same 'since'
// resumes the running total after the process is killed and the app relaunched (force quit,
// swiped away, OS terminated while backgrounded, or after a reboot). We use a private UserDefaults
// suite to avoid collisions with the host app or any other libraries.
public final class StepsSessionStore {
  public static let suiteName = "org.pinge.steps.session"

  private enum Keys {
    static let sessionActive = "sessionActive"
    static let sessionStart = "sessionStart"
    static let lastEmittedAt = "lastEmittedAt"
    // Step goal runtime state, persisted so a resume (a later start() with the same 'since', e.g.
    // after the app was force quit and relaunched) continues the same period window without
    // re-notifying or losing the baseline. The goal configuration is not persisted, iOS always has
    // JavaScript call start() with the goal, unlike Android's native sticky restart.
    static let goalPeriodKey = "goalPeriodKey"
    static let goalBaseline = "goalBaseline"
    static let goalNotified = "goalNotified"
  }

  private let defaults: UserDefaults

  public init(suiteName: String = StepsSessionStore.suiteName) {
    // A custom suite is only nil for reserved names (e.g. the standard suite), fall back.
    defaults = UserDefaults(suiteName: suiteName) ?? .standard
  }

  // Whether a persisted session exists. This is set on startFreshSession() and never cleared by stop,
  // so a later start() with the same 'since' can resume the session.
  public var isActive: Bool {
    defaults.bool(forKey: Keys.sessionActive)
  }

  // Start Date of the current session.
  public var sessionStart: Date? {
    date(forKey: Keys.sessionStart)
  }

  // The end cursor of the last accepted reading for gap reconciliation on session resume.
  public var lastEmittedAt: Date? {
    date(forKey: Keys.lastEmittedAt)
  }

  // Starts a fresh session, discarding any previous state.
  public func startFreshSession(_ start: Date) {
    defaults.set(true, forKey: Keys.sessionActive)
    defaults.set(start.timeIntervalSince1970, forKey: Keys.sessionStart)
    defaults.set(start.timeIntervalSince1970, forKey: Keys.lastEmittedAt)
  }

  // Advances the emit cursor after a reading is accepted, so a later resume reconciles only the gap since the last emit.
  public func saveLastEmittedAt(_ date: Date) {
    defaults.set(date.timeIntervalSince1970, forKey: Keys.lastEmittedAt)
  }

  // Key of the current period window the goal is tracking (see StepsGoal.periodKey). 0 = unset.
  public var goalPeriodKey: Int {
    defaults.integer(forKey: Keys.goalPeriodKey)
  }

  // The accumulated session total captured when the current period window began.
  // "Total steps during this period" is acceptedSteps - goalBaseline.
  public var goalBaseline: Int {
    defaults.integer(forKey: Keys.goalBaseline)
  }

  // Whether the goal notification has already fired in the current period window.
  public var goalNotified: Bool {
    defaults.bool(forKey: Keys.goalNotified)
  }

  // Persists the goal runtime state. Updated when the window rolls over (new baseline, notified flag
  // reset) and when the goal notification fires, so a resume neither re-fires nor loses the baseline.
  public func saveGoalState(periodKey: Int, baseline: Int, notified: Bool) {
    defaults.set(periodKey, forKey: Keys.goalPeriodKey)
    defaults.set(baseline, forKey: Keys.goalBaseline)
    defaults.set(notified, forKey: Keys.goalNotified)
  }

  // Wipes the entire persisted session (session cursor + goal runtime). Used by a clearing stop()
  // (session logout) so a later start() always begins fresh rather than resuming. Only the library's
  // own keys are removed, never the host app's other UserDefaults.
  public func clear() {
    let keys = [
      Keys.sessionActive, Keys.sessionStart, Keys.lastEmittedAt,
      Keys.goalPeriodKey, Keys.goalBaseline, Keys.goalNotified,
    ]
    keys.forEach { defaults.removeObject(forKey: $0) }
  }

  private func date(forKey key: String) -> Date? {
    let seconds = defaults.double(forKey: key)
    return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
  }
}
