package io.dataloom.assets

import io.dataloom.api.random.AppleDataLoomSecureRandom
import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.assets.transform.AssetChunkCipher
import io.dataloom.assets.transform.AssetKeyResolver

actual fun platformSecureRandom(): DataLoomSecureRandom = AppleDataLoomSecureRandom()

// AES-GCM is unsupported on Apple (ADR-0014), so the shared tests use the toy AEAD there.
actual fun testCipher(keys: AssetKeyResolver, random: DataLoomSecureRandom): AssetChunkCipher =
    TestAeadCipher(keys, random)
