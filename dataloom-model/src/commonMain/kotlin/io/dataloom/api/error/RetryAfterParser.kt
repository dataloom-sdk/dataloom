package io.dataloom.api.error

import io.dataloom.api.time.DataLoomClock

/**
 * Normalizes the value of an HTTP `Retry-After` header into a
 * [RetryDelayHint].
 *
 * This is the one shared parser for every HTTP-based DataLoom transport
 * adapter (Ktor, Retrofit, GraphQL over HTTP), so that they all accept and
 * reject exactly the same inputs. The shared retry runtime never parses raw
 * headers; adapters call this object and attach the result to the canonical
 * error they already produce.
 *
 * Accepted forms (RFC 9110 section 10.2.3):
 * - `delay-seconds`: one or more ASCII digits, for example `120`.
 * - `HTTP-date` in any of the three formats RFC 9110 requires recipients to
 *   accept: IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`), the obsolete
 *   RFC 850 form (`Sunday, 06-Nov-94 08:49:37 GMT`), and asctime
 *   (`Sun Nov  6 08:49:37 1994`). Two-digit RFC 850 years 00-69 mean 2000-2069
 *   and 70-99 mean 1970-1999.
 *
 * Everything else (empty, signed, fractional, non-numeric, non-GMT dates,
 * impossible calendar dates) is rejected by returning `null`; a hint is never
 * guessed. Raw header text is never retained: the result carries only a closed
 * numeric delay, and this function throws no exception for any input.
 *
 * Bounds: a delay is never negative (an HTTP-date that is not after the
 * clock's current instant yields a zero delay) and never exceeds
 * [MAXIMUM_DELAY_MILLISECONDS] (larger values, including numbers too large for
 * a `Long`, are clamped to it). The retry runtime still applies its own
 * configured maximum on top of this ceiling.
 */
public object RetryAfterParser {
    /** Ceiling applied to every parsed delay: 24 hours. */
    public const val MAXIMUM_DELAY_MILLISECONDS: Long = 86_400_000L

    private const val MAXIMUM_DELAY_SECONDS: Long = MAXIMUM_DELAY_MILLISECONDS / 1_000L
    private const val MILLISECONDS_PER_SECOND: Long = 1_000L
    private const val SECONDS_PER_DAY: Long = 86_400L

    private val monthNames: List<String> = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )
    private val weekdayNames: List<String> = listOf(
        "mon", "tue", "wed", "thu", "fri", "sat", "sun",
    )
    private val weekdayLongNames: List<String> = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    /**
     * Parses [values], the values of the `Retry-After` header of one response.
     *
     * Retry-After is a single-valued header. If the response carries several
     * values, only the first is considered (matching the previous Ktor
     * behavior) and the rest are ignored.
     *
     * @param values header values in wire order; empty when the header is absent.
     * @param clock source of "now" for the HTTP-date form. When `null`, the
     *   HTTP-date form cannot be honored and yields `null`; the delay-seconds
     *   form never consults a clock.
     * @return a [RetryDelayHintSource.SERVER] hint, or `null` when [values] is
     *   empty or the first value is not a valid `Retry-After` value.
     */
    public fun parse(values: List<String>, clock: DataLoomClock?): RetryDelayHint? {
        val raw: String = values.firstOrNull()?.trim(' ', '\t') ?: return null
        if (raw.isEmpty()) return null

        val delayMilliseconds: Long = if (raw.all(::isAsciiDigit)) {
            parseDelaySeconds(raw) * MILLISECONDS_PER_SECOND
        } else {
            val resetAtEpochSeconds: Long = parseHttpDateEpochSeconds(raw) ?: return null
            val nowMilliseconds: Long = clock?.now()?.epochMilliseconds ?: return null
            delayUntil(resetAtEpochSeconds, nowMilliseconds)
        }
        return RetryDelayHint(
            delayMilliseconds = delayMilliseconds,
            source = RetryDelayHintSource.SERVER,
        )
    }

    /** Saturating digit-string to seconds conversion; never overflows. */
    private fun parseDelaySeconds(digits: String): Long {
        var seconds = 0L
        for (digit in digits) {
            seconds = seconds * 10L + (digit - '0')
            if (seconds >= MAXIMUM_DELAY_SECONDS) return MAXIMUM_DELAY_SECONDS
        }
        return seconds
    }

    private fun delayUntil(resetAtEpochSeconds: Long, nowMilliseconds: Long): Long {
        // Compare in seconds first so a far-future date cannot overflow the
        // multiplication to milliseconds.
        val nowSeconds: Long = nowMilliseconds / MILLISECONDS_PER_SECOND
        if (resetAtEpochSeconds - nowSeconds >= MAXIMUM_DELAY_SECONDS) {
            return MAXIMUM_DELAY_MILLISECONDS
        }
        val delay: Long = resetAtEpochSeconds * MILLISECONDS_PER_SECOND - nowMilliseconds
        return delay.coerceIn(0L, MAXIMUM_DELAY_MILLISECONDS)
    }

    /**
     * Returns the epoch second of [text] for any of the three HTTP-date
     * formats, or `null` when [text] is not one of them.
     *
     * IMF-fixdate and RFC 850 tokenize identically once `,`, `-` and spaces are
     * treated as separators: `weekday day month year time GMT`. asctime is
     * `weekday month day time year`.
     */
    private fun parseHttpDateEpochSeconds(text: String): Long? {
        val tokens: List<String> = text.split(' ', ',', '-', '\t').filter { it.isNotEmpty() }
        val day: Int
        val month: Int
        val year: Int
        val time: String
        when (tokens.size) {
            6 -> {
                if (!isWeekday(tokens[0])) return null
                if (!tokens[5].equals("GMT", ignoreCase = true)) return null
                day = parseSmallNumber(tokens[1], maxDigits = 2) ?: return null
                month = monthNumber(tokens[2]) ?: return null
                year = parseYear(tokens[3]) ?: return null
                time = tokens[4]
            }

            5 -> {
                if (!isWeekday(tokens[0])) return null
                month = monthNumber(tokens[1]) ?: return null
                day = parseSmallNumber(tokens[2], maxDigits = 2) ?: return null
                time = tokens[3]
                year = parseSmallNumber(tokens[4], maxDigits = 4)
                    ?.takeIf { tokens[4].length == 4 } ?: return null
            }

            else -> return null
        }

        val timeParts: List<String> = time.split(':')
        if (timeParts.size != 3 || timeParts.any { it.length != 2 }) return null
        val hour: Int = parseSmallNumber(timeParts[0], maxDigits = 2) ?: return null
        val minute: Int = parseSmallNumber(timeParts[1], maxDigits = 2) ?: return null
        val second: Int = parseSmallNumber(timeParts[2], maxDigits = 2) ?: return null
        // 60 is the permitted leap second.
        if (hour > 23 || minute > 59 || second > 60) return null
        if (day < 1 || day > daysInMonth(year, month)) return null

        return daysFromCivil(year, month, day) * SECONDS_PER_DAY +
            hour * 3_600L + minute * 60L + second
    }

    private fun parseYear(token: String): Int? {
        val value: Int = parseSmallNumber(token, maxDigits = 4) ?: return null
        return when (token.length) {
            4 -> value
            2 -> if (value < 70) 2000 + value else 1900 + value
            else -> null
        }
    }

    private fun isWeekday(token: String): Boolean {
        val lower: String = token.lowercase()
        return lower in weekdayNames || lower in weekdayLongNames
    }

    private fun monthNumber(token: String): Int? {
        val index: Int = monthNames.indexOf(token.lowercase())
        return if (index >= 0) index + 1 else null
    }

    private fun parseSmallNumber(token: String, maxDigits: Int): Int? {
        if (token.isEmpty() || token.length > maxDigits || !token.all(::isAsciiDigit)) return null
        return token.toInt()
    }

    private fun isAsciiDigit(character: Char): Boolean = character in '0'..'9'

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeapYear(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val shiftedYear: Int = if (month <= 2) year - 1 else year
        val era: Int = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
        val yearOfEra: Int = shiftedYear - era * 400
        val shiftedMonth: Int = if (month > 2) month - 3 else month + 9
        val dayOfYear: Int = (153 * shiftedMonth + 2) / 5 + day - 1
        val dayOfEra: Int = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}
