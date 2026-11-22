package com.aniob.core.capability

import com.aniob.core.domain.DeviceCommand
import com.aniob.core.domain.DeviceArgumentType
import com.aniob.core.domain.DeviceRisk

/** Registry of executable contracts. IDs are exact and unique; no aliases or prompt matching. */
class AniobCapabilityRegistry(capabilities: List<AniobCapability>) {
    private val entries = capabilities.map { capability ->
        capability.copy(
            schema = CapabilitySchema(
                parameters = java.util.Collections.unmodifiableList(capability.schema.parameters.map {
                    it.copy(acceptedTypes = java.util.Collections.unmodifiableSet(it.acceptedTypes.toSet()))
                }),
                requiredAnyOf = java.util.Collections.unmodifiableList(capability.schema.requiredAnyOf.map {
                    java.util.Collections.unmodifiableSet(it.toSet())
                })
            ),
            permissions = java.util.Collections.unmodifiableSet(capability.permissions.toSet())
        )
    }
    private val byId = entries.associateBy { it.id }

    init { require(byId.size == entries.size) { "duplicate capability ID" } }

    fun all(): List<AniobCapability> = entries.toList()
    fun find(id: String): AniobCapability? = byId[id]
    fun available(): List<AniobCapability> = entries.filter { it.availability(emptyMap()) is CapabilityAvailability.Available }

    /** Snapshot for a model offer; unavailable entries are omitted unless explicitly requested. */
    fun toolDefinitions(includeUnavailable: Boolean = false): List<CapabilityToolDefinition> = entries.mapNotNull { capability ->
        val availability = capability.availability(emptyMap())
        if (!includeUnavailable && availability is CapabilityAvailability.Unavailable) null
        else CapabilityToolDefinition(
            capability.id, capability.description, capability.schema, capability.risk,
            capability.permissions, capability.observer, capability.effect, availability
        )
    }

    /** Revalidates every call, including ones previously selected from an available tool offer. */
    fun validate(invocation: CapabilityInvocation): List<String> {
        val capability = find(invocation.capabilityId) ?: return listOf("unknown capability '${invocation.capabilityId}'")
        val errors = capability.schema.validate(invocation.arguments).toMutableList()
        val availability = capability.availability(invocation.arguments)
        if (availability is CapabilityAvailability.Unavailable) errors += availability.reason
        return errors
    }

    companion object {
        /**
         * Bridges the existing command metadata and live permission check without duplicating the
         * command catalogue. Types are declared alongside the canonical device command metadata;
         * an Android execution adapter must convert these validated values to its wire format.
         */
        fun deviceCommands(state: () -> AniobCapabilityManifest.DeviceState): AniobCapabilityRegistry =
            AniobCapabilityRegistry(DeviceCommand.entries.map { command ->
                AniobCapability(
                    id = command.wire,
                    description = command.summary,
                    schema = CapabilitySchema(
                        parameters = command.argNames.map { name ->
                            val type = when (command.argumentTypes[name]) {
                                DeviceArgumentType.INTEGER -> CapabilityValueType.INTEGER
                                DeviceArgumentType.DECIMAL -> CapabilityValueType.DECIMAL
                                DeviceArgumentType.BOOLEAN -> CapabilityValueType.BOOLEAN
                                null -> CapabilityValueType.TEXT
                            }
                            val recipient = name in setOf("phone", "contact", "to") && command in setOf(
                                DeviceCommand.CALL, DeviceCommand.MESSAGE_SMS,
                                DeviceCommand.MESSAGE_WHATSAPP, DeviceCommand.MESSAGE_TELEGRAM
                            )
                            CapabilityParameter(
                                name, type,
                                if (recipient) setOf(CapabilityValueType.TEXT, CapabilityValueType.ENTITY) else setOf(type)
                            )
                        },
                        requiredAnyOf = command.required.map { it.split('|').toSet() }
                    ),
                    risk = if (command.risk == DeviceRisk.CONFIRM) CapabilityRisk.APPROVAL_REQUIRED else CapabilityRisk.LOW,
                    permissions = buildSet {
                        if (command.needsAccessibility) add("accessibility")
                        if (command.needsNotificationAccess) add("notification_access")
                    },
                    executionKey = command.wire,
                    effect = if (command.readOnly) CapabilityEffect.READ_ONLY else CapabilityEffect.MUTATING,
                    // Dispatch itself is never a verification observer.
                    observer = "independent device or screen observation",
                    availability = { args ->
                        val strings = args.mapNotNull { (k, v) ->
                            when (v) {
                                is CapabilityValue.Text -> k to v.value
                                is CapabilityValue.Integer -> k to v.value.toString()
                                is CapabilityValue.Decimal -> k to v.value.toString()
                                is CapabilityValue.BooleanValue -> k to v.value.toString()
                                is CapabilityValue.Entity -> null
                            }
                        }.toMap()
                        AniobCapabilityManifest.blockedReason(command, state(), strings)?.let {
                            CapabilityAvailability.Unavailable(it)
                        } ?: CapabilityAvailability.Available
                    }
                )
            })
    }
}

/** Model-facing projection of a registry descriptor, not an execution binding. */
data class CapabilityToolDefinition(
    val id: String,
    val description: String,
    val schema: CapabilitySchema,
    val risk: CapabilityRisk,
    val permissions: Set<String>,
    val observer: String,
    val effect: CapabilityEffect,
    val availability: CapabilityAvailability
)
