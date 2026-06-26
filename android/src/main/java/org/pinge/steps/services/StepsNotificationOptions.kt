package org.pinge.steps.services

import com.facebook.react.bridge.ReadableMap

/**
 * Customization options for the foreground service notification UI. If no options are set, from()
 * applies English text fallbacks where applicable, a default icon but no deep link URL.
 * TODO i18n for all languages
 * TODO default title as app name?
 */
data class StepsNotificationOptions(
  val title: String,
  val text: String,
  val channel: String,
  val icon: String,
  val url: String?,
) {
  companion object {
    // Foreground service notification defaults for the whole module. StepsForegroundService reuses
    // these same constants for its sticky restart mechanism.
    const val DEFAULT_TITLE = "Counting steps"
    const val DEFAULT_TEXT = "{{steps}} steps"
    const val DEFAULT_CHANNEL = "Step Counter"

    // Default small icon drawable resource name, shared by the foreground and goal notifications.
    // This matches StepsForegroundService's Android framework fallback (android.R.drawable.ic_menu_compass),
    // so the default renders even when the host app bundles no custom drawable.
    const val DEFAULT_ICON = "ic_menu_compass"

    // Defaults for the once per period goal achieved notification (a separate, alerting,
    // dismissable notification, distinct from the ongoing foreground service notification).
    const val DEFAULT_GOAL_TITLE = "🎯 Daily goal reached!"
    const val DEFAULT_GOAL_TEXT = "👟 {{steps}} steps so far!"
    const val DEFAULT_GOAL_CHANNEL = "Daily Steps Goal"

    // Parses the foreground service notification options, applying the foreground defaults.
    fun from(map: ReadableMap?): StepsNotificationOptions =
      from(map, DEFAULT_TITLE, DEFAULT_TEXT, DEFAULT_CHANNEL)

    // Parses the goal achieved notification options, applying the goal defaults.
    fun fromGoal(map: ReadableMap?): StepsNotificationOptions =
      from(map, DEFAULT_GOAL_TITLE, DEFAULT_GOAL_TEXT, DEFAULT_GOAL_CHANNEL)

    private fun from(
      map: ReadableMap?,
      defaultTitle: String,
      defaultText: String,
      defaultChannel: String,
    ): StepsNotificationOptions =
      StepsNotificationOptions(
        title = map.getStringOr("title", defaultTitle),
        text = map.getStringOr("text", defaultText),
        channel = map.getStringOr("channel", defaultChannel),
        icon = map.getStringOr("icon", DEFAULT_ICON),
        url = map.getStringOrNull("url")?.takeIf { it.isNotBlank() },
      )

    private fun ReadableMap?.getStringOr(key: String, fallback: String): String =
      if (this != null && hasKey(key) && !isNull(key)) getString(key) ?: fallback else fallback

    private fun ReadableMap?.getStringOrNull(key: String): String? =
      if (this != null && hasKey(key) && !isNull(key)) getString(key) else null
  }
}
