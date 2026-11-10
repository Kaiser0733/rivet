package com.kaiser.rivet.browser

/** The two browser surfaces: invisible text for the AI, full-screen visual for people. */
enum class BrowserSurface { Text, Visual }

enum class BrowserIntent {
    UserOpen,
    ToolOpen,
    ToolRead,
    ToolFind,
    ToolClick,
    ToolType,
    ToolScroll,
    ToolBack,
    ToolShot,
    TextNeedsRenderer,
}

/**
 * Pure routing. A text read is deliberately not a visual action: it must not open
 * or mutate the browser screen. Interaction and a reader that needs a DOM route to
 * the visual session, which starts the watch card but not the full-screen screen.
 */
object BrowserModeDecision {
    fun surfaceFor(intent: BrowserIntent): BrowserSurface = when (intent) {
        BrowserIntent.UserOpen,
        BrowserIntent.ToolClick,
        BrowserIntent.ToolType,
        BrowserIntent.ToolScroll,
        BrowserIntent.ToolBack,
        BrowserIntent.ToolShot,
        BrowserIntent.TextNeedsRenderer -> BrowserSurface.Visual

        BrowserIntent.ToolOpen,
        BrowserIntent.ToolRead,
        BrowserIntent.ToolFind -> BrowserSurface.Text
    }

    /** Only a human opening the browser gets the full-screen screen. */
    fun showsVisualScreen(intent: BrowserIntent): Boolean = intent == BrowserIntent.UserOpen

    /** Text work never spins a renderer; visual work always does. */
    fun startsRenderer(intent: BrowserIntent): Boolean = surfaceFor(intent) == BrowserSurface.Visual
}
