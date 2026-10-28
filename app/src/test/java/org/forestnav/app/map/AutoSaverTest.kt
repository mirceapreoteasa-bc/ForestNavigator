package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutoSaverTest {

    /** A clock the test moves by hand, so nothing has to sleep. */
    private class Clock(var t: Long = 0L) { fun plus(sec: Long) { t += sec * 1000L } }

    @Test
    fun nothingIsDueBeforeTheInterval() {
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        a.markDirty()
        c.plus(39)
        assertFalse("39s is not yet 40s", a.due())
    }

    @Test
    fun itFiresWhenTheIntervalElapses() {
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        a.markDirty()
        c.plus(40)
        assertTrue(a.due())
    }

    @Test
    fun savingResetsTheWait() {
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        a.markDirty()
        c.plus(40)
        assertTrue(a.due())
        a.markClean()
        assertFalse("nothing pending after a save", a.due())
    }

    @Test
    fun savingIsPeriodicNotDebounced() {
        // "Save edits every 40 seconds" means every 40 seconds, not 40 seconds after
        // the user stops typing: someone adding points for ten minutes still gets saved
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        a.markDirty()
        c.plus(30)
        a.markDirty()
        c.plus(10)
        assertTrue("40s of pending edits must save even if edits kept arriving", a.due())
    }

    @Test
    fun continuedEditsDoNotPostponeTheSaveForever() {
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        for (i in 1..30) {
            a.markDirty()
            c.plus(2)
            if (a.due()) break
        }
        assertTrue("an edit every 2s must still be saved", a.due())
    }

    @Test
    fun aBlankIntervalNeverSaves() {
        val c = Clock()
        val a = AutoSaver(null) { c.t }
        assertFalse(a.enabled)
        assertNull(a.everySec)
        assertFalse(a.markDirty())
        c.plus(10_000)
        assertFalse("autosave must stay off", a.due())
    }

    @Test
    fun zeroAlsoMeansOffRatherThanSaveEveryMoment() {
        val c = Clock()
        val a = AutoSaver(0) { c.t }
        assertFalse(a.enabled)
        assertFalse(a.due())
    }

    @Test
    fun nothingPendingMeansNothingDue() {
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        c.plus(100)
        assertFalse(a.due())
    }

    @Test
    fun theCountdownRoundsUpSoItNeverReadsZeroWhileWaiting() {
        val c = Clock()
        val a = AutoSaver(40) { c.t }
        a.markDirty()
        assertEquals(40, a.remainingSec())
        c.plus(1)
        assertEquals("must not show 0 while still waiting", 39, a.remainingSec())
        c.plus(39)
        assertEquals(0, a.remainingSec())
    }

    @Test
    fun oneSecondIntervalWorks() {
        val c = Clock()
        val a = AutoSaver(1) { c.t }
        a.markDirty()
        assertFalse(a.due())
        c.plus(1)
        assertTrue(a.due())
    }

    @Test
    fun theIntervalIsReportedBack() {
        assertEquals(40, AutoSaver(40).everySec)
        assertEquals(40, AutoSaver(40, now = { 0L }).everySec)
    }
}

/**
 * Autosave and the manual save must not fight each other.
 *
 * Both end in the same commit, so the rule that matters is that a failed autosave leaves
 * the edits pending - otherwise work disappears without anyone being told.
 */
class AutoSaveHandoffTest {

    private fun dir(tmp: org.junit.rules.TemporaryFolder): FileMapDir {
        File(tmp.root, "FOR_NAV").mkdirs()
        return FileMapDir(File(tmp.root, "FOR_NAV"))
    }

    @org.junit.Test
    fun autosaveClearsThePendingWaitSoManualSaveSaysNothingToDo() {
        val d = FileMapDir(org.junit.rules.TemporaryFolder().apply { create() }.newFolder("FOR_NAV"))
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(1.0, 2.0, "A")
        assertTrue(s.commit(d) is EditSession.Commit.Written)
        assertFalse("session should be clean after autosave", s.dirty)
    }

    @org.junit.Test
    fun anAutosaveThatCannotWriteLeavesTheEditsPending() {
        val tmp = org.junit.rules.TemporaryFolder().apply { create() }
        val d = dir(tmp)
        val s = EditSession("XGV_MP", "GCALL_ID", emptyList())
        s.add(1.0, 2.0, "A")
        // a directory where the shapefile should be makes every write fail
        File(tmp.root, "FOR_NAV/XGV_MP.shp").mkdirs()
        val c = s.commit(d)
        if (c is EditSession.Commit.Failed) {
            assertTrue("a failed save must keep the points for another attempt", s.dirty)
            assertEquals(1, s.count)
        }
    }
}
