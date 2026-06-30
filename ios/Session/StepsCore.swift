import CoreMotion
import Foundation
import UIKit

// The iOS step counting session engine: the resume-vs-fresh decision, the cold-launch bucketed
// reconciliation, the live updates, and the cadence-capped emit path. It owns all the CMPedometer
// orchestration, while the ObjectiveC++ module stays a thin shim that exports the spec, owns the
// RCTEventEmitter, and forwards start() and stop() here.
//
// Concurrency: every field below is owned by stateQueue (read and written only inside it), so the
// React Native method queue, the CMPedometer handler queues, and overlapping reconciliations all see a
// consistent view. sessionGeneration is bumped on every start/stop, so stale async work re-checks
// it under the lock and bails once the session has moved on.
@objc(RNStepsCore)
public final class StepsCore: NSObject {
  // The shim owns StepsCore strongly and StepsCore must call back into it to emit, so the sink is
  // held weakly to avoid a shim/core retain cycle that would otherwise leak both for the process
  // lifetime. (StepsEventSink is AnyObject-bound precisely so it can be held weakly.)
  private weak var sink: StepsEventSink?
  private let store: StepsSessionStore
  private let pedometer = CMPedometer()
  private let goalNotifier = StepsGoalNotifier()
  private let stateQueue = DispatchQueue(label: "org.pinge.steps.state")
  // A dedicated queue used only to space out the bucketed reconciliation's emits (one per hour boundary),
  // pausing backfillEmitInterval between them so the burst doesn't flood the bridge.
  //
  // It deliberately is not stateQueue, because that would deadlock. Each spaced out step queries
  // the pedometer, and the pedometer's completion handler then calls emitStep, which does a
  // blocking stateQueue.sync to read/update the shared state. If the spacing itself happened on
  // stateQueue, that completion would try to stateQueue.sync back onto the queue it's already
  // running on, and a blocking sync onto the current serial queue waits forever for itself.
  // A separate queue lets the completion re-enter stateQueue safely.
  private let backfillQueue = DispatchQueue(label: "org.pinge.steps.backfill")

  // MARK: stateQueue-owned session state
  private var sessionStartDate: Date?
  private var lastRawCumulative: Int = -1  // monotonic dedup + delta base; -1 = none
  private var acceptedSteps: Int = 0       // last emitted, cadence-capped, cumulative steps
  private var lastSeenAt: Date?            // endDate of the last reading seen, for the live cadence window
  private var cadence: Double = Cadence.disabled
  private var reconcileInFlight: Int = 0   // running reconciliation chains; >0 suppresses live emits
  private var liveStarted: Bool = false    // whether live pedometer updates were started this session
  private var sessionGeneration: Int = 0   // bumped on every start/stop; stale async work checks it and bails

  // MARK: stateQueue-owned daily steps goal state
  private var goal: StepsGoal?             // nil = no goal (the whole feature is a no-op)
  private var goalPeriodKey: Int = 0       // current period window key (StepsGoal.periodKey)
  private var goalBaseline: Int = 0        // acceptedSteps when the current window began
  private var goalNotified: Bool = false   // whether the goal notification fired this window

  // Touched on the React Native method queue (start/stop), not stateQueue.
  private var observingForeground: Bool = false

  init(sink: StepsEventSink, store: StepsSessionStore) {
    self.sink = sink
    self.store = store
    super.init()
  }

  @objc public convenience init(sink: StepsEventSink) {
    self.init(sink: sink, store: StepsSessionStore())
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
  }

  // MARK: - Public API

  // 'since' is a valid Unix millisecond value (JavaScript resolves the default/invalid/future
  // cases before invocation), so we just convert it to the seconds Date expects, with no native
  // guard. 'notification' configures the Android foreground service notification. iOS has none,
  // so it is ignored here (the parameter only exists to satisfy the shared codegen spec). Pin
  // the ObjectiveC selector explicitly: Swift would otherwise export this as 'startSince:...' (it only
  // inserts "With" for initializers), but the shim calls 'startWithSince:notification:cadence:'.
  // Returns nil once the session is live, or an error message when it could not start.
  @objc(startWithSince:notification:cadence:goal:)
  public func start(since: Double, notification: [AnyHashable: Any]?, cadence: Double, goal goalDict: [AnyHashable: Any]?) -> String? {
    // 'notification' configures the Android foreground service only, iOS has none, so it's ignored.
    // 'goal' is used on iOS as it fires a once per period local notification.
    _ = notification

    // The device has no step counting hardware, so a step counting session can never emit step events.
    guard CMPedometer.isStepCountingAvailable() else {
      return "step counting is not available on this device"
    }
    // Motion access was explicitly denied/restricted, so CMPedometer queries fail. .notDetermined is
    // allowed through since starting triggers the system prompt on the first query.
    switch CMPedometer.authorizationStatus() {
    case .denied, .restricted:
      return "motion & fitness access is denied"
    default:
      break
    }

    // JavaScript validates cadence, re-sanitize defensively (an out-of-range value becomes disabled).
    let sanitizedCadence = Cadence.sanitize(cadence)
    // Parse the optional daily steps goal. nil = no goal, so the goal execution path is a no-op below.
    let parsedGoal = StepsGoal.parse(goalDict)

    pedometer.stopUpdates()

    let requested = Date(timeIntervalSince1970: since / 1000.0)

    // Resume the active session only when this start targets the same 'since' it began with: keep the
    // original start label and reconcile just the gap since the last emit, so 'steps' stays cumulative.
    // A different 'since' (or no active session) begins fresh at 'requested'.
    let chosenStart: Date
    let gapStart: Date
    let isFresh: Bool
    if store.isActive, let persistedStart = store.sessionStart,
       datesMatch(persistedStart, requested) {
      chosenStart = persistedStart
      gapStart = store.lastEmittedAt ?? persistedStart
      isFresh = false
    } else {
      chosenStart = requested
      store.startFreshSession(requested)
      gapStart = requested
      isFresh = true
    }

    // Resolve the goal runtime. A fresh session resets it (baseline 0, current window of chosenStart,
    // not yet fired). A resume continues the persisted window so it neither re-fires nor loses the
    // baseline. The goal config always comes from this start() call, so it is not persisted.
    let goalKey: Int
    let goalBase: Int
    let goalFired: Bool
    if isFresh {
      goalKey = parsedGoal?.periodKey(for: chosenStart) ?? 0
      goalBase = 0
      goalFired = false
      store.saveGoalState(periodKey: goalKey, baseline: goalBase, notified: goalFired)
    } else {
      goalKey = store.goalPeriodKey
      goalBase = store.goalBaseline
      goalFired = store.goalNotified
    }

    // Reset all stateQueue-owned state before reconciliation/live chains can read it. Bumping the
    // generation invalidates any async work still in flight from a previous session.
    stateQueue.sync {
      sessionGeneration += 1
      sessionStartDate = chosenStart
      lastRawCumulative = -1
      acceptedSteps = 0
      lastSeenAt = nil
      self.cadence = sanitizedCadence
      reconcileInFlight = 0
      liveStarted = false
      goal = parsedGoal
      goalPeriodKey = goalKey
      goalBaseline = goalBase
      goalNotified = goalFired
    }

    // Request notification authorization lazily the first time a goal is configured.
    if parsedGoal != nil {
      goalNotifier.requestAuthorizationIfNeeded()
    }

    registerForegroundObserver()

    // Bucketed reconciliation (covers cold launch). Live updates begin automatically once this reconciliation
    // (plus any overlapping foreground reconciliation) drains, see reconcileChainDidFinish().
    reconcile(from: gapStart)

    // The session is established (sessionStartDate is set), so report success. Reconciliation and
    // live updates continue asynchronously, and any error flows through the error event, not here.
    return nil
  }

  // By default stop() is a pause, not a terminate: it stops live updates and resets the in-memory
  // state, but does NOT clear the persisted session, so a later start() with the same 'since' resumes
  // the running total (a different 'since' overwrites it). When 'clear' is true (the session-logout
  // path) it additionally wipes the persisted session + goal runtime, so a later start() always
  // begins fresh regardless of 'since'.
  @objc(stopWithClear:)
  public func stop(clear: Bool) {
    pedometer.stopUpdates()
    unregisterForegroundObserver()

    stateQueue.sync {
      sessionGeneration += 1
      sessionStartDate = nil
      lastRawCumulative = -1
      acceptedSteps = 0
      lastSeenAt = nil
      cadence = Cadence.disabled
      reconcileInFlight = 0
      liveStarted = false
      // Reset in-memory goal state. On a plain pause the persisted goal runtime is left intact so a
      // later resume (same 'since') continues the same period window without re-notifying.
      goal = nil
      goalPeriodKey = 0
      goalBaseline = 0
      goalNotified = false
    }

    if clear {
      store.clear()
    }
  }

  // Whether step events are actively being produced right now. This is true only when the hardware
  // can count, motion authorization is not blocked, and a step counting session is currently active
  // in this process (sessionStartDate is set by start() and cleared by stop()).
  @objc public func isCounting() -> Bool {
    guard CMPedometer.isStepCountingAvailable() else { return false }
    // Only an explicit denial/restriction blocks counting. .notDetermined paired with a live session
    // is still counting (updates are running; the first query resolves the prompt).
    switch CMPedometer.authorizationStatus() {
    case .denied, .restricted:
      return false
    default:
      break
    }
    return currentSessionStart() != nil
  }

  // MARK: - Foreground observer (warm-foreground reconciliation)

  private func registerForegroundObserver() {
    guard !observingForeground else { return }
    observingForeground = true
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(handleAppDidBecomeActive),
      name: UIApplication.didBecomeActiveNotification,
      object: nil
    )
  }

  private func unregisterForegroundObserver() {
    guard observingForeground else { return }
    observingForeground = false
    NotificationCenter.default.removeObserver(
      self,
      name: UIApplication.didBecomeActiveNotification,
      object: nil
    )
  }

  @objc private func handleAppDidBecomeActive() {
    let sessionStart = currentSessionStart()
    guard store.isActive, let sessionStart = sessionStart else { return }
    let gapStart = store.lastEmittedAt ?? sessionStart
    reconcile(from: gapStart)
  }

  // MARK: - Bucketed reconciliation + live updates

  // Wall clock gap inserted between consecutive backfill emits. On a first start (or a warm-foreground
  // catch-up) over pre-existing CMPedometer data, the bucketed reconciliation fires one cumulative event
  // per hour boundary back to back. Each event crosses the bridge and can hypothetically drive a consumer
  // side persistence write, so an un-spaced burst could congest the bridge and hammer local resource.
  // Pacing each emit at around one display frame apart gives the JavaScript thread some room to drain
  // one event before the next arrives. The total added latency before live updates begin is bounded by
  // the 7-day retention cap (~168 hourly points worst case ≈ a few seconds), and CMPedometer's own first
  // live reading lags by seconds regardless, so this is not the bottleneck for when live counting starts.
  private static let backfillEmitInterval: DispatchTimeInterval = .milliseconds(33)

  // Snapshot the stateQueue-owned session start so the reconciliation/live queries see a consistent value
  // regardless of which queue they run on.
  private func currentSessionStart() -> Date? {
    var start: Date?
    stateQueue.sync { start = sessionStartDate }
    return start
  }

  // Emit one cumulative event at each hour boundary (the start of every clock hour: 09:00, 10:00,
  // ...) across [gapStart, now], in increasing end-date order, so the consumer can bucket each
  // hour's delta accurately. While a reconciliation is in flight, live emits are held back; the
  // in-flight count tracks how many are running at once, so overlapping reconciliations stay safe.
  private func reconcile(from gapStart: Date) {
    var sessionStart: Date?
    var generation = 0
    stateQueue.sync {
      sessionStart = sessionStartDate
      generation = sessionGeneration
    }
    guard let sessionStart = sessionStart else { return }

    let now = Date()

    // Cap the gap at CMPedometer's ~7-day retention window.
    let earliest = now.addingTimeInterval(-7 * 24 * 60 * 60)
    let effectiveStart = max(gapStart, earliest)

    // Build the end time of each query, in increasing order: every hour boundary strictly after
    // effectiveStart and before now, then 'now' itself. Each one becomes the 'to' of a cumulative
    // query running from sessionStart, so we get one event ending at each hour boundary up to now.
    var points: [Date] = []
    let calendar = Calendar.current
    var boundary = nextHourBoundary(after: effectiveStart, calendar: calendar)
    while boundary < now {
      points.append(boundary)
      // Step by a calendar hour (not a fixed 3600s) so boundaries stay on the local hour boundary across
      // DST transitions, including fractional hour DST zones (e.g. Lord Howe Island).
      boundary = calendar.date(byAdding: .hour, value: 1, to: boundary) ?? boundary.addingTimeInterval(3600)
    }
    points.append(now)

    // Mark a reconciliation as in progress (which pauses live emits) before sending any event. But if
    // a new start()/stop() replaced the session since we read it above, this work is stale, so bail
    // without starting.
    var proceed = false
    stateQueue.sync {
      guard sessionGeneration == generation else { return }
      reconcileInFlight += 1
      proceed = true
    }
    guard proceed else { return }

    emitReconcilePoints(points, sessionStart: sessionStart, generation: generation, index: 0) { [weak self] in
      self?.reconcileChainDidFinish(generation: generation)
    }
  }

  // One reconciliation just finished, so decrement the in progress count. Once that count hits zero
  // (every overlapping reconciliation has finished), it's safe to start live updates, which we do
  // exactly once per session.
  private func reconcileChainDidFinish(generation: Int) {
    var drained = false
    stateQueue.sync {
      guard sessionGeneration == generation else { return } // stale chain; leave current state untouched
      if reconcileInFlight > 0 {
        reconcileInFlight -= 1
      }
      drained = (reconcileInFlight == 0)
    }
    if drained {
      startLiveUpdatesIfNeeded(generation: generation)
    }
  }

  private func nextHourBoundary(after date: Date, calendar: Calendar) -> Date {
    // The hour boundary at the start of the hour containing 'date'; the next boundary is
    // +1 calendar hour (strictly after 'date'). Use the calendar (not a fixed 3600s) so this first
    // boundary also stays correct across DST.
    let hourStart = calendar.dateInterval(of: .hour, for: date)?.start ?? date
    return calendar.date(byAdding: .hour, value: 1, to: hourStart) ?? hourStart.addingTimeInterval(3600)
  }

  private func emitReconcilePoints(
    _ points: [Date],
    sessionStart: Date,
    generation: Int,
    index: Int,
    completion: @escaping () -> Void
  ) {
    guard index < points.count else {
      completion()
      return
    }
    let endDate = points[index]
    pedometer.queryPedometerData(from: sessionStart, to: endDate) { [weak self] data, error in
      guard let self = self else { return }
      // If the session was replaced/stopped while this query was in flight, abandon the chain.
      var stale = false
      self.stateQueue.sync { stale = self.sessionGeneration != generation }
      if stale {
        completion()
        return
      }
      if let error = error {
        self.emitError(error.localizedDescription)
      } else if let data = data {
        // Reconciliation emits are authoritative historical backfill, never cadence-capped (live: false).
        self.emitStep(data: data, endDate: endDate, generation: generation, live: false)
      }
      // Continue the chain regardless, preserving emission order, but pace the next hop by
      // backfillEmitInterval so the per hour backfill events arrive spread out instead of as one
      // synchronous burst. The next hop rechecks the generation at the top, so a start()/stop()
      // during this gap still tears the chain down cleanly and releases the reconciliation hold
      // via completion() (every exit path of this method calls it).
      self.backfillQueue.asyncAfter(deadline: .now() + Self.backfillEmitInterval) {
        self.emitReconcilePoints(points, sessionStart: sessionStart, generation: generation, index: index + 1, completion: completion)
      }
    }
  }

  // Start live pedometer updates exactly once per session. Decoupled from any single reconciliation's
  // completion, so an overlapping or superseding reconciliation cannot strand live startup.
  private func startLiveUpdatesIfNeeded(generation: Int) {
    var shouldStart = false
    var sessionStart: Date?
    stateQueue.sync {
      guard sessionGeneration == generation else { return }
      if !liveStarted, sessionStartDate != nil {
        liveStarted = true
        shouldStart = true
        sessionStart = sessionStartDate
      }
    }
    guard shouldStart, let sessionStart = sessionStart else { return }

    pedometer.startUpdates(from: sessionStart) { [weak self] data, error in
      guard let self = self else { return }
      if let error = error {
        var stale = false
        self.stateQueue.sync { stale = self.sessionGeneration != generation }
        if !stale {
          self.emitError(error.localizedDescription)
        }
        return
      }
      guard let data = data else { return }
      // Emit only for the current session and while no bucketed reconciliation chain is in flight.
      var emit = false
      self.stateQueue.sync {
        emit = (self.sessionGeneration == generation) && (self.reconcileInFlight == 0)
      }
      guard emit else { return }
      // Live updates are subject to the cadence cap (live: true).
      self.emitStep(data: data, endDate: Date(), generation: generation, live: true)
    }
  }

  // MARK: - Emit

  // The shared emit path: a cumulative (cadence-capped) 'steps' total, a fixed session 'start', and
  // an explicit 'end', with a monotonic guard and a persisted cursor advance.
  //
  // 'live' distinguishes the two callers. Reconciliation points (live: false) are authoritative historical
  // backfill, accepted in full. Live updates (live: true) are capped at 'cadence': a reading may add
  // at most floor(elapsed * cadence) steps over the window since the previous accepted reading, and
  // the excess is dropped. Because the cap scales with the time since the previous reading, a
  // legitimately batched delivery (many steps reported at once over a long window) is accepted in
  // full, while a fast spike over a short window is trimmed. The raw cursor and time reference advance
  // on every accepted reading even when nothing is accepted, so the steps dropped from a trimmed
  // burst are not added back to a later reading.
  private func emitStep(data: CMPedometerData, endDate: Date, generation: Int, live: Bool) {
    var body: [String: Any]?
    // Holds the goal notification to send, if this reading hits the goal. It's filled in inside the
    // lock below by evaluateGoalLocked() but the notification is actually sent after the lock is
    // released, since we don't want to execute that logic while holding stateQueue.
    var goalFire: (goal: StepsGoal, windowSteps: Int)?
    stateQueue.sync {
      guard sessionGeneration == generation else { return } // stale session; drop

      let rawCumulative = data.numberOfSteps.intValue
      // Skip duplicates by comparing to the last raw cumulative value we saw, since a reconciliation
      // and a live update can overlap and report the same reading. The value only ever increases,
      // because every query and live update reads the cumulative count measured from sessionStart.
      guard rawCumulative > lastRawCumulative else { return }
      let rawDelta = (lastRawCumulative < 0) ? rawCumulative : (rawCumulative - lastRawCumulative)

      let acceptedDelta: Int
      if live, cadence > Cadence.disabled, let lastEnd = lastSeenAt {
        let elapsed = endDate.timeIntervalSince(lastEnd)
        acceptedDelta = min(rawDelta, Cadence.maxSteps(over: elapsed, cadence: cadence))
      } else {
        // Disabled, reconciliation (authoritative), or the first reading of the session: accept in full.
        acceptedDelta = rawDelta
      }

      // Advance the raw cursor + time reference for every accepted reading, even when acceptedDelta is
      // 0, so the next live window measures from here. This is what stops dropped steps from being
      // added back to a later reading.
      lastRawCumulative = rawCumulative
      lastSeenAt = endDate

      // Whole delta was an over-cadence burst (or session start missing); nothing new to emit.
      guard acceptedDelta > 0, let start = sessionStartDate else { return }
      acceptedSteps += acceptedDelta
      body = StepEvent.build(steps: acceptedSteps, start: start, end: endDate, data: data)
      goalFire = evaluateGoalLocked(endDate: endDate)
    }

    // Send the goal achieved notification (if any) now, before the body guard below. The goal was
    // already marked as fired inside the lock, so if a nil body returned early there, it'd be lost.
    if let goalFire = goalFire {
      goalNotifier.post(goal: goalFire.goal, windowSteps: goalFire.windowSteps)
    }

    guard let body = body else { return }
    store.saveLastEmittedAt(endDate)
    sink?.emitStep(body)
  }

  // Checks whether the latest acceptedSteps has reached the goal. Must run on stateQueue, since it
  // reads and updates the goal* state. Returns the goal and the steps this period when the
  // notification should fire, otherwise nil.
  //
  // For a 'daily' goal the window resets when the local calendar day changes: we record the current
  // total as the new baseline and clear the fired flag, so the goal restarts around local midnight
  // even mid-session across days. We only notify for the current day. A cold launch or didBecomeActive
  // reconciliation also replays past days through here, but those just update the rollover bookkeeping
  // and never fire for a goal already met on an earlier day.
  private func evaluateGoalLocked(endDate: Date) -> (goal: StepsGoal, windowSteps: Int)? {
    guard let goal = goal else { return nil }
    var changed = false
    let key = goal.periodKey(for: endDate)
    if key != goalPeriodKey {
      // When goalPeriodKey is 0 it means we haven't tracked a window yet, so this is the first one,
      // and not an actual day change: the baseline stays 0 and we count the whole period. On a real
      // day change we instead save the current total as the baseline, so the next day starts counting
      // from there.
      goalBaseline = goalPeriodKey == 0 ? 0 : acceptedSteps
      goalPeriodKey = key
      goalNotified = false
      changed = true
    }
    // "Steps during this period" is the total steps since that baseline.
    let windowSteps = acceptedSteps - goalBaseline
    var fire: (goal: StepsGoal, windowSteps: Int)?
    if !goalNotified, windowSteps >= goal.steps, key == goal.periodKey(for: Date()) {
      goalNotified = true
      changed = true
      fire = (goal, windowSteps)
    }
    if changed {
      store.saveGoalState(periodKey: goalPeriodKey, baseline: goalBaseline, notified: goalNotified)
    }
    return fire
  }

  private func emitError(_ message: String) {
    sink?.emitError(message)
  }

  // MARK: - Helpers

  // Whether two dates refer to the same instant within sub-millisecond tolerance. 'since' arrives as
  // an integer Unix millisecond value but is persisted as a seconds double, so an exact compare can
  // pick up floating-point dust; half a millisecond is well below the resolution resume cares about.
  private func datesMatch(_ a: Date, _ b: Date) -> Bool {
    abs(a.timeIntervalSince1970 - b.timeIntervalSince1970) < 0.0005
  }
}
