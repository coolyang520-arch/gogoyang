package com.gogoyang.plates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideTest {
    @Test fun nothingRecorded() {
        assertEquals(Decide.Verdict.None, Decide.decide(emptyMap()))
    }

    @Test fun weakScoresAreIgnored() {
        assertEquals(Decide.Verdict.None, Decide.decide(mapOf("a" to 12, "b" to 5)))
    }

    @Test fun oneClearMatch() {
        assertEquals(Decide.Verdict.Same("a", 90), Decide.decide(mapOf("a" to 90, "b" to 30, "c" to 4)))
    }

    @Test fun clearMatchWithNoRunnerUp() {
        assertEquals(Decide.Verdict.Same("a", 45), Decide.decide(mapOf("a" to 45)))
    }

    @Test fun middlingScoreIsOnlyAMaybe() {
        assertEquals(Decide.Verdict.Maybe("a", 30), Decide.decide(mapOf("a" to 30, "b" to 8)))
    }

    @Test fun twoCloseMatchesAreNeverGuessed() {
        val v = Decide.decide(mapOf("a" to 80, "b" to 70, "c" to 10))
        assertEquals(Decide.Verdict.Several(listOf("a", "b")), v)
    }

    @Test fun severalListsAtMostFourBestFirst() {
        val v = Decide.decide(mapOf("a" to 50, "b" to 60, "c" to 55, "d" to 52, "e" to 51))
        assertTrue(v is Decide.Verdict.Several)
        assertEquals(listOf("b", "c", "d", "e"), (v as Decide.Verdict.Several).ids)
    }

    @Test fun plateBox() {
        assertTrue(Decide.inPlate(0.5f, 0.7f))
        assertTrue(!Decide.inPlate(0.5f, 0.2f))
        assertTrue(!Decide.inPlate(0.1f, 0.7f))
    }
}
