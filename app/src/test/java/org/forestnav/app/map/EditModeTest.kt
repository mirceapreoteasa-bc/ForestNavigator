package org.forestnav.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that keep road cutting out of a button a user presses while walking the map.
 *
 * These are the assertions worth having fail loudly, because each one protects against a
 * road being cut when the user meant to do something else entirely.
 */
class EditModeTest {

    private fun noText(d: EditMode.Decision): String? = (d as? EditMode.Decision.No)?.why

    // ---- starting points ---------------------------------------------------

    @Test
    fun pointsStartWhenNothingElseIsOpen() {
        assertTrue(EditMode.canStartPoints(pointSessionOpen = false, lineSessionExists = false) is EditMode.Decision.Go)
    }

    @Test
    fun pointsRefuseWhenAlreadyOpen() {
        val d = EditMode.canStartPoints(pointSessionOpen = true, lineSessionExists = false)
        assertTrue(d is EditMode.Decision.No)
        assertEquals("Already editing", noText(d))
    }

    @Test
    fun pointsRefuseWhileLineCuttingIsOpen() {
        val d = EditMode.canStartPoints(pointSessionOpen = false, lineSessionExists = true)
        assertTrue(d is EditMode.Decision.No)
        assertEquals("Stop line editing first (Stop edit)", noText(d))
    }

    // ---- starting cutting --------------------------------------------------

    @Test
    fun cuttingStartsWhenNothingElseIsOpen() {
        val d = EditMode.canStartCutting(pointSessionOpen = false, lineSessionExists = false)
        assertTrue(d is EditMode.Decision.Go)
    }

    @Test
    fun cuttingRefusesWhilePointEditingIsOpen() {
        // The dangerous direction: armed cutting plus a point session would mean one tap
        // could be read as either, and the layer written is different each time.
        val d = EditMode.canStartCutting(pointSessionOpen = true, lineSessionExists = false)
        assertTrue(d is EditMode.Decision.No)
        assertEquals("Stop point editing first (Stop edit)", noText(d))
    }

    @Test
    fun cuttingRefusesASecondTimeSoStagedCutsAreNotLost() {
        val d = EditMode.canStartCutting(pointSessionOpen = false, lineSessionExists = true)
        assertTrue(d is EditMode.Decision.No)
        assertEquals("Already cutting - save or stop that first", noText(d))
    }

    @Test
    fun theTwoEditorsAreNeverBothOpen() {
        // The whole invariant as a property over every combination of state.
        for (pointsOpen in listOf(false, true)) {
            for (cutsExist in listOf(false, true)) {
                if (cutsExist) {
                    assertTrue(
                        "a cut session must always block starting points",
                        EditMode.canStartPoints(pointsOpen, cutsExist) is EditMode.Decision.No,
                    )
                }
                if (pointsOpen) {
                    assertTrue(
                        "open points must always block starting cuts",
                        EditMode.canStartCutting(pointsOpen, cutsExist) is EditMode.Decision.No,
                    )
                }
                if (EditMode.canStartCutting(pointsOpen, cutsExist) is EditMode.Decision.Go) {
                    assertFalse("cutting allowed while points open", pointsOpen)
                    assertFalse("cutting allowed twice", cutsExist)
                }
            }
        }
    }

    @Test
    fun onceOneEditorIsOpenTheOtherCannotBeOpened() {
        // From a clean state both are offered - the user picks one. The invariant is that
        // opening either closes off the other, so a tap can never be ambiguous.
        for (pointsOpen in listOf(false, true)) {
            for (cutsExist in listOf(false, true)) {
                val pointsAllowed = EditMode.canStartPoints(pointsOpen, cutsExist) is EditMode.Decision.Go
                val cutsAllowed = EditMode.canStartCutting(pointsOpen, cutsExist) is EditMode.Decision.Go
                if (pointsOpen) {
                    assertFalse("points were open yet could be started again", pointsAllowed)
                }
                if (cutsExist) {
                    assertFalse("a cut session existed yet cutting could start", cutsAllowed)
                }
                if (!pointsOpen && !cutsExist) {
                    assertTrue("from a clean state the user must be able to start something",
                        pointsAllowed && cutsAllowed)
                }
            }
        }
    }

    // ---- taps --------------------------------------------------------------

    @Test
    fun aTapCutsOnlyWhenCuttingIsArmed() {
        assertTrue(EditMode.tapCutsLine(cutArmed = true))
        assertFalse(
            "with no cut armed, a tap must never cut a road",
            EditMode.tapCutsLine(cutArmed = false),
        )
    }

    // ---- the banner --------------------------------------------------------

    @Test
    fun noBannerWhenNotCutting() {
        assertNull(EditMode.lineBanner(cutArmed = false, pending = 0))
        assertNull(
            "pending cuts with nothing armed means the session was cancelled; no banner",
            EditMode.lineBanner(cutArmed = false, pending = 2),
        )
    }

    @Test
    fun theBannerCountsUnsavedCuts() {
        val banner = EditMode.lineBanner(cutArmed = true, pending = 3)
        assertTrue("banner must name the count so a cut is not forgotten: $banner",
            banner!!.contains("3"))
        assertTrue(banner.contains("not yet saved"))
    }

    @Test
    fun anEmptyCutSessionStillSaysWhatToDo() {
        val banner = EditMode.lineBanner(cutArmed = true, pending = 0)
        assertTrue(banner!!.contains("tap the line"))
    }

    // ---- stopping ----------------------------------------------------------

    @Test
    fun stoppingWithStagedCutsOffersSaveDiscardOrCarryOn() {
        assertEquals(EditMode.StopAction.SAVE, EditMode.stopAction(pendingCuts = 2))
        assertEquals(
            "with nothing staged, Stop edit must not open a dialog about saving",
            EditMode.StopAction.NOT_CUTTING, EditMode.stopAction(pendingCuts = 0),
        )
    }
}