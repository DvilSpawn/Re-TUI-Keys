package com.dvil.retui.keyboard

import org.junit.Assert.assertThrows
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
}
