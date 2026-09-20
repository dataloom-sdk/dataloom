package io.dataloom.assets.testkit

import io.dataloom.api.asset.AssetManifest
import io.dataloom.api.asset.AssetMediaType
import io.dataloom.api.identifier.AssetId
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.DigestAlgorithm
import io.dataloom.assets.AssetChunkPlan
import io.dataloom.assets.AssetChunkUpload
import io.dataloom.assets.AssetErrorKind
import io.dataloom.assets.AssetIntegrityVerifier
import io.dataloom.assets.AssetProvider
import io.dataloom.assets.AssetQuota
import io.dataloom.assets.AssetTransferError
import io.dataloom.assets.AssetTransferSessionId
import io.dataloom.assets.AssetUploadRequest
import io.dataloom.assets.memory.InMemoryAssetSource
import kotlinx.coroutines.CancellationException

/**
 * Framework-neutral behavioural contract suite for [AssetProvider]
 * implementations (the FR provider test kit).
 *
 * It uses no test framework: [run] executes every scenario against a fresh
 * provider and returns an [AssetProviderContractReport]; a provider's own
 * test asserts the report (see [AssetProviderContractReport.assertAllPassed]),
 * so the kit works under any test runner on every platform.
 *
 * The scenarios cover the [AssetProvider] contract's idempotency, integrity,
 * quota and visibility rules: round trip, resume after partial commit,
 * out-of-order and duplicate chunks, chunk and whole-object digest mismatch,
 * length and range checks, incomplete completion, session conflicts, abort
 * semantics, per-asset and total quota with reservation release, version
 * conflicts, and read visibility. Both directions are exercised: upload
 * through the upload operations and download through
 * [AssetProvider.readManifest]/[AssetProvider.readChunk].
 *
 * @param digests digest calculator the kit uses to build valid manifests.
 * @param providerFactory creates a *fresh, empty* provider enforcing
 *   [AssetQuota]. Called once per scenario (and again for scenarios that need
 *   a different quota).
 */
public class AssetProviderContractKit(
    private val digests: DataLoomIncrementalDigestCalculator,
    private val providerFactory: suspend (AssetQuota) -> AssetProvider,
) {

    /** Runs every scenario and reports each one's outcome. Never throws for a scenario failure. */
    public suspend fun run(): AssetProviderContractReport {
        val results = scenarios.map { (name, scenario) ->
            val failure = try {
                scenario(Fixture())
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } catch (e: AssertionError) {
                e
            }
            AssetProviderContractReport.Result(name, failure)
        }
        return AssetProviderContractReport(results)
    }

    private inner class Fixture {
        suspend fun provider(quota: AssetQuota = AssetQuota.UNLIMITED): AssetProvider = providerFactory(quota)

        fun chunkSize(provider: AssetProvider): Int = provider.chunkSizeBounds.negotiate(16)

        /** An asset of `fullChunks` whole chunks plus a partial last chunk. */
        suspend fun asset(
            provider: AssetProvider,
            id: String,
            version: Long = 1,
            fullChunks: Int = 3,
            seed: Int = 1,
        ): TestAsset {
            val chunkSize = chunkSize(provider)
            val bytes = ByteArray(chunkSize * fullChunks + chunkSize / 2 + 1) { ((it * 31 + seed * 7) and 0xFF).toByte() }
            val plan = AssetChunkPlan(bytes.size.toLong(), chunkSize)
            val manifest = AssetIntegrityVerifier(digests).prepareManifest(
                AssetId(id), version, AssetMediaType("application/octet-stream"),
                InMemoryAssetSource(bytes), plan, DigestAlgorithm.SHA_256,
            )
            return TestAsset(manifest, bytes)
        }
    }

    private class TestAsset(val manifest: AssetManifest, val bytes: ByteArray) {
        val chunkCount: Int get() = manifest.chunkLayout.chunkCount

        fun chunk(index: Int): ByteArray {
            val descriptor = manifest.chunkLayout.chunks[index]
            return bytes.copyOfRange(descriptor.offsetBytes.toInt(), (descriptor.offsetBytes + descriptor.lengthBytes).toInt())
        }
    }

    private val scenarios: List<Pair<String, suspend (Fixture) -> Unit>> = listOf(
        "upload, complete and download round trip" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "roundtrip")
            val session = AssetTransferSessionId("s-roundtrip")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            for (i in 0 until asset.chunkCount) p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
            require(p.completeUpload(session).success("complete") == asset.manifest) { "completed manifest differs" }
            require(p.readManifest(asset.manifest.assetId, null).success("readManifest") == asset.manifest) {
                "readManifest differs"
            }
            for (i in 0 until asset.chunkCount) {
                require(p.readChunk(asset.manifest.assetId, 1, i).success("readChunk $i").contentEquals(asset.chunk(i))) {
                    "downloaded chunk $i differs"
                }
            }
        },

        "reopening a partially uploaded session reports exactly the committed chunks, and upload resumes" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "resume")
            val session = AssetTransferSessionId("s-resume")
            require(p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open").committedChunks.isEmpty()) {
                "fresh session reported committed chunks"
            }
            p.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0))).success("chunk 0")
            p.uploadChunk(AssetChunkUpload(session, 2, asset.chunk(2))).success("chunk 2")
            val reopened = p.openUpload(AssetUploadRequest(session, asset.manifest)).success("reopen")
            require(reopened.committedChunks == setOf(0, 2)) { "reopen reported ${reopened.committedChunks}, expected [0, 2]" }
            for (i in 0 until asset.chunkCount) if (i !in reopened.committedChunks) {
                p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
            }
            p.completeUpload(session).success("complete after resume")
        },

        "chunks may arrive out of order and be redelivered" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "ooo")
            val session = AssetTransferSessionId("s-ooo")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            for (i in (0 until asset.chunkCount).reversed()) {
                p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
                p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("duplicate chunk $i")
            }
            p.completeUpload(session).success("complete")
            require(p.readChunk(asset.manifest.assetId, 1, 0).success("read").contentEquals(asset.chunk(0))) { "content differs" }
        },

        "a chunk with the wrong digest is rejected and not committed" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "badchunk")
            val session = AssetTransferSessionId("s-badchunk")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            val corrupted = asset.chunk(1).also { it[0] = (it[0] + 1).toByte() }
            p.uploadChunk(AssetChunkUpload(session, 1, corrupted)).expectFailure(AssetErrorKind.CHUNK_DIGEST_MISMATCH, "corrupt chunk")
            val status = p.openUpload(AssetUploadRequest(session, asset.manifest)).success("reopen")
            require(1 !in status.committedChunks) { "corrupt chunk was committed" }
            p.uploadChunk(AssetChunkUpload(session, 1, asset.chunk(1))).success("retransmit")
        },

        "a chunk with the wrong length is rejected" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "badlength")
            val session = AssetTransferSessionId("s-badlength")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            p.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0) + byteArrayOf(0)))
                .expectFailure(AssetErrorKind.CHUNK_LENGTH_MISMATCH, "long chunk")
            p.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0).copyOf(asset.chunk(0).size - 1)))
                .expectFailure(AssetErrorKind.CHUNK_LENGTH_MISMATCH, "short chunk")
        },

        "a chunk index outside the manifest is rejected" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "badindex")
            val session = AssetTransferSessionId("s-badindex")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            p.uploadChunk(AssetChunkUpload(session, asset.chunkCount, asset.chunk(0)))
                .expectFailure(AssetErrorKind.CHUNK_OUT_OF_RANGE, "index past the end")
        },

        "uploading a chunk to an unknown session fails" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "nosession")
            p.uploadChunk(AssetChunkUpload(AssetTransferSessionId("nobody"), 0, asset.chunk(0)))
                .expectFailure(AssetErrorKind.SESSION_NOT_FOUND, "unknown session")
            p.completeUpload(AssetTransferSessionId("nobody")).expectFailure(AssetErrorKind.SESSION_NOT_FOUND, "complete unknown")
        },

        "completing before every chunk is committed fails and exposes nothing" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "incomplete")
            val session = AssetTransferSessionId("s-incomplete")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            p.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0))).success("chunk 0")
            p.completeUpload(session).expectFailure(AssetErrorKind.INCOMPLETE_UPLOAD, "incomplete complete")
            p.readManifest(asset.manifest.assetId, null).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "read after failed complete")
        },

        "a whole-object digest mismatch fails completion and exposes nothing" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "badobject")
            val wrongWhole = digests.digest(DigestAlgorithm.SHA_256, byteArrayOf(9, 9, 9))
            val tampered = asset.manifest.copy(checksum = wrongWhole)
            val session = AssetTransferSessionId("s-badobject")
            p.openUpload(AssetUploadRequest(session, tampered)).success("open")
            for (i in 0 until asset.chunkCount) p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
            p.completeUpload(session).expectFailure(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "tampered whole digest")
            p.readManifest(asset.manifest.assetId, null).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "corrupted asset visible")
        },

        "completing twice returns the same committed manifest" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "twice")
            val session = AssetTransferSessionId("s-twice")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            for (i in 0 until asset.chunkCount) p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
            val first = p.completeUpload(session).success("first complete")
            require(p.completeUpload(session).success("second complete") == first) { "second complete differs" }
            require(p.openUpload(AssetUploadRequest(session, asset.manifest)).success("reopen completed").committedChunks.size == asset.chunkCount) {
                "reopened completed session lost chunks"
            }
        },

        "reopening a session with a different manifest is rejected" to { f ->
            val p = f.provider()
            val a = f.asset(p, "conflict", seed = 1)
            val b = f.asset(p, "conflict", seed = 2)
            val session = AssetTransferSessionId("s-conflict")
            p.openUpload(AssetUploadRequest(session, a.manifest)).success("open")
            p.openUpload(AssetUploadRequest(session, b.manifest)).expectFailure(AssetErrorKind.SESSION_CONFLICT, "different manifest")
        },

        "abort is idempotent, discards the session, and never removes a committed asset" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "abort")
            val session = AssetTransferSessionId("s-abort")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            p.uploadChunk(AssetChunkUpload(session, 0, asset.chunk(0))).success("chunk 0")
            p.abortUpload(session).success("abort")
            p.abortUpload(session).success("abort again")
            p.abortUpload(AssetTransferSessionId("never-existed")).success("abort unknown")
            p.uploadChunk(AssetChunkUpload(session, 1, asset.chunk(1))).expectFailure(AssetErrorKind.SESSION_NOT_FOUND, "chunk after abort")

            val done = f.asset(p, "abort-done")
            val doneSession = AssetTransferSessionId("s-abort-done")
            p.openUpload(AssetUploadRequest(doneSession, done.manifest)).success("open done")
            for (i in 0 until done.chunkCount) p.uploadChunk(AssetChunkUpload(doneSession, i, done.chunk(i))).success("chunk $i")
            p.completeUpload(doneSession).success("complete")
            p.abortUpload(doneSession).success("abort completed")
            p.readManifest(done.manifest.assetId, null).success("committed asset survives abort")
        },

        "an asset larger than the maximum asset size is rejected at open" to { f ->
            val probe = f.provider()
            val asset = f.asset(probe, "toobig")
            val p = f.provider(AssetQuota(maxAssetSizeBytes = asset.manifest.sizeBytes - 1))
            p.openUpload(AssetUploadRequest(AssetTransferSessionId("s-toobig"), asset.manifest))
                .expectFailure(AssetErrorKind.QUOTA_EXCEEDED, "over max asset size")
            val fits = f.provider(AssetQuota(maxAssetSizeBytes = asset.manifest.sizeBytes))
            fits.openUpload(AssetUploadRequest(AssetTransferSessionId("s-fits"), asset.manifest)).success("exactly at the limit")
        },

        "the total quota rejects the upload that would exceed it, and abort releases the reservation" to { f ->
            val probe = f.provider()
            val a = f.asset(probe, "quota-a")
            val b = f.asset(probe, "quota-b")
            val p = f.provider(AssetQuota(maxTotalBytes = a.manifest.sizeBytes + b.manifest.sizeBytes - 1))
            val sa = AssetTransferSessionId("s-quota-a")
            val sb = AssetTransferSessionId("s-quota-b")
            p.openUpload(AssetUploadRequest(sa, a.manifest)).success("open a")
            p.openUpload(AssetUploadRequest(sb, b.manifest)).expectFailure(AssetErrorKind.QUOTA_EXCEEDED, "b over total while a reserved")
            p.abortUpload(sa).success("abort a")
            p.openUpload(AssetUploadRequest(sb, b.manifest)).success("b fits once a's reservation is released")
        },

        "committed assets count against the total quota" to { f ->
            val probe = f.provider()
            val a = f.asset(probe, "quota-c")
            val b = f.asset(probe, "quota-d")
            val p = f.provider(AssetQuota(maxTotalBytes = a.manifest.sizeBytes + b.manifest.sizeBytes - 1))
            val sa = AssetTransferSessionId("s-quota-c")
            p.openUpload(AssetUploadRequest(sa, a.manifest)).success("open a")
            for (i in 0 until a.chunkCount) p.uploadChunk(AssetChunkUpload(sa, i, a.chunk(i))).success("chunk $i")
            p.completeUpload(sa).success("complete a")
            p.openUpload(AssetUploadRequest(AssetTransferSessionId("s-quota-d"), b.manifest))
                .expectFailure(AssetErrorKind.QUOTA_EXCEEDED, "b over total after a committed")
        },

        "a failed whole-object verification releases the quota reservation" to { f ->
            val probe = f.provider()
            val a = f.asset(probe, "quota-e")
            val p = f.provider(AssetQuota(maxTotalBytes = a.manifest.sizeBytes))
            val tampered = a.manifest.copy(checksum = digests.digest(DigestAlgorithm.SHA_256, byteArrayOf(1)))
            val bad = AssetTransferSessionId("s-quota-bad")
            p.openUpload(AssetUploadRequest(bad, tampered)).success("open tampered")
            for (i in 0 until a.chunkCount) p.uploadChunk(AssetChunkUpload(bad, i, a.chunk(i))).success("chunk $i")
            p.completeUpload(bad).expectFailure(AssetErrorKind.OBJECT_DIGEST_MISMATCH, "tampered")
            p.openUpload(AssetUploadRequest(AssetTransferSessionId("s-quota-good"), a.manifest))
                .success("reservation released by failed verification")
        },

        "the same asset version cannot be committed twice" to { f ->
            val p = f.provider()
            val first = f.asset(p, "versioned", version = 1, seed = 1)
            val second = f.asset(p, "versioned", version = 1, seed = 2)
            val s1 = AssetTransferSessionId("s-v1")
            p.openUpload(AssetUploadRequest(s1, first.manifest)).success("open first")
            for (i in 0 until first.chunkCount) p.uploadChunk(AssetChunkUpload(s1, i, first.chunk(i))).success("chunk $i")
            p.completeUpload(s1).success("complete first")
            p.openUpload(AssetUploadRequest(AssetTransferSessionId("s-v1-again"), second.manifest))
                .expectFailure(AssetErrorKind.ASSET_VERSION_CONFLICT, "second commit of version 1")
        },

        "reading without a version returns the highest committed version" to { f ->
            val p = f.provider()
            for (v in listOf(1L, 3L, 2L)) {
                val asset = f.asset(p, "multi", version = v, seed = v.toInt())
                val session = AssetTransferSessionId("s-multi-$v")
                p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open v$v")
                for (i in 0 until asset.chunkCount) p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
                p.completeUpload(session).success("complete v$v")
            }
            require(p.readManifest(AssetId("multi"), null).success("latest").version == 3L) { "latest was not version 3" }
            require(p.readManifest(AssetId("multi"), 2L).success("v2").version == 2L) { "explicit version 2 not returned" }
            p.readManifest(AssetId("multi"), 9L).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "missing version")
        },

        "reading an unknown asset or an out-of-range chunk fails" to { f ->
            val p = f.provider()
            p.readManifest(AssetId("ghost"), null).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "unknown asset")
            p.readChunk(AssetId("ghost"), 1, 0).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "chunk of unknown asset")
            val asset = f.asset(p, "readrange")
            val session = AssetTransferSessionId("s-readrange")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            for (i in 0 until asset.chunkCount) p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
            p.completeUpload(session).success("complete")
            p.readChunk(asset.manifest.assetId, 1, asset.chunkCount).expectFailure(AssetErrorKind.CHUNK_OUT_OF_RANGE, "past the end")
        },

        "an upload in progress is not readable" to { f ->
            val p = f.provider()
            val asset = f.asset(p, "hidden")
            val session = AssetTransferSessionId("s-hidden")
            p.openUpload(AssetUploadRequest(session, asset.manifest)).success("open")
            for (i in 0 until asset.chunkCount) p.uploadChunk(AssetChunkUpload(session, i, asset.chunk(i))).success("chunk $i")
            p.readManifest(asset.manifest.assetId, null).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "visible before completion")
            p.readChunk(asset.manifest.assetId, 1, 0).expectFailure(AssetErrorKind.ASSET_NOT_FOUND, "chunk visible before completion")
        },
    )

    private fun <T> ProviderOperationResult<T>.success(what: String): T = when (this) {
        is ProviderOperationResult.Success -> value
        is ProviderOperationResult.Failure -> throw AssertionError("$what: expected success but failed with $error")
    }

    private fun <T> ProviderOperationResult<T>.expectFailure(kind: AssetErrorKind, what: String) {
        when (this) {
            is ProviderOperationResult.Success -> throw AssertionError("$what: expected $kind but the operation succeeded")
            is ProviderOperationResult.Failure -> {
                val actual = (error as? AssetTransferError)?.kind
                if (actual != kind) throw AssertionError("$what: expected $kind but was $error")
            }
        }
    }
}

/** Outcome of an [AssetProviderContractKit.run]. */
public class AssetProviderContractReport internal constructor(public val results: List<Result>) {

    /** One scenario's outcome; [failure] is `null` when it passed. */
    public class Result(public val name: String, public val failure: Throwable?) {
        /** `true` if the scenario passed. */
        public val passed: Boolean get() = failure == null
    }

    /** Results of the scenarios that failed. */
    public val failures: List<Result> get() = results.filter { !it.passed }

    /** Throws an [AssertionError] listing every failed scenario, if any failed. */
    public fun assertAllPassed() {
        if (failures.isNotEmpty()) {
            throw AssertionError(
                failures.joinToString(
                    separator = "\n",
                    prefix = "${failures.size} of ${results.size} asset provider contract scenarios failed:\n",
                ) { "- ${it.name}: ${it.failure}" },
            )
        }
    }
}
