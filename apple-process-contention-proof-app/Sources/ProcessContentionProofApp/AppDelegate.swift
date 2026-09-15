// Minimal UIKit application used only by the Apple Simulator cross-process
// probe-contention CI proof (#94/#95). It is not a distributable product and
// performs no networking and no production credentials -- see
// docs/apple/process-contention-proof.md for the full CI shape this app is
// built, installed, and raced under.
//
// This single Swift file, and the single shared Info.plist beside it, are
// used by BOTH app targets this project declares --
// ProcessContentionProofAppA and ProcessContentionProofAppB. The two targets
// differ only in their own PRODUCT_BUNDLE_IDENTIFIER build setting
// (io.dataloom.processcontentionproof.appa / ...appb) -- there is no
// source-level "A" vs "B" branch baked into this file at build time. Instead,
// the CI script launches each installed app with a distinct DATALOOM_ROLE
// environment variable (via `simctl launch`'s own documented
// `SIMCTL_CHILD_*` environment-forwarding convention), and this file reads
// that at runtime. This keeps the whole two-process race to one Xcode
// project, one Swift file, and one Kotlin/Native module, rather than forking
// the proof logic into two copies that could drift.
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
        // attempting to reopen an already-open circuit.
        let role = environment["DATALOOM_ROLE"] ?? "B"

        // Runs on a background queue, never the main thread: this call
        // blocks its calling thread (via Kotlin's own `runBlocking`) for up
        // to DEFAULT_MAX_POLL_ATTEMPTS * 5ms while it busy-polls for the
        // shared "go" signal, and this app's UIWindow must still stand up
        // immediately so `xcrun simctl launch` and the app's own process
        // reach a genuinely running, launched state without waiting on the
        // race to finish first.
        DispatchQueue.global(qos: .userInitiated).async {
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
            // DEFAULT_MAX_POLL_ATTEMPTS -- Kotlin/Native's Objective-C header
            // generation does not expose Kotlin default parameter values to
            // Swift, so this value must be passed explicitly here.
            let result = AppleCircuitBreakerProbeContentionProof.shared.waitForGoSignalThenAttemptProbe(
                directoryPath: sharedDirectory,
                goSignalPath: goSignalPath,
                maxPollAttempts: 12000
            )

            // The host CI script reads this file directly from outside this
            // process (the same "host reads a file the app wrote, not a
            // claim the app makes via IPC" pattern
            // apple-process-termination-proof-app's own AppDelegate
            // established) -- this report, not an in-process assertion, is
            // what the CI job's own assertions are built on. The real
            // mutual-exclusion enforcement is
            // AppleFileCircuitBreakerStateStore's flock-based
            // compare-and-set inside the call above, not this file write.
            let line = "\(result.outcome)\t\(result.rejectionReason)\t\(result.probeGeneration)\n"
            try? line.write(toFile: resultFilePath, atomically: true, encoding: .utf8)
        }

        window = UIWindow(frame: UIScreen.main.bounds)
        window?.backgroundColor = .white
        window?.rootViewController = UIViewController()
        window?.makeKeyAndVisible()
        return true
    }
}
