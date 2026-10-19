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
}
