package com.dvil.retui.keyboard

import com.dvil.retui.contract.RetuiVisualContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherFrameRendererTest {
    @Test
    fun validatesSlicesAgainstDecodedDimensions() {
        LauncherFrameRenderer.FrameSpec(4, 5, 4, 5).validate(20, 24)
        assertThrows(IllegalArgumentException::class.java) {
            LauncherFrameRenderer.FrameSpec(10, 2, 10, 2).validate(20, 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LauncherFrameRenderer.FrameSpec(0, 2, 2, 2).validate(20, 20)
        }
    }

    @Test
    fun scalesAllBordersTogetherForShortKeys() {
        assert(
            LauncherFrameRenderer.FrameSpec(8, 8, 8, 8).destinationBorders(80, 12) ==
                LauncherFrameRenderer.FrameSpec(6, 6, 6, 6)
        )
    }

    @Test
    fun coversAllContractRoles() {
        assertEquals(16, LauncherFrameRenderer.ROLE_NAMES.size)
        assertEquals(RetuiVisualContract.KEYBOARD_FRAME_ROLES.toSet(), LauncherFrameRenderer.ROLE_NAMES)
    }

    @Test
    fun mergesRolesIndependentlyAndIgnoresUnknownRoles() {
        val sharedImage = "a".repeat(64)
        val keyboard = state("keyboard-v1", sharedImage)
        val settings = state("settings-v1", sharedImage)
        val current = mapOf(
            RetuiVisualContract.FRAME_ROLE_KEYBOARD to keyboard,
            RetuiVisualContract.FRAME_ROLE_SETTINGS to settings
        )

        val cleared = LauncherFrameRenderer.mergeRoleStates(
            current,
            mapOf(
                RetuiVisualContract.FRAME_ROLE_SETTINGS to null,
                "unknown" to state("ignored", "b".repeat(64))
            )
        )

        assertEquals(mapOf(RetuiVisualContract.FRAME_ROLE_KEYBOARD to keyboard), cleared)
        assertEquals(sharedImage, keyboard.imageId)
        assertEquals(sharedImage, settings.imageId)
    }

    @Test
    fun missingOrInvalidUpdatesRetainLastValidState() {
        val keyboard = state("keyboard-v1", "c".repeat(64))
        val current = mapOf(RetuiVisualContract.FRAME_ROLE_KEYBOARD to keyboard)

        assertEquals(current, LauncherFrameRenderer.mergeRoleStates(current, emptyMap()))
        assertTrue(LauncherFrameRenderer.mergeRoleStates(current, emptyMap()).containsValue(keyboard))
    }

    private fun state(assetId: String, imageId: String) = LauncherFrameRenderer.RoleState(
        assetId,
        imageId,
        LauncherFrameRenderer.FrameSpec(4, 4, 4, 4)
    )
}
