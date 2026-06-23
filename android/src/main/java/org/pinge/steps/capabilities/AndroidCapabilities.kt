package org.pinge.steps.capabilities

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Android API-level helper methods required to run the background foreground service
 * and its notification across the supported API range (minSdkVersion 24).
 */
object AndroidCapabilities {
  /**
   * Notification channels exist from Android 8.0 (API 26). On API 24 and 25 notifications are
   * posted with a legacy priority and no channel.
   */
  fun supportsNotificationChannels(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

  /**
   * The 'health' foreground-service type and its accompanying runtime permission gate only exist
   * from Android API level 34. Below API 34 a foreground service can be started without satisfying
   * a per type permission requirement.
   */
  fun requiresHealthForegroundServiceGate(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

  /**
   * The foreground-service type passed to ServiceCompat.startForeground.
   *
   * - API 34+ : ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH (256), matching the manifest 'health'
   *   type. This is the only API level that enforces the runtime-permission gate.
   * - API < 34 : ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE (0). On API < 29, ServiceCompat ignores
   *   the type entirely and falls back to the legacy two-argument 'startForeground'.
   */
  fun foregroundServiceType(): Int =
    if (requiresHealthForegroundServiceGate()) {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    } else {
      ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
    }
}
