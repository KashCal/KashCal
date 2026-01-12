package org.onekash.kashcal.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for LogUtils email masking function.
 */
class LogUtilsTest {

    @Test
    fun `maskEmail with standard email`() {
        assertEquals("joh***@***.com", "john.doe@icloud.com".maskEmail())
    }

    @Test
    fun `maskEmail with short local part 3 chars`() {
        // 3 chars: atIndex=3, which is <=3, so shows first char + "***"
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
        // Unicode characters in local part: 3 chars "日本語", atIndex=3, shows first char + "***"
        assertEquals("日***@***.com", "日本語@example.com".maskEmail())
    }

    @Test
    fun `maskEmail with subdomain`() {
        // "user@mail.icloud.com" - lastIndexOf('.') finds ".com", masks to "***@***.com"
        // This loses subdomain info, which is MORE secure (intentional)
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
}
