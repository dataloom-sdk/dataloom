// Minimal UIKit application used only by the Apple Simulator
// process-termination/relaunch CI proof for durable retry-budget state
// (#94's extension of the circuit-breaker proof to a second, genuinely
// separate durable structure). It is not a distributable product and
// performs no networking, no real synchronization, and no production
// credentials -- see docs/apple/process-termination-proof.md for the full CI
// shape this app is built, installed, launched, killed (via
// `xcrun simctl terminate`), and relaunched under.
//
// This is a deliberately separate app target/project from
// ProcessTerminationProofApp (the circuit-breaker proof's own app), rather
// than one app performing both proofs on a single launch: the two proofs
// persist to genuinely distinct on-disk snapshot files
// (`dataloom-circuit-state-v1.tsv` vs `dataloom-queue-state-v1.tsv`), so
// nothing is shared by combining them into one process, and keeping them
// separate means this new, never-run-before proof cannot regress the
// already-proven circuit-breaker proof app if something about this one is
// wrong -- the two are built, installed, launched, and verified as fully
// independent CI job steps. See docs/apple/process-termination-proof.md for
// the full reasoning.
//
// This app deliberately omits Info.plist's `UIApplicationSceneManifest` key,
// so UIKit uses the legacy, scene-less application lifecycle: window setup
// happens directly in `application(_:didFinishLaunchingWithOptions:)` below,
// with no separate SceneDelegate. This keeps the whole app to one file,
// mirroring ProcessTerminationProofApp's own AppDelegate.swift exactly.

import Foundation
import UIKit
import DataLoomProcessTerminationProof

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {

    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let proofDirectory = Self.proofDirectoryPath()

        // Idempotent by design: this app is launched twice by the CI proof
        // (once before `simctl terminate`, once after, as a genuinely new OS
        // process).
        //
        // - The *first* launch against a given container performs the real
        //   production write -- AppleRetryBudgetProcessTerminationProof
        //   .writeRetryBudgetAndPersist enqueues a fresh entry and would
        //   otherwise fail with a duplicate-entry error on a second call.
        //   Unlike the circuit-breaker proof app's own `readPersistedState`
        //   guard, this checks file existence rather than performing a real
        //   read: AppleFileQueueProvider's only public read path (`acquire`)
        //   mutates the persisted snapshot by assigning a lease, which would
        //   make this same check observe a spurious change on its own next
        //   call. See AppleRetryBudgetProcessTerminationProof's own KDoc.
        // - The *second* launch (the genuine relaunch, after a real
        //   `xcrun simctl terminate`) re-drives the real
        //   `AppleFileQueueProvider.acquire` gate against that
        //   already-persisted entry via `redriveAcquireGateAfterRelaunch`
        //   -- not just a raw-row read -- and writes its outcome, including
        //   the acquired entry's `availableAt`, to a second, separate
        //   result file the apple-gate-redrive-proof.yml CI job reads and
        //   asserts on, but ONLY when launched with the
        //   `--dataloom-gate-redrive` argument. `apple-validation.yml`'s
        //   own `apple-process-termination-proof-retry-budget` job
        //   launches this same app binary WITHOUT that argument, and its
        //   byte-diff requires the persisted queue-state file to be
        //   unchanged across the kill/relaunch -- `acquire` can itself
        //   mutate the persisted snapshot (assigning a lease), which would
        //   silently break that already-required check if run
        //   unconditionally on every second launch. Gating on the launch
        //   argument keeps that job's relaunch exactly as inert as before
        //   this change.
        let shouldRedriveGate = CommandLine.arguments.contains("--dataloom-gate-redrive")
        if !AppleRetryBudgetProcessTerminationProof.shared.hasPersistedRetryBudgetState(directoryPath: proofDirectory) {
            _ = AppleRetryBudgetProcessTerminationProof.shared.writeRetryBudgetAndPersist(directoryPath: proofDirectory)
        } else if shouldRedriveGate {
            let redrive = AppleRetryBudgetProcessTerminationProof.shared.redriveAcquireGateAfterRelaunch(directoryPath: proofDirectory)
            Self.writeGateRedriveResult(redrive, proofDirectory: proofDirectory)
        }

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.backgroundColor = .white
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        return true
    }

    /// The exact directory
    /// `xcrun simctl get_app_container <device> <bundle-id> data`
    /// plus `/Documents/RetryBudgetProof` resolves to from outside this
    /// process -- see the CI script step in
    /// `.github/workflows/apple-validation.yml`. Deliberately a different
    /// leaf directory name than ProcessTerminationProofApp's own
    /// `CircuitProof` -- this is a separate app/bundle with its own
    /// container, so a distinct name is not strictly required for
    /// isolation, but keeps the two proofs' on-disk paths self-describing.
    private static func proofDirectoryPath() -> String {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("RetryBudgetProof", isDirectory: true).path
    }

    /// Serializes the real acquire-gate re-drive's outcome to a plain
    /// tab-separated file alongside the queue-state file, for
    /// `.github/workflows/apple-gate-redrive-proof.yml` to read directly
    /// from the Simulator's app-container filesystem. Field order:
    /// retryAttemptNumber, retryWindowStartedAtEpochMillis,
    /// retryLastEvaluatedAtEpochMillis, retryCumulativeDelayMillis,
    /// availableAtEpochMillis.
    private static func writeGateRedriveResult(
        _ result: RetryBudgetProcessTerminationProofState,
        proofDirectory: String
    ) {
        let line = [
            String(result.retryAttemptNumber),
            String(result.retryWindowStartedAtEpochMillis),
            String(result.retryLastEvaluatedAtEpochMillis),
            String(result.retryCumulativeDelayMillis),
            String(result.availableAtEpochMillis),
        ].joined(separator: "\t")
        let path = proofDirectory + "/" + gateRedriveResultFileName
        try? line.write(toFile: path, atomically: true, encoding: .utf8)
    }
}

/// Shared with `.github/workflows/apple-gate-redrive-proof.yml`, which
/// resolves this exact file name beneath the same `RetryBudgetProof`
/// directory `apple-validation.yml`'s own job already uses for
/// `dataloom-queue-state-v1.tsv`.
let gateRedriveResultFileName = "dataloom-retry-budget-gate-redrive-v1.tsv"
