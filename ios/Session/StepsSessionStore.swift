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

  private func date(forKey key: String) -> Date? {
    let seconds = defaults.double(forKey: key)
    return seconds > 0 ? Date(timeIntervalSince1970: seconds) : nil
  }
}
