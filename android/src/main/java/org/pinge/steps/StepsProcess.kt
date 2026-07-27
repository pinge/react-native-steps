package org.pinge.steps

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Process helpers for the :steps process.
 *
 * The step counting foreground service runs in a dedicated :steps process. Because Android instantiates
 * the app's Application and invokes onCreate() in every process, consuming apps must guard React Native
 * initialization (and their own app level SDK init) to the main process. There is one Application class
 * per app, shared by all processes, and it is owned by the app.
 *
 * Use isMain at the top of Application.onCreate():
 *
 * ```kotlin
 * override fun onCreate() {
 *   super.onCreate()
 *   if (!StepsProcess.isMain(this)) return // <- add this for the :steps process to skip react native init
 *   SoLoader.init(this, OpenSourceMergedSoMapping)
 *   // ...rest of your main process init
 * }
 * ```
 */
object StepsProcess {
  /**
   * Whether the current process is the app's main process (as opposed to the :steps service
   * process). The main process name equals the package name, the service process is
   * "<packageName>:steps". Fails open (returns true) if the process name can't be resolved, so
   * initialization still runs rather than being silently skipped.
   */
  @JvmStatic
  fun isMain(context: Context): Boolean {
    val name = currentProcessName(context)
    return name == null || name == context.packageName
  }

  private fun currentProcessName(context: Context): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      return Application.getProcessName()
    }
    @Suppress("DEPRECATION")
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val pid = Process.myPid()
    @Suppress("DEPRECATION")
    return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
  }
}
