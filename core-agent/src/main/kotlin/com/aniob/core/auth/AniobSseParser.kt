package com.aniob.core.auth

/** Token counts reported when a response completes. */
data class AniobUsage(val inputTokens: Long?, val outputTokens: Long?)

sealed interface AniobStreamEvent {
    data class TextDelta(val text: String) : AniobStreamEvent
    data class ToolCallStarted(val itemId: String, val callId: String?, val name: String?) : AniobStreamEvent
    data class ToolCallArgumentsDelta(val itemId: String, val delta: String) : AniobStreamEvent
    data class ToolCallDone(val itemId: String, val callId: String?, val name: String?, val arguments: String) : AniobStreamEvent
    data class Completed(val usage: AniobUsage?) : AniobStreamEvent
    data class ItemDoneText(val text: String) : AniobStreamEvent

    /** The service said the response failed or was cut short. [message] is a one-line summary. */
    data class Failed(val code: String?, val message: String) : AniobStreamEvent
}

/**
 * Incremental parser for the Responses-API server-sent-event stream. Pure JVM.
 *
 * Handles chunks that split anywhere (mid-line, mid-JSON), several events in one chunk, CRLF,
 * `:` comment lines, multi-line `data:` and `[DONE]`. A broken JSON payload is skipped rather
 * than guessed at. Call [finish] at end of stream: if no terminal event was seen the stream was
 * truncated and a [AniobStreamEvent.Failed] is produced instead of a made-up completion.
 */
class AniobSseParser {
    private val buffer = StringBuilder()
    private var terminal = false

    val isFinished: Boolean get() = terminal

    fun feed(chunk: String): List<AniobStreamEvent> {
        buffer.append(chunk)
        val out = mutableListOf<AniobStreamEvent>()
        while (true) {
            val boundary = findBoundary() ?: break
            val block = buffer.substring(0, boundary.first)
            buffer.delete(0, boundary.first + boundary.second)
            parseBlock(block, out)
        }
        return out
    }

    fun finish(): List<AniobStreamEvent> {
        val out = mutableListOf<AniobStreamEvent>()
        if (buffer.isNotBlank()) {
            val rest = buffer.toString()
            buffer.setLength(0)
            parseBlock(rest, out)
        }
        if (!terminal) {
            terminal = true
            out += AniobStreamEvent.Failed("truncated", "The reply ended before it was complete.")
        }
        return out
    }

    /** (index of the blank line, length of the separator) for the earliest event boundary. */
    private fun findBoundary(): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (sep in SEPARATORS) {
            val i = buffer.indexOf(sep)
            if (i >= 0 && (best == null || i < best.first)) best = i to sep.length
        }
        return best
    }

    private fun parseBlock(block: String, out: MutableList<AniobStreamEvent>) {
        val data = StringBuilder()
        var hasData = false
        for (raw in block.split("\r\n", "\n", "\r")) {
            if (raw.isEmpty() || raw.startsWith(":")) continue
            if (raw.startsWith("data:")) {
                if (hasData) data.append('\n')
                data.append(raw.removePrefix("data:").removePrefix(" "))
                hasData = true
            }
            // `event:`, `id:` and `retry:` carry nothing we need: the payload names its own type.
        }
        if (!hasData) return
        val payload = data.toString().trim()
        if (payload == "[DONE]") {
            terminal = true
            return
        }
        val json = AniobAuthJson.parseObject(payload) ?: return
        translate(json, out)
    }

    private fun translate(json: Map<String, Any?>, out: MutableList<AniobStreamEvent>) {
        when (val type = AniobAuthJson.str(json["type"])) {
            "response.output_text.delta" ->
                AniobAuthJson.str(json["delta"])?.let { out += AniobStreamEvent.TextDelta(it) }

            "response.output_item.added" -> {
                val item = AniobAuthJson.obj(json["item"]) ?: return
                if (AniobAuthJson.str(item["type"]) == "function_call") {
                    val id = AniobAuthJson.str(item["id"]) ?: AniobAuthJson.str(item["call_id"]) ?: return
                    out += AniobStreamEvent.ToolCallStarted(id, AniobAuthJson.str(item["call_id"]), AniobAuthJson.str(item["name"]))
                }
            }

            "response.function_call_arguments.delta" -> {
                val id = AniobAuthJson.str(json["item_id"]) ?: return
                (json["delta"] as? String)?.let { out += AniobStreamEvent.ToolCallArgumentsDelta(id, it) }
            }

            "response.output_item.done" -> {
                val item = AniobAuthJson.obj(json["item"]) ?: return
                val itemType = AniobAuthJson.str(item["type"])
                if (itemType == "function_call") {
                    val id = AniobAuthJson.str(item["id"]) ?: AniobAuthJson.str(item["call_id"]) ?: return
                    out += AniobStreamEvent.ToolCallDone(
                        id, AniobAuthJson.str(item["call_id"]), AniobAuthJson.str(item["name"]),
                        (item["arguments"] as? String).orEmpty()
                    )
                } else if (itemType == "message") {
                    val content = item["content"] as? List<*>
                    content?.forEach { c ->
                        val obj = c as? Map<*, *>
                        if (obj?.get("type") == "output_text") {
                            (obj["text"] as? String)?.takeIf { it.isNotEmpty() }?.let { t ->
                                out += AniobStreamEvent.ItemDoneText(t)
                            }
                        }
                    }
                }
            }

            "response.completed" -> {
                terminal = true
                val resp = AniobAuthJson.obj(json["response"])
                val usage = AniobAuthJson.obj(resp?.get("usage"))
                val output = resp?.get("output") as? List<*>
                output?.forEach { item ->
                    val itemObj = item as? Map<*, *>
                    if (itemObj?.get("type") == "message") {
                        val content = itemObj["content"] as? List<*>
                        content?.forEach { c ->
                            val obj = c as? Map<*, *>
                            if (obj?.get("type") == "output_text") {
                                (obj["text"] as? String)?.takeIf { it.isNotEmpty() }?.let { t ->
                                    out += AniobStreamEvent.ItemDoneText(t)
                                }
                            }
                        }
                    }
                }
                out += AniobStreamEvent.Completed(
                    usage?.let { AniobUsage(AniobAuthJson.long(it["input_tokens"]), AniobAuthJson.long(it["output_tokens"])) }
                )
            }

            "response.failed", "response.incomplete", "error" -> {
                terminal = true
                val response = AniobAuthJson.obj(json["response"])
                val error = AniobAuthJson.obj(json["error"]) ?: AniobAuthJson.obj(response?.get("error"))
                val reason = AniobAuthJson.str(AniobAuthJson.obj(response?.get("incomplete_details"))?.get("reason"))
                val code = AniobAuthJson.str(error?.get("code")) ?: reason ?: type
                val message = AniobAuthJson.str(error?.get("message")) ?: AniobAuthJson.str(json["message"])
                    ?: reason?.let { "The reply was cut short ($it)." } ?: "The model service reported a failure."
                out += AniobStreamEvent.Failed(code, message.replace(Regex("\\s+"), " ").take(MESSAGE_LIMIT))
            }
            else -> Unit // many informational events (response.created, content_part.added, ...)
        }
    }

    private companion object {
        val SEPARATORS = listOf("\r\n\r\n", "\n\n", "\r\r")
        const val MESSAGE_LIMIT = 200
    }
}

/** Folds a finished event list into the one string the app's provider interface returns. */
class AniobStreamAccumulator {
    private val text = StringBuilder()
    var usage: AniobUsage? = null
        private set
    var failure: AniobStreamEvent.Failed? = null
        private set
    var completed: Boolean = false
        private set
    val toolCalls = mutableListOf<AniobStreamEvent.ToolCallDone>()
    private var itemText: String? = null

    /** Returns the new text, if the event carried any. */
    fun accept(event: AniobStreamEvent): String? = when (event) {
        is AniobStreamEvent.TextDelta -> { text.append(event.text); event.text }
        is AniobStreamEvent.Completed -> { completed = true; usage = event.usage; null }
        is AniobStreamEvent.Failed -> { if (failure == null) failure = event; null }
        is AniobStreamEvent.ToolCallDone -> { toolCalls += event; null }
        is AniobStreamEvent.ItemDoneText -> { if (itemText == null) itemText = event.text; null }
        else -> null
    }

    fun text(): String = if (text.isNotEmpty()) text.toString() else itemText.orEmpty()
}
