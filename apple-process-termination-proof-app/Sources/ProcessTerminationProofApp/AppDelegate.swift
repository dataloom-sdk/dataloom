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
        // perform the real production write -- AppleCircuitBreakerProcessTerminationProof
        // .openCircuitAndPersist expects to create the record from scratch
        // (expectedVersion == null) and would otherwise report a conflict on
        // the second launch. The CI proof's own verification -- comparing the
        // circuit-breaker state file's content, read directly from the
        // Simulator's app-container filesystem from *outside* this process,
        // before the kill and after the relaunch -- does not depend on this
        // app doing anything differently between the two launches.
        if AppleCircuitBreakerProcessTerminationProof.shared.readPersistedState(directoryPath: proofDirectory) == nil {
            _ = AppleCircuitBreakerProcessTerminationProof.shared.openCircuitAndPersist(directoryPath: proofDirectory)
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
}
