package io.dataloom.assets.transform

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

internal actual fun platformAesGcmSupported(): Boolean = true

internal actual fun platformAesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    plaintext: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    cipher.updateAAD(associatedData)
    return cipher.doFinal(plaintext)
}

internal actual fun platformAesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    associatedData: ByteArray,
    sealed: ByteArray,
): ByteArray =
    try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        cipher.doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        // AEADBadTagException and friends: never surface JCE detail, it may be provider-specific.
        throw AssetChunkAuthenticationException("Sealed chunk failed authentication.")
    }
