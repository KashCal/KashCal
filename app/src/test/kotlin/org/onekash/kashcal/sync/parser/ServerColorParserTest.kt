package org.onekash.kashcal.sync.parser

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [ServerColorParser.parseCaldavColorToArgb], which returns null for unparseable input.
 *
 * The calendar-metadata refresh in `PullStrategy` and the discovery services' update of an
 * existing calendar keep the local color on null. It differs from the discovery services'
 * private parseColor, which falls back to a default color.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ServerColorParserTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @Test
    fun `null input returns null`() {
        assertNull(ServerColorParser.parseCaldavColorToArgb(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(ServerColorParser.parseCaldavColorToArgb(""))
        assertNull(ServerColorParser.parseCaldavColorToArgb("   "))
    }

    @Test
    fun `six-digit hex returns ARGB with full alpha`() {
        val result = ServerColorParser.parseCaldavColorToArgb("#FF5733")
        assertEquals(0xFFFF5733.toInt(), result)
    }

    @Test
    fun `six-digit hex lowercase returns ARGB with full alpha`() {
        val result = ServerColorParser.parseCaldavColorToArgb("#ff5733")
        assertEquals(0xFFFF5733.toInt(), result)
    }

    @Test
    fun `three-digit hex is expanded to six-digit`() {
        val result = ServerColorParser.parseCaldavColorToArgb("#F53")
        // #F53 expands to #FF5533, ARGB 0xFFFF5533
        assertEquals(0xFFFF5533.toInt(), result)
    }

    @Test
    fun `eight-digit iCloud RRGGBBAA is converted to AARRGGBB`() {
        // iCloud returns #RRGGBBAA; Android's Color.parseColor expects #AARRGGBB.
        // Input RR=FF, GG=57, BB=33, AA=CC gives AA=CC, RR=FF, GG=57, BB=33
        val result = ServerColorParser.parseCaldavColorToArgb("#FF5733CC")
        assertEquals(0xCCFF5733.toInt(), result)
    }

    @Test
    fun `CSS3 named color resolves via EventColorPalette`() {
        // "red" is a CSS3 name. EventColorPalette.hexForName resolves it because
        // Android's Color.parseColor supports only the 17 W3C basic colors, not the
        // full CSS3 set.
        val expected = EventColorPalette.hexForName("red")
        assertEquals(expected, ServerColorParser.parseCaldavColorToArgb("red"))
    }

    @Test
    fun `CSS3 extended name mediumorchid resolves via palette`() {
        val expected = EventColorPalette.hexForName("mediumorchid")
        assertEquals(
            "mediumorchid is a CSS3-extended name not in Color.parseColor's basic set",
            expected,
            ServerColorParser.parseCaldavColorToArgb("mediumorchid")
        )
    }

    @Test
    fun `unparseable hex returns null`() {
        assertNull(ServerColorParser.parseCaldavColorToArgb("#ZZZZZZ"))
    }

    @Test
    fun `garbage string returns null`() {
        assertNull(ServerColorParser.parseCaldavColorToArgb("not-a-color"))
    }

    @Test
    fun `hex with whitespace is trimmed`() {
        val result = ServerColorParser.parseCaldavColorToArgb("  #FF5733  ")
        assertEquals(0xFFFF5733.toInt(), result)
    }

    @Test
    fun `hex without leading hash is not accepted`() {
        // A bare "FF5733" is rejected to avoid false positives: a hex value must
        // start with #. RFC 7986 §5.9 defines COLOR as a CSS3 color name, with no hex form.
        assertNull(ServerColorParser.parseCaldavColorToArgb("FF5733"))
    }

    @Test
    fun `empty hash returns null not exception`() {
        assertNull(ServerColorParser.parseCaldavColorToArgb("#"))
    }

    @Test
    fun `never throws on weird input`() {
        // Property-style smoke test: each odd input must return null, never throw,
        // so a malformed server response can't crash the sync loop.
        val weird = listOf(
            "#",
            "##FF5733",
            "#FF57",
            "#FF57331",       // 7 digits, invalid length
            "#FF5733CCAA",    // 10 digits, invalid length
            "rgba(255,87,51,0.8)",
            "hsl(9, 100%, 60%)",
            "\u0000\u0001",  // raw control chars, escaped for a text-clean file
            "null",
            "None"
        )
        for (input in weird) {
            assertNull(
                "expected null for weird input: '$input'",
                ServerColorParser.parseCaldavColorToArgb(input)
            )
        }
    }

    @Test
    fun `additional invalid-length hex strings return null`() {
        // Lengths 2, 5, and 6 (after the '#') all fall through to the else branch.
        assertNull(ServerColorParser.parseCaldavColorToArgb("#FF"))
        assertNull(ServerColorParser.parseCaldavColorToArgb("#FF57"))
        assertNull(ServerColorParser.parseCaldavColorToArgb("#FF573"))
    }

    @Test
    fun `three-digit hex with non-hex chars returns null`() {
        // The 4-length branch expands #GHI to #GGHHII then Color.parseColor throws.
        assertNull(ServerColorParser.parseCaldavColorToArgb("#GHI"))
    }

    @Test
    fun `eight-digit hex with non-hex chars returns null`() {
        // The 9-length branch reslices to #AARRGGBB then Color.parseColor throws.
        assertNull(ServerColorParser.parseCaldavColorToArgb("#FF5733ZZ"))
    }

    @Test
    fun `eight-digit color with zero alpha yields fully transparent ARGB`() {
        // AA=00 gives a fully transparent color, a boundary apart from the CC-alpha case.
        assertEquals(0x00FF5733, ServerColorParser.parseCaldavColorToArgb("#FF573300"))
    }

    @Test
    fun `named color is case-insensitive`() {
        // hexForName lowercases its input, so server-supplied casing resolves.
        val expected = EventColorPalette.hexForName("red")
        assertEquals(expected, ServerColorParser.parseCaldavColorToArgb("RED"))
        assertEquals(expected, ServerColorParser.parseCaldavColorToArgb("Red"))
    }

    @Test
    fun `hash-prefixed CSS name is not resolved as a named color`() {
        // The palette lookup runs on the trimmed string before the '#' check, so
        // "#red" matches no name and then fails hex parsing.
        assertNull(ServerColorParser.parseCaldavColorToArgb("#red"))
    }
}
