package com.aniob.app.device

import android.content.ClipboardManager
import android.content.Context

/**
 * `ClipboardManager.OnPrimaryClipChangedListener` for rapid copy-paste flows.
 *
 * Android 10+ lets only the focused app or the active IME read the clipboard, so a read from the
 * background can come back empty. The watcher therefore remembers the last text it was *told* about
 * at change time (while the service context is allowed to read it); `clipboard_get` falls back to that
 * cache before reporting "empty". Memory only, trimmed, never logged.
 */
object AniobClipboardWatcher {

    private const val MAX_CHARS = 2000

    @Volatile var lastText: String? = null
        private set

    @Volatile var lastChangedAt: Long = 0L
        private set

    private var manager: ClipboardManager? = null
    private var appContext: Context? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val ctx = appContext ?: return@OnPrimaryClipChangedListener
        val text = runCatching {
            manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        }.getOrNull()
        lastChangedAt = System.currentTimeMillis()
        lastText = text?.take(MAX_CHARS)
    }

    @Synchronized
    fun start(context: Context) {
        if (manager != null) return
        appContext = context.applicationContext
        manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.addPrimaryClipChangedListener(listener)
    }

    @Synchronized
    fun stop() {
        manager?.removePrimaryClipChangedListener(listener)
        manager = null
        appContext = null
    }

    /** Records text the app itself just put on the clipboard (the listener may not fire for own writes). */
    fun noteOwnWrite(text: String) {
        lastText = text.take(MAX_CHARS)
        lastChangedAt = System.currentTimeMillis()
    }
}
