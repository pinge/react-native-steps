#import <React/RCTEventEmitter.h>
#import <ReactNativeStepsSpec/ReactNativeStepsSpec.h>

// Import the Swift implementation of the module, the generated Swift-to-ObjectiveC header, named
// after the pod's product module. The __has_include guard covers both integration modes: framework
// (use_frameworks!) resolves to the <> import and the default static library resolves to the "" import.
#if __has_include(<ReactNativeSteps/ReactNativeSteps-Swift.h>)
#import <ReactNativeSteps/ReactNativeSteps-Swift.h>
#else
#import "ReactNativeSteps-Swift.h"
#endif

// Implements RNStepsEventSink so the Swift core can push step/error events through RCTEventEmitter.
@interface ReactNativeSteps : RCTEventEmitter <NativeReactNativeStepsSpec, RNStepsEventSink>
@end

@implementation ReactNativeSteps {
  RNStepsCore *_core;
}

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

RCT_EXPORT_MODULE(ReactNativeSteps);

// Do not @synthesize bridge or callableJSModules since RCTEventEmitter already declares and
// receives them. Re-synthesizing in the subclass shadows those ivars, so React Native's injected
// values land in the subclass's shadowing ivars and never reach the inherited RCTEventEmitter
// accessors on this instance, throwing "RCTCallableJSModules is not set". Letting RCTEventEmitter
// own them keeps sendEventWithName() working in both React Native's old and new architectures.
- (instancetype)init {
  self = [super init];
  if (self) {
    // The core holds the sink (self) weakly, so this shim -> core -> shim chain does not retain-cycle.
    // RNStepsEventSink is AnyObject-bound so it can be held weakly.
    _core = [[RNStepsCore alloc] initWithSink:self];
  }
  return self;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[
    @"ReactNativeSteps.step",
    @"ReactNativeSteps.error"
  ];
}

#pragma mark - Public API

RCT_EXPORT_METHOD(canCountSteps:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve(@([RNHardwareCapabilities canCountSteps]));
}

RCT_EXPORT_METHOD(getSensors:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve([RNHardwareCapabilities availableSensors]);
}

RCT_EXPORT_METHOD(start:(double)since
                  notification:(NSDictionary *)notification
                  cadence:(double)cadence
                  goal:(NSDictionary *)goal) {
  [_core startWithSince:since notification:notification cadence:cadence goal:goal];
}

RCT_EXPORT_METHOD(stop:(BOOL)clear) {
  [_core stopWithClear:clear];
}

#pragma mark - RNStepsEventSink

- (void)emitStep:(NSDictionary<NSString *, id> *)body {
  [self sendEventWithName:@"ReactNativeSteps.step" body:body];
}

- (void)emitError:(NSString *)message {
  [self sendEventWithName:@"ReactNativeSteps.error"
                     body:@{ @"message": message ?: @"unknown error" }];
}

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
  (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeReactNativeStepsSpecJSI>(params);
}
#endif // RCT_NEW_ARCH_ENABLED

@end
