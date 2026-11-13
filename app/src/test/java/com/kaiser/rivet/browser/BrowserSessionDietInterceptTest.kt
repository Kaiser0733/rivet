package com.kaiser.rivet.browser

import android.app.Application
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionDietInterceptTest {
    private fun fakeRequest(
        url: String,
        mainFrame: Boolean,
        headers: Map<String, String>,
    ): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = headers
    }

    @Test fun strictDietNeverInterceptsMainFrameHtmlWithImageAcceptHeader() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        val webView = WebView(app)
        session.attach(webView)
        session.refreshBudget(ownGrowthBytes = 150L * 1024 * 1024)
        assertEquals(BrowserTier.StrictDiet, session.state.value.tier)

        val client = webView.webViewClient
        val mainFrameHtml = fakeRequest(
            url = "https://en.m.wikipedia.org/wiki/Cat",
            mainFrame = true,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            ),
        )
        assertNull(
            "Main-frame HTML navigation must pass through untouched under StrictDiet",
            client.shouldInterceptRequest(webView, mainFrameHtml),
        )

        val subresourceImage = fakeRequest(
            url = "https://upload.wikimedia.org/wikipedia/commons/thumb/cat.jpg",
            mainFrame = false,
            headers = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Sec-Fetch-Dest" to "image",
            ),
        )
        val imageResponse = client.shouldInterceptRequest(webView, subresourceImage)
        assertNotNull("Subresource images remain blocked with 1x1 PNG under StrictDiet", imageResponse)
        assertEquals("image/png", imageResponse!!.mimeType)
        session.endSession()
    }
}
