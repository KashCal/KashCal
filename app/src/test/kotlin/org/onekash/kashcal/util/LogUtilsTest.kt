package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the `maskEmail` and `maskHost` log-masking extensions. */
class LogUtilsTest {

    @Test
    fun `maskEmail with standard email`() {
        assertEquals("joh***@***.com", "john.doe@icloud.com".maskEmail())
    }

    @Test
    fun `maskEmail with short local part 3 chars`() {
        // A local part of 3 chars or fewer keeps only its first char.
        assertEquals("j***@***.com", "joe@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with short local part 2 chars`() {
        assertEquals("j***@***.com", "jo@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with short local part 1 char`() {
        assertEquals("j***@***.com", "j@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with no at symbol`() {
        assertEquals("***", "invalid-email".maskEmail())
    }

    @Test
    fun `maskEmail with empty string`() {
        assertEquals("***", "".maskEmail())
    }

    @Test
    fun `maskEmail with at at start`() {
        assertEquals("***", "@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with no dot in domain`() {
        assertEquals("use***@***", "user@localhost".maskEmail())
    }

    @Test
    fun `maskEmail with unicode local part`() {
        // "日本語" is 3 chars, so only the first is kept.
        assertEquals("日***@***.com", "日本語@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with subdomain`() {
        // Only the domain's last label survives, so the subdomain is dropped on purpose.
        assertEquals("use***@***.com", "user@mail.icloud.com".maskEmail())
    }

    @Test
    fun `maskEmail with long local part`() {
        assertEquals("ver***@***.com", "verylonglocalpart@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with special chars in local part`() {
        assertEquals("use***@***.com", "user+tag@example.com".maskEmail())
    }

    @Test
    fun `maskHost keeps the first three characters and the top-level label`() {
        assertEquals("cal***.com", "caldav.example.com".maskHost())
        assertEquals("p18***.com", "p180-caldav.icloud.com".maskHost())
    }

    @Test
    fun `maskHost keeps localhost and masks addresses and single labels`() {
        assertEquals("localhost", "localhost".maskHost())
        assertEquals("192.***", "192.168.1.20".maskHost())
        assertEquals("fe80:***", "fe80::1".maskHost())
        assertEquals("nas***", "nas".maskHost())
        assertEquals("***", "".maskHost())
    }
}
