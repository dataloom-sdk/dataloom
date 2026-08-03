package io.dataloom.consumer

import io.dataloom.api.operational.OperationalEnvelopeDecodeResult
import io.dataloom.api.operational.OperationalEnvelopeWireCodec
import io.dataloom.api.operational.OperationalEventEnvelope
import io.dataloom.api.operational.OperationalSchemaVersion
import io.dataloom.runtime.operational.OperationalEnvelopeUpcastResult
import io.dataloom.runtime.operational.OperationalEnvelopeUpcaster
import io.dataloom.runtime.operational.OperationalEnvelopeUpcasterRegistry

/** Published-style compilation coverage for the operational wire surface. */
public object OperationalWireExternalConsumerProbe {
    public fun roundTrip(
        envelope: OperationalEventEnvelope,
    ): OperationalEnvelopeDecodeResult = OperationalEnvelopeWireCodec.decode(
        OperationalEnvelopeWireCodec.encode(envelope),
    )

    public fun upcast(
        envelope: OperationalEventEnvelope,
        targetSchemaVersion: OperationalSchemaVersion,
        vararg upcasters: OperationalEnvelopeUpcaster,
    ): OperationalEnvelopeUpcastResult = OperationalEnvelopeUpcasterRegistry(
        upcasters.toList(),
    ).upcast(envelope, targetSchemaVersion)
}
