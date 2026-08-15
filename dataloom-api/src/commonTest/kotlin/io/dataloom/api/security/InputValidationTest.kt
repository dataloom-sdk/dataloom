package io.dataloom.api.security

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputValidationTest {

    private val isAsciiLetter: (Char) -> Boolean = { it in 'a'..'z' }

    @Test
    fun `accepts a value within bounds using only allowed characters`() {
        assertTrue(isBoundedToken("abc", maxLength = 5, isAllowedCharacter = isAsciiLetter))
    }

    @Test
    fun `rejects an empty value`() {
        assertFalse(isBoundedToken("", maxLength = 5, isAllowedCharacter = isAsciiLetter))
    }

    @Test
    fun `rejects a value exceeding maxLength`() {
        assertFalse(isBoundedToken("abcdef", maxLength = 5, isAllowedCharacter = isAsciiLetter))
    }

    @Test
    fun `accepts a value exactly at maxLength`() {
        assertTrue(isBoundedToken("abcde", maxLength = 5, isAllowedCharacter = isAsciiLetter))
    }

    @Test
    fun `rejects a value containing one disallowed digit character`() {
        assertFalse(isBoundedToken("abc1", maxLength = 5, isAllowedCharacter = isAsciiLetter))
    }

    @Test
    fun `rejects a value containing a disallowed punctuation character`() {
        val underscoreSeparated = "abc" + "_" + "d"
        assertFalse(isBoundedToken(underscoreSeparated, maxLength = 10, isAllowedCharacter = isAsciiLetter))
    }

    @Test
    fun `throws for a non-positive maxLength`() {
        assertFailsWith<IllegalArgumentException> {
            isBoundedToken("a", maxLength = 0, isAllowedCharacter = isAsciiLetter)
        }
        assertFailsWith<IllegalArgumentException> {
            isBoundedToken("a", maxLength = -1, isAllowedCharacter = isAsciiLetter)
        }
    }

    @Test
    fun `different predicates genuinely produce different results for the same value`() {
        val value = "path:to/thing"
        assertFalse(isBoundedToken(value, maxLength = 32, isAllowedCharacter = isAsciiLetter))
        assertTrue(
            isBoundedToken(value, maxLength = 32) { character ->
                character in 'a'..'z' || character == ':' || character == '/'
            },
        )
    }
}
