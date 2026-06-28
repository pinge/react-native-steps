import { NAME, VERSION, type SensorType, type NotificationOptions, type StepEvent, type StepErrorEvent, type DeviceSensor, type IOSSensor, type AndroidSensor } from './NativeReactNativeSteps.js';
/**
 * Lower and upper bounds (inclusive) for the {@link StartCountingOptions.cadence} cap, in steps
 * per second. The maximum value is "brisk walking" and running is out of scope, so the Android
 * accelerometer step counter only detects walking.
 */
export declare const MIN_CADENCE: number;
export declare const MAX_CADENCE: number;
/**
 * Sentinel for {@link StartCountingOptions.cadence} that disables the cap: 0 steps/second means
 * "no cap". Passed to native when `cadence` is omitted or set to this value explicitly.
 */
export declare const CADENCE_DISABLED: number;
/** The recurrence window a {@link Goal} resets on. Only `'daily'` is supported for now. */
export type GoalPeriod = 'daily';
/** Supported {@link GoalPeriod} values, used to validate {@link Goal.period}. */
export declare const GOAL_PERIODS: readonly GoalPeriod[];
/**
 * An optional goal to achieve based on a time period and a total number of steps. When set, native
 * fires a one shot notification the moment the step count reaches {@link Goal.steps} within the
 * current {@link Goal.period} window, then stays quiet until the window resets (a `'daily'` goal
 * resets at local midnight). When omitted, this feature is a no-op.
 *
 * On Android the foreground notification service evaluates the goal continuously, so it fires even
 * while the app is backgrounded or swiped away.
 */
export type Goal = {
    /** The recurrent time per window after which the goal 'steps' is reset. */
    period: GoalPeriod;
    /** The total number of steps as a target that triggers the goal notification once per period. Must be finite and > 0. */
    steps: number;
    /**
     * Strings used to render the goal achieved notification. Any omitted field has a built-in native
     * fallback. Unlike {@link StartCountingOptions.notification}, this notification is shown on both
     * Android and iOS, but the `channel` field is Android.
     */
    notification?: NotificationOptions;
};
/** Whether the device supports step counting with hardware sensors */
export declare function canCountSteps(): Promise<boolean>;
/** Lists the available step counting hardware sensors for this device */
export declare function getSensors(): Promise<DeviceSensor[]>;
/** Options for {@link startCounting}. */
interface StartCountingOptions {
    /**
     * Date instance representing the start of the range over which to measure steps. Defaults to new Date().
     * On iOS, the past seven days' worth of data are stored and available to retrieve. Specifying a `since`
     * older than seven days ago returns only the available data.
     */
    since?: Date;
    /**
     * Callback for each {@link StepEvent}.
     *
     * On iOS, CMPedometer does not emit individual steps. The motion coprocessor batches steps for
     * power efficiency and emits a step count every 1-3 seconds, so `steps` typically advances by
     * several at a time. On Android, the sensors emit more granular step increments.
     */
    onStep: (event: StepEvent) => void;
    /** Optional callback when an error occurs (e.g. CMPedometer failure on iOS, no usable sensor on Android) */
    onError?: (error: StepErrorEvent) => void;
    /**
     * Customization options for the Android foreground service notification.
     * Any omitted text field (except the deep link `url`) has a built-in English fallback supplied
     * on the native side. If the deep link `url` is omitted, the app will just open without an
     * initial URL when tapping the notification. Android only, ignored on iOS.
     */
    notification?: NotificationOptions;
    /**
     * Maximum walking cadence, in steps per second, between {@link MIN_CADENCE} and {@link MAX_CADENCE}.
     * This option is useful to reject false positive bursts and improve accuracy. Pass `0` to disable
     * the cap explicitly (the same as omitting it). */
    cadence?: number;
    /**
     * Optional {@link Goal} to achieve as total number of steps during a certain period of time.
     * When set, the native module fires a notification once per period when the steps target is
     * reached. See {@link Goal} for delivery notes.
     */
    goal?: Goal;
}
/** Start counting steps. */
export declare function startCounting(options: StartCountingOptions): void;
/** Options for {@link stopCounting}. */
interface StopCountingOptions {
    /**
     * When true, resets all persisted step counting session state. Useful for a session logout or a
     * reset button. After invoking stop({ clear: true }), a later {@link startCounting} always starts
     * a fresh session, even with the same `since`. Defaults to `false`, so the default start()/stop()
     * acts as start/resume for convenience.
     */
    clear?: boolean;
}
/** Stop counting steps. */
export declare function stopCounting(options?: StopCountingOptions): void;
/**
 * Whether a step counting session is currently active.
 *
 * NOTE: on Android the foreground service can keep counting after the app is swiped away from
 * recents. On a newly launched JavaScript context that has no active subscription yet, this
 * function returns `false` even if a native session is still alive. Use it to mirror the current
 * session in the UI, not as the source of truth for a backgrounded native session.
 */
export declare function isCounting(): boolean;
export { NAME, VERSION, type SensorType, type NotificationOptions, type StepEvent, type StepErrorEvent, type DeviceSensor, type IOSSensor, type AndroidSensor, type StartCountingOptions, type StopCountingOptions, };
//# sourceMappingURL=index.d.ts.map