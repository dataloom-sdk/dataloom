# Apple (macOS) CI is opt-in per pull request

GitHub-hosted macOS minutes are the most expensive CI resource this project
uses and the budget is limited. One full pull-request run of the Apple
workflows costs roughly 130 billed macOS minutes (about $8 at the 3-core
rate of $0.062 per minute), because `apple-validation.yml` and
`apple-gate-redrive-proof.yml` start nine macOS jobs per run.

## The rule

The macOS jobs run only when one of these holds:

- the workflow is started manually (`workflow_dispatch`), or
- the pull request carries the **`run-apple-ci`** label.

Without the label the jobs are *skipped* (a skipped job is not billed). Adding
the label starts them, because both workflows listen for the `labeled` event.
Pushes to `main` no longer start them either.

## When to add the label

Add it when you need real macOS/iOS-Simulator evidence and nothing cheaper
gives it: a change to Apple-specific source (`*/src/iosMain`, `*/src/iosTest`,
`apple-*` modules/apps, `AppleFile*` stores), to a proof app or Xcode project,
or to the Apple workflows themselves. Do not add it for documentation, JVM,
Android, or pure-common changes that cross-compile cleanly.

Before adding the label, be sure the change is right, because every further
push while the label is present re-runs all nine jobs:

1. Cross-compile every changed Kotlin/Native module locally, per target:
   `./gradlew -Pdataloom.appleKlibCrossCompile=true :<module>:compileTestKotlinIosSimulatorArm64`
   (and `IosArm64`, `IosX64`). This catches the compile errors that otherwise
   cost a full macOS run (a JVM-only API in `commonTest` is the usual one).
2. Run the JVM/Android tests and `checkKotlinAbi` locally.
3. Push once, then add the label once. Remove the label afterwards if more
   pushes are expected.

Adding an unrelated label to a PR that already has `run-apple-ci` re-runs the
macOS jobs; avoid it.

## What this does not change

The Apple jobs are unchanged apart from the condition. A change to Apple code
that merges without the label has had no real macOS execution, so its tests
stay "cross-compiled only" in the dashboard until a labelled run or a manual
dispatch has executed them.
