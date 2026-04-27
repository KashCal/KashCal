package org.onekash.kashcal.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiMessageTest {

    @Test
    fun `ResId without args holds the id and empty args`() {
        val msg = UiMessage.ResId(id = 42)

        assertEquals(42, msg.id)
        assertEquals(emptyList<Any>(), msg.args)
    }

    @Test
    fun `ResId with args preserves the args list`() {
        val msg = UiMessage.ResId(id = 42, args = listOf("alice@example.com", 7))

        assertEquals(42, msg.id)
        assertEquals(listOf("alice@example.com", 7), msg.args)
    }

    @Test
    fun `ResId equality is structural - same id and same args are equal`() {
        val a = UiMessage.ResId(id = 42, args = listOf("foo"))
        val b = UiMessage.ResId(id = 42, args = listOf("foo"))

        assertEquals(a, b)
    }

    @Test
    fun `ResId equality distinguishes different args`() {
        val a = UiMessage.ResId(id = 42, args = listOf("foo"))
        val b = UiMessage.ResId(id = 42, args = listOf("bar"))

        assertNotEquals(a, b)
    }

    @Test
    fun `ResId equality distinguishes different ids`() {
        val a = UiMessage.ResId(id = 42)
        val b = UiMessage.ResId(id = 43)

        assertNotEquals(a, b)
    }

    @Test
    fun `Literal holds the text`() {
        val msg = UiMessage.Literal(text = "Server returned 401")

        assertEquals("Server returned 401", msg.text)
    }

    @Test
    fun `Literal equality is structural`() {
        val a = UiMessage.Literal(text = "error")
        val b = UiMessage.Literal(text = "error")

        assertEquals(a, b)
    }

    @Test
    fun `Literal and ResId are never equal even with coincidental values`() {
        val literal: UiMessage = UiMessage.Literal(text = "42")
        val resId: UiMessage = UiMessage.ResId(id = 42)

        assertNotEquals(literal, resId)
    }
}
