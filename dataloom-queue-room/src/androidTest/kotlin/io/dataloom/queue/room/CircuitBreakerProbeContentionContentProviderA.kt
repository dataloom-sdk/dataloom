package io.dataloom.queue.room

/**
 * Racer A's [android.content.ContentProvider], declared once in
 * `src/androidTest/AndroidManifest.xml` under
 * [CircuitBreakerProbeContentionContract.AUTHORITY_A] /
 * [CircuitBreakerProbeContentionContract.PROCESS_SUFFIX_A].
 *
 * A genuinely distinct class from [CircuitBreakerProbeContentionContentProviderB]
 * -- not the same class declared twice -- because Android's
 * `PackageManagerService` addresses every component by `ComponentName`
 * (package + class name) and only supports one live registration per
 * `ComponentName`, even though the manifest XML schema and AAPT2's binary
 * manifest compilation both accept a class declared more than once without
 * complaint. See [CircuitBreakerProbeContentionContentProviderBase]'s class
 * doc for the full real-device failure this fixes.
 *
 * All actual proof logic lives in [CircuitBreakerProbeContentionContentProviderBase];
 * this class exists only to give Racer A its own distinct, independently
 * resolvable `ComponentName`.
 */
public class CircuitBreakerProbeContentionContentProviderA :
    CircuitBreakerProbeContentionContentProviderBase()
