package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineChatImageSizeTest {
    @Test fun bothAspectRatiosFitPhoneAndTabletInEitherOrientation() {
        for ((chat, screen) in listOf(328f to 800f, 748f to 360f, 768f to 1280f, 1008f to 800f, 174f to 360f)) {
            for ((sourceWidth, sourceHeight) in listOf(600f to 1350f, 1300f to 600f)) {
                val (width, height) = inlineMessageImageSize(sourceWidth, sourceHeight, chat, screen)
                assertTrue(width > 0f && width <= minOf(chat * 0.55f, 320f) + 0.01f)
                assertTrue(height > 0f && height <= minOf(screen * 0.46f, 280f) + 0.01f)
                assertEquals(sourceWidth / sourceHeight, width / height, 0.001f)
            }
        }
    }

    @Test fun landscapeImageUsesTheWidthCapAndPortraitImageUsesTheHeightCap() {
        val (wide, _) = inlineMessageImageSize(1200f, 500f, 1008f, 800f)
        val (_, tall) = inlineMessageImageSize(460f, 950f, 768f, 1280f)
        assertEquals(320f, wide, 0.01f)
        assertEquals(280f, tall, 0.01f)
        assertEquals(80f to 60f, inlineMessageImageSize(80f, 60f, 768f, 1280f))
    }
}
