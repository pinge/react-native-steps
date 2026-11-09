#import <React/RCTEventEmitter.h>
#ifdef RCT_NEW_ARCH_ENABLED
#import <ReactNativeStepsSpec/ReactNativeStepsSpec.h>
#endif

// Import the Swift implementation of the module, the generated Swift-to-ObjectiveC header, named
// after the pod's product module. The __has_include guard covers both integration modes: framework
// (use_frameworks!) resolves to the <> import and the default static library resolves to the "" import.
#if __has_include(<ReactNativeSteps/ReactNativeSteps-Swift.h>)
#import <ReactNativeSteps/ReactNativeSteps-Swift.h>
#else
#import "ReactNativeSteps-Swift.h"
#endif

#ifdef RCT_NEW_ARCH_ENABLED
// Codegen owns method dispatch and argument conversion in the New Architecture.
#define RNS_EXPORT_METHOD(signature) - (void)signature

static NSDictionary *RNSNotificationDictionary(const JS::NativeReactNativeSteps::NotificationOptions &options) {
  NSMutableDictionary *dictionary = [NSMutableDictionary dictionary];
  // Assigning nil omits absent fields so the Swift core can apply its defaults.
  dictionary[@"title"] = options.title();
  dictionary[@"text"] = options.text();
  dictionary[@"channel"] = options.channel();
  dictionary[@"icon"] = options.icon();
  dictionary[@"url"] = options.url();
  return dictionary;
}

static NSDictionary *RNSGoalDictionary(const std::optional<JS::NativeReactNativeSteps::NativeGoal> &goal) {
  if (!goal.has_value()) {
    return nil;
  }
  return @{
    @"period": goal->period(),
    @"steps": @(goal->steps()),
    @"notification": RNSNotificationDictionary(goal->notification())
  };
}
#else
// Keep bridge exports for consumers using the legacy architecture.
#define RNS_EXPORT_METHOD(signature) RCT_EXPORT_METHOD(signature)
#endif

// Implements RNStepsEventSink so the Swift core can push step/error events through RCTEventEmitter.
#ifdef RCT_NEW_ARCH_ENABLED
@interface ReactNativeSteps : RCTEventEmitter <NativeReactNativeStepsSpec, RNStepsEventSink>
#else
@interface ReactNativeSteps : RCTEventEmitter <RNStepsEventSink>
#endif
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

RNS_EXPORT_METHOD(canCountSteps:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve(@([RNHardwareCapabilities canCountSteps]));
}

RNS_EXPORT_METHOD(getSensors:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve([RNHardwareCapabilities availableSensors]);
}

#ifdef RCT_NEW_ARCH_ENABLED
- (void)start:(double)since
 notification:(JS::NativeReactNativeSteps::NotificationOptions &)notification
      cadence:(double)cadence
      options:(JS::NativeReactNativeSteps::NativeStartOptions &)options
      resolve:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject {
  NSDictionary *notificationDictionary = RNSNotificationDictionary(notification);
  NSDictionary *goalDictionary = RNSGoalDictionary(options.goal());
#else
RCT_EXPORT_METHOD(start:(double)since
                  notification:(NSDictionary *)notification
                  cadence:(double)cadence
                  options:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  NSDictionary *notificationDictionary = notification;
  id goal = options[@"goal"];
  NSDictionary *goalDictionary = goal == [NSNull null] ? nil : goal;
#endif
  NSString *error = [_core startWithSince:since notification:notificationDictionary cadence:cadence goal:goalDictionary];
  if (error) {
    reject(@"E_START_FAILED", error, nil);
  } else {
    resolve(nil);
  }
}

RNS_EXPORT_METHOD(stop:(BOOL)clear
                  resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  [_core stopWithClear:clear];
  resolve(nil);
}

RNS_EXPORT_METHOD(isCounting:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject) {
  resolve(@([_core isCounting]));
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

#undef RNS_EXPORT_METHOD
