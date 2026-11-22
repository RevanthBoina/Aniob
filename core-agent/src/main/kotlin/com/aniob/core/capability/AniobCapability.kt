package com.aniob.core.capability

import com.aniob.core.domain.EntityReference

/** Values accepted by a capability schema; a label is never an entity identity. */
sealed interface CapabilityValue {
    data class Text(val value: String) : CapabilityValue
    data class Integer(val value: Long) : CapabilityValue
    data class Decimal(val value: java.math.BigDecimal) : CapabilityValue
    data class BooleanValue(val value: Boolean) : CapabilityValue
    data class Entity(val value: EntityReference) : CapabilityValue
}

enum class CapabilityValueType { TEXT, INTEGER, DECIMAL, BOOLEAN, ENTITY }
enum class CapabilityRisk { LOW, APPROVAL_REQUIRED }
enum class CapabilityEffect { READ_ONLY, MUTATING }

data class CapabilityParameter(
    val name: String,
    val type: CapabilityValueType,
    /** Additional accepted value kinds for heterogeneous domain slots such as a person recipient. */
    val acceptedTypes: Set<CapabilityValueType> = setOf(type)
) {
    init { require(name.isNotBlank()); require(type in acceptedTypes); require(acceptedTypes.isNotEmpty()) }
}

/** Every group requires at least one of its named fields. Unknown fields are rejected. */
data class CapabilitySchema(
    val parameters: List<CapabilityParameter>,
    val requiredAnyOf: List<Set<String>> = emptyList()
) {
    init {
        val names = parameters.map { it.name }
        require(names.distinct().size == names.size) { "duplicate parameter" }
        require(requiredAnyOf.all { it.isNotEmpty() && names.containsAll(it) }) { "invalid required group" }
    }

    fun validate(arguments: Map<String, CapabilityValue>, allowMissingRequired: Boolean = false): List<String> = buildList {
        val allowed = parameters.associateBy { it.name }
        arguments.forEach { (name, value) ->
            val parameter = allowed[name]
            if (parameter == null) add("unknown argument '$name'")
            else if (value.type() !in parameter.acceptedTypes) add("argument '$name' must be ${parameter.acceptedTypes}")
            else if (value is CapabilityValue.Text && value.value.isBlank()) add("argument '$name' must not be blank")
            else if (value is CapabilityValue.Decimal && !value.value.toDouble().isFinite()) {
                add("argument '$name' is outside the supported numeric range")
            }
        }
        if (!allowMissingRequired) requiredAnyOf.forEach { alternatives ->
            if (alternatives.none { it in arguments }) add("requires one of: ${alternatives.sorted().joinToString(", ")}")
        }
    }
}

private fun CapabilityValue.type(): CapabilityValueType = when (this) {
    is CapabilityValue.Text -> CapabilityValueType.TEXT
    is CapabilityValue.Integer -> CapabilityValueType.INTEGER
    is CapabilityValue.Decimal -> CapabilityValueType.DECIMAL
    is CapabilityValue.BooleanValue -> CapabilityValueType.BOOLEAN
    is CapabilityValue.Entity -> CapabilityValueType.ENTITY
}

sealed interface CapabilityAvailability {
    data object Available : CapabilityAvailability
    data class Unavailable(val reason: String) : CapabilityAvailability {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * Platform-neutral descriptor. [executionKey] is an adapter binding, not a natural-language alias.
 * The observer is identified here, but postconditions themselves continue to use the existing
 * SuccessCriteria contract until the legacy completion policies are migrated.
 */
data class AniobCapability(
    val id: String,
    val description: String,
    val schema: CapabilitySchema,
    val risk: CapabilityRisk,
    val permissions: Set<String>,
    val executionKey: String,
    val observer: String,
    val availability: (Map<String, CapabilityValue>) -> CapabilityAvailability,
    val effect: CapabilityEffect = CapabilityEffect.MUTATING
) {
    init {
        require(id.isNotBlank() && description.isNotBlank() && executionKey.isNotBlank() && observer.isNotBlank())
    }
}

data class CapabilityInvocation(val capabilityId: String, val arguments: Map<String, CapabilityValue> = emptyMap())
