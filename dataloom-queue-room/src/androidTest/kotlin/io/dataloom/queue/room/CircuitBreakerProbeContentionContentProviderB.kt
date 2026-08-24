package io.dataloom.queue.room

/**
 * Racer B's [android.content.ContentProvider], declared once in
 * `src/androidTest/AndroidManifest.xml` under
 * [CircuitBreakerProbeContentionContract.AUTHORITY_B] /
 * [CircuitBreakerProbeContentionContract.PROCESS_SUFFIX_B].
 *
 * A genuinely distinct class from [CircuitBreakerProbeContentionContentProviderA]
 * -- not the same class declared twice -- because Android's
 * `PackageManagerService` addresses every component by `ComponentName`
 * (package + class name) and only supports one live registration per
 * `ComponentName`, even though the manifest XML schema and AAPT2's binary
 * manifest compilation both accept a class declared more than once without
 * complaint. See [CircuitBreakerProbeContentionContentProviderBase]'s class
 * doc for the full real-device failure this fixes.
 *
 * All actual proof logic lives in [CircuitBreakerProbeContentionContentProviderBase];
 * this class exists only to give Racer B its own distinct, independently
 * resolvable `ComponentName`.
 */
public class CircuitBreakerProbeContentionContentProviderB :
    CircuitBreakerProbeContentionContentProviderBase()
