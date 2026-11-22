package com.aniob.core.providers

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.capability.AniobCapabilityRegistry
import com.aniob.core.capability.CapabilityInvocation
import com.aniob.core.capability.CapabilityValue
import com.aniob.core.capability.CapabilityValueType

/**
 * Parsed shape of the JSON tool call an on-device SLM is expected to emit.
 *
 * v2: the element is named by a [SemanticTarget]; node ids are no longer expressible.
 */
data class AniobToolCall(
    val thought: String,
    val tool: String,
    val target: SemanticTarget? = null,
    val text: String? = null,
    val packageName: String? = null,
    val direction: SwipeDirection? = null,
    val key: KeyType? = null,
    val summary: String? = null,
    val reason: String? = null
)

/**
 * Outcome of a local generation attempt.
 *
 * The critical contract: only [Ready] carries model output. Every other variant means
 * "no model produced this" so callers must surface an explicit, user-actionable failure
 * instead of executing a fabricated action against a node id that may not exist.
 */
sealed class AniobGenerationResult {
    data class Ready(val fullText: String) : AniobGenerationResult()

    /** Neither the native library nor the model file is usable on this device. */
    data object ModelUnavailable : AniobGenerationResult()

    /** Native library/model present but generation failed or timed out (>15s). */
    data class GenerationFailed(val reason: String) : AniobGenerationResult()

    /**
     * Model ran, but the emitted text is not a parseable action tool call. Raw text is
     * retained for the caller's diagnostics but must not be executed as an action.
     */
    data class Unparseable(val rawText: String) : AniobGenerationResult()
}

/**
 * Thin adapter over the single canonical parser.
 *
 * v2 note: this object performs NO JSON parsing of its own. It delegates to
 * [AniobActionSchema.parseActionJson] and only validates SoM targets against the live screen,
 * so the "hallucinated node id" guarantee survives the action-model rewrite.
 */
object AniobToolCallParser {

    /** A schema-validated proposal; it must still pass StepPipeline safety and live dispatch gates. */
    sealed interface CapabilityConversion {
        data class Proposed(val action: AniobAction.DeviceControl) : CapabilityConversion
        data class Rejected(val reasons: List<String>) : CapabilityConversion
    }

    /**
     * Bridge canonical device capabilities to the existing guarded action pipeline. There is no
     * alias lookup, no entity-to-label conversion, and no execution in this method.
     */
    fun capabilityToAction(
        invocation: CapabilityInvocation,
        registry: AniobCapabilityRegistry
    ): CapabilityConversion {
        val args = java.util.Collections.unmodifiableMap(LinkedHashMap(invocation.arguments))
        val snapshot = invocation.copy(arguments = args)
        val errors = registry.validate(snapshot)
        if (errors.isNotEmpty()) return CapabilityConversion.Rejected(errors)
        val descriptor = registry.find(snapshot.capabilityId)!!
        val command = DeviceCommand.entries.firstOrNull {
            it.wire == descriptor.executionKey && it.wire == descriptor.id
        } ?: return CapabilityConversion.Rejected(listOf("no canonical device adapter for '${descriptor.id}'"))
        val entityArgs = args.mapNotNull { (name, value) ->
            (value as? CapabilityValue.Entity)?.let { name to it.value }
        }.toMap()
        if (entityArgs.size > 1) {
            return CapabilityConversion.Rejected(listOf("one entity reference per device call is supported"))
        }
        val wireArgs = args.mapNotNull { (name, value) ->
            val wire = when (value) {
                is CapabilityValue.Text -> value.value
                is CapabilityValue.Integer -> value.value.toString()
                is CapabilityValue.Decimal -> value.value.toString()
                is CapabilityValue.BooleanValue -> value.value.toString()
                is CapabilityValue.Entity -> null
            }
            wire?.let { name to it }
        }.toMap().toMutableMap()
        // Android's legacy bridge accepts a string slot; this marker is never interpreted as
        // user data. The opaque reference travels separately in entityArguments.
        entityArgs.keys.forEach { name ->
            val slot = when (name) {
                "phone" -> if ("contact" in command.argNames) "contact" else name
                else -> name
            }
            if (slot !in wireArgs && slot in command.argNames) wireArgs[slot] = "__selected_entity__"
        }
        return CapabilityConversion.Proposed(
            AniobAction.DeviceControl(
                command,
                args = java.util.Collections.unmodifiableMap(LinkedHashMap(wireArgs)),
                thought = "Execute selected capability",
                entityArguments = java.util.Collections.unmodifiableMap(LinkedHashMap(entityArgs))
            )
        )
    }

    /**
     * Transitional wire adapter for existing DeviceControl callers. Android's old executor accepts
     * strings, but those strings must still satisfy the registry's typed schema and live availability.
     * This does not approve or perform the action; the shared execution gates remain mandatory.
     */
    sealed interface InvocationConversion {
        data class Proposed(val invocation: CapabilityInvocation) : InvocationConversion
        data class Rejected(val reasons: List<String>) : InvocationConversion
    }

    /** Preserve typed arguments when translating a deterministic shortcut into a plan. */
    fun legacyDeviceInvocation(
        command: DeviceCommand,
        wireArguments: Map<String, String>,
        registry: AniobCapabilityRegistry
    ): InvocationConversion {
        val schema = registry.find(command.wire)?.schema
            ?: return InvocationConversion.Rejected(listOf("unknown capability '${command.wire}'"))
        val params = schema.parameters.associateBy { it.name }
        val typed = linkedMapOf<String, CapabilityValue>()
        for ((name, raw) in wireArguments.toMap()) {
            val param = params[name] ?: return InvocationConversion.Rejected(listOf("unknown argument '$name'"))
            if (raw.isBlank()) continue
            val value = when (param.type) {
                CapabilityValueType.TEXT -> CapabilityValue.Text(raw)
                CapabilityValueType.INTEGER -> raw.trim().toLongOrNull()?.let(CapabilityValue::Integer)
                CapabilityValueType.DECIMAL -> raw.trim().toBigDecimalOrNull()?.let(CapabilityValue::Decimal)
                CapabilityValueType.BOOLEAN -> when {
                    raw.trim().equals("true", ignoreCase = true) -> CapabilityValue.BooleanValue(true)
                    raw.trim().equals("false", ignoreCase = true) -> CapabilityValue.BooleanValue(false)
                    else -> null
                }
                CapabilityValueType.ENTITY -> null
            } ?: return InvocationConversion.Rejected(listOf("argument '$name' must be ${param.type}"))
            typed[name] = value
        }
        val invocation = CapabilityInvocation(command.wire, java.util.Collections.unmodifiableMap(typed))
        val errors = registry.validate(invocation)
        return if (errors.isEmpty()) InvocationConversion.Proposed(invocation)
        else InvocationConversion.Rejected(errors)
    }

    fun legacyDeviceAction(
        command: DeviceCommand,
        wireArguments: Map<String, String>,
        registry: AniobCapabilityRegistry
    ): CapabilityConversion = when (val converted = legacyDeviceInvocation(command, wireArguments, registry)) {
        is InvocationConversion.Proposed -> capabilityToAction(converted.invocation, registry)
        is InvocationConversion.Rejected -> CapabilityConversion.Rejected(converted.reasons)
    }

    /** Parses raw model text into the canonical action, or null when it is not a valid action. */
    fun parseToAction(rawText: String): AniobAction? = AniobActionSchema.parseActionJsonOrNull(rawText)

    /**
     * Maps raw model text onto an action, failing explicitly when an SoM index is not present
     * on [screen]. Semantic targets (text/resource-id/desc) defer to the grounding resolver.
     */
    fun toAction(rawText: String, screen: com.aniob.core.domain.AniobScreenState?): AniobAction {
        val action = parseToAction(rawText)
            ?: return AniobAction.Fail(reason = "Local model output was not a valid action", thought = "unparseable")
        return validateAgainstScreen(action, screen)
    }

    private fun validateAgainstScreen(
        action: AniobAction,
        screen: com.aniob.core.domain.AniobScreenState?
    ): AniobAction {
        if (screen == null) return action
        val validIds = screen.nodes.map { it.id }.toSet()
        fun check(target: SemanticTarget, failReason: String): AniobAction? {
            if (target is SemanticTarget.SomIndex && target.index == com.aniob.core.domain.ActionBurst.UNNAMED_TARGET) {
                // The one intentional non-element target: an input without a named field. It is
                // valid only when the live screen has exactly one obvious field to type into.
                if (action is AniobAction.InputText) {
                    val field = com.aniob.core.perception.ActionTargetResolver.soleEditableField(screen)
                    return if (field != null && !field.isPassword) {
                        null // accepted: the executor resolves it against the same rule
                    } else {
                        AniobAction.Fail(
                            reason = "$failReason: the screen does not have exactly one usable editable field",
                            thought = "unnamed input needs a single editable field"
                        )
                    }
                }
                return AniobAction.Fail(
                    reason = "$failReason on missing SoM index ${target.index}",
                    thought = "hallucinated target"
                )
            }
            if (target is SemanticTarget.SomIndex && target.index !in validIds) {
                return AniobAction.Fail(
                    reason = "$failReason on missing SoM index ${target.index}",
                    thought = "hallucinated target"
                )
            }
            return null
        }
        return when (action) {
            is AniobAction.Tap -> check(action.target, "Local model proposed tap") ?: action
            is AniobAction.LongPress -> check(action.target, "Local model proposed long_press") ?: action
            is AniobAction.InputText -> check(action.target, "Local model proposed input_text") ?: action
            else -> action
        }
    }
}