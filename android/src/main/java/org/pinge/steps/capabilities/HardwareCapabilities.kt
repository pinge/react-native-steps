package org.pinge.steps.capabilities

import android.content.Context
import android.content.pm.PackageManager.FEATURE_SENSOR_ACCELEROMETER
import android.content.pm.PackageManager.FEATURE_SENSOR_STEP_COUNTER
import android.hardware.Sensor
import android.hardware.SensorManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import org.pinge.steps.counters.SensorTypes

/**
 * Helper methods to query available step counting hardware sensors.
 *
 * A sensor counts as available only when the device both declares the feature
 * (PackageManager.hasSystemFeature) and returns a real default-sensor instance
 * (SensorManager.getDefaultSensor). Requiring both keeps the strategy selection
 * accurate: a sensor that has no backing instance is not available.
 */
object HardwareCapabilities {
  /** Whether the dedicated hardware pedometer is present and usable. */
  fun hasPedometer(context: Context): Boolean = pedometer(context) != null

  /** Whether the accelerometer (the permission-free fallback) is present and usable. */
  fun hasAccelerometer(context: Context): Boolean = accelerometer(context) != null

  /** Whether the device can count steps with either sensor. */
  fun canCountSteps(context: Context): Boolean = hasPedometer(context) || hasAccelerometer(context)

  /**
   * The device's available step-counting sensors as the 'getSensors' wire payload.
   * Returns the most relevant sensor first (pedometer if exists, then accelerometer).
   */
  fun availableSensors(context: Context): WritableArray =
    Arguments.createArray().apply {
      pedometer(context)?.let { pushMap(sensorMap(it, SensorTypes.PEDOMETER)) }
      accelerometer(context)?.let { pushMap(sensorMap(it, SensorTypes.ACCELEROMETER)) }
    }

  private fun pedometer(context: Context): Sensor? =
    usableSensor(context, FEATURE_SENSOR_STEP_COUNTER, Sensor.TYPE_STEP_COUNTER)

  private fun accelerometer(context: Context): Sensor? =
    usableSensor(context, FEATURE_SENSOR_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER)

  // returns the default sensor only when the sensor feature is declared and the hardware is present
  private fun usableSensor(context: Context, feature: String, sensorType: Int): Sensor? {
    if (!context.packageManager.hasSystemFeature(feature)) return null
    val sensor = context.sensorManager.getDefaultSensor(sensorType)
    return sensor
  }

  private fun sensorMap(sensor: Sensor, type: String): WritableMap =
    Arguments.createMap().apply {
      putString("os", "android")
      putString("type", type)
      putString("name", sensor.name)
      putString("vendor", sensor.vendor)
      putDouble("power", sensor.power.toDouble())
      putDouble("resolution", sensor.resolution.toDouble())
      putInt("minDelay", sensor.minDelay)
      putInt("maxDelay", sensor.maxDelay)
      putBoolean("wakeUp", sensor.isWakeUpSensor)
    }

  private val Context.sensorManager: SensorManager
    get() = getSystemService(Context.SENSOR_SERVICE) as SensorManager
}
