package io.dataloom.transport.grpc

import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.grpc.ManagedChannel
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException

/**
 * Abstract reference [TransportProvider] backed by unary gRPC calls via
 * [grpc-kotlin](https://github.com/grpc/grpc-kotlin).
 *
 * ## Platform scope
 *
 * **JVM and native Android only.** Google's `grpc-kotlin` is built on
 * `grpc-java` and has no Kotlin/Native (iOS) target. Do not use this module
 * in a KMP module targeting iOS. iOS support is a separate follow-up item
 * pending a viable Kotlin/Native gRPC client. See
 * `docs/transport/grpc-reference.md` for the explicit platform limitation
 * statement.
 *
 * ## Reference implementation
 *
 * This class is a reference implementation. Applications are expected to
 * subclass it, supply their own generated gRPC stubs, and optionally fork or
 * replace it entirely.
 *
 * ## Schema-agnostic design
 *
 * `GrpcTransportProvider` does not assume any specific `.proto` schema. The
 * actual service definition and generated stub types are entirely
 * application-owned. Subclasses implement [executePushUnary] and
 * [executePullUnary] using their own stubs and map protocol-specific request
 * and response types to DataLoom contracts.
 *
 * ## Usage
 *
 * ```kotlin
 * class MyGrpcTransportProvider(
 *     channel: ManagedChannel,
 *     private val stub: MySyncServiceGrpcKt.MySyncServiceCoroutineStub,
 * ) : GrpcTransportProvider(channel) {
 *
 *     override val descriptor: ProviderDescriptor = ProviderDescriptor(
 *         id = ProviderId("my.grpc.transport"),
 *         name = ProviderName("My gRPC Transport"),
 *         type = ProviderType.TRANSPORT,
 *         version = ProviderVersion("1.0.0"),
 *     )
 *
 *     override suspend fun executePushUnary(
 *         request: PushChangesRequest,
 *     ): ChangeSetAcknowledgement {
 *         val grpcRequest = MyProtoMapper.toPushRequest(request)
 *         val grpcResponse = stub.pushChanges(grpcRequest)   // unary RPC
 *         return MyProtoMapper.toChangeSetAcknowledgement(grpcResponse)
 *     }
 *
 *     override suspend fun executePullUnary(
 *         request: PullChangesRequest,
 *     ): PullChangesResult {
 *         val grpcRequest = MyProtoMapper.toPullRequest(request)
 *         val grpcResponse = stub.pullChanges(grpcRequest)   // unary RPC
 *         return MyProtoMapper.toPullChangesResult(grpcResponse)
 *     }
 * }
 * ```
 *
 * ## Cancellation
 *
 * [CancellationException] is always re-thrown so that structured concurrency is
 * preserved. gRPC status errors ([StatusException], [StatusRuntimeException])
 * are mapped to canonical [io.dataloom.api.error.DataLoomError] values via
 * [GrpcStatusMapper] and returned as [ProviderOperationResult.Failure]; they
 * do not cross the public API surface as raw gRPC types.
 *
 * ## Security
 *
 * No credential, token, call-metadata, or header value may appear in logs,
 * diagnostics, or `toString()`. Subclasses must observe the same restriction.
 *
 * @param channel [ManagedChannel] used for gRPC communication. The caller
 *   owns the channel lifecycle and is responsible for shutting it down.
 */
public abstract class GrpcTransportProvider(
    /** [ManagedChannel] used for gRPC communication. Caller-owned lifecycle. */
    protected val channel: ManagedChannel,
) : TransportProvider {

    /**
     * Executes an application-defined unary gRPC push RPC.
     *
     * Subclasses must use [channel] (or a stub built from it) to call the
     * application-owned push RPC, map the proto response to a
     * [ChangeSetAcknowledgement], and return it. Subclasses must not swallow
     * [CancellationException].
     *
     * Throw [StatusException] or [StatusRuntimeException] on gRPC failure —
     * the base class maps them to canonical [io.dataloom.api.error.DataLoomError].
     *
     * @param request immutable push request containing the synchronization
     *   request and outbound change set.
     * @return [ChangeSetAcknowledgement] describing how the remote participant
     *   responded to each pushed event.
     * @throws StatusException on gRPC transport or server-side failure.
     * @throws StatusRuntimeException on gRPC transport or server-side failure.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    protected abstract suspend fun executePushUnary(
        request: PushChangesRequest,
    ): ChangeSetAcknowledgement

    /**
     * Executes an application-defined unary gRPC pull RPC.
     *
     * Subclasses must use [channel] (or a stub built from it) to call the
     * application-owned pull RPC, map the proto response to a
     * [PullChangesResult], and return it. Subclasses must not swallow
     * [CancellationException].
     *
     * Throw [StatusException] or [StatusRuntimeException] on gRPC failure —
     * the base class maps them to canonical [io.dataloom.api.error.DataLoomError].
     *
     * @param request immutable pull request containing the synchronization
     *   request, optional entity-type restrictions, optional batch hint, and
     *   optional prior checkpoint.
     * @return [PullChangesResult] describing the inbound changes or absence
     *   thereof, together with an optional next checkpoint.
     * @throws StatusException on gRPC transport or server-side failure.
     * @throws StatusRuntimeException on gRPC transport or server-side failure.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    protected abstract suspend fun executePullUnary(
        request: PullChangesRequest,
    ): PullChangesResult

    /**
     * Pushes outbound synchronization changes via an application-supplied
     * unary gRPC RPC.
     *
     * Delegates to [executePushUnary]. Coroutine cancellation is preserved and
     * never swallowed. gRPC status errors are mapped to a
     * [ProviderOperationResult.Failure] containing a canonical DataLoom error;
     * raw `io.grpc.*` types do not cross this method's return boundary.
     *
     * @param request immutable push request containing the synchronization
     *   request and outbound change set.
     * @return [ProviderOperationResult.Success] with a [ChangeSetAcknowledgement]
     *   on success, or [ProviderOperationResult.Failure] with a canonical
     *   [io.dataloom.api.error.DataLoomError] on transport or server failure.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> {
        return try {
            ProviderOperationResult.Success(executePushUnary(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: StatusException) {
            ProviderOperationResult.Failure(GrpcStatusMapper.map(e))
        } catch (e: StatusRuntimeException) {
            ProviderOperationResult.Failure(GrpcStatusMapper.map(e))
        }
    }

    /**
     * Pulls inbound synchronization changes via an application-supplied
     * unary gRPC RPC.
     *
     * Delegates to [executePullUnary]. Coroutine cancellation is preserved and
     * never swallowed. gRPC status errors are mapped to a
     * [ProviderOperationResult.Failure] containing a canonical DataLoom error;
     * raw `io.grpc.*` types do not cross this method's return boundary.
     *
     * @param request immutable pull request containing the synchronization
     *   request, optional entity-type restrictions, optional batch hint, and
     *   optional prior checkpoint.
     * @return [ProviderOperationResult.Success] with a [PullChangesResult] on
     *   success, or [ProviderOperationResult.Failure] with a canonical
     *   [io.dataloom.api.error.DataLoomError] on transport or server failure.
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> {
        return try {
            ProviderOperationResult.Success(executePullUnary(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: StatusException) {
            ProviderOperationResult.Failure(GrpcStatusMapper.map(e))
        } catch (e: StatusRuntimeException) {
            ProviderOperationResult.Failure(GrpcStatusMapper.map(e))
        }
    }
}
