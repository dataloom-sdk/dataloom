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
        // process). Only the *first* launch against a given container may
        // perform the real production write --
        // AppleRetryBudgetProcessTerminationProof.writeRetryBudgetAndPersist
        // enqueues a fresh entry and would otherwise fail with a duplicate-
        // entry error on the second launch. Unlike the circuit-breaker proof
        // app's own `readPersistedState` guard, this checks file existence
        // rather than performing a real read: AppleFileQueueProvider's only
        // public read path (`acquire`) mutates the persisted snapshot by
        // assigning a lease, which would make the CI proof's own outside
        // byte-for-byte file diff observe a spurious change across the
        // kill/relaunch even though the underlying retry-budget fields never
        // changed. See AppleRetryBudgetProcessTerminationProof's own KDoc.
        if !AppleRetryBudgetProcessTerminationProof.shared.hasPersistedRetryBudgetState(directoryPath: proofDirectory) {
            _ = AppleRetryBudgetProcessTerminationProof.shared.writeRetryBudgetAndPersist(directoryPath: proofDirectory)
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
}
