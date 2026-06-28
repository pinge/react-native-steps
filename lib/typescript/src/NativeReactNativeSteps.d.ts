import { type TurboModule } from 'react-native';
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
 * Strings used to render a notification. Shared by the Android foreground service notification
 * (Android only) and the cross-platform goal achieved notification. The `{{steps}}` token in
 * `text` is substituted with the corresponding step count by the native side on each render.
 * - title: notification title line.
 * - text: notification body. Any `{{steps}}` token is replaced with the step count; if the token
 *   is absent the body is static (no substitution).
 * - channel: user visible notification channel name (Android 8+; ignored on iOS and Android < 8).
 * - icon: small icon drawable resource name (Android only; ignored on iOS).
 * - url: deep link opened when the notification is tapped (both platforms).
 */
export type NotificationOptions = {
    /** The notification title line. */
    title?: string;
    /**
     * The notification body that should include the `{{steps}}` template tag, replaced with the step
     * count. If the token is absent the body is static (no template substitution).
     */
    text?: string;
    /** User visible notification channel name. Android only, ignored on iOS. */
    channel?: string;
    /**
     * Name of the small icon drawable resource (e.g. `"ic_menu_compass"`, the default) used for the
     * notification. The host app must provide a drawable (or mipmap) with this name, and if it is
     * not found a built-in Android fallback icon is used. Android only and ignored on iOS, where
     * notifications always show the app's own icon and a custom small icon cannot be set.
     */
    icon?: string;
    /** Deep link URL opened when the user taps the notification. By default it just opens the app. */
    url?: string;
};
/**
 * Codegen-facing shape of the goal config passed to `start` (or `null` when no goal is set).
 * We mirror the public `Goal` type but changed the `period` to `string` because React Native
 * codegen cannot express the `GoalPeriod` literal union and `notification` is always present
 * since it defaults to `{}` on the JavaScript side.
 */
export type NativeGoal = {
    /** The recurrent time per window after which the goal 'steps' is reset on. The only option is 'daily'. */
    period: string;
    /** The total number of steps as a target that triggers the goal notification once per period. */
    steps: number;
    /** Title and body translations, deep link URL and icon to render with the goal achieved notification when the 'steps' target is reached. */
    notification: NotificationOptions;
};
export interface Spec extends TurboModule {
    canCountSteps(): Promise<boolean>;
    start(since: number, notification: NotificationOptions, cadence: number, goal: NativeGoal | null): void;
    stop(clear: boolean): void;
    getSensors(): Promise<NativeDeviceSensor[]>;
    addListener(event: string): void;
    removeListeners(count: number): void;
}
export declare const NAME = "ReactNativeSteps";
export declare const VERSION: string;
export declare const stepEventName = "ReactNativeSteps.step";
export declare const errorEventName = "ReactNativeSteps.error";
declare const _default: Spec;
export default _default;
//# sourceMappingURL=NativeReactNativeSteps.d.ts.map