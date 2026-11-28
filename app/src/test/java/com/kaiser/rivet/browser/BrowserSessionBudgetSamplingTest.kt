package com.kaiser.rivet.browser

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionBudgetSamplingTest {
    private lateinit var scheduler: TestCoroutineScheduler

    @Before fun setUp() {
        val dispatcher = UnconfinedTestDispatcher()
        scheduler = dispatcher.scheduler
        Dispatchers.setMain(dispatcher)
        RuntimeEnvironment.getApplication().getSharedPreferences("browser_memory", Context.MODE_PRIVATE)
            .edit().putInt("limit_mb", 250).commit()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun newBrowserSessionsUseTheSavedLimitAndDefaultToTwoGigabytes() {
        val app: Application = RuntimeEnvironment.getApplication()
        setAvailableMemory(app, availableMb = 4096)
        val preferences = app.getSharedPreferences("browser_memory", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val original = BrowserSession(app, models = null)
        try {
            original.userOpen()
            original.refreshBudget(ownGrowthBytes = 250L * 1024 * 1024)
            assertEquals(2048, original.state.value.capMb)
            assertEquals(BrowserTier.Normal, original.state.value.tier)

            assertTrue(preferences.edit().putInt("limit_mb", 250).commit())
            val next = BrowserSession(app, models = null)
            try {
                next.refreshBudget(ownGrowthBytes = 250L * 1024 * 1024)
                assertEquals(250, next.state.value.capMb)
                assertEquals(BrowserTier.NoRenderer, next.state.value.tier)
                original.refreshBudget()
                assertEquals(2048, original.state.value.capMb)
            } finally {
                next.endSession()
            }
            original.endSession()
            original.userOpen()
            original.refreshBudget(ownGrowthBytes = 250L * 1024 * 1024)
            assertEquals(250, original.state.value.capMb)
        } finally {
            original.endSession()
        }
    }

    @Test fun onPageFinishedSamplesMemoryAndRaisesNoticeWithoutWaitingFiveSeconds() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        setAvailableMemory(app)
        val session = BrowserSession(app, models = null)
        val pssBytes = AtomicLong(40L * 1024 * 1024)
        val samples = AtomicInteger(0)
        session.memoryProbe = BrowserMemoryProbe(
            ownPss = {
                samples.incrementAndGet()
                pssBytes.get()
            },
            rendererPss = { null },
        )
        session.userOpen()
        val webView = WebView(app)
        session.attach(webView)
        withTimeout(2_000) {
            while (samples.get() < 1) delay(20)
        }

        val samplesBeforeFinish = samples.get()
        pssBytes.set(40L * 1024 * 1024 + 150L * 1024 * 1024)
        webView.webViewClient.onPageFinished(webView, "https://example.com/heavy")

        val tightened = withTimeout(2_500) {
            session.state.first {
                it.tier == BrowserTier.StrictDiet && it.notice?.contains("diet tightened") == true
            }
        }
        assertTrue(samples.get() > samplesBeforeFinish)
        assertEquals(BrowserTier.StrictDiet, tightened.tier)
        session.endSession()
    }

    @Test fun postLoadSpikeSurfacesWithinPostLoadFollowUpWindow() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        setAvailableMemory(app)
        val session = BrowserSession(app, models = null)
        val pssBytes = AtomicLong(40L * 1024 * 1024)
        val samples = AtomicInteger(0)
        session.memoryProbe = BrowserMemoryProbe(
            ownPss = {
                samples.incrementAndGet()
                pssBytes.get()
            },
            rendererPss = { null },
        )
        // Leave the session not-visible: the 5 s periodic fallback must not be able to
        // satisfy this assertion. Only the +1.5/+3.0 s post-load follow-up should.
        val webView = WebView(app)
        session.attach(webView)
        withTimeout(2_000) {
            while (samples.get() < 1) delay(20)
        }

        webView.webViewClient.onPageFinished(webView, "https://example.com/async-decode")
        withTimeout(2_000) {
            while (samples.get() < 2) delay(20)
        }
        pssBytes.set(40L * 1024 * 1024 + 150L * 1024 * 1024)
        // The probe increments `samples` while the post-load coroutine is in
        // withContext(Dispatchers.Default), before that coroutine resumes on Main and
        // registers its next virtual delay. Give that handoff a real-time turn before
        // advancing the virtual clock. Cap the virtual advance at +4.5 s so the 5 s
        // periodic fallback cannot satisfy this test.
        var advancedMs = 0L
        while (samples.get() < 3 && advancedMs < 4_500L) {
            scheduler.runCurrent()
            delay(50)
            scheduler.advanceTimeBy(1_500L)
            advancedMs += 1_500L
            scheduler.runCurrent()
        }
        assertTrue("post-load follow-up sampled", samples.get() >= 3)

        val tightened = withTimeout(3_500) {
            session.state.first {
                it.tier == BrowserTier.StrictDiet && it.notice?.contains("diet tightened") == true
            }
        }
        assertEquals(BrowserTier.StrictDiet, tightened.tier)
        session.endSession()
    }

    private fun setAvailableMemory(app: Application, availableMb: Long = 1024) {
        val manager = requireNotNull(app.getSystemService(ActivityManager::class.java))
        val info = ActivityManager.MemoryInfo().apply {
            availMem = availableMb * 1024 * 1024
            totalMem = availMem + 1024L * 1024 * 1024
            threshold = 0L
            lowMemory = false
        }
        Shadow.extract<ShadowActivityManager>(manager).setMemoryInfo(info)
    }
}
