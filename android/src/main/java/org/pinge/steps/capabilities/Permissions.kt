package org.pinge.steps.capabilities

import android.content.Context
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.core.content.PermissionChecker.checkSelfPermission

/**
 * Helper method for runtime permission checks.
 */
object Permissions {
  private const val PERMISSION_ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"

  /**
   * Whether the ACTIVITY_RECOGNITION permission is granted. Required to receive TYPE_STEP_COUNTER
   * events on API 29+ and to run the 'health' foreground service on API 34+.
   */
  fun isActivityRecognitionGranted(context: Context): Boolean =
    checkSelfPermission(context, PERMISSION_ACTIVITY_RECOGNITION) == PERMISSION_GRANTED
}
