package io.dataloom.governance

import io.dataloom.api.security.DataLoomHmacCalculator
import io.dataloom.api.security.SystemDataLoomHmacCalculator

internal actual fun platformHmacCalculator(): DataLoomHmacCalculator = SystemDataLoomHmacCalculator()
