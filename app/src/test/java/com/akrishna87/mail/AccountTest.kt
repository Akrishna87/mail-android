package com.akrishna87.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountTest {
    @Test
    fun stringRepresentationRedactsCredentialsAndPersonalDetails() {
        val account = Account("person@example.com", "Private User", "test-only-password")
        val rendered = account.toString()
        assertEquals("Account([REDACTED])", rendered)
        assertFalse(rendered.contains(account.email))
        assertFalse(rendered.contains(account.name))
        assertFalse(rendered.contains(account.password))
        assertEquals(rendered, "$account")
    }

    @Test
    fun redactionPreservesAccountValuesAndEquality() {
        val account = Account("person@example.com", "Private User", "test-only-password")
        assertEquals("test-only-password", account.password)
        assertEquals(account, account.copy())
        assertNotEquals(account, account.copy(password = "different-test-password"))
        assertEquals("Account([REDACTED])", account.copy(password = "different-test-password").toString())
    }
}
