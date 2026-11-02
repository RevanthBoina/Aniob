package com.aniob.core.memory

/**
 * The on-disk shape of `remember_cache.json`.
 *
 * Hand-rolled instead of a reflection adapter: the schema is five scalars and one array, and the
 * pure-JVM core must not grow a serialization dependency for it. The parser is a small
 * recursive-descent JSON reader that returns "nothing" for damaged input rather than throwing,
 * so a truncated file costs a cache miss, never a failed task.
 */
internal object RememberJson {

    private const val VERSION = 1

    fun write(cache: RememberCache): String = buildString {
        append("{\"version\":").append(VERSION).append(",\"entries\":[")
        cache.snapshot().forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append('{')
            append("\"goal\":").append(quote(entry.goalKey)).append(',')
            append("\"package\":").append(quote(entry.packageName)).append(',')
            append("\"hash\":").append(quote(entry.screenHash)).append(',')
            append("\"updated\":").append(entry.updatedAtMs).append(',')
            append("\"hits\":").append(entry.hits).append(',')
            append("\"actions\":[")
            entry.actions.forEachIndexed { actionIndex, action ->
                if (actionIndex > 0) append(',')
                append('{')
                append("\"kind\":").append(quote(action.kind)).append(',')
                append("\"viewId\":").append(quote(action.viewId)).append(',')
                append("\"text\":").append(quote(action.text)).append(',')
                append("\"desc\":").append(quote(action.contentDescription)).append(',')
                append("\"input\":").append(quote(action.inputText)).append(',')
                append("\"clear\":").append(action.clearFirst).append(',')
                append("\"dir\":").append(quote(action.direction)).append(',')
                append("\"nx\":").append(action.normalizedX).append(',')
                append("\"ny\":").append(action.normalizedY)
                append('}')
            }
            append("]}")
        }
        append("]}")
    }

    fun read(text: String): List<RememberCache.Entry> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { Parser(text).parseValue() as? Map<*, *> }.getOrNull() ?: return emptyList()
        val rawEntries = root["entries"] as? List<*> ?: return emptyList()
        return rawEntries.mapNotNull { raw ->
            val obj = raw as? Map<*, *> ?: return@mapNotNull null
            val goal = obj.string("goal") ?: return@mapNotNull null
            val packageName = obj.string("package").orEmpty()
            val hash = obj.string("hash") ?: return@mapNotNull null
            val actions = (obj["actions"] as? List<*>).orEmpty().mapNotNull { item ->
                val a = item as? Map<*, *> ?: return@mapNotNull null
                val kind = a.string("kind") ?: return@mapNotNull null
                RememberAction(
                    kind = kind,
                    viewId = a.string("viewId").orEmpty(),
                    text = a.string("text").orEmpty(),
                    contentDescription = a.string("desc").orEmpty(),
                    inputText = a.string("input").orEmpty(),
                    clearFirst = a.boolean("clear") ?: false,
                    direction = a.string("dir").orEmpty(),
                    normalizedX = a.double("nx") ?: -1.0,
                    normalizedY = a.double("ny") ?: -1.0
                )
            }
            if (actions.isEmpty()) return@mapNotNull null
            RememberCache.Entry(
                goalKey = goal,
                packageName = packageName,
                screenHash = hash,
                actions = actions,
                updatedAtMs = obj.long("updated") ?: 0L,
                hits = (obj.long("hits") ?: 0L).toInt()
            )
        }
    }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String
    private fun Map<*, *>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<*, *>.boolean(key: String): Boolean? = this[key] as? Boolean

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Recursive-descent reader for the subset above (objects, arrays, strings, numbers, booleans). */
    private class Parser(private val text: String) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (val c = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else null
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            index++ // '{'
            skipWhitespace()
            if (peek() == '}') { index++; return result }
            while (index < text.length) {
                skipWhitespace()
                if (peek() != '"') return result
                val key = parseString() ?: return result
                skipWhitespace()
                if (peek() != ':') return result
                index++
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> return result
                }
            }
            return result
        }

        private fun parseArray(): List<Any?> {
            val result = ArrayList<Any?>()
            index++ // '['
            skipWhitespace()
            if (peek() == ']') { index++; return result }
            while (index < text.length) {
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> return result
                }
            }
            return result
        }

        private fun parseString(): String? {
            if (peek() != '"') return null
            index++
            val sb = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) return sb.toString()
                        when (val escape = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (index + 4 <= text.length) {
                                    val hex = text.substring(index, index + 4)
                                    index += 4
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                }
                            }
                            else -> sb.append(escape)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Double? {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.' ||
                    text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')
            ) {
                index++
            }
            return text.substring(start, index).toDoubleOrNull()
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (text.startsWith(literal, index)) {
                index += literal.length
                return value
            }
            index++
            return null
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
