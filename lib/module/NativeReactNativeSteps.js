"use strict";

import { TurboModuleRegistry } from 'react-native';
import { version } from '@pinge/react-native-steps/package.json';

/**
 * Codegen-facing shape of one `getSensors()` entry. React Native codegen cannot express the
 * {@link DeviceSensor} union, so the spec uses this permissive flat type with all platform
 * specific fields as optional. The public `getSensors()` re-types the result as {@link DeviceSensor}[].
 */

/**
 * Compile time guards to keep {@link NativeDeviceSensor} in sync with the rich {@link DeviceSensor}
 * union, so the `as DeviceSensor[]` re-type in the public `getSensors()` stays sound.
 *  - Key coverage: every field on a union member is mapped onto the flat codegen type.
 *    Adding a field to {@link IOSSensor}/{@link AndroidSensor} without adding it here breaks this.
 *  - Type compatibility: each mapped field's type is assignable to the flat type's.
 */

// The guards run via this ambient signature: `declare function` emits no runtime code and isn't
// exported, so the checks are enforced at compile time without leaking helper types into the public
// API. If either parameter's condition resolves to `false` (a key is unmapped, or a type is no
// longer assignable), it becomes `Expect<false>`, which violates the `extends true` constraint and
// fails compilation.

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

/**
 * Codegen-facing shape of the goal config passed to `start` (or `null` when no goal is set).
 * We mirror the public `Goal` type but changed the `period` to `string` because React Native
 * codegen cannot express the `GoalPeriod` literal union and `notification` is always present
 * since it defaults to `{}` on the JavaScript side.
 */

export const NAME = 'ReactNativeSteps';
export const VERSION = version;
export const stepEventName = 'ReactNativeSteps.step';
export const errorEventName = 'ReactNativeSteps.error';
export default TurboModuleRegistry.getEnforcing('ReactNativeSteps');
//# sourceMappingURL=NativeReactNativeSteps.js.map