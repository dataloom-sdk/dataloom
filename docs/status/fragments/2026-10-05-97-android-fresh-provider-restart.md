# Fragment: gate #97, Android fresh-provider restart proof (2026-10-05)

## (a) Proposed "Recently shipped" row

| 2026-10-05 | `#97` Android restart proof now covers the file provider too. With `#481` (`FileAssetProvider` rebuilds its committed index from disk), `AndroidReferenceConsumerAssetTransferInstrumentedTest` no longer reuses one provider across the restart: after the first DataLoom and its Room database are shut down, it builds a FRESH `FileAssetProvider` over the same directory plus a fresh Room session store, reads the session back (`COMPLETED`, same revision and digest), and downloads through a second DataLoom into a `FileAssetSink`, byte-identical. **Verified by execution:** `DATALOOM_ANDROID_BUILD=true ./gradlew :runtime-android-reference-consumer:connectedDebugAndroidTest` on a local `Pixel_8_Pro` AVD (Android 16): JUnit XML `tests="4" failures="0" errors="0" skipped="0"`; `lintDebug` exit 0 (0 errors, 1 pre-existing GradleDependency warning about compileSdk 36). Revert-and-observe: pointing the fresh provider at an empty directory made the test fail (`tests="4" failures="1"`, download returned `NotStarted`); restored, `failures="0"`. `@RequiresApi(26)` and `@SdkSuppress(minSdkVersion = 26)` kept; no lint suppressions. **Not verified:** real network transport on Android, interrupted-transfer resume/quota/cancel/cleanup on the emulator, Android API 21-25 (still an unresolved product gap: `java.nio.file` needs API 26, `minSdk` is 21), a physical device, iOS execution. | `#97` |

## (b) Gate row percentage

`#97`: unchanged. It strengthens the Android leg of AC-FUNC-005 but does not close it.

## (c) "Still pending" text

Remove the `FileAssetProvider` committed-index-recovery item from the AC-FUNC-005 remainder for Android. Remaining: iOS execution, Android through a real transport, on-device interrupted resume/quota/cancel/cleanup, and API 21-25.
