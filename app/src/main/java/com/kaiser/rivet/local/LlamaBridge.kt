package com.kaiser.rivet.local

/** One loaded llama.cpp model and its context. Native methods are synchronized by
 * [LlamaBridge]; a second load must be preceded by close() in [LocalRuntime]. */
internal class LlamaModel internal constructor(val handle: Long) : AutoCloseable {
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
        if (available && model.handle != 0L) nativeFreeModel(model.handle)
    }

    fun stop(model: LlamaModel) {
        if (available && model.handle != 0L) nativeStop(model.handle)
    }

    fun chatTemplate(model: LlamaModel): String =
        if (!available) "" else nativeChatTemplate(model.handle)

    fun applyTemplate(model: LlamaModel, roles: List<String>, contents: List<String>,
                      addAssistant: Boolean): String {
        if (!available || roles.size != contents.size) return ""
        return nativeApplyTemplate(model.handle, roles.toTypedArray(), contents.toTypedArray(),
            addAssistant)
    }

    fun tokenize(model: LlamaModel, text: String, addSpecial: Boolean): IntArray =
        if (!available) IntArray(0) else nativeTokenize(model.handle, text, addSpecial)

    fun generate(model: LlamaModel, prompt: String, maxTokens: Int, temperature: Float,
                 topP: Float, grammar: String?, onToken: (String) -> Unit): String {
        if (!available) return ""
        val callback = object {
            @Suppress("unused")
            fun onToken(token: String) = onToken.invoke(token)
        }
        return nativeGenerate(model.handle, prompt, maxTokens, temperature, topP,
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
