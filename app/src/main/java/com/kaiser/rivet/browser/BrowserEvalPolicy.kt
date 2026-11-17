package com.kaiser.rivet.browser

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Pure policy for bounded, same-origin JavaScript evaluation. The WebView bridge and
 * auth/focus decisions stay in BrowserSession; this object normalizes origins, wraps a
 * script in a serialization harness, and keeps results inside the tool budget.
 */
object BrowserEvalPolicy {
    const val MAX_EXPRESSION_CHARS = 8_192
    const val MAX_SCRIPT_CHARS = 16_384
    const val MAX_RESULT_BYTES = 12 * 1_024
    private const val MAX_JS_RESULT_CHARS = 16_384

    data class BoundedResult(val json: String, val truncated: Boolean)

    /** Canonical scheme://host[:port] origin, or null for anything not http(s). */
    fun originOf(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        val host = uri.host?.trim('[', ']')?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) return null
        val port = uri.port.takeIf { it != -1 } ?: if (scheme == "http") 80 else 443
        val renderedHost = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (scheme == "http") 80 else 443
        return buildString {
            append(scheme).append("://").append(renderedHost)
            if (port != defaultPort) append(':').append(port)
        }
    }

    fun sameOrigin(a: String, b: String): Boolean {
        val left = originOf(a) ?: return false
        return left == originOf(b)
    }

    /**
     * Wraps both expression-style input (score.textContent) and statement-style input
     * (const a = 1; return a + 1;) without giving up exception details. Promises are
     * deliberately not awaited; callers use browser_wait_for for async conditions.
     */
    fun wrapScript(expression: String, maxChars: Int = MAX_SCRIPT_CHARS): String {
        val source = expression.trim()
        require(source.isNotEmpty()) { "expression must not be blank" }
        require(source.length <= MAX_EXPRESSION_CHARS) {
            "expression is ${source.length} chars; the limit is $MAX_EXPRESSION_CHARS"
        }
        require(maxChars > 0) { "maxChars must be positive" }
        val script = """
            (function(){
              try {
                var __src = ${jsonQuote(source)};
                var __fn;
                try { __fn = new Function('return (' + __src + '\n)'); }
                catch (__syntax) { __fn = new Function(__src); }
                var __r = __fn.call(window);
                var __seen = [];
                var __max = $MAX_JS_RESULT_CHARS;
                function __clip(value, depth) {
                  if (value === undefined) return '[undefined]';
                  if (typeof value === 'function') return '[function]';
                  if (typeof value === 'number' && !isFinite(value)) return String(value);
                  if (value === null || typeof value !== 'object') return value;
                  if (value.nodeType === 1) {
                    var out = '<' + value.tagName.toLowerCase();
                    if (value.id) out += '#' + value.id;
                    if (value.className && typeof value.className === 'string') {
                      out += '.' + value.className.trim().split(/\s+/).join('.');
                    }
                    return out + '>';
                  }
                  if (__seen.indexOf(value) >= 0) return '[circular]';
                  if (depth > 6) return '[depth]';
                  __seen.push(value);
                  var clipped;
                  if (Array.isArray(value)) {
                    clipped = [];
                    for (var i = 0; i < value.length && i < 100; i++) {
                      clipped.push(__clip(value[i], depth + 1));
                    }
                    if (value.length > 100) clipped.push('[truncated]');
                  } else {
                    clipped = {};
                    var keys = Object.keys(value);
                    for (var k = 0; k < keys.length && k < 100; k++) {
                      try { clipped[keys[k]] = __clip(value[keys[k]], depth + 1); }
                      catch (__read) { clipped[keys[k]] = '[unreadable]'; }
                    }
                    if (keys.length > 100) clipped['__truncated__'] = true;
                  }
                  __seen.pop();
                  return clipped;
                }
                var __value = __clip(__r, 0);
                var __out = JSON.stringify({ok:true, type:typeof __r, value:__value});
                if (__out.length > __max) {
                  var __preview = String(__value);
                  if (__preview.length > __max) __preview = __preview.slice(0, __max);
                  __out = JSON.stringify({ok:true, type:typeof __r, value:__preview,
                    truncated:true});
                }
                return __out;
              } catch (__e) {
                return JSON.stringify({
                  ok:false,
                  name:String(__e && __e.name || 'Error'),
                  message:String(__e && __e.message || __e).slice(0, 1000),
                  stack:String(__e && __e.stack || '').slice(0, 2000)
                });
              }
            })()
        """.trimIndent()
        require(script.length <= maxChars) {
            "wrapped script is ${script.length} chars; the limit is $maxChars"
        }
        return script
    }

    /**
     * Ensures the serialized result is valid JSON within [maxBytes], truncating only the
     * value preview and marking the result. This runs after the WebView bridge, so the
     * page can never force an oversized tool result.
     */
    fun boundJson(raw: String, maxBytes: Int = MAX_RESULT_BYTES): BoundedResult {
        require(maxBytes > 0) { "maxBytes must be positive" }
        if (raw.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return BoundedResult(raw, false)
        }
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            ?: return BoundedResult(clipUtf8(raw, maxBytes), true)
        val obj = runCatching { element.jsonObject }.getOrNull()
            ?: return BoundedResult(clipUtf8(raw, maxBytes), true)
        val original = obj["value"]?.let {
            if (it is JsonPrimitive) it.content else it.toString()
        }.orEmpty()
        var keep = original.length
        while (keep >= 0) {
            val copy = obj.toMutableMap()
            copy["value"] = JsonPrimitive(if (original.length > keep) {
                original.take(keep) + "…"
            } else original)
            copy["truncated"] = JsonPrimitive(true)
            val candidate = JsonObject(copy).toString()
            if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                return BoundedResult(candidate, true)
            }
            if (keep == 0) break
            keep = (keep * 3) / 4
        }
        val minimal = JsonObject(mapOf(
            "ok" to (obj["ok"] ?: JsonPrimitive(false)),
            "truncated" to JsonPrimitive(true),
            "value" to JsonPrimitive(""),
        )).toString()
        return BoundedResult(minimal, true)
    }

    private fun clipUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end = (end * 3) / 4
        }
        return value.substring(0, end)
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else append(ch)
            }
        }
        append('"')
    }
}
