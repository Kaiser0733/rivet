package com.kaiser.rivet.ui.chat

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h700dp")
class ChatImageScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aDetachedReaderKeepsBothIndexAndOffsetWhenTheBitmapArrives() {
        checkDecode(followBottom = false)
    }

    @Test fun aFollowingReaderMovesByTheReservedDeltaOfZero() {
        checkDecode(followBottom = true)
    }

    private fun checkDecode(followBottom: Boolean) {
        val bounds = Size(400, 200)
        val image = mutableStateOf<ChatImageState>(ChatImageState.Loading(bounds))
        val list = LazyListState()
        compose.setContent {
            LazyColumn(Modifier.height(260.dp), state = list) {
                items(39) { Box(Modifier.height(48.dp)) }
                item {
                    Column {
                        Box(Modifier.height(420.dp))
                        ReservedChatImage(image.value, "Image") {}
                    }
                }
            }
            LaunchedEffect(list) {
                list.scrollToItem(39, 10_000)
                if (followBottom) {
                    snapshotFlow {
                        val layout = list.layoutInfo
                        val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow 0
                        last.offset + last.size - layout.viewportEndOffset
                    }.collect { gap ->
                        if (gap > 0) list.scrollBy(gap.toFloat())
                    }
                }
            }
        }

        val before = compose.runOnIdle {
            Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset,
                list.layoutInfo.visibleItemsInfo.last().size)
        }
        assertEquals(39, before.first)
        assertTrue(before.third > list.layoutInfo.viewportSize.height)
        compose.runOnIdle {
            // The decoder can downsample the bitmap, but its dimensions never drive the layout.
            val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
            image.value = ChatImageState.Ready(bounds, bitmap)
        }
        compose.runOnIdle {
            val delta = list.layoutInfo.visibleItemsInfo.last().size - before.third
            assertEquals(0, delta)
            assertEquals(before.first, list.firstVisibleItemIndex)
            assertEquals(before.second + if (followBottom) delta else 0, list.firstVisibleItemScrollOffset)
            assertFalse(list.canScrollForward)
        }
    }
}
