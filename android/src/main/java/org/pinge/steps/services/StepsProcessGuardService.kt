package org.pinge.steps.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlin.system.exitProcess

/**
 * Kills the main (UI / React Native) process when the app's task is swiped away from recents, so the
 * next launch is a guaranteed cold start (fresh React context) instead of Android reusing the cached
 * main process warm. A warm React context reuse is what leaves libraries like react-native-screens and
 * react-native-gesture-handler with a stale native view tree (e.g. broken rotation, dead gesture taps,
 * AppState always with background), so killing the process resets all native state, so no stale context
 * is reused when the app is reopened again.
 *
 * This runs in the main process (no `android:process` in the manifest). It is started by
 * StepsSessionCoordinator only while a background (foreground service) counting session is active and
 * stopped when counting stops, so a swipe force quits the UI only in the exact scenario where the
 * :steps process needs it. Step counting itself runs in the separate :steps process (its own foreground
 * service) and is unaffected, so step counting continues, and no steps are lost when the app is not
 * running.
 */
class StepsProcessGuardService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  // Not sticky. After we kill the process on task removal, Android must not restart the service,
  // which would resurrect the main process we just tore down for a clean cold start.
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

  override fun onTaskRemoved(rootIntent: Intent?) {
    stopSelf()
    exitProcess(0)
  }
}
