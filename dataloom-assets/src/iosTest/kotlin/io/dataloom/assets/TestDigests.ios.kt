package io.dataloom.assets

import io.dataloom.api.security.AppleDataLoomDigestCalculator
import io.dataloom.api.security.DataLoomIncrementalDigestCalculator

actual fun platformDigests(): DataLoomIncrementalDigestCalculator = AppleDataLoomDigestCalculator()
