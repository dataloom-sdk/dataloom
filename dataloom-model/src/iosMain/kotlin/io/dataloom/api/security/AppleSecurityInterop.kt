@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.dataloom.api.security

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * Pins this array and invokes [block] with a `CPointer<ByteVar>` to its
 * first element, or `null` when this array is empty.
 *
 * Pinning an empty [ByteArray] and calling `addressOf(0)` on it is invalid
 * (there is no element zero). CommonCrypto's one-shot digest/HMAC functions
 * treat a `null` data pointer paired with a zero length as an empty input,
 * so this is the shared pattern [AppleDataLoomDigestCalculator] and
 * [AppleDataLoomHmacCalculator] use to support hashing/authenticating zero
 * bytes without a special case at each call site.
 */
internal inline fun <R> ByteArray.usePinnedAddressOrNull(block: (CPointer<ByteVar>?) -> R): R =
    if (isEmpty()) block(null) else usePinned { pinned -> block(pinned.addressOf(0)) }
