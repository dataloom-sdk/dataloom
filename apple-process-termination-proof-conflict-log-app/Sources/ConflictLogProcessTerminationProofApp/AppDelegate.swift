// Minimal UIKit application used only by the Apple Simulator
// process-termination/relaunch CI proof for `#95`'s two durable conflict-log
// domains (DurableUnresolvedConflictLog / DurableResolvedConflictDecisionLog).
// It is not a distributable product and performs no networking, no real
// synchronization, and no production credentials -- see
// docs/apple/process-termination-proof.md for the full CI shape this app is
// built, installed, launched, killed (via `xcrun simctl terminate`), and
// relaunched under.
//
// This is a genuinely separate app project from ProcessTerminationProofApp
// (circuit-breaker, `#94`) and RetryBudgetProcessTerminationProofApp (retry
// budget, `#94`), matching round 31's own "separate app per genuinely
// distinct on-disk store" precedent. Unlike those two, this single app/target
// is reused, unmodified, across BOTH of `#95`'s conflict-log domains -- one
// Xcode project, one Swift file, one bundle id
// (io.dataloom.processterminationproof.conflictlog.app) -- rather than a
// third and fourth per-domain app target. Mirrors the reasoning
// apple-process-contention-proof-app's own AppDelegate.swift already
// documents for reusing ProcessContentionProofAppC/AppD, unmodified, across
// the same two domains via a launch-time environment variable: the two
// domains share the identical AppleFileDurableStateStore persistence path and
// only differ in which Kotlin proof object is driven and which on-disk state
// file results, so a source-level "unresolved" vs. "resolved" branch baked in
// at build time would only duplicate this file for no benefit. The CI job's
// own matrix entry sets DATALOOM_DOMAIN ("UNRESOLVED_CONFLICT" or
// "RESOLVED_DECISION") via `simctl launch`'s documented `SIMCTL_CHILD_*`
// environment-forwarding convention before each launch; this file reads it at
// runtime.
//
// Unlike apple-process-contention-proof-app's own AppDelegate.swift, this is
// a single-process kill/relaunch proof, not a two-process race: there is no
// go-signal wait, no background dispatch queue, and no result file for a host
// script to poll -- the real production write happens synchronously on
// launch, exactly like ProcessTerminationProofApp's and
// RetryBudgetProcessTerminationProofApp's own AppDelegate.swift, and the host
// CI script reads the persisted state file directly from the Simulator's
// app-container filesystem, from outside this process, before the kill and
// after the relaunch.
//
// This app deliberately omits Info.plist's `UIApplicationSceneManifest` key,
// so UIKit uses the legacy, scene-less application lifecycle: window setup
// happens directly in `application(_:didFinishLaunchingWithOptions:)` below,
// with no separate SceneDelegate. This keeps the whole app to one file,
// mirroring ProcessTerminationProofApp's own AppDelegate.swift exactly.

import UIKit
import DataLoomProcessTerminationProof

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        guard let domain = ProcessInfo.processInfo.environment["DATALOOM_DOMAIN"] else {
            fatalError(
                "ConflictLogProcessTerminationProofApp requires a DATALOOM_DOMAIN " +
                "environment variable (\"UNRESOLVED_CONFLICT\" or \"RESOLVED_DECISION\") -- " +
                "see the 'apple-conflict-log-process-termination-proof' job in " +
                ".github/workflows/apple-validation.yml."
            )
        }

        let proofDirectory = Self.proofDirectoryPath()

        // Idempotent by design: this app is launched twice by the CI proof
        // (once before `simctl terminate`, once after, as a genuinely new OS
        // process). Only the *first* launch against a given container may
        // perform the real production write -- both
        // AppleUnresolvedConflictLogProcessTerminationProof.recordAndPersist
        // and AppleResolvedConflictDecisionLogProcessTerminationProof.recordAndPersist
        // expect to insert the record from scratch and fail loudly if called
        // again against a directory that already has one recorded (see their
        // own KDoc) -- the same idempotency-guard shape
        // ProcessTerminationProofApp's own AppDelegate already established
        // for AppleCircuitBreakerProcessTerminationProof.
        switch domain {
        case "UNRESOLVED_CONFLICT":
            if AppleUnresolvedConflictLogProcessTerminationProof.shared.readPersistedState(directoryPath: proofDirectory) == nil {
                _ = AppleUnresolvedConflictLogProcessTerminationProof.shared.recordAndPersist(directoryPath: proofDirectory)
            }
        case "RESOLVED_DECISION":
            if AppleResolvedConflictDecisionLogProcessTerminationProof.shared.readPersistedState(directoryPath: proofDirectory) == nil {
                _ = AppleResolvedConflictDecisionLogProcessTerminationProof.shared.recordAndPersist(directoryPath: proofDirectory)
            }
        default:
            fatalError(
                "Unrecognized DATALOOM_DOMAIN value \"\(domain)\" -- expected \"UNRESOLVED_CONFLICT\" or " +
                "\"RESOLVED_DECISION\"."
            )
        }

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.backgroundColor = .white
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        return true
    }

    /// The exact directory
    /// `xcrun simctl get_app_container <device> <bundle-id> data`
    /// plus `/Documents/ConflictLogProof` resolves to from outside this
    /// process -- see the CI job step in
    /// `.github/workflows/apple-validation.yml`. Shared by both domains
    /// (their state-file names already differ --
    /// AppleUnresolvedConflictLogProcessTerminationProof.STATE_FILE_NAME vs.
    /// AppleResolvedConflictDecisionLogProcessTerminationProof.STATE_FILE_NAME
    /// -- so nothing collides).
    private static func proofDirectoryPath() -> String {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("ConflictLogProof", isDirectory: true).path
    }
}
