package com.kaiser.rivet.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSheetTest {
    @Test fun menuIsLaidOutFromThePlusButton() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val screen = root.resolve("java/com/kaiser/rivet/ui/chat/ChatScreen.kt").readText()
        val plus = screen.indexOf("onClick = { attachmentsOpen = true }")
        assertTrue(plus >= 0 && screen.indexOf("AttachmentSheet(", plus) in (plus + 1)..(plus + 550))
    }

    @Test fun menuEndsAboveThePlusAndAlignsWithTheComposerAcrossWindowSizes() {
        val position = AttachmentSheetPosition(composerInset = 4)
        for (window in listOf(IntSize(360, 780), IntSize(780, 360), IntSize(900, 1200), IntSize(1200, 900))) {
            val left = (window.width - 720).coerceAtLeast(0) / 2 + 12
            val top = window.height - 60
            assertEquals(IntOffset(left, top - 196), position.calculatePosition(
                IntRect(left + 4, top, left + 52, top + 48), window,
                LayoutDirection.Ltr, IntSize(264, 196),
            ))
        }
    }
}
