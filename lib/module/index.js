"use strict";

import ReactNativeStepsModule, { stepEventName, errorEventName, NAME, VERSION } from "./NativeReactNativeSteps.js";
import { NativeEventEmitter, Platform } from 'react-native';
const LINKING_PLATFORM_HINT = Platform.select({
  ios: '- You have run `pod install` in the `ios` directory, then cleaned, rebuilt and re-ran the app. You may also need to re-open Xcode to pick up the new pods.',
  android: '- You have the Android development environment set up: https://reactnative.dev/docs/environment-setup',
  default: ''
});
const LINKING_ERROR = `The package '@pinge/react-native-steps' doesn't seem to be linked. Make sure:

${LINKING_PLATFORM_HINT}
- You ran \`npx react-native clean\` to clear the module's caches (select the watchman, yarn, metro, android and npm options), then re-installed packages and rebuilt the app.
- You rebuilt the app after installing the package.
- You are not using Expo Go.

If none of these fix the issue, please open an issue on the GitHub repository: https://github.com/pinge/react-native-steps`;
const ReactNativeSteps = ReactNativeStepsModule ? ReactNativeStepsModule : new Proxy({}, {
  get() {
    throw new Error(LINKING_ERROR);
  }
});
const StepEventEmitter = new NativeEventEmitter(ReactNativeSteps);

/**
 * Lower and upper bounds (inclusive) for the {@link StartCountingOptions.cadence} cap, in steps
 * per second. The maximum value is "brisk walking" and running is out of scope, so the Android
 * accelerometer step counter only detects walking.
 */
export const MIN_CADENCE = 1.0;
export const MAX_CADENCE = 2.5;

/**
 * Sentinel for {@link StartCountingOptions.cadence} that disables the cap: 0 steps/second means
 * "no cap". Passed to native when `cadence` is omitted or set to this value explicitly.
 */
export const CADENCE_DISABLED = 0;

/** The recurrence window a {@link Goal} resets on. Only `'daily'` is supported for now. */

/** Supported {@link GoalPeriod} values, used to validate {@link Goal.period}. */
export const GOAL_PERIODS = ['daily'];

/**
 * An optional goal to achieve based on a time period and a total number of steps. When set, native
 * fires a one shot notification the moment the step count reaches {@link Goal.steps} within the
 * current {@link Goal.period} window, then stays quiet until the window resets (a `'daily'` goal
 * resets at local midnight). When omitted, this feature is a no-op.
 *
 * On Android the foreground notification service evaluates the goal continuously, so it fires even
 * while the app is backgrounded or swiped away.
 */

// Tracks the subscriptions created by the most recent start() call (step updates, and the optional
// error listener), so clearSubscriptions() can remove exactly these.
let _eventSubscription = null;
let _errorSubscription = null;

/** Whether the device supports step counting with hardware sensors */
export function canCountSteps() {
  return ReactNativeSteps.canCountSteps();
}

/** Lists the available step counting hardware sensors for this device */
export function getSensors() {
  // Codegen can only describe a loose shape (every platform field as optional), so the native
  // module result is typed as NativeDeviceSensor[]. The runtime values are already correct, so
  // we re-type it to DeviceSensor, allowing type narrowing by checking the `os` property.
  return ReactNativeSteps.getSensors();
}

/** Options for {@link startCounting}. */

/** Start counting steps. */
export function startCounting(options) {
  const {
    since,
    onStep,
    onError,
    notification: notificationOptions,
    cadence: cadenceOption,
    goal: goalOption
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
  const notification = notificationOptions ?? {};
  _eventSubscription = StepEventEmitter.addListener(stepEventName, event => onStep(event));
  // Only subscribe to errors when a handler is supplied, so the listener count is minimal.
  if (onError) {
    _errorSubscription = StepEventEmitter.addListener(errorEventName, event => onError(event));
  }
  ReactNativeSteps.start(resolvedSince, notification, cadence, goal);
}

/** Options for {@link stopCounting}. */

/** Stop counting steps. */
export function stopCounting(options) {
  const {
    clear = false
  } = options ?? {};
  clearSubscriptions();
  ReactNativeSteps.stop(clear);
}

/**
 * Whether a step counting session is currently active.
 *
 * NOTE: on Android the foreground service can keep counting after the app is swiped away from
 * recents. On a newly launched JavaScript context that has no active subscription yet, this
 * function returns `false` even if a native session is still alive. Use it to mirror the current
 * session in the UI, not as the source of truth for a backgrounded native session.
 */
export function isCounting() {
  return _eventSubscription != null;
}

// Removes only the subscriptions registered by this library (step + optional error), leaving any
// external subscribers (e.g. debug log components) intact. Used before a new start() and on stop().
function clearSubscriptions() {
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
function resolveCadence(cadence) {
  if (cadence === undefined || cadence === CADENCE_DISABLED) {
    return CADENCE_DISABLED;
  }
  if (!Number.isFinite(cadence) || cadence < MIN_CADENCE || cadence > MAX_CADENCE) {
    throw new RangeError(`cadence must be ${CADENCE_DISABLED} (disabled) or a number between ${MIN_CADENCE} and ${MAX_CADENCE} steps/second; received ${cadence}`);
  }
  return cadence;
}

/**
 * Validates and resolves the optional {@link StartCountingOptions.goal} into the {@link NativeGoal}
 * the native `start` method expects, or `null` when no goal is set. Throws a `RangeError` for an
 * unsupported {@link Goal.period} or a non finite/non positive {@link Goal.steps}, so a
 * misconfiguration surfaces immediately.
 */
function resolveGoal(goal) {
  if (goal === undefined) {
    return null;
  }
  const {
    period,
    steps,
    notification
  } = goal;
  if (!GOAL_PERIODS.includes(period)) {
    throw new RangeError(`goal.period must be one of ${GOAL_PERIODS.join(', ')}; received ${period}`);
  }
  if (!Number.isFinite(steps) || steps <= 0) {
    throw new RangeError(`goal.steps must be a finite number greater than 0; received ${steps}`);
  }
  return {
    period,
    steps,
    notification: notification ?? {}
  };
}

/**
 * Resolves the optional {@link StartCountingOptions.since} into the Unix-millisecond timestamp the
 * native `start` method expects. Native has no guard/fallback, so this is the single source of truth:
 * it defaults to now when omitted and falls back to now for any timestamp that is invalid
 * (non-finite/non-positive) or in the future.
 */
function resolveSince(since) {
  const now = Date.now();
  const requested = (since ?? new Date()).getTime();
  const isValid = Number.isFinite(requested) && requested > 0;
  const isFuture = requested > now;
  return isValid && !isFuture ? requested : now;
}
export { NAME, VERSION };
//# sourceMappingURL=index.js.map