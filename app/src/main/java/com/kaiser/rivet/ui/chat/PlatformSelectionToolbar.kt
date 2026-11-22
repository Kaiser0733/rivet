package com.kaiser.rivet.ui.chat

import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import com.kaiser.rivet.R
import kotlin.math.ceil
import kotlin.math.floor

@Composable
internal fun PlatformSelectionToolbar(content: @Composable () -> Unit) {
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val shareClipboard = remember(clipboard) { SelectionClipboard(clipboard) }
    val toolbar = remember(view, shareClipboard) { ChatTextToolbar(view, shareClipboard) }
    DisposableEffect(toolbar) { onDispose(toolbar::hide) }
    CompositionLocalProvider(
        LocalClipboardManager provides shareClipboard,
        LocalTextToolbar provides toolbar,
        content = content,
    )
}

private class SelectionClipboard(private val base: ClipboardManager) : ClipboardManager by base {
    private var collect: ((String) -> Unit)? = null

    override fun setText(annotatedString: AnnotatedString) {
        val receive = collect
        if (receive != null) receive(annotatedString.text) else base.setText(annotatedString)
    }

    fun selection(copy: () -> Unit): String? {
        var selected: String? = null
        collect = { selected = it }
        try { copy() } finally { collect = null }
        return selected
    }
}

private class ChatTextToolbar(private val view: View, private val clipboard: SelectionClipboard) :
    ActionMode.Callback2(), TextToolbar {
    private data class Action(val id: Int, val title: Int, val run: () -> Unit)

    private var mode: ActionMode? = null
    private var bounds = Rect.Zero
    private var actions = emptyList<Action>()
    override val status: TextToolbarStatus
        get() = if (mode == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown

    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                          onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        bounds = rect
        actions = listOfNotNull(
            onCopyRequested?.let { Action(android.R.id.copy, android.R.string.copy, it) },
            onPasteRequested?.let { Action(android.R.id.paste, android.R.string.paste, it) },
            onCutRequested?.let { Action(android.R.id.cut, android.R.string.cut, it) },
            onSelectAllRequested?.let { Action(android.R.id.selectAll, android.R.string.selectAll, it) },
            onCopyRequested?.let { copy -> Action(android.R.id.shareText, R.string.chat_share_selection) {
                clipboard.selection(copy)?.takeIf(String::isNotBlank)?.let { text ->
                    view.context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, null))
                }
            } },
        )
        val active = mode
        if (active == null) mode = view.startActionMode(this, ActionMode.TYPE_FLOATING)
        else {
            active.invalidate()
            active.invalidateContentRect()
        }
    }

    override fun hide() { mode?.finish() }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = onPrepareActionMode(mode, menu)

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.clear()
        actions.forEachIndexed { order, action -> menu.add(Menu.NONE, action.id, order, action.title) }
        return actions.isNotEmpty()
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
        outRect.set(floor(bounds.left).toInt(), floor(bounds.top).toInt(),
            ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = actions.firstOrNull { it.id == item.itemId } ?: return false
        action.run()
        if (item.itemId != android.R.id.selectAll) mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        if (this.mode == mode) this.mode = null
    }
}
