import { TurboModuleRegistry, type TurboModule } from 'react-native';
import { version } from '@pinge/react-native-steps/package.json';

export type SensorType = 'PEDOMETER' | 'ACCELEROMETER';

export type StepEvent = {
  /** The type of hardware sensor used to count this step. */
  sensor: SensorType;
  /** The total number of steps taken since the start of the current session. */
  steps: number;
  /** The session start in Unix timestamp in milliseconds */
  start: number;
  /** The end of the step  in Unix timestamp in milliseconds */
  end: number;
  /** Distance in meters reported by CMPedometer (iOS only). */
  distance?: number;
  /** Number of ascended floors reported by CMPedometer (iOS only). */
  floorsAscended?: number;
  /** Number of descended floors reported by CMPedometer (iOS only). */
  floorsDescended?: number;
};

export type StepErrorEvent = {
  /** Human-readable error description (e.g. a CMPedometer failure on iOS, or no usable sensor on Android). */
  message: string;
};

export interface IOSSensor {
  /** Device OS used to narrow the sensor type is always "ios". */
  os: 'ios';
  /** Sensor type is always "PEDOMETER" on iOS. */
  type: SensorType;
  /** Hardware sensor name is always "CMPedometer" on iOS. */
  name: string;
  /** Whether the device can count steps (CMPedometer.isStepCountingAvailable). */
  stepCounting: boolean;
  /** Whether the device can report pace, in seconds per meter (CMPedometer.isPaceAvailable). */
  pace: boolean;
  /** Whether the device can report cadence, in steps per second (CMPedometer.isCadenceAvailable). */
  cadence: boolean;
  /** Whether the device can report distance, in meters (CMPedometer.isDistanceAvailable). */
  distance: boolean;
  /** Whether the device can count floors ascended/descended (CMPedometer.isFloorCountingAvailable). */
  floorCounting: boolean;
}

export interface AndroidSensor {
  /** Device OS used to narrow the sensor type is always "android". */
  os: 'android';
  /** Sensor type can be "PEDOMETER" (hardware step counter) or "ACCELEROMETER" (fallback). */
  type: SensorType;
  /** Hardware sensor name reported by the device. */
  name: string;
  /** Sensor vendor reported by the device. */
  vendor: string;
  /** Power draw while in use, in mA (e.g. 0.15). */
  power: number;
  /** Smallest change the sensor reports in its native unit. */
  resolution: number;
  /** Minimum delay between events in microseconds (0 for on-change sensors). */
  minDelay: number;
  /** Maximum delay between events in microseconds. */
  maxDelay: number;
  /** Whether this is a wake up sensor (can wake the device to deliver events). */
  wakeUp: boolean;
}

export type DeviceSensor = IOSSensor | AndroidSensor;

/**
 * Codegen-facing shape of one `getSensors()` entry. React Native codegen cannot express the
 * {@link DeviceSensor} union, so the spec uses this permissive flat type with all platform
 * specific fields as optional. The public `getSensors()` re-types the result as {@link DeviceSensor}[].
 */
export type NativeDeviceSensor = {
  os: string;
  type: string;
  name: string;
  stepCounting?: boolean;
  pace?: boolean;
  cadence?: boolean;
  distance?: boolean;
  floorCounting?: boolean;
  vendor?: string;
  power?: number;
  resolution?: number;
  minDelay?: number;
  maxDelay?: number;
  wakeUp?: boolean;
};

/**
 * Compile time guards to keep {@link NativeDeviceSensor} in sync with the rich {@link DeviceSensor}
 * union, so the `as DeviceSensor[]` re-type in the public `getSensors()` stays sound.
 *  - Key coverage: every field on a union member is mapped onto the flat codegen type.
 *    Adding a field to {@link IOSSensor}/{@link AndroidSensor} without adding it here breaks this.
 *  - Type compatibility: each mapped field's type is assignable to the flat type's.
 */
type Expect<T extends true> = T;
type KeysUnmappedToNative = Exclude<
  keyof IOSSensor | keyof AndroidSensor,
  keyof NativeDeviceSensor
>;
// The guards run via this ambient signature: `declare function` emits no runtime code and isn't
// exported, so the checks are enforced at compile time without leaking helper types into the public
// API. If either parameter's condition resolves to `false` (a key is unmapped, or a type is no
// longer assignable), it becomes `Expect<false>`, which violates the `extends true` constraint and
// fails compilation.
declare function _assertNativeSensorMapping(
  keysMapped: Expect<[KeysUnmappedToNative] extends [never] ? true : false>,
  typesCompatible: Expect<
    DeviceSensor extends NativeDeviceSensor ? true : false
  >
): void;

/**
 * Strings used to render the Android background foreground-service notification.
 * All fields are resolved (defaults applied) by the JS layer before reaching native.
 * - title: notification title line.
 * - text: notification body. Any `{{steps}}` token is replaced with the live step count on every
 *   update; if the token is absent the body stays the same as the count changes (no substitution).
 * - channel: user-visible notification channel name (Android 8+; ignored on iOS and Android < 8).
 */
export type AndroidNotificationOptions = {
  /** The foreground service notification title line.  */
  title?: string;
  /**
   * The foreground service notification body that should include `{{steps}}` template tag that is
   * replaced with the step count. If the token is absent the notification body becomes as the step
   * count changes (no template substitution).
   */
  text?: string;
  /** User visible notification channel name. */
  channel?: string;
};

export interface Spec extends TurboModule {
  /* ReactNativeStepsModule methods */
  canCountSteps(): Promise<boolean>;
  start(
    since: number,
    notification: AndroidNotificationOptions,
    cadence: number
  ): void;
  stop(): void;
  getSensors(): Promise<NativeDeviceSensor[]>;
  /* NativeEventEmitter required methods */
  addListener(event: string): void;
  removeListeners(count: number): void;
}

export const NAME = 'ReactNativeSteps';
export const VERSION = version;
export const stepEventName = 'ReactNativeSteps.step';
export const errorEventName = 'ReactNativeSteps.error';

export default TurboModuleRegistry.getEnforcing<Spec>('ReactNativeSteps');
