package com.aniob.core.providers

import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.perception.BurstPlanScope
import com.aniob.core.capability.AniobCapabilityRegistry
import com.aniob.core.capability.CapabilityInvocation
import com.aniob.core.capability.CapabilityValue
import com.aniob.core.capability.CapabilityValueType
import com.aniob.core.domain.AniobTaskPlan
import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.EntityReference
import com.aniob.core.domain.MissingCapabilityInput
import com.aniob.core.domain.SuccessCriteria
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import okio.Buffer
import java.io.IOException
import java.math.BigDecimal

/**
 * The one structured-output boundary for every provider (finding #3).
 *
 * Cloud, local SLM, mock and skill replay all return *data*; parsing lives here, and only here,
 * so three ad-hoc parsers can no longer drift. Flow:
 *
 * ```
 * raw --parse--> action?  --miss--> one repair retry with the schema error fed back
 *                          --miss--> deterministic fallback (never a guessed tap)
 * ```
 *
 * The fallback for executor-role output is a bounded `Wait`, not a fabricated tap: a model that
 * cannot emit the vocabulary must not move the UI. Callers can distinguish a repaired parse from
 * a fallback via [StructuredParse.usedFallback].
 *
 * The bundled-plan path ([parseBurstOrRepair]) is deliberately *not* fail-closed the way a single
 * action is. A plan is usually correct and merely spans more than one screen; the part of it that
 * this screen can ground is returned and the rest is deferred. Only a reply the parser cannot read
 * at all, or one whose first action names an element that is not on the screen, is refused — and
 * even then it is refused as `null` so the caller's single-action path stays available, instead of
 * being replaced by a `Wait` that used to cost a whole extra round trip per step.
 */
object AniobStructuredOutput {

    /** A parsed proposal is not permission to execute: availability and approval are checked again. */
    sealed interface TaskPlanParse {
        data class Accepted(val plan: AniobTaskPlan) : TaskPlanParse
        data class Rejected(val errors: List<String>) : TaskPlanParse
    }

    /** Planning instructions and executable capabilities come from the same registry. */
    fun taskPlanPrompt(registry: AniobCapabilityRegistry): String {
        val tools = registry.toolDefinitions().map { tool ->
            mapOf(
                "id" to tool.id,
                "description" to tool.description,
                "parameters" to tool.schema.parameters.map { mapOf("name" to it.name, "type" to it.type.name, "accepted_types" to it.acceptedTypes.map { accepted -> accepted.name }) },
                "required_any_of" to tool.schema.requiredAnyOf.map { it.sorted() },
                "risk" to tool.risk.name,
                "effect" to tool.effect.name,
                "permissions" to tool.permissions.sorted(),
                "observer" to tool.observer,
                "availability" to "AVAILABLE"
            )
        }
        val toolsJson = Moshi.Builder().build().adapter(Any::class.java).toJson(tools)
        return """Select ANSWER, READ, EXECUTE, or CLARIFY and return exactly one JSON object.
Use only the capability IDs and typed arguments in the executable tools list. Never invent entity
references: use only references supplied by the runtime. Calls are ordered. Dispatch is not evidence.
READ calls must be READ_ONLY; EXECUTE may include read-only or mutating calls.
For READ or EXECUTE, include observable postconditions when known. Do not invent evidence; a task
with no independent evidence remains unverified. CLARIFY includes a question and may identify a
missing argument. ANSWER includes a response and no calls. Unknown fields are rejected.
Plan shape:
{"mode":"ANSWER|READ|EXECUTE|CLARIFY","requested_outcome":"...",
 "response":"answer or question when applicable",
 "invocations":[{"capability_id":"...","arguments":{"parameter":"value"}}],
 "unresolved_inputs":[{"capability_id":"...","argument":"..."}],
 "entity_bindings":{"binding":{"kind":"...","id":"..."}},
 "postconditions":{"must_contain_text":["..."],"must_show_package":["..."],
 "must_show_activity":["..."],"device_asserts":["TORCH_ON|TORCH_OFF"],"must_be_installed":["..."]}}
Omit fields that do not apply. TEXT is a string, INTEGER a JSON integer, DECIMAL a JSON number, BOOLEAN a JSON boolean,
and ENTITY an object with a runtime-supplied kind and id (use the displayed binding name as id, never an opaque resolver ID). Do not claim a task is complete without
observing its required postconditions.
Executable capability definitions:
$toolsJson"""
    }

    /** Compact companion to the existing action prompt; tools still come from the live registry. */
    fun taskPlanAlternativePrompt(registry: AniobCapabilityRegistry): String {
        val tools = registry.toolDefinitions().joinToString("; ") { tool ->
            val args = tool.schema.parameters.joinToString(",") { "${it.name}:${it.acceptedTypes.joinToString("|")}" }
            val required = tool.schema.requiredAnyOf.joinToString("+") { it.sorted().joinToString("|") }
            "${tool.id}($args) ${tool.effect.name}${if (required.isNotBlank()) " requires:$required" else ""}"
        }
        return """For direct device work you may instead return a structured task plan as one JSON object:
{"mode":"ANSWER|READ|EXECUTE|CLARIFY","requested_outcome":"...","response":"answer or clarification",
"invocations":[{"capability_id":"...","arguments":{}}],"unresolved_inputs":[],"postconditions":{}}.
Omit inapplicable fields. READ uses read-only calls; EXECUTE can use either; CLARIFY has a question and no execution.
Calls are ordered. Do not invent entity references or postconditions. A dispatch is not evidence of completion.
If the next step needs the on-screen UI, use the normal action schema instead. Available typed capabilities:
$tools"""
    }

    /**
     * Parses exactly one strict JSON object using the existing structured-output boundary.
     * [knownEntities] comes from task-scoped resolution, never from the model itself. An opaque
     * reference not present there is rejected even if its shape is valid.
     */
    fun parseTaskPlan(
        raw: String,
        registry: AniobCapabilityRegistry,
        knownEntities: Set<EntityReference> = emptySet(),
        knownEntityBindings: Map<String, EntityReference> = emptyMap()
    ): TaskPlanParse {
        if (raw.length > MAX_TASK_PLAN_CHARS) return TaskPlanParse.Rejected(listOf("task plan is too large"))
        return try {
            val reader = JsonReader.of(Buffer().writeUtf8(raw))
            val value = readTaskPlanJson(reader, 0)
            if (reader.peek() != JsonReader.Token.END_DOCUMENT) throw TaskPlanSchemaException("trailing JSON content")
            val plan = taskPlanFromJson(
                objectValue(value, "plan"), registry, knownEntities.toSet(), knownEntityBindings.toMap()
            )
            val errors = plan.validate(registry)
            if (errors.isEmpty()) TaskPlanParse.Accepted(plan) else TaskPlanParse.Rejected(errors)
        } catch (e: TaskPlanSchemaException) {
            TaskPlanParse.Rejected(listOf(e.message ?: "invalid task plan"))
        } catch (_: JsonDataException) {
            TaskPlanParse.Rejected(listOf("invalid task plan JSON"))
        } catch (_: IOException) {
            TaskPlanParse.Rejected(listOf("invalid task plan JSON"))
        }
    }

    private class TaskPlanSchemaException(message: String) : IllegalArgumentException(message)
    private const val MAX_TASK_PLAN_CHARS = 65_536
    private const val MAX_TASK_PLAN_DEPTH = 16
    private const val MAX_TASK_PLAN_ITEMS = 128

    /** Moshi handles JSON syntax; this visitor preserves integer precision and rejects duplicate keys. */
    private fun readTaskPlanJson(reader: JsonReader, depth: Int): Any? {
        if (depth > MAX_TASK_PLAN_DEPTH) throw TaskPlanSchemaException("task plan nesting is too deep")
        return when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                val result = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    if (result.size >= MAX_TASK_PLAN_ITEMS) throw TaskPlanSchemaException("too many object fields")
                    val name = reader.nextName()
                    if (result.containsKey(name)) throw TaskPlanSchemaException("duplicate field '$name'")
                    result[name] = readTaskPlanJson(reader, depth + 1)
                }
                reader.endObject()
                result
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                val result = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (result.size >= MAX_TASK_PLAN_ITEMS) throw TaskPlanSchemaException("too many array items")
                    result += readTaskPlanJson(reader, depth + 1)
                }
                reader.endArray()
                result
            }
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> try {
                BigDecimal(reader.nextString())
            } catch (_: NumberFormatException) {
                throw TaskPlanSchemaException("invalid number")
            }
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> reader.nextNull<Any>()
            else -> throw TaskPlanSchemaException("expected JSON value")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value: Any?, path: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: throw TaskPlanSchemaException("$path must be an object")

    private fun fields(value: Map<String, Any?>, allowed: Set<String>, path: String) {
        val unknown = value.keys.firstOrNull { it !in allowed }
        if (unknown != null) throw TaskPlanSchemaException("unknown field '$path.$unknown'")
    }

    private fun text(value: Any?, path: String): String =
        (value as? String)?.takeIf { it.isNotBlank() }
            ?: throw TaskPlanSchemaException("$path must be a nonblank string")

    private fun array(value: Any?, path: String): List<*> =
        value as? List<*> ?: throw TaskPlanSchemaException("$path must be an array")

    private fun entity(
        value: Any?, path: String, known: Set<EntityReference>, bindings: Map<String, EntityReference>
    ): EntityReference {
        val obj = objectValue(value, path)
        fields(obj, setOf("kind", "id"), path)
        val supplied = EntityReference(text(obj["kind"], "$path.kind"), text(obj["id"], "$path.id"))
        if (supplied in known) return supplied
        // Prompts use stable task-local slot names, never the resolver's opaque UUID. The runtime
        // maps only a previously selected binding back to its private reference after strict parse.
        val bound = bindings[supplied.id]?.takeIf { it.kind == supplied.kind }
            ?: throw TaskPlanSchemaException("$path is not a known entity binding")
        return bound
    }

    private fun taskPlanFromJson(
        root: Map<String, Any?>, registry: AniobCapabilityRegistry, known: Set<EntityReference>,
        knownBindings: Map<String, EntityReference>
    ): AniobTaskPlan {
        fields(root, setOf("mode", "requested_outcome", "invocations", "unresolved_inputs", "entity_bindings", "postconditions", "response"), "plan")
        val modeName = text(root["mode"], "plan.mode")
        val mode = AniobTaskPlan.Mode.entries.firstOrNull { it.name == modeName }
            ?: throw TaskPlanSchemaException("unknown task plan mode")
        val outcome = text(root["requested_outcome"], "plan.requested_outcome")
        val response = if (root.containsKey("response")) text(root["response"], "plan.response") else null
        val calls = if (root.containsKey("invocations")) array(root["invocations"], "plan.invocations").mapIndexed { index, item ->
            val path = "plan.invocations[$index]"
            val obj = objectValue(item, path)
            fields(obj, setOf("capability_id", "arguments"), path)
            val id = text(obj["capability_id"], "$path.capability_id")
            val capability = registry.find(id) ?: throw TaskPlanSchemaException("unknown capability '$id'")
            val args = if (obj.containsKey("arguments")) objectValue(obj["arguments"], "$path.arguments") else emptyMap()
            CapabilityInvocation(id, args.mapValues { (name, v) ->
                val argPath = "$path.arguments.$name"
                when (v) {
                    is String -> CapabilityValue.Text(v)
                    is Boolean -> CapabilityValue.BooleanValue(v)
                    is BigDecimal -> if (capability.schema.parameters.firstOrNull { it.name == name }?.type == CapabilityValueType.DECIMAL) {
                        CapabilityValue.Decimal(v)
                    } else try {
                        CapabilityValue.Integer(v.longValueExact())
                    } catch (_: ArithmeticException) {
                        throw TaskPlanSchemaException("$argPath must be a 64-bit integer")
                    }
                    is Map<*, *> -> CapabilityValue.Entity(entity(v, argPath, known, knownBindings))
                    else -> throw TaskPlanSchemaException("$argPath has an unsupported value")
                }
            })
        } else emptyList()
        val missing = if (root.containsKey("unresolved_inputs")) array(root["unresolved_inputs"], "plan.unresolved_inputs").mapIndexed { index, item ->
            val path = "plan.unresolved_inputs[$index]"
            val obj = objectValue(item, path)
            fields(obj, setOf("capability_id", "argument"), path)
            MissingCapabilityInput(text(obj["capability_id"], "$path.capability_id"), text(obj["argument"], "$path.argument"))
        } else emptyList()
        val bindings = if (root.containsKey("entity_bindings")) {
            objectValue(root["entity_bindings"], "plan.entity_bindings").mapValues { (name, value) ->
                entity(value, "plan.entity_bindings.$name", known, knownBindings)
            }
        } else emptyMap()
        val criteria = if (root.containsKey("postconditions")) {
            val obj = objectValue(root["postconditions"], "plan.postconditions")
            fields(obj, setOf("must_contain_text", "must_show_package", "must_show_activity", "device_asserts", "must_be_installed"), "plan.postconditions")
            fun stringList(key: String): List<String> = if (obj.containsKey(key)) {
                array(obj[key], "plan.postconditions.$key").mapIndexed { i, v -> text(v, "plan.postconditions.$key[$i]") }
            } else emptyList()
            val asserts = stringList("device_asserts").map { name ->
                DeviceAssertion.entries.firstOrNull { it.name == name }
                    ?: throw TaskPlanSchemaException("unknown device assertion")
            }
            SuccessCriteria(
                mustContainText = stringList("must_contain_text"),
                mustShowPackage = stringList("must_show_package"),
                mustShowActivity = stringList("must_show_activity"),
                deviceAsserts = asserts,
                mustBeInstalled = stringList("must_be_installed")
            )
        } else SuccessCriteria()
        val immutableCalls = java.util.Collections.unmodifiableList(calls.map { call ->
            call.copy(arguments = java.util.Collections.unmodifiableMap(LinkedHashMap(call.arguments)))
        })
        return AniobTaskPlan(
            mode = mode,
            requestedOutcome = outcome,
            invocations = immutableCalls,
            unresolvedInputs = java.util.Collections.unmodifiableList(missing.toList()),
            entityBindings = java.util.Collections.unmodifiableMap(LinkedHashMap(bindings)),
            postconditions = criteria.copy(
                mustContainText = java.util.Collections.unmodifiableList(criteria.mustContainText.toList()),
                mustShowPackage = java.util.Collections.unmodifiableList(criteria.mustShowPackage.toList()),
                mustShowActivity = java.util.Collections.unmodifiableList(criteria.mustShowActivity.toList()),
                deviceAsserts = java.util.Collections.unmodifiableList(criteria.deviceAsserts.toList()),
                mustBeInstalled = java.util.Collections.unmodifiableList(criteria.mustBeInstalled.toList())
            ),
            response = response
        )
    }

    /**
     * Parses a bundled plan and reduces it to the actions the observed screen can execute.
     *
     * @param goal the user's request, used to decide whether typing into an unnamed field is
     *   trustworthy (an injected screen must not be able to push arbitrary text into the UI).
     * @return null when the reply is not a bundled plan, or when nothing in it can be grounded on
     *   [screenState]; a non-null result always has at least one executable action, or is an
     *   explicit `is_complete` with no actions left to run.
     */
    fun parseBurstOrRepair(raw: String, screenState: AniobScreenState, goal: String = ""): ActionBurst? {
        if (!PLAN_ARRAY.containsMatchIn(raw)) return null
        val parsed = AniobActionSchema.parseActionBurstOrNull(raw) ?: return null

        // Completion claimed with an empty action list: nothing to ground, nothing to execute.
        if (parsed.planned.isEmpty()) return parsed.takeIf { it.isComplete }

        val scoped = BurstPlanScope.scope(parsed, screenState, goal)
        if (!scoped.groundedOnScreen) return null
        return scoped.actionable
    }

    /** Outcome of the parse-or-repair boundary. */
    data class StructuredParse(
        val action: AniobAction,
        val repaired: Boolean,
        val usedFallback: Boolean,
        val reason: String
    )

    /**
     * @param screenState live screen used to reject hallucinated SoM indices.
     * @param decode per-role generation params; the fallback and prompt budget derive from it.
     * @param repairCall given a repair prompt (schema + parse error + original text), returns a
     *   fresh completion or null when the provider declines / is unavailable.
     */
    fun parseOrRepair(
        raw: String,
        screenState: AniobScreenState?,
        decode: AniobDecodeConfig = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR),
        repairCall: (String) -> String? = { null }
    ): StructuredParse {
        firstAttempt(raw, screenState)?.let { return it }

        val error = describeError(raw)
        val repairedRaw = runCatching { repairCall(repairPrompt(raw, error)) }.getOrNull()
        if (repairedRaw != null) {
            firstAttempt(repairedRaw, screenState)?.let {
                return it.copy(repaired = true, reason = "repaired: ${it.reason}")
            }
        }

        return StructuredParse(
            action = fallbackFor(decode.role),
            repaired = true,
            usedFallback = true,
            reason = "unparseable after repair ($error) - using deterministic fallback"
        )
    }

    /** Returns a parse result, or null when [raw] is not a valid action (repair not yet tried). */
    private fun firstAttempt(raw: String, screenState: AniobScreenState?): StructuredParse? {
        val parsed = AniobToolCallParser.parseToAction(raw) ?: return null
        // A model-emitted, schema-valid FAIL is legitimate output, not a parse failure.
        if (parsed is AniobAction.Fail && parsed.reason.isBlank()) return null
        val validated = AniobToolCallParser.toAction(raw, screenState)
        return StructuredParse(validated, repaired = false, usedFallback = false, reason = "parsed")
    }

    /** Builds the repair prompt so suspend-aware callers can fetch the retry themselves. */
    fun repairPrompt(raw: String): String = repairPrompt(raw, describeError(raw))

    /** Builds the repair prompt for a bundled plan that could not be grounded. */
    fun burstRepairPrompt(raw: String, reason: String): String = buildString {
        appendLine("Your previous reply was a multi-action plan, not a single action, and its first")
        appendLine("action does not name an element that exists on the screen.")
        appendLine("ERROR: $reason")
        appendLine("Return ONE JSON object for the NEXT action only:")
        appendLine(AniobActionSchema.ACTION_JSON_SCHEMA)
        appendLine("PREVIOUS OUTPUT:")
        appendLine(raw.take(600))
        append("Use only ids present in the element list. Do not include prose or coordinates.")
    }

    private fun describeError(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "empty response"
        // A refusal ("I can't operate your phone") gets a correction, not a schema lecture.
        if (com.aniob.core.capability.AniobRefusalGuard.isRefusal(trimmed)) {
            return "refused the task. " + com.aniob.core.capability.AniobCapabilityManifest.REFUSAL_CORRECTION
        }
        if (PLAN_ARRAY.containsMatchIn(trimmed)) {
            return "returned a multi-action plan where one action was required"
        }
        val coordField = COORDINATE_HINTS.firstOrNull { Regex("\"$it\"\\s*:").containsMatchIn(trimmed) }
        if (coordField != null) return "coordinate field '$coordField' is not part of the vocabulary"
        return "no valid tool object found"
    }

    private fun repairPrompt(raw: String, error: String): String = buildString {
        appendLine("Your previous output could not be parsed into an action.")
        appendLine("ERROR: $error")
        appendLine("SCHEMA:")
        appendLine(AniobActionSchema.ACTION_JSON_SCHEMA)
        appendLine("PREVIOUS OUTPUT:")
        appendLine(raw.take(600))
        append("Return ONE JSON object matching the schema. Do not include prose or coordinates.")
    }

    /**
     * Deterministic fallback per role. Executor waits (never taps blind); a planner keeps its
     * prior summary and a reflector retries an alternative, both represented as a bounded Wait.
     */
    private fun fallbackFor(role: AniobDecodeConfig.Role): AniobAction = when (role) {
        AniobDecodeConfig.Role.EXECUTOR,
        AniobDecodeConfig.Role.PLANNER,
        AniobDecodeConfig.Role.REFLECTOR,
        AniobDecodeConfig.Role.GRILL,
        AniobDecodeConfig.Role.EXPLORER -> AniobAction.Wait(durationMs = 1000)

        // Plan-5.6 advisory roles never drive the device. An unparseable answer from one of them
        // is a fact about the answer, not a reason to touch the screen, so it fails loudly instead
        // of degrading into a Wait that would look like progress.
        AniobDecodeConfig.Role.VERIFIER,
        AniobDecodeConfig.Role.CONSISTENCY,
        AniobDecodeConfig.Role.SUMMARIZER,
        AniobDecodeConfig.Role.EXTRACTOR ->
            AniobAction.Fail("${role.name.lowercase()} returned an unparseable response")
    }

    private val COORDINATE_HINTS = listOf("x", "y", "startx", "endx", "bounds", "target_node_id")

    private val PLAN_ARRAY = Regex("\"(?:actions|steps|plan)\"\\s*:\\s*\\[")

    /** True when [raw] claims to be a bundled plan; used to pick the plan repair wording. */
    fun looksLikePlan(raw: String): Boolean = PLAN_ARRAY.containsMatchIn(raw)
}
