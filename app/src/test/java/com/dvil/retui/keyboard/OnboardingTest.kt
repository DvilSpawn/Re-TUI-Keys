package com.dvil.retui.keyboard

import com.dvil.retui.keyboard.Onboarding.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingTest {
    @Test
    fun freshInstallGetsSetup() {
        assertEquals(Surface.SETUP, Onboarding.surfaceFor(setupDone = false, keyboardEnabled = false, currentVersion = 35, seenVersion = null))
    }

    @Test
    fun existingUserUpdatingGetsTheNotesNotSetup() {
        assertEquals(Surface.CHANGELOG, Onboarding.surfaceFor(setupDone = false, keyboardEnabled = true, currentVersion = 35, seenVersion = null))
    }

    @Test
    fun unfinishedSetupResumesAfterTheKeyboardWasTurnedOn() {
        assertEquals(Surface.SETUP, Onboarding.surfaceFor(setupDone = false, keyboardEnabled = true, currentVersion = 35, seenVersion = null, setupStarted = true))
    }

    @Test
    fun newerVersionShowsTheNotesAgain() {
        assertEquals(Surface.CHANGELOG, Onboarding.surfaceFor(setupDone = true, keyboardEnabled = true, currentVersion = 36, seenVersion = 35))
    }

    @Test
    fun seenVersionGoesStraightToSettings() {
        assertEquals(Surface.SETTINGS, Onboarding.surfaceFor(setupDone = true, keyboardEnabled = true, currentVersion = 35, seenVersion = 35))
        assertEquals(Surface.SETTINGS, Onboarding.surfaceFor(setupDone = true, keyboardEnabled = false, currentVersion = 35, seenVersion = 35))
    }
}
