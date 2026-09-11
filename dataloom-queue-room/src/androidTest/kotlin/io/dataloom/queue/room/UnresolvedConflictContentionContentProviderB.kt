package io.dataloom.queue.room

/**
 * Racer B's [android.content.ContentProvider], declared once in
 * `src/androidTest/AndroidManifest.xml` under
 * [UnresolvedConflictContentionContract.AUTHORITY_B] /
 * [UnresolvedConflictContentionContract.PROCESS_SUFFIX_B].
 *
 * A genuinely distinct class from
 * [UnresolvedConflictContentionContentProviderA] -- not the same class
 * declared twice -- because Android's `PackageManagerService` addresses every
 * component by `ComponentName` (package + class name) and only supports one
 * live registration per `ComponentName` at runtime, even though the manifest
 * XML schema and AAPT2's binary manifest compilation both accept a class
 * declared more than once without complaint. See
 * [CircuitBreakerProbeContentionContentProviderBase]'s class doc for the full
 * real-device `Unknown authority` failure this shape avoids.
 *
 * All actual proof logic lives in
 * [UnresolvedConflictContentionContentProviderBase]; this class exists only
 * to give Racer B its own distinct, independently resolvable `ComponentName`.
 */
public class UnresolvedConflictContentionContentProviderB :
    UnresolvedConflictContentionContentProviderBase()
