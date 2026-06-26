import Foundation

// A Goal represents the effort required in terms of steps during a certain period of time. This is
// used as a gamification UX concept for users to acquire a healthy habit of walking a certain amount
// of steps on a recurring basis.
//
// Parsed step goal configuration coming from JavaScript, plus the per period window key used to
// detect a rollover (a new local calendar day for a 'daily' goal). A `nil` parse result (or a
// non positive step target) means "no goal", so nothing downstream evaluates if a goal was achieved
// and the feature is disabled (no-op) when unset.
//
// Only 'daily' is supported for now; 'weekly' is reserved and falls back to daily until implemented.
struct StepsGoal {
  let steps: Int
  let period: String
  // The goal achieved notification text translations and deep linking URL. `channel` and `icon` are
  // Android only and are intentionally dropped here (iOS notifications always use the app icon).
  // `url` is attached to the notification's userInfo on tap, but Linking.getInitialURL() won't catch it.
  let title: String
  let text: String
  let url: String?

  static let periodDaily = "daily"

  static let defaultTitle = "🎯 Daily goal reached!"
  static let defaultText = "👟 {{steps}} steps so far!"

  // Parses the goal dictionary passed to `start`. Returns `nil` for a missing dictionary or a
  // non positive step target (the "no goal" cases). JavaScript also validates these.
  static func parse(_ dict: [AnyHashable: Any]?) -> StepsGoal? {
    guard let dict = dict,
          let steps = (dict["steps"] as? NSNumber)?.intValue,
          steps > 0 else { return nil }

    let period = (dict["period"] as? String) ?? periodDaily
    let notification = dict["notification"] as? [AnyHashable: Any]
    let url = (notification?["url"] as? String).flatMap { $0.isEmpty ? nil : $0 }

    return StepsGoal(
      steps: steps,
      period: period,
      title: (notification?["title"] as? String) ?? defaultTitle,
      text: (notification?["text"] as? String) ?? defaultText,
      url: url
    )
  }

  // The key identifying the current period window for a date. A change between two readings signals
  // a rollover. For 'daily' this is the local calendar day. Uses the device's current calendar, so
  // the daily reset happens around local midnight ("does not have to be exact").
  func periodKey(for date: Date, calendar: Calendar = .current) -> Int {
    switch period {
    // case Self.periodWeekly: weekKey(...) // reserved for later
    default:
      // Avoid Calendar.Component.dayOfYear, which is only available on iOS 18+. year/month/day is
      // available since iOS 8 and year * 10000 + month * 100 + day is unique per local day (month
      // <= 12, day <= 31 never collide) and only ever compared for equality.
      let components = calendar.dateComponents([.year, .month, .day], from: date)
      let year = components.year ?? 0
      let month = components.month ?? 0
      let day = components.day ?? 0
      return year * 10000 + month * 100 + day
    }
  }

  // Renders the notification body, substituting `{{steps}}` with the steps taken this period.
  func renderBody(windowSteps: Int) -> String {
    text.replacingOccurrences(of: "{{steps}}", with: String(windowSteps))
  }
}
