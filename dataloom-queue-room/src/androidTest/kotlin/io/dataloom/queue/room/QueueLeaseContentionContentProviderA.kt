package io.dataloom.queue.room

/**
 * Racer A's [android.content.ContentProvider], declared once in
 * `src/androidTest/AndroidManifest.xml` under
 * [QueueLeaseContentionContract.AUTHORITY_A] /
 * [QueueLeaseContentionContract.PROCESS_SUFFIX_A]. A genuinely distinct class
 * from [QueueLeaseContentionContentProviderB]; see
 * [CircuitBreakerProbeContentionContentProviderBase] for why the same class
 * cannot be declared twice.
 */
public class QueueLeaseContentionContentProviderA : QueueLeaseContentionContentProviderBase()
