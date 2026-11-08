package com.example.colorpatterngenerator

import org.junit.Test
import org.junit.Assert.*

class CommandParserTest {

    private val parser = CommandParser()

    @Test
    fun testParseSetColor() {
        val result = parser.parse("color=255,0,0")
        assertTrue(result is Command.SetColor)
        val cmd = result as Command.SetColor
        assertEquals(255, cmd.r)
        assertEquals(0, cmd.g)
        assertEquals(0, cmd.b)
    }

    @Test
    fun testParseSetCircleColor() {
        val result = parser.parse("color_o=0,255,0")
        assertTrue(result is Command.SetCircleColor)
        val cmd = result as Command.SetCircleColor
        assertEquals(0, cmd.r)
        assertEquals(255, cmd.g)
        assertEquals(0, cmd.b)
    }

    @Test
    fun testParseBasicConfig() {
        val result = parser.parse("basic config")
        assertTrue(result is Command.BasicConfig)
    }

    @Test
    fun testParseStreamStart() {
        val result = parser.parse("stream_start")
        assertTrue(result is Command.StreamStart)
    }

    @Test
    fun testParseStreamStop() {
        val result = parser.parse("stream_stop")
        assertTrue(result is Command.StreamStop)
    }

    @Test
    fun testParseImageHeader() {
        val result = parser.parse("image:test.png:1024")
        assertTrue(result is Command.ImageHeader)
        val cmd = result as Command.ImageHeader
        assertEquals("test.png", cmd.name)
        assertEquals(1024, cmd.size)
    }

    @Test
    fun testParseInvalidColor() {
        val result = parser.parse("color=255,0")
        assertTrue(result is Command.Unknown)
    }
    
    @Test
    fun testParseInvalidImage() {
        val result = parser.parse("image:test.png")
        assertTrue(result is Command.Unknown)
    }

    @Test
    fun testParseBrightnessSettlingQuery() {
        assertSame(Command.GetBrightnessSettleMs, parser.parse("get_brightness_settle_ms"))
    }

    @Test
    fun testParseBrightnessSettlingBounds() {
        for (delayMs in listOf(0L, 3_000L, 60_000L)) {
            assertEquals(
                Command.SetBrightnessSettleMs(delayMs),
                parser.parse("set_brightness_settle_ms:$delayMs")
            )
        }
    }

    @Test
    fun testRejectInvalidBrightnessSettlingValues() {
        for (command in listOf(
            "set_brightness_settle_ms",
            "set_brightness_settle_ms:",
            "set_brightness_settle_ms=-1",
            "set_brightness_settle_ms:-1",
            "set_brightness_settle_ms:60001",
            "set_brightness_settle_ms:1.5",
            "set_brightness_settle_ms:NaN",
            "set_brightness_settle_ms:Infinity",
            "set_brightness_settle_ms:3000:4000",
            "set_brightness_settle_ms:9223372036854775808"
        )) {
            assertSame(command, Command.InvalidBrightnessSettleMs, parser.parse(command))
        }
    }

    @Test
    fun testRejectNonFiniteColorBrightness() {
        for (prefix in listOf("color=", "color_o=")) {
            for (brightness in listOf("NaN", "Infinity", "-Infinity", "1e99")) {
                assertSame(
                    "$prefix with $brightness",
                    Command.Unknown,
                    parser.parse("${prefix}1,2,3,$brightness")
                )
            }
        }
    }

    @Test
    fun testPreserveOptionalBrightnessAndFiniteClamping() {
        assertEquals(Command.SetColor(1, 2, 3), parser.parse("color=1,2,3"))
        assertEquals(Command.SetCircleColor(1, 2, 3), parser.parse("color_o=1,2,3"))
        assertEquals(Command.SetColor(1, 2, 3, 50f), parser.parse("color=1,2,3,50"))
        assertEquals(Command.SetColor(1, 2, 3, 0.01f), parser.parse("color=1,2,3,-10"))
        assertEquals(Command.SetCircleColor(1, 2, 3, 100f), parser.parse("color_o=1,2,3,120"))
    }
}
