// Minimal UIKit application used only by the Apple Simulator cross-process
// contention CI proofs (#94/#95). It is not a distributable product and
// performs no networking and no production credentials -- see
// docs/apple/process-contention-proof.md for the full CI shape this app is
// built, installed, and raced under.
//
// This single Swift file, and the single shared Info.plist beside it, are
// used by all FOUR app targets this project declares --
// ProcessContentionProofAppA/AppB (the original circuit-breaker domain) and
// ProcessContentionProofAppC/AppD (added for #95's two conflict-log
// domains). The four targets differ only in their own
// PRODUCT_BUNDLE_IDENTIFIER build setting (io.dataloom.processcontentionproof.appa/
// ...appb/...appc/...appd) -- there is no source-level "A" vs "B" vs "C" vs
// "D" branch baked into this file at build time. Instead, the CI script
// launches each installed app with distinct DATALOOM_ROLE/DATALOOM_DOMAIN
// environment variables (via `simctl launch`'s own documented
// `SIMCTL_CHILD_*` environment-forwarding convention), and this file reads
// those at runtime. This keeps the whole family of two-process races to one
// Xcode project, one Swift file, and one Kotlin/Native module, rather than
// forking the proof logic into per-domain copies that could drift --
// AppC/AppD are reused, unmodified, across both of #95's conflict-log
// domains by varying DATALOOM_DOMAIN per CI job/matrix entry rather than by
// declaring yet more app targets.
//
// DATALOOM_DOMAIN selects which real production path this launch drives:
// "CIRCUIT_BREAKER" (the default when unset, preserving AppA/AppB's original
// behavior exactly), "UNRESOLVED_CONFLICT", "RESOLVED_DECISION", or
// "RETRY_BUDGET_LEASE" (#94's own retry-budget durable structure, launched
// on AppA/AppB like CIRCUIT_BREAKER, not AppC/AppD). Only CIRCUIT_BREAKER and
// RETRY_BUDGET_LEASE have an asymmetric opener/seeder("A")/racer("B") shape;
// the two conflict-log domains are symmetric -- both racing processes warm up
// and then race identically, mirroring their own Android precedents
// (UnresolvedConflictContentionContentProviderBase/
// ResolvedConflictDecisionContentionContentProviderBase) exactly. See
// AppleUnresolvedConflictLogContentionProof's/
// AppleResolvedConflictDecisionLogContentionProof's own KDoc
// (apple-process-contention-proof/src/iosMain/...) for why. RETRY_BUDGET_LEASE
// has no Android contention-specific precedent of its own to mirror at all --
// see AppleRetryBudgetLeaseContentionProof's own KDoc for how its race shape
// was designed directly from AppleFileQueueProvider.acquire's real
// implementation instead.
//
// This app deliberately omits Info.plist's `UIApplicationSceneManifest` key,
// matching apple-process-termination-proof-app's own precedent: UIKit uses
// the legacy, scene-less application lifecycle, with window setup directly
// in `application(_:didFinishLaunchingWithOptions:)` below.

import UIKit
import DataLoomProcessContentionProof

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let environment = ProcessInfo.processInfo.environment
        guard
            let sharedDirectory = environment["DATALOOM_SHARED_DIR"],
            let readyMarkerPath = environment["DATALOOM_READY_MARKER"],
            let goSignalPath = environment["DATALOOM_GO_SIGNAL"],
            let resultFilePath = environment["DATALOOM_RESULT_FILE"]
        else {
            fatalError(
                "ProcessContentionProofApp requires DATALOOM_SHARED_DIR, " +
                "DATALOOM_READY_MARKER, DATALOOM_GO_SIGNAL, and " +
                "DATALOOM_RESULT_FILE environment variables -- see the " +
                "'apple-process-contention-proof' job in " +
                ".github/workflows/apple-validation.yml."
            )
        }
        // Defaults to "B" (the non-opening racer) rather than failing
        // loudly, since only DATALOOM_ROLE == "A" changes behavior at all;
        // an absent/unexpected value falls back to the strictly narrower
        // "B" path (touch the ready marker, then race) instead of silently
        // attempting to reopen an already-open circuit. Only consulted when
        // DATALOOM_DOMAIN is "CIRCUIT_BREAKER" (or absent) -- the two
        // conflict-log domains below have no role asymmetry of their own.
        let role = environment["DATALOOM_ROLE"] ?? "B"

        // Defaults to "CIRCUIT_BREAKER", preserving AppA/AppB's original,
        // already-macOS-CI-proven behavior byte-for-byte when this variable
        // is absent (see docs/apple/process-contention-proof.md's "Update:
        // first real macOS CI run" section for that proof). AppC/AppD are
        // launched with this set explicitly by the CI job/matrix entry
        // driving #95's conflict-log domains.
        let domain = environment["DATALOOM_DOMAIN"] ?? "CIRCUIT_BREAKER"

        // Launching the second proof app backgrounds this one; without an
        // assertion iOS may suspend it mid-race, so it never reports a result.
        backgroundTask = application.beginBackgroundTask(withName: "dataloom-contention-proof") { [weak self] in
            guard let self = self, self.backgroundTask != .invalid else { return }
            application.endBackgroundTask(self.backgroundTask)
            self.backgroundTask = .invalid
        }

        // Runs on a background queue, never the main thread: this call
        // blocks its calling thread (via Kotlin's own `runBlocking`) for up
        // to DEFAULT_MAX_POLL_ATTEMPTS * 5ms while it busy-polls for the
        // shared "go" signal, and this app's UIWindow must still stand up
        // immediately so `xcrun simctl launch` and the app's own process
        // reach a genuinely running, launched state without waiting on the
        // race to finish first.
        DispatchQueue.global(qos: .userInitiated).async {
            let line: String
            switch domain {
            case "UNRESOLVED_CONFLICT":
                // Symmetric racers -- both processes warm up (harmless
                // read), then race. No role branch, unlike CIRCUIT_BREAKER
                // below; see AppleUnresolvedConflictLogContentionProof's own
                // KDoc for why.
                AppleUnresolvedConflictLogContentionProof.shared.warmUpAndSignalReady(
                    directoryPath: sharedDirectory,
                    readyMarkerPath: readyMarkerPath
                )
                let result = AppleUnresolvedConflictLogContentionProof.shared.waitForGoSignalThenAttemptRecord(
                    directoryPath: sharedDirectory,
                    goSignalPath: goSignalPath,
                    maxPollAttempts: 12000
                )
                line = "\(result.outcome)\t\(result.persistedVersion)\n"

            case "RESOLVED_DECISION":
                AppleResolvedConflictDecisionLogContentionProof.shared.warmUpAndSignalReady(
                    directoryPath: sharedDirectory,
                    readyMarkerPath: readyMarkerPath
                )
                let result = AppleResolvedConflictDecisionLogContentionProof.shared.waitForGoSignalThenAttemptRecord(
                    directoryPath: sharedDirectory,
                    goSignalPath: goSignalPath,
                    maxPollAttempts: 12000
                )
                line = "\(result.outcome)\t\(result.persistedVersion)\n"

            case "RETRY_BUDGET_LEASE":
                // Asymmetric like CIRCUIT_BREAKER above: role "A" performs
                // the one-time real enqueue/acquire/reschedule setup that
                // seeds the single RETRY_WAITING entry with real persisted
                // retry-budget state; role "B" (the non-seeding racer) just
                // signals its own ready marker directly, identically to
                // CIRCUIT_BREAKER's own non-opening racer below. See
                // AppleRetryBudgetLeaseContentionProof's own KDoc for why
                // this domain reuses that asymmetric shape rather than the
                // conflict-log domains' symmetric one.
                if role == "A" {
                    AppleRetryBudgetLeaseContentionProof.shared.seedRetryWaitingEntryAndSignalReady(
                        directoryPath: sharedDirectory,
                        readyMarkerPath: readyMarkerPath
                    )
                } else {
                    FileManager.default.createFile(atPath: readyMarkerPath, contents: nil)
                }

                // Each racing process uses its own distinct lease/consumer
                // identity (derived from its own role) so a winning
                // acquisition's lease unambiguously identifies which process
                // won -- see AppleRetryBudgetLeaseContentionProof's own
                // waitForGoSignalThenAttemptAcquire KDoc.
                let result = AppleRetryBudgetLeaseContentionProof.shared.waitForGoSignalThenAttemptAcquire(
                    directoryPath: sharedDirectory,
                    goSignalPath: goSignalPath,
                    racerLeaseId: "lease-race-\(role.lowercased())",
                    racerConsumerId: "consumer-race-\(role.lowercased())",
                    maxPollAttempts: 12000
                )
                line = "\(result.outcome)\t\(result.retryAttemptNumber)\t" +
                    "\(result.retryWindowStartedAtEpochMillis)\t" +
                    "\(result.retryLastEvaluatedAtEpochMillis)\t" +
                    "\(result.retryCumulativeDelayMillis)\n"

            default: // "CIRCUIT_BREAKER" -- AppA/AppB's original, unmodified behavior.
                if role == "A" {
                    AppleCircuitBreakerProbeContentionProof.shared.openCircuitAndSignalReady(
                        directoryPath: sharedDirectory,
                        readyMarkerPath: readyMarkerPath
                    )
                } else {
                    FileManager.default.createFile(atPath: readyMarkerPath, contents: nil)
                }

                // 12,000 * 5ms == 60s, matching
                // AppleCircuitBreakerProbeContentionProof's own Kotlin-side
                // DEFAULT_MAX_POLL_ATTEMPTS -- Kotlin/Native's Objective-C
                // header generation does not expose Kotlin default parameter
                // values to Swift, so this value must be passed explicitly
                // here.
                let result = AppleCircuitBreakerProbeContentionProof.shared.waitForGoSignalThenAttemptProbe(
                    directoryPath: sharedDirectory,
                    goSignalPath: goSignalPath,
                    maxPollAttempts: 12000
                )
                line = "\(result.outcome)\t\(result.rejectionReason)\t\(result.probeGeneration)\n"
            }

            // The host CI script reads this file directly from outside this
            // process (the same "host reads a file the app wrote, not a
            // claim the app makes via IPC" pattern
            // apple-process-termination-proof-app's own AppDelegate
            // established) -- this report, not an in-process assertion, is
            // what each CI job's own assertions are built on. The real
            // mutual-exclusion enforcement is each domain's own
            // AppleFileCircuitBreakerStateStore/AppleFileDurableStateStore
            // flock-based compare-and-set inside the calls above, not this
            // file write.
            try? line.write(toFile: resultFilePath, atomically: true, encoding: .utf8)

            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.backgroundTask != .invalid else { return }
                application.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = .invalid
            }
        }

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.backgroundColor = .white
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        return true
    }
}
