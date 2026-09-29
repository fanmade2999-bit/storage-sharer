package com.pocket.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    @Test
    fun samePasswordAndSaltProducesSameHash() {
        val salt = PasswordHasher.newSalt()
        val first = PasswordHasher.hash("pocket".toCharArray(), salt)
        val second = PasswordHasher.hash("pocket".toCharArray(), salt)

        assertTrue(PasswordHasher.constantTimeEquals(first, second))
    }

    @Test
    fun differentPasswordDoesNotMatch() {
        val salt = PasswordHasher.newSalt()
        val expected = PasswordHasher.hash("pocket".toCharArray(), salt)
        val actual = PasswordHasher.hash("not-pocket".toCharArray(), salt)

        assertFalse(PasswordHasher.constantTimeEquals(expected, actual))
    }

    @Test
    fun differentSaltProducesDifferentHash() {
        val first = PasswordHasher.newSalt()
        val second = PasswordHasher.newSalt()

        assertNotEquals(
            PasswordHasher.hash("pocket".toCharArray(), first).toList(),
            PasswordHasher.hash("pocket".toCharArray(), second).toList()
        )
    }
}
