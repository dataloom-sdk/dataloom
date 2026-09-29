package io.dataloom.assets

import io.dataloom.api.random.DataLoomSecureRandom
import io.dataloom.api.random.SystemDataLoomSecureRandom
import io.dataloom.assets.transform.AesGcmAssetChunkCipher
import io.dataloom.assets.transform.AssetChunkCipher
import io.dataloom.assets.transform.AssetKeyResolver

actual fun platformSecureRandom(): DataLoomSecureRandom = SystemDataLoomSecureRandom()

actual fun testCipher(keys: AssetKeyResolver, random: DataLoomSecureRandom): AssetChunkCipher =
    AesGcmAssetChunkCipher(keys, random)
