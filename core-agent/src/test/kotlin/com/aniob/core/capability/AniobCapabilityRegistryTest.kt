package com.aniob.core.capability

import com.aniob.core.domain.DeviceCommand
import org.junit.Assert.*
import org.junit.Test

class AniobCapabilityRegistryTest {
    @Test fun `command catalogue derives IDs and schemas from canonical commands`() {
        val registry = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }
        assertEquals(DeviceCommand.entries.map { it.wire }.toSet(), registry.all().map { it.id }.toSet())
        for (command in DeviceCommand.entries) {
            val capability = registry.find(command.wire)!!
            assertEquals(command.summary, capability.description)
            assertEquals(command.argNames.toSet(), capability.schema.parameters.map { it.name }.toSet())
            assertEquals(command.wire, capability.executionKey)
            assertTrue(capability.observer.isNotBlank())
        }
        assertNull(registry.find("flashlight_on"))
    }

    @Test fun `registry rejects duplicate IDs and invalid schemas`() {
        val cap = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }.all().first()
        assertThrows(IllegalArgumentException::class.java) { AniobCapabilityRegistry(listOf(cap, cap)) }
        assertThrows(IllegalArgumentException::class.java) {
            CapabilitySchema(listOf(CapabilityParameter("name", CapabilityValueType.TEXT)), listOf(setOf("missing")))
        }
    }

    @Test fun `schema rejects unknown wrong type and missing required alternatives`() {
        val registry = AniobCapabilityRegistry.deviceCommands { AniobCapabilityManifest.DeviceState() }
        assertTrue(registry.validate(CapabilityInvocation("volume_set")).isNotEmpty())
        assertTrue(registry.validate(CapabilityInvocation("volume_set", mapOf("percent" to CapabilityValue.Text("50")))).isNotEmpty())
        assertTrue(registry.validate(CapabilityInvocation("volume_set", mapOf("percent" to CapabilityValue.Decimal(java.math.BigDecimal("50")), "extra" to CapabilityValue.Text("x")))).isNotEmpty())
        assertTrue(registry.validate(CapabilityInvocation("volume_set", mapOf("percent" to CapabilityValue.Decimal(java.math.BigDecimal("50"))))).isEmpty())
        assertTrue(registry.validate(CapabilityInvocation("unknown")).isNotEmpty())
    }

    @Test fun `typed schemas accept stable entity references rather than labels`() {
        val schema = CapabilitySchema(
            parameters = listOf(CapabilityParameter("target", CapabilityValueType.ENTITY)),
            requiredAnyOf = listOf(setOf("target"))
        )
        assertTrue(schema.validate(mapOf("target" to CapabilityValue.Text("Alex"))).isNotEmpty())
        val ref = com.aniob.core.domain.EntityReference("contact", "opaque-id")
        assertTrue(schema.validate(mapOf("target" to CapabilityValue.Entity(ref))).isEmpty())
    }

    @Test fun `registry snapshots the supplied schemas`() {
        val fields = mutableListOf(CapabilityParameter("name", CapabilityValueType.TEXT))
        val groups = mutableListOf<Set<String>>(setOf("name"))
        val cap = AniobCapability(
            "sample", "description", CapabilitySchema(fields, groups), CapabilityRisk.LOW,
            emptySet(), "sample", "observer", { CapabilityAvailability.Available }
        )
        val registry = AniobCapabilityRegistry(listOf(cap))
        fields.clear()
        groups.clear()
        assertTrue(registry.validate(CapabilityInvocation("sample")).isNotEmpty())
        assertTrue(registry.validate(CapabilityInvocation("sample", mapOf("name" to CapabilityValue.Text("x")))).isEmpty())
    }

    @Test fun `availability is live and offers only available commands`() {
        var state = AniobCapabilityManifest.DeviceState(notificationAccess = false)
        val registry = AniobCapabilityRegistry.deviceCommands { state }
        assertFalse(registry.available().any { it.id == "otp_copy" })
        assertTrue(registry.validate(CapabilityInvocation("otp_copy")).isNotEmpty())
        state = state.copy(notificationAccess = true)
        assertTrue(registry.available().any { it.id == "otp_copy" })
        assertTrue(registry.validate(CapabilityInvocation("otp_copy")).isEmpty())
    }

    @Test fun `dispatcher checks risk and live availability and never equates dispatch with proof`() {
        var state = AniobCapabilityManifest.DeviceState(callPhone = true)
        val registry = AniobCapabilityRegistry.deviceCommands { state }
        val calls = mutableListOf<String>()
        val port = CapabilityExecutionPort { key, _ -> calls += key; "accepted" }
        val invocation = CapabilityInvocation("call", mapOf("number" to CapabilityValue.Text("123")))
        val denied = AniobCapabilityDispatcher(registry, port).dispatch(invocation)
        assertTrue(denied is CapabilityDispatchResult.Rejected)
        assertTrue(calls.isEmpty())
        val unavailableAfterApproval = AniobCapabilityDispatcher(registry, port) {
            state = state.copy(callPhone = false)
            true
        }.dispatch(invocation)
        assertTrue(unavailableAfterApproval is CapabilityDispatchResult.Rejected)
        assertTrue(calls.isEmpty())
        state = state.copy(callPhone = true)
        val result = AniobCapabilityDispatcher(registry, port) { true }.dispatch(invocation)
        assertTrue(result is CapabilityDispatchResult.Submitted)
        assertEquals(listOf("call"), calls)
    }
}
