import Foundation
import UserNotifications

// Posts the once per period step goal local notification on iOS. There is no always on background
// service on iOS, so the goal is evaluated while the app is active and running in the foreground.
// This just posts a local notification when the core decides the goal was reached. Mirrors the
// Android implementation, with `channel` (an Android concept) intentionally omitted.
//
// While the app is in the foreground, iOS only shows a posted notification as a banner if the host
// app installs a `UNUserNotificationCenterDelegate` returning presentation options. Without one the
// notification is still delivered (it appears in Notification Center). The library deliberately does
// not install a delegate, to avoid conflicting with the host app libraries that handle notifications.
final class StepsGoalNotifier {
  private let center: UNUserNotificationCenter
  // Whether we have already asked for authorization this process (avoids repeat prompts/requests).
  private var didRequestAuthorization = false

  init(center: UNUserNotificationCenter = .current()) {
    self.center = center
  }

  // Lazily requests notification authorization the first time a goal is configured. Called from
  // `start` on the React Native method queue, so the `didRequestAuthorization` guard is effectively
  // single-threaded. A valid double request is the worst case if ever called off that queue.
  func requestAuthorizationIfNeeded() {
    guard !didRequestAuthorization else { return }
    didRequestAuthorization = true
    center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
  }

  // Posts the goal achieved notification immediately (`nil` trigger). The token `{{steps}}` in the
  // body is rendered with the steps counted during this period. The optional deep link `url` is
  // attached to `userInfo` so it's available on the app (Linking.getInitialUrl() doesn't catch it).
  func post(goal: StepsGoal, windowSteps: Int) {
    let content = UNMutableNotificationContent()
    content.title = goal.title
    content.body = goal.renderBody(windowSteps: windowSteps)
    content.sound = .default
    if let url = goal.url {
      content.userInfo = ["url": url]
    }
    let request = UNNotificationRequest(
      identifier: UUID().uuidString,
      content: content,
      trigger: nil
    )
    center.add(request, withCompletionHandler: nil)
  }
}
