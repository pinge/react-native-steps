package org.pinge.steps.services

import com.facebook.react.bridge.ReadableMap

/**
 * Customization options for the foreground service notification UI. If no options
 * are set, from() applies English text fallbacks for any that are missing.
 * TODO i18n for all languages
 * TODO default title as app name?
 */
data class StepsNotificationOptions(
  val title: String,
  val text: String,
  val channel: String,
  val url: String?,
) {
  companion object {
    // Notification options defaults for the whole module. StepsForegroundService reuses
    // these same constants for its sticky restart mechanism.
    const val DEFAULT_TITLE = "Counting steps"
    const val DEFAULT_TEXT = "{{steps}} steps"
    const val DEFAULT_CHANNEL = "Step Counter"

    fun from(map: ReadableMap): StepsNotificationOptions =
      StepsNotificationOptions(
        title = map.getStringOr("title", DEFAULT_TITLE),
        text = map.getStringOr("text", DEFAULT_TEXT),
        channel = map.getStringOr("channel", DEFAULT_CHANNEL),
        url = map.getStringOrNull("url")?.takeIf { it.isNotBlank() },
      )

    private fun ReadableMap.getStringOr(key: String, fallback: String): String =
      if (hasKey(key) && !isNull(key)) getString(key) ?: fallback else fallback

    private fun ReadableMap.getStringOrNull(key: String): String? =
      if (hasKey(key) && !isNull(key)) getString(key) else null
  }
}
