import CoreMotion
import Foundation
import UIKit

// The iOS step counting session engine: the resume-vs-fresh decision, the cold-launch bucketed
// reconcile, the live updates, and the cadence-capped emit path. It owns all the CMPedometer
// orchestration, while the ObjectiveC++ module stays a thin shim that exports the spec, owns the
// RCTEventEmitter, and forwards start() and stop() here.
//
// Concurrency: every field below is owned by stateQueue (read and written only inside it), so the
// React Native method queue, the CMPedometer handler queues, and overlapping reconciles all see a
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
  private let stateQueue = DispatchQueue(label: "org.pinge.steps.state")

  // MARK: stateQueue-owned session state
  private var sessionStartDate: Date?
  private var lastRawCumulative: Int = -1  // monotonic dedup + delta base; -1 = none
  private var acceptedSteps: Int = 0       // last emitted, cadence-capped, cumulative steps
  private var lastSeenAt: Date?            // endDate of the last reading seen, for the live cadence window
  private var cadence: Double = Cadence.disabled
  private var reconcileInFlight: Int = 0   // running reconcile chains; >0 suppresses live emits
  private var liveStarted: Bool = false    // whether live pedometer updates were started this session
  private var sessionGeneration: Int = 0   // bumped on every start/stop; stale async work checks it and bails

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
  @objc(startWithSince:notification:cadence:)
  public func start(since: Double, notification: [AnyHashable: Any]?, cadence: Double) {
    _ = notification

    // JavaScript validates cadence, re-sanitize defensively (an out-of-range value becomes disabled).
    let sanitizedCadence = Cadence.sanitize(cadence)

    pedometer.stopUpdates()

    let requested = Date(timeIntervalSince1970: since / 1000.0)

    // Resume the active session only when this start targets the same 'since' it began with: keep the
    // original start label and reconcile just the gap since the last emit, so 'steps' stays cumulative.
    // A different 'since' (or no active session) begins fresh at 'requested'.
    let chosenStart: Date
    let gapStart: Date
    if store.isActive, let persistedStart = store.sessionStart,
       datesMatch(persistedStart, requested) {
      chosenStart = persistedStart
      gapStart = store.lastEmittedAt ?? persistedStart
    } else {
      chosenStart = requested
      store.startFreshSession(requested)
      gapStart = requested
    }

    // Reset all stateQueue-owned state before reconcile/live chains can read it. Bumping the
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
    }

    registerForegroundObserver()

    // Bucketed reconcile (covers cold launch). Live updates begin automatically once this reconcile
    // (plus any overlapping foreground reconcile) drains, see reconcileChainDidFinish().
    reconcile(from: gapStart)
  }

  // stop() is a pause, not a terminate: it stops live updates and resets the in-memory state, but
  // does NOT clear the persisted session. A later start() with the same 'since' resumes the running
  // total, while a different 'since' overwrites it. The reset is driven by 'since' changing, so
  // there is no separate terminate path.
  @objc public func stop() {
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
    }
  }

  // MARK: - Foreground observer (warm-foreground reconcile)

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

  // MARK: - Bucketed reconcile + live updates

  // Snapshot the stateQueue-owned session start so the reconcile/live queries see a consistent value
  // regardless of which queue they run on.
  private func currentSessionStart() -> Date? {
    var start: Date?
    stateQueue.sync { start = sessionStartDate }
    return start
  }

  // Emit one cumulative event per top-of-hour across [gapStart, now], in increasing end-date order,
  // so the consumer can bucket each hour's delta accurately. Live emits are suppressed while one or
  // more reconcile chains are in flight, and the in-flight count makes overlapping reconciles safe.
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

    // Ordered endpoints: each top-of-hour strictly after effectiveStart and < now, then 'now'.
    var points: [Date] = []
    let calendar = Calendar.current
    var boundary = nextHourBoundary(after: effectiveStart, calendar: calendar)
    while boundary < now {
      points.append(boundary)
      // Step by a calendar hour (not a fixed 3600s) so boundaries stay on local top-of-hour across
      // DST transitions, including fractional-hour DST zones (e.g. Lord Howe Island).
      boundary = calendar.date(byAdding: .hour, value: 1, to: boundary) ?? boundary.addingTimeInterval(3600)
    }
    points.append(now)

    // Take a reconcile hold (suppresses live emits) before emitting any point, unless the session
    // was replaced between the snapshot above and here.
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

  // One reconcile chain finished. Clear its suppression hold, and when the last overlapping chain
  // drains, resume live updates (exactly once per session).
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
    // Top of the hour containing 'date'; the next boundary is +1 calendar hour (strictly after 'date').
    // Use the calendar (not a fixed 3600s) so this first boundary also stays correct across DST.
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
        // Reconcile emits are authoritative historical backfill, never cadence-capped (live: false).
        self.emitStep(data: data, endDate: endDate, generation: generation, live: false)
      }
      // Continue the chain regardless, preserving emission order.
      self.emitReconcilePoints(points, sessionStart: sessionStart, generation: generation, index: index + 1, completion: completion)
    }
  }

  // Start live pedometer updates exactly once per session. Decoupled from any single reconcile's
  // completion, so an overlapping or superseding reconcile cannot strand live startup.
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
      // Emit only for the current session and while no bucketed reconcile chain is in flight.
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
  // 'live' distinguishes the two callers. Reconcile points (live: false) are authoritative historical
  // backfill, accepted in full. Live updates (live: true) are capped at 'cadence': a reading may add
  // at most floor(elapsed * cadence) steps over the window since the previous accepted reading, and
  // the excess is dropped. The raw cursor and time reference advance on every accepted reading even
  // when nothing is accepted, so a trimmed burst is not "refunded" later (no time-banking).
  private func emitStep(data: CMPedometerData, endDate: Date, generation: Int, live: Bool) {
    var body: [String: Any]?
    stateQueue.sync {
      guard sessionGeneration == generation else { return } // stale session; drop

      let rawCumulative = data.numberOfSteps.intValue
      // Dedupe against the last RAW cumulative seen (reconcile-vs-live overlap). Monotonic because
      // every query/update reads cumulative-from-sessionStart.
      guard rawCumulative > lastRawCumulative else { return }
      let rawDelta = (lastRawCumulative < 0) ? rawCumulative : (rawCumulative - lastRawCumulative)

      let acceptedDelta: Int
      if live, cadence > Cadence.disabled, let lastEnd = lastSeenAt {
        let elapsed = endDate.timeIntervalSince(lastEnd)
        acceptedDelta = min(rawDelta, Cadence.maxSteps(over: elapsed, cadence: cadence))
      } else {
        // Disabled, reconcile (authoritative), or the first reading of the session: accept in full.
        acceptedDelta = rawDelta
      }

      // Advance the raw cursor + time reference for every accepted reading, even when acceptedDelta is
      // 0, so the next live window measures from here. This is what prevents banking dropped steps.
      lastRawCumulative = rawCumulative
      lastSeenAt = endDate

      // Whole delta was an over-cadence burst (or session start missing); nothing new to emit.
      guard acceptedDelta > 0, let start = sessionStartDate else { return }
      acceptedSteps += acceptedDelta
      body = StepEvent.build(steps: acceptedSteps, start: start, end: endDate, data: data)
    }

    guard let body = body else { return }
    store.saveLastEmittedAt(endDate)
    sink?.emitStep(body)
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
