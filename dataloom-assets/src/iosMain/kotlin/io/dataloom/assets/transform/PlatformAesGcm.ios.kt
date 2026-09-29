package io.dataloom.assets.transform

// Apple platforms cannot run AES-GCM from Kotlin/Native in this slice:
// `platform.CoreCrypto` (CommonCrypto) exposes no GCM mode, and CryptoKit is
// Swift-only with no Objective-C surface to bind. This is reported honestly as
// AssetTransformUnsupportedException rather than emulated; see ADR-0014.

private const val UNSUPPORTED_MESSAGE = "AES-256-GCM is not available on this platform."

internal actual fun platformAesGcmSupported(): Boolean = false

internal actual fun platformAesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    plaintext: ByteArray,
): ByteArray = throw AssetTransformUnsupportedException(UNSUPPORTED_MESSAGE)

internal actual fun platformAesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    sealed: ByteArray,
): ByteArray = throw AssetTransformUnsupportedException(UNSUPPORTED_MESSAGE)
