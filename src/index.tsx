import type { EventSubscription } from 'react-native';
import ReactNativeStepsModule, {
  stepEventName,
  errorEventName,
  NAME,
  VERSION,
  type SensorType,
  type NotificationOptions,
  type NativeGoal,
  type Spec,
  type StepEvent,
  type StepErrorEvent,
  type DeviceSensor,
  type IOSSensor,
  type AndroidSensor,
} from './NativeReactNativeSteps';
import { NativeEventEmitter, Platform } from 'react-native';

const LINKING_PLATFORM_HINT = Platform.select({
  ios: '- You have run `pod install` in the `ios` directory, then cleaned, rebuilt and re-ran the app. You may also need to re-open Xcode to pick up the new pods.',
  android:
    '- You have the Android development environment set up: https://reactnative.dev/docs/environment-setup',
  default: '',
});

const LINKING_ERROR = `The package '@pinge/react-native-steps' doesn't seem to be linked. Make sure:

${LINKING_PLATFORM_HINT}
- You ran \`npx react-native clean\` to clear the module's caches (select the watchman, yarn, metro, android and npm options), then re-installed packages and rebuilt the app.
- You rebuilt the app after installing the package.
- You are not using Expo Go.

If none of these fix the issue, please open an issue on the GitHub repository: https://github.com/pinge/react-native-steps`;

const ReactNativeSteps = (
  ReactNativeStepsModule
    ? ReactNativeStepsModule
    : new Proxy(
        {},
        {
          get() {
            throw new Error(LINKING_ERROR);
          },
        }
      )
) as Spec;

const StepEventEmitter = new NativeEventEmitter(ReactNativeSteps);

/**
 * Lower and upper bounds (inclusive) for the {@link StartCountingOptions.cadence} cap, in steps
 * per second. The maximum value is "brisk walking" and running is out of scope, so the Android
 * accelerometer step counter only detects walking.
 */
export const MIN_CADENCE: number = 1.0;
export const MAX_CADENCE: number = 2.5;

/**
 * Sentinel for {@link StartCountingOptions.cadence} that disables the cap: 0 steps/second means
 * "no cap". Passed to native when `cadence` is omitted or set to this value explicitly.
 */
export const CADENCE_DISABLED: number = 0;

/** The recurrence window a {@link Goal} resets on. Only `'daily'` is supported for now. */
export type GoalPeriod = 'daily';

/** Supported {@link GoalPeriod} values, used to validate {@link Goal.period}. */
export const GOAL_PERIODS: readonly GoalPeriod[] = ['daily'];

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

// Tracks the subscriptions created by the most recent start() call (step updates, and the optional
// error listener), so clearSubscriptions() can remove exactly these.
let _eventSubscription: EventSubscription | null = null;
let _errorSubscription: EventSubscription | null = null;

/** Whether the device supports step counting with hardware sensors */
export async function canCountSteps(): Promise<boolean> {
  return ReactNativeSteps.canCountSteps();
}

/** Lists the available step counting hardware sensors for this device */
export async function getSensors(): Promise<DeviceSensor[]> {
  // Codegen can only describe a loose shape (every platform field as optional), so the native
  // module result is typed as NativeDeviceSensor[]. The runtime values are already correct, so
  // we re-type it to DeviceSensor, allowing type narrowing by checking the `os` property.
  return ReactNativeSteps.getSensors() as unknown as Promise<DeviceSensor[]>;
}

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
export async function startCounting(
  options: StartCountingOptions
): Promise<void> {
  const {
    since,
    onStep,
    onError,
    notification: notificationOptions,
    cadence: cadenceOption,
    goal: goalOption,
  } = options;
  // Validate cadence and goal first so a misconfiguration throws before we add listeners or start
  // counting steps. resolveGoal returns null (no goal) when goalOption is omitted.
  const cadence = resolveCadence(cadenceOption);
  const goal = resolveGoal(goalOption);
  // Clear any previous subscriptions before creating new ones, clean slate.
  clearSubscriptions();
  // Resolve the start timestamp here so native can trust it directly (there is no native guard/fallback).
  const resolvedSince = resolveSince(since);
  // Pass through only the notification options the caller supplied.
  // The native module has the default values and any missing option is filled in.
  const notification: NotificationOptions = notificationOptions ?? {};
  _eventSubscription = StepEventEmitter.addListener(stepEventName, (event) =>
    onStep(event as StepEvent)
  );
  // Only subscribe to errors when a handler is supplied, so the listener count is minimal.
  if (onError) {
    _errorSubscription = StepEventEmitter.addListener(errorEventName, (event) =>
      onError(event as StepErrorEvent)
    );
  }
  return ReactNativeSteps.start(
    resolvedSince,
    notification,
    cadence,
    goal
  ).catch((error) => {
    clearSubscriptions();
    throw error;
  });
}

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
export async function stopCounting(
  options?: StopCountingOptions
): Promise<void> {
  const { clear = false } = options ?? {};
  clearSubscriptions();
  return ReactNativeSteps.stop(clear);
}

/**
 * Whether a step counting session is currently active.
 *
 * NOTE: on Android the foreground service can keep counting after the app is swiped away from
 * recents. On a newly launched JavaScript context that has no active subscription yet, this
 * function returns `false` even if a native session is still alive. Use it to mirror the current
 * session in the UI, not as the source of truth for a backgrounded native session.
 */
export async function isCounting(): Promise<boolean> {
  return ReactNativeSteps.isCounting();
}

// Removes only the subscriptions registered by this library (step + optional error), leaving any
// external subscribers (e.g. debug log components) intact. Used before a new start() and on stop().
function clearSubscriptions(): void {
  _eventSubscription?.remove();
  _eventSubscription = null;
  _errorSubscription?.remove();
  _errorSubscription = null;
}

/**
 * Validates and resolves the optional {@link StartCountingOptions.cadence} into the value the native
 * module `start` method expects. Either a number between {@link MIN_CADENCE} and {@link MAX_CADENCE},
 * or {@link CADENCE_DISABLED} when omitted or passed explicitly as `0`. Throws a `RangeError` for any
 * out of range or non finite value so a misconfiguration surfaces immediately rather than silently
 * disabling the walking cadence cap.
 */
function resolveCadence(cadence: number | undefined): number {
  if (cadence === undefined || cadence === CADENCE_DISABLED) {
    return CADENCE_DISABLED;
  }
  if (
    !Number.isFinite(cadence) ||
    cadence < MIN_CADENCE ||
    cadence > MAX_CADENCE
  ) {
    throw new RangeError(
      `cadence must be ${CADENCE_DISABLED} (disabled) or a number between ${MIN_CADENCE} and ${MAX_CADENCE} steps/second; received ${cadence}`
    );
  }
  return cadence;
}

/**
 * Validates and resolves the optional {@link StartCountingOptions.goal} into the {@link NativeGoal}
 * the native `start` method expects, or `null` when no goal is set. Throws a `RangeError` for an
 * unsupported {@link Goal.period} or a non finite/non positive {@link Goal.steps}, so a
 * misconfiguration surfaces immediately.
 */
function resolveGoal(goal: Goal | undefined): NativeGoal | null {
  if (goal === undefined) {
    return null;
  }
  const { period, steps, notification } = goal;
  if (!GOAL_PERIODS.includes(period)) {
    throw new RangeError(
      `goal.period must be one of ${GOAL_PERIODS.join(', ')}; received ${period}`
    );
  }
  if (!Number.isFinite(steps) || steps <= 0) {
    throw new RangeError(
      `goal.steps must be a finite number greater than 0; received ${steps}`
    );
  }
  return { period, steps, notification: notification ?? {} };
}

/**
 * Resolves the optional {@link StartCountingOptions.since} into the Unix-millisecond timestamp the
 * native `start` method expects. Native has no guard/fallback, so this is the single source of truth:
 * it defaults to now when omitted and falls back to now for any timestamp that is invalid
 * (non-finite/non-positive) or in the future.
 */
function resolveSince(since: Date | undefined): number {
  const now = Date.now();
  const requested = (since ?? new Date()).getTime();
  const isValid = Number.isFinite(requested) && requested > 0;
  const isFuture = requested > now;
  return isValid && !isFuture ? requested : now;
}

export {
  NAME,
  VERSION,
  type SensorType,
  type NotificationOptions,
  type StepEvent,
  type StepErrorEvent,
  type DeviceSensor,
  type IOSSensor,
  type AndroidSensor,
  type StartCountingOptions,
  type StopCountingOptions,
};
