// Minimal UIKit application used only by the Apple Simulator
// process-termination/relaunch CI proof (#94). It is not a distributable
// product and performs no networking, no real synchronization, and no
// production credentials -- see docs/apple/process-termination-proof.md for
// the full CI shape this app is built, installed, launched, killed (via
// `xcrun simctl terminate`), and relaunched under.
//
// This app deliberately omits Info.plist's `UIApplicationSceneManifest` key,
// so UIKit uses the legacy, scene-less application lifecycle: window setup
// happens directly in `application(_:didFinishLaunchingWithOptions:)` below,
// with no separate SceneDelegate. This keeps the whole app to one file.

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
        //   production write -- AppleCircuitBreakerProcessTerminationProof
        //   .openCircuitAndPersist expects to create the record from
        //   scratch (expectedVersion == null) and would otherwise report a
        //   conflict on a second call.
        // - The *second* launch (the genuine relaunch, after a real
        //   `xcrun simctl terminate`) re-drives the real
        //   `CircuitBreakerCoordinator` gate against that already-persisted
        //   record via `redriveGateAfterRelaunch` -- not just a raw-row
        //   read -- and writes its outcome to a second, separate result
        //   file the apple-gate-redrive-proof.yml CI job reads and asserts
        //   on, but ONLY when launched with the `--dataloom-gate-redrive`
        //   argument. `apple-validation.yml`'s own `apple-process-termination-proof`
        //   job launches this same app binary WITHOUT that argument, and
        //   its byte-diff requires the persisted state file to be
        //   unchanged across the kill/relaunch -- calling `recordSuccess`/
        //   `acquire` on the real gate can itself mutate persisted state
        //   (e.g. granting a probe bumps the generation), which would
        //   silently break that already-required check if run
        //   unconditionally on every second launch. Gating on the launch
        //   argument keeps that job's relaunch exactly as inert as before
        //   this change -- see `docs/apple/process-termination-proof.md`.
        let shouldRedriveGate = CommandLine.arguments.contains("--dataloom-gate-redrive")
        if AppleCircuitBreakerProcessTerminationProof.shared.readPersistedState(directoryPath: proofDirectory) == nil {
            _ = AppleCircuitBreakerProcessTerminationProof.shared.openCircuitAndPersist(directoryPath: proofDirectory)
        } else if shouldRedriveGate {
            let redrive = AppleCircuitBreakerProcessTerminationProof.shared.redriveGateAfterRelaunch(directoryPath: proofDirectory)
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
    /// plus `/Documents/CircuitProof` resolves to from outside this process
    /// -- see the CI script step in `.github/workflows/apple-validation.yml`.
    private static func proofDirectoryPath() -> String {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("CircuitProof", isDirectory: true).path
    }

    /// Serializes the real gate re-drive's three decisions (reject-before-
    /// deadline / probe-granted-at-deadline / recovery-after-success) to a
    /// plain tab-separated file alongside the circuit-state file, for
    /// `.github/workflows/apple-gate-redrive-proof.yml` to read directly
    /// from the Simulator's app-container filesystem, the same way the
    /// existing proof reads `dataloom-circuit-state-v1.tsv`. Field order:
    /// beforeDeadlineOutcome, beforeDeadlineRejectionReason,
    /// probeAtDeadlineOutcome, probeGeneration, recoveryOutcome.
    private static func writeGateRedriveResult(
        _ result: CircuitBreakerGateRedriveProofState,
        proofDirectory: String
    ) {
        let line = [
            result.beforeDeadlineOutcome,
            result.beforeDeadlineRejectionReason,
            result.probeAtDeadlineOutcome,
            String(result.probeGeneration),
            result.recoveryOutcome,
        ].joined(separator: "\t")
        let path = proofDirectory + "/" + gateRedriveResultFileName
        try? line.write(toFile: path, atomically: true, encoding: .utf8)
    }
}

/// Shared with `.github/workflows/apple-gate-redrive-proof.yml`, which
/// resolves this exact file name beneath the same `CircuitProof` directory
/// `apple-validation.yml`'s own job already uses for `dataloom-circuit-state-v1.tsv`.
let gateRedriveResultFileName = "dataloom-circuit-gate-redrive-v1.tsv"
