package io.dataloom.governance

import io.dataloom.api.security.AppleDataLoomHmacCalculator
import io.dataloom.api.security.DataLoomHmacCalculator

internal actual fun platformHmacCalculator(): DataLoomHmacCalculator = AppleDataLoomHmacCalculator()
