package com.gogoyang.plates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateLogicTest {
    @Test fun extractsCommonFormats() {
        assertEquals("ABC1234", PlateLogic.extract("ABC-1234"))
        assertEquals("123ABC", PlateLogic.extract("123-ABC"))
        assertEquals("AB1234", PlateLogic.extract("AB 1234"))
    }

    @Test fun fixesOneConfusedCharacter() {
        assertEquals("ABC1234", PlateLogic.extract("ABC-1Z34"))
        assertEquals("ABC1234", PlateLogic.extract("A8C-1234"))
    }

    @Test fun joinsTwoLinePlate() {
        assertEquals("ABC1234", PlateLogic.extract("ABC\n1234"))
    }

    @Test fun ignoresNoise() {
        assertNull(PlateLogic.extract("HELLO"))
        assertNull(PlateLogic.extract(""))
    }

    @Test fun formatsPlate() {
        assertEquals("ABC-1234", PlateLogic.format("ABC1234"))
        assertEquals("123-ABC", PlateLogic.format("123ABC"))
    }

    @Test fun nearMatching() {
        val recs = listOf(Rec("ABC1234", 0L))
        assertEquals(1, PlateLogic.findNear("ABC1Z34", recs).size)
        assertEquals(1, PlateLogic.findNear("ABC123", recs).size)
        assertTrue(PlateLogic.findNear("XYZ9999", recs).isEmpty())
        assertTrue(PlateLogic.findNear("ABC1234", recs).isEmpty())
    }

    @Test fun lev1Works() {
        assertTrue(PlateLogic.lev1("ABC1234", "ABC1284"))
        assertFalse(PlateLogic.lev1("ABC1234", "ABC1299"))
    }

    @Test fun searchPartial() {
        val recs = listOf(Rec("ABC1234", 0L), Rec("XYZ9999", 1L))
        assertEquals(1, PlateLogic.search("1234", recs).size)
        assertEquals(1, PlateLogic.search("XYZ", recs).size)
    }

    @Test fun diffCountsChars() {
        assertEquals(0, PlateLogic.diff("ABC1234", "ABC1234"))
        assertEquals(0, PlateLogic.diff("ABC1234", "A8C1234"))
        assertEquals(2, PlateLogic.diff("ABC1234", "ABC1299"))
        assertEquals(1, PlateLogic.diff("ABC1234", "ABC134"))
        assertEquals(99, PlateLogic.diff("ABC1234", "AB12"))
    }
}
