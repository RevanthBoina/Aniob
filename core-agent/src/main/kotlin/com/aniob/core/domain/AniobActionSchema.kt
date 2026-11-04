package com.aniob.core.domain

/**
 * The ONE parser for LLM-emitted action JSON.
 *
 * Every provider (local SLM, cloud, mock, skill replay) must route raw model text through
 * [parseActionJson]. No other file may parse action JSON — three ad-hoc parsers previously
 * drifted apart and let two of them emit unschema'd garbage.
 *
 * Accepted wire shape (closed vocabulary):
 * ```
 * {
 *   "thought": "...",
 *   "tool": "TAP" | "LONG_PRESS" | "INPUT_TEXT" | "SWIPE" | "OPEN_APP" | "SYSTEM_KEY"
 *          | "DEVICE_CONTROL" | "WAIT" | "FINISH" | "FAIL",
 *   "command": "volume_set", "args": { "percent": "70" },   // DEVICE_CONTROL only
 *   "target": { "kind": "som_index"|"resource_id"|"text"|"content_desc", "value": "..." },
 *   "text": "...", "distance_px": 600, "direction": "DOWN",
 *   "package_name": "...", "key": "BACK", "duration_ms": 1000,
 *   "summary": "...", "reason": "..."
 * }
 * ```
 * `target` is required for TAP/LONG_PRESS/INPUT_TEXT. Any coordinate field (`x`, `y`, `bounds`,
 * `startX`, ...) is a hard error — coordinates are not representable in this vocabulary.
 */
object AniobActionSchema {

    /** Human-readable schema for prompts and repair feedback. */
    const val ACTION_JSON_SCHEMA: String = """
{
  "thought": "why this step",
  "tool": "TAP|LONG_PRESS|INPUT_TEXT|SWIPE|OPEN_APP|SYSTEM_KEY|DEVICE_CONTROL|WAIT|FINISH|FAIL",
  "target": {"kind": "som_index|resource_id|text|content_desc", "value": "..."},
  "text": "for INPUT_TEXT",
  "submit": true,
  "direction": "UP|DOWN|LEFT|RIGHT for SWIPE",
  "distance_px": 600,
  "package_name": "for OPEN_APP",
  "key": "BACK|HOME|ENTER|RECENTS for SYSTEM_KEY",
  "command": "for DEVICE_CONTROL, e.g. volume_set, torch_on, media_next, call, otp_copy, file_find",
  "args": {"name": "value for DEVICE_CONTROL"},
  "duration_ms": 1000,
  "summary": "for FINISH",
  "reason": "for FAIL"
}
Coordinates are NOT valid. target is required for TAP, LONG_PRESS and INPUT_TEXT.
DEVICE_CONTROL runs a direct phone command with no screen needed (see DEVICE CAPABILITIES); prefer it over tapping through apps.
Set "submit": true when the text is a query or a phone number and the field must be confirmed
(press the keyboard's search/go key) for the action to have any effect.
"""

    /** Fields that would smuggle pixel coordinates into the action vocabulary. */
    private val COORDINATE_FIELDS = listOf("x", "y", "startx", "starty", "endx", "endy", "bounds", "target_node_id", "targetnodeid", "node_id")
    private val COORDINATE_FIELD_REGEXES = COORDINATE_FIELDS.associateWith { Regex("\"$it\"\\s*:") }

    private val TOOL_OBJECT = Regex("\\{(?:[^{}]|\\{[^{}]*\\})*\"tool\"\\s*:\\s*\"[^\"]+\"(?:[^{}]|\\{[^{}]*\\})*\\}", RegexOption.DOT_MATCHES_ALL)
    private val ARGS_BLOCK_REGEX = Regex("\"args\"\\s*:\\s*\\{([^{}]*)\\}")
    private val ARG_PAIR_REGEX = Regex(
        "\"([A-Za-z_]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+(?:\\.\\d+)?)|(true|false))"
    )
    private val TARGET_BLOCK_REGEX = Regex("\"target\"\\s*:\\s*\\{([^{}]*)\\}")
    private val STRING_FIELD_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val INT_FIELD_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val BOOL_FIELD_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun stringFieldRegex(field: String): Regex =
        STRING_FIELD_CACHE.computeIfAbsent(field) { Regex("\"$it\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") }

    private fun intFieldRegex(field: String): Regex =
        INT_FIELD_CACHE.computeIfAbsent(field) { Regex("\"$it\"\\s*:\\s*(-?\\d+)") }

    private fun boolFieldRegex(field: String): Regex =
        BOOL_FIELD_CACHE.computeIfAbsent(field) { Regex("\"$it\"\\s*:\\s*(true|false)") }

    class ActionSchemaException(message: String) : IllegalArgumentException(message)

    /**
     * Parses [json] into an [AniobAction].
     *
     * @throws ActionSchemaException with a field-level message when the payload is not a
     * valid action — including any attempt to pass coordinates.
     */
    fun parseActionJson(json: String): AniobAction {
        val candidate = TOOL_OBJECT.find(json)?.value ?: run {
            val firstBrace = json.indexOf('{')
            val lastBrace = json.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace > firstBrace) {
                json.substring(firstBrace, lastBrace + 1)
            } else {
                json.trim()
            }
        }
        if (!candidate.contains("\"tool\"") && !candidate.contains("\"action\"")) {
            throw ActionSchemaException("missing required field 'tool'")
        }
        rejectCoordinateFields(candidate)

        // `action` is accepted as an alias for `tool` so older cloud models that emit
        // {"action":"tap"} still route through this single parser instead of a second one.
        val toolRaw = str(candidate, "tool") ?: str(candidate, "action")
            ?: throw ActionSchemaException("missing required field 'tool'")
        val tool = toolRaw.trim().uppercase().replace('-', '_')
        val thought = str(candidate, "thought") ?: "Local model step"

        return when (tool) {
            "TAP", "CLICK" -> AniobAction.Tap(requireTarget(candidate, tool), thought)
            "LONG_PRESS" -> AniobAction.LongPress(
                target = requireTarget(candidate, tool),
                durationMs = int(candidate, "duration_ms")?.toLong() ?: 1000L,
                thought = thought
            )
            "INPUT_TEXT", "TYPE" -> AniobAction.InputText(
                target = requireTarget(candidate, tool),
                text = str(candidate, "text") ?: throw ActionSchemaException("INPUT_TEXT requires 'text'"),
                clearFirst = bool(candidate, "clear_first") ?: false,
                submit = bool(candidate, "submit") ?: false,
                thought = thought
            )
            "SWIPE", "SCROLL" -> AniobAction.Swipe(
                direction = str(candidate, "direction")?.let(::swipeDirection)
                    ?: throw ActionSchemaException("SWIPE requires 'direction' in UP|DOWN|LEFT|RIGHT"),
                distancePx = int(candidate, "distance_px") ?: 600,
                thought = thought
            )
            "OPEN_APP" -> AniobAction.OpenApp(
                packageName = str(candidate, "package_name")
                    ?: str(candidate, "package")
                    ?: throw ActionSchemaException("OPEN_APP requires 'package_name'"),
                thought = thought
            )
            "SYSTEM_KEY", "PRESS_KEY", "PRESS_BACK", "PRESS_HOME" -> AniobAction.SystemKey(
                key = str(candidate, "key")?.let(::keyType) ?: keyFromTool(tool),
                thought = thought
            )
            "DEVICE_CONTROL", "DEVICE", "DEVICE_COMMAND", "SYSTEM_ACTION", "REFLEX" ->
                deviceControl(candidate, str(candidate, "command") ?: str(candidate, "name"), thought)
            "WAIT" -> AniobAction.Wait(
                durationMs = int(candidate, "duration_ms")?.toLong() ?: AniobAction.Wait.DEFAULT_WAIT_MS,
                thought = thought
            ).clamped()
            "FINISH" -> AniobAction.Finish(
                summary = str(candidate, "summary") ?: str(candidate, "reason") ?: "Task completed",
                thought = thought
            )
            "FAIL" -> AniobAction.Fail(
                reason = str(candidate, "reason") ?: "Model reported failure",
                thought = thought
            )
            // A model that names the command directly ({"tool":"TORCH_ON"}, {"tool":"media_next"})
            // still reaches the device instead of failing a parse it was 95% right about.
            else -> {
                DeviceCommand.fromWire(tool)?.let { return deviceControl(candidate, tool, thought) }
                throw ActionSchemaException("unknown tool '$toolRaw'")
            }
        }
    }

    /**
     * Builds a [AniobAction.DeviceControl]. Arguments come from the nested `"args":{...}` object and,
     * for models that flatten it, from top-level fields carrying the command's own argument names.
     */
    private fun deviceControl(json: String, commandName: String?, thought: String): AniobAction {
        val command = DeviceCommand.fromWire(commandName) ?: throw ActionSchemaException(
            "unknown device command '${commandName.orEmpty()}'; valid: ${DeviceCommand.wireNames().joinToString(", ")}"
        )
        val args = LinkedHashMap<String, String>()
        ARGS_BLOCK_REGEX.find(json)?.groupValues?.get(1)?.let { block ->
            ARG_PAIR_REGEX.findAll(block).forEach { m ->
                val value = m.groups[2]?.value?.let(::unescapeJsonString)
                    ?: m.groups[3]?.value ?: m.groups[4]?.value ?: return@forEach
                args[m.groupValues[1].lowercase()] = value
            }
        }
        for (name in command.argNames) {
            if (args.containsKey(name)) continue
            (str(json, name) ?: int(json, name)?.toString() ?: bool(json, name)?.toString())
                ?.let { args[name] = it }
        }
        command.missingRequired(args)?.let { missing ->
            throw ActionSchemaException("${command.wire} requires '$missing' in args")
        }
        return AniobAction.DeviceControl(command, args, thought)
    }

    /**
     * Parses the burst reply used by the multi-action conditions:
     * ```
     * {"thought":"open wifi then tap scan","is_complete":false,
     *  "actions":[{"type":"tap","id":2},{"type":"input","id":4,"text":"home"},{"type":"back"}]}
     * ```
     *
     * `type` is `tap`, `input`, `scroll` or `back`; `id` is the sequential element id from the
     * observation (not a pixel, not a resource id). Returns null when nothing usable is present so
     * the caller can fall back to the single-action parser instead of failing the step.
     */
    fun parseActionBurstOrNull(json: String): ActionBurst? {
        val array = arrayAfter(json, "actions") ?: return null
        val thought = str(json, "thought").orEmpty().take(120)
        val isComplete = bool(json, "is_complete") ?: false

        val actions = ArrayList<AniobAction>(ActionBurst.MAX_ACTIONS)
        for (element in splitObjects(array)) {
            if (actions.size >= ActionBurst.MAX_ACTIONS) return null
            val action = burstAction(element, thought) ?: return null
            actions.add(action)
        }
        if (actions.isEmpty()) {
            return if (isComplete) {
                ActionBurst(thought, isComplete = true, actions = emptyList())
            } else {
                null
            }
        }
        return ActionBurst(thought, isComplete, actions)
    }

    /** Maps one burst element (`{"type":"tap","id":2,...}`) onto a canonical action. */
    private fun burstAction(element: String, thought: String): AniobAction? {
        val type = str(element, "type")?.trim()?.lowercase() ?: return null
        val target = int(element, "id")?.let { SemanticTarget.SomIndex(it) }
        return when (type) {
            "tap", "click" -> target?.let { AniobAction.Tap(it, thought) }
            "input", "type", "input_text" -> {
                val text = str(element, "text").orEmpty()
                if (target != null && text.isNotBlank()) {
                    AniobAction.InputText(
                        target,
                        text,
                        clearFirst = bool(element, "clear_first") ?: false,
                        submit = bool(element, "submit") ?: false,
                        thought = thought
                    )
                } else {
                    null
                }
            }
            // A bare scroll gesture: swiping up reveals the content below, which is what "scroll"
            // means in a plan. An explicit direction is honoured when the model supplies one.
            "scroll", "swipe" -> {
                val direction = when (str(element, "direction")?.lowercase()) {
                    "up" -> SwipeDirection.UP
                    "down" -> SwipeDirection.DOWN
                    "left" -> SwipeDirection.LEFT
                    "right" -> SwipeDirection.RIGHT
                    else -> SwipeDirection.UP
                }
                AniobAction.Swipe(direction = direction, thought = thought)
            }
            "back", "system_key", "press_back" -> AniobAction.PressKey(KeyType.BACK, thought)
            "wait" -> AniobAction.Wait(durationMs = (int(element, "duration_ms") ?: 800).toLong(), thought = thought)
            else -> null
        }
    }

    /** Substring between the brackets after `"field": [`, honouring strings and nesting. */
    private fun arrayAfter(json: String, field: String): String? {
        val key = Regex("\"$field\"\\s*:\\s*\\[", RegexOption.DOT_MATCHES_ALL).find(json) ?: return null
        val start = key.range.last + 1
        var depth = 1
        var inString = false
        var escaped = false
        var i = start
        while (i < json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return json.substring(start, i)
                }
            }
            i++
        }
        return null
    }

    /** Splits a JSON array body into its top-level object literals. */
    private fun splitObjects(array: String): List<String> {
        val objects = ArrayList<String>(ActionBurst.MAX_ACTIONS)
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        for (i in array.indices) {
            val c = array[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(array.substring(start, i + 1))
                        start = -1

                    }
                }
            }
        }
        return objects
    }

    /** Lenient parse: null instead of throwing. Used where a caller prefers a fallback path. */
    fun parseActionJsonOrNull(json: String): AniobAction? =
        try {
            parseActionJson(json)
        } catch (_: ActionSchemaException) {
            null
        }

    /** Serializes back to the wire shape (used by tests and repair prompts). */
    fun toActionJson(action: AniobAction): String = when (action) {
        is AniobAction.Tap -> """{"thought":"${esc(action.thought)}","tool":"TAP","target":${targetJson(action.target)}}"""
        is AniobAction.LongPress -> """{"thought":"${esc(action.thought)}","tool":"LONG_PRESS","target":${targetJson(action.target)},"duration_ms":${action.durationMs}}"""
        is AniobAction.InputText -> """{"thought":"${esc(action.thought)}","tool":"INPUT_TEXT","target":${targetJson(action.target)},"text":"${esc(action.text)}","clear_first":${action.clearFirst},"submit":${action.submit}}"""
        is AniobAction.Swipe -> """{"thought":"${esc(action.thought)}","tool":"SWIPE","direction":"${action.direction}","distance_px":${action.distancePx}}"""
        is AniobAction.OpenApp -> """{"thought":"${esc(action.thought)}","tool":"OPEN_APP","package_name":"${esc(action.packageName)}"}"""
        is AniobAction.SystemKey -> """{"thought":"${esc(action.thought)}","tool":"SYSTEM_KEY","key":"${action.key}"}"""
        is AniobAction.PressKey -> """{"thought":"${esc(action.thought)}","tool":"SYSTEM_KEY","key":"${action.key}"}"""
        is AniobAction.Wait -> """{"thought":"${esc(action.thought)}","tool":"WAIT","duration_ms":${action.durationMs}}"""
        is AniobAction.Finish -> """{"thought":"${esc(action.thought)}","tool":"FINISH","summary":"${esc(action.summary)}"}"""
        is AniobAction.Fail -> """{"thought":"${esc(action.thought)}","tool":"FAIL","reason":"${esc(action.reason)}"}"""
        is AniobAction.DeviceControl -> """{"thought":"${esc(action.thought)}","tool":"DEVICE_CONTROL","command":"${action.command.wire}","args":{${
            action.args.entries.joinToString(",") { "\"${esc(it.key)}\":\"${esc(it.value)}\"" }
        }}}"""
        else -> throw ActionSchemaException("tool '${action.toolName}' is not part of the closed action vocabulary")
    }

    private fun targetJson(target: SemanticTarget): String = when (target) {
        is SemanticTarget.SomIndex -> """{"kind":"som_index","value":${target.index}}"""
        is SemanticTarget.ResourceId -> """{"kind":"resource_id","value":"${esc(target.id)}"}"""
        is SemanticTarget.Text -> """{"kind":"text","value":"${esc(target.text)}","exact":${target.exact}}"""
        is SemanticTarget.ContentDesc -> """{"kind":"content_desc","value":"${esc(target.desc)}","exact":${target.exact}}"""
        is SemanticTarget.NormalizedCoordinate -> """{"kind":"coord","x":${target.normX},"y":${target.normY}}"""
        is SemanticTarget.BoundingBox -> """{"kind":"box","left":${target.left},"top":${target.top},"right":${target.right},"bottom":${target.bottom}}"""
    }

    /** `target` may be a nested object or a bare string/int scalar. */
    private fun requireTarget(json: String, tool: String): SemanticTarget =
        parseTarget(json) ?: throw ActionSchemaException("$tool requires a 'target' with kind and value")

    private fun parseTarget(json: String): SemanticTarget? {
        val block = TARGET_BLOCK_REGEX.find(json)?.groupValues?.get(1)
        if (block != null) {
            val kind = str(block, "kind")?.trim()?.lowercase()
            if (kind == "coord") {
                val x = int(block, "x") ?: return null
                val y = int(block, "y") ?: return null
                return SemanticTarget.NormalizedCoordinate(x, y)
            }
            if (kind == "box") {
                val left = int(block, "left") ?: return null
                val top = int(block, "top") ?: return null
                val right = int(block, "right") ?: return null
                val bottom = int(block, "bottom") ?: return null
                return SemanticTarget.BoundingBox(left, top, right, bottom)
            }
            val value = str(block, "value") ?: int(block, "value")?.toString()
            val exact = bool(block, "exact") ?: false
            if (kind == null || value == null) return null
            return when (kind) {
                "som_index", "som", "index", "node" -> value.toIntOrNull()?.let { SemanticTarget.SomIndex(it) }
                "resource_id", "id", "view_id" -> SemanticTarget.ResourceId(value)
                "text" -> SemanticTarget.Text(value, exact)
                "content_desc", "content_description", "desc" -> SemanticTarget.ContentDesc(value, exact)
                else -> null
            }
        }
        // Scalar shorthand: "target": 3  |  "target": "Wi-Fi"
        int(json, "target")?.let { return SemanticTarget.SomIndex(it) }
        str(json, "target")?.let { return SemanticTarget.Text(it) }
        return null
    }

    private fun rejectCoordinateFields(json: String) {
        val lower = json.lowercase()
        // Match the field *name* (quoted and followed by a colon) so a thought value of "x"
        // or "y" is not mistaken for a coordinate field.
        val offending = COORDINATE_FIELD_REGEXES.entries
            .filter { (_, regex) -> regex.containsMatchIn(lower) }
            .map { it.key }
        if (offending.isNotEmpty()) {
            throw ActionSchemaException(
                "coordinate field(s) ${offending.joinToString(", ")} are not allowed; " +
                    "use target:{kind,value} — pixels are resolved from the live tree"
            )
        }
    }

    private fun keyFromTool(tool: String): KeyType = when (tool) {
        "PRESS_BACK" -> KeyType.BACK
        "PRESS_HOME" -> KeyType.HOME
        else -> KeyType.BACK
    }

    private fun str(json: String, field: String): String? {
        val match = stringFieldRegex(field).find(json) ?: return null
        return unescapeJsonString(match.groupValues[1])
    }

    /**
     * Single-pass deterministic state-machine JSON string unescaper.
     * Prevents double-unescaping corruption on literal "\\n" sequences.
     */
    private fun unescapeJsonString(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    '\"' -> { sb.append('\"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(ch)
                                i++
                            }
                        } else {
                            sb.append(ch)
                            i++
                        }
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun int(json: String, field: String): Int? =
        intFieldRegex(field).find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun bool(json: String, field: String): Boolean? =
        boolFieldRegex(field).find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun swipeDirection(raw: String): SwipeDirection? =
        SwipeDirection.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private fun keyType(raw: String): KeyType? =
        KeyType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
}