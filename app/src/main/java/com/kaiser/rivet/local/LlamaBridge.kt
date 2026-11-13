package com.kaiser.rivet.local

import java.util.concurrent.atomic.AtomicLong

/** One loaded llama.cpp model and its context. Native methods are synchronized by
 * [LlamaBridge]; a second load must be preceded by close() in [LocalRuntime]. */
internal class LlamaModel internal constructor(initialHandle: Long) : AutoCloseable {
    private val rawHandle = AtomicLong(initialHandle)

    val handle: Long
        get() = rawHandle.get()

    internal fun takeHandle(): Long = rawHandle.getAndSet(0L)

    override fun close() = LlamaBridge.free(this)
}

internal object LlamaBridge {
    private val available: Boolean = runCatching {
        System.loadLibrary("rivet_llama")
        true
    }.getOrElse { false }

    fun isAvailable(): Boolean = available

    fun load(path: String, contextTokens: Int, threads: Int): LlamaModel? {
        if (!available) return null
        val handle = nativeLoadModel(path, contextTokens.coerceAtLeast(512), threads.coerceAtLeast(1))
        return handle.takeIf { it != 0L }?.let(::LlamaModel)
    }

    fun free(model: LlamaModel) {
        val handle = model.takeHandle()
        if (available && handle != 0L) nativeFreeModel(handle)
    }

    fun stop(model: LlamaModel) {
        val handle = model.handle
        if (available && handle != 0L) nativeStop(handle)
    }

    fun chatTemplate(model: LlamaModel): String {
        val handle = model.handle
        return if (!available || handle == 0L) "" else nativeChatTemplate(handle)
    }

    fun applyTemplate(model: LlamaModel, roles: List<String>, contents: List<String>,
                      addAssistant: Boolean): String {
        val handle = model.handle
        if (!available || handle == 0L || roles.size != contents.size) return ""
        return nativeApplyTemplate(handle, roles.toTypedArray(), contents.toTypedArray(),
            addAssistant)
    }

    fun tokenize(model: LlamaModel, text: String, addSpecial: Boolean): IntArray {
        val handle = model.handle
        return if (!available || handle == 0L) IntArray(0) else nativeTokenize(handle, text, addSpecial)
    }

    fun generate(model: LlamaModel, prompt: String, maxTokens: Int, temperature: Float,
                 topP: Float, grammar: String?, onToken: (String) -> Unit): String {
        val handle = model.handle
        if (!available || handle == 0L) return ""
        val callback = object {
            @Suppress("unused")
            fun onToken(token: String) = onToken.invoke(token)
        }
        return nativeGenerate(handle, prompt, maxTokens, temperature, topP,
            grammar.orEmpty(), callback)
    }

    private external fun nativeLoadModel(path: String, contextTokens: Int, threads: Int): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeChatTemplate(handle: Long): String
    private external fun nativeApplyTemplate(handle: Long, roles: Array<String>,
                                             contents: Array<String>, addAssistant: Boolean): String
    private external fun nativeTokenize(handle: Long, text: String, addSpecial: Boolean): IntArray
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int,
                                        temperature: Float, topP: Float, grammar: String,
                                        callback: Any): String
}
