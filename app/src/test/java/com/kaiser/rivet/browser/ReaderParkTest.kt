package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderParkTest {
    private class FakeSurface : RendererSurface {
        val calls = mutableListOf<String>()
        override fun pause() { calls += "pause" }
        override fun setVisible(visible: Boolean) { calls += "visible=$visible" }
        override fun resume() { calls += "resume" }
    }

    @Test fun readerOnPausesThenHidesTheRenderer() {
        val surface = FakeSurface()
        ReaderPark.apply(surface, ReaderPark.Mode.ReaderOn)
        assertEquals(listOf("pause", "visible=false"), surface.calls)
    }

    @Test fun readerOffShowsThenResumesTheRenderer() {
        val surface = FakeSurface()
        ReaderPark.apply(surface, ReaderPark.Mode.ReaderOff)
        assertEquals(listOf("visible=true", "resume"), surface.calls)
    }

    @Test fun aNavigationExitUnparksAReaderPausedSurface() {
        val surface = FakeSurface()
        ReaderPark.apply(surface, ReaderPark.Mode.ReaderOn)
        ReaderPark.unpark(surface)
        assertEquals(listOf("pause", "visible=false", "visible=true", "resume"), surface.calls)
    }
}
