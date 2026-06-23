package org.pinge.steps.counters

/**
 * Used to normalize the sensor type exposed across the JavaScript bridge (and as a TS type SensorType)
 * to improve the library's DX. iOS returns CMPedometer (no accelerometer available) while Android can
 * return "Step Counter" (for pedometer) or something like "LSM6DSV Accelerometer".
 */
object SensorTypes {
  const val PEDOMETER = "PEDOMETER"
  const val ACCELEROMETER = "ACCELEROMETER"
}
