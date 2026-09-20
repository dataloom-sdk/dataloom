package io.dataloom.assets

import io.dataloom.api.security.DataLoomIncrementalDigestCalculator
import io.dataloom.api.security.SystemDataLoomDigestCalculator

actual fun platformDigests(): DataLoomIncrementalDigestCalculator = SystemDataLoomDigestCalculator()
