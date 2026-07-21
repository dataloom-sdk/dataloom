package io.dataloom.api.context

import io.dataloom.api.identifier.ConfigurationVersion
import io.dataloom.api.identifier.CorrelationId
import io.dataloom.api.identifier.ExecutionId
import io.dataloom.api.identifier.LocaleTag
import io.dataloom.api.identifier.RequestId
import io.dataloom.api.identifier.RuntimeVersion
import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.TraceId
import io.dataloom.api.identifier.UserId

/**
 * Immutable synchronization execution context.
 *
 * This contract carries caller-provided context values and does not generate
 * identifiers, perform environment lookups, or trigger runtime behavior.
 *
 * Do not place authentication tokens, credentials, encryption keys, personal
 * data, or full payloads in this context.
 *
 * `toString()` is for diagnostics only and is not a serialization format.
 */
public data class ExecutionContext(
    /** Required canonical identifier for this execution context instance. */
    public val executionId: ExecutionId,

    /** Required correlation identifier shared across integration boundaries. */
    public val correlationId: CorrelationId,

    /** Optional trace identifier for observability integration. */
    public val traceId: TraceId? = null,

    /** Optional request identifier supplied by the request initiator. */
    public val requestId: RequestId? = null,

    /** Optional tenant identifier for enterprise tenancy context. */
    public val tenantId: TenantId? = null,

    /** Optional user identifier supplied by host authentication/domain layers. */
    public val userId: UserId? = null,

    /** Optional locale tag supplied by the host or request initiator. */
    public val localeTag: LocaleTag? = null,

    /** Optional DataLoom runtime version label. */
    public val runtimeVersion: RuntimeVersion? = null,

    /** Optional host configuration version label. */
    public val configurationVersion: ConfigurationVersion? = null,

    /** Optional immutable contextual metadata. */
    public val metadata: DataLoomMetadata = DataLoomMetadata.Empty,
)
