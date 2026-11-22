package com.aniob.core.capability

import com.aniob.core.domain.AniobTaskPlan
import com.aniob.core.domain.EntityReference
import com.aniob.core.providers.AniobStructuredOutput
import org.junit.Assert.*
import org.junit.Test

class AniobStructuredTaskPlanTest {
    private val state = AniobCapabilityManifest.DeviceState()
    private val registry = AniobCapabilityRegistry.deviceCommands { state }

    @Test fun `planning prompt offers available registry definitions only`() {
        val disabled = AniobCapabilityRegistry.deviceCommands { state.copy(notificationAccess = false) }
        val prompt = AniobStructuredOutput.taskPlanPrompt(disabled)
        assertTrue(prompt.contains("\"id\":\"torch_on\""))
        assertFalse(prompt.contains("\"id\":\"otp_copy\""))
        assertTrue(prompt.contains("\"required_any_of\""))
        assertTrue(prompt.contains("\"type\":\"TEXT\""))
        assertTrue(prompt.contains("\"risk\":\"APPROVAL_REQUIRED\""))
    }

    @Test fun `parses all modes and ordered calls with independent criteria`() {
        val answer = """{"mode":"ANSWER","requested_outcome":"Explain","response":"Answer"}"""
        val clarify = """{"mode":"CLARIFY","requested_outcome":"Set volume","response":"What value?","invocations":[{"capability_id":"volume_set","arguments":{}}],"unresolved_inputs":[{"capability_id":"volume_set","argument":"percent"}]}"""
        val calls = """{"mode":"EXECUTE","requested_outcome":"Change light","invocations":[{"capability_id":"torch_on"},{"capability_id":"torch_off"}],"postconditions":{"device_asserts":["TORCH_OFF"]}}"""
        val read = """{"mode":"READ","requested_outcome":"View apps","invocations":[{"capability_id":"installed_apps"}],"postconditions":{"must_show_package":["com.example"]}}"""
        val readFiles = """{"mode":"READ","requested_outcome":"Find files","invocations":[{"capability_id":"file_find"},{"capability_id":"alarms_show"}]}"""
        assertEquals(AniobTaskPlan.Mode.ANSWER, accepted(answer).mode)
        assertEquals(AniobTaskPlan.Mode.CLARIFY, accepted(clarify).mode)
        assertEquals(listOf("torch_on", "torch_off"), accepted(calls).invocations.map { it.capabilityId })
        assertEquals(AniobTaskPlan.Mode.READ, accepted(read).mode)
        assertEquals(listOf("file_find", "alarms_show"), accepted(readFiles).invocations.map { it.capabilityId })
    }

    @Test fun `rejects unknown and unavailable calls but permits missing criteria`() {
        val unknown = """{"mode":"EXECUTE","requested_outcome":"Run","invocations":[{"capability_id":"flashlight_on"}],"postconditions":{"device_asserts":["TORCH_ON"]}}"""
        val missing = """{"mode":"EXECUTE","requested_outcome":"Run","invocations":[{"capability_id":"torch_on"}]}"""
        val unavailable = """{"mode":"EXECUTE","requested_outcome":"Run","invocations":[{"capability_id":"otp_copy"}],"postconditions":{"must_show_package":["com.example"]}}"""
        assertRejected(unknown)
        assertEquals(AniobTaskPlan.Mode.EXECUTE, accepted(missing).mode)
        assertTrue(AniobStructuredOutput.parseTaskPlan(unavailable, AniobCapabilityRegistry.deviceCommands { state.copy(notificationAccess = false) }) is AniobStructuredOutput.TaskPlanParse.Rejected)
    }

    @Test fun `READ mode cannot disguise a mutating capability`() {
        val json = """{"mode":"READ","requested_outcome":"Place call","invocations":[{"capability_id":"call","arguments":{"number":"123"}}],"postconditions":{"must_show_package":["com.example"]}}"""
        assertRejected(json)
    }

    @Test fun `rejects malformed and ambiguous JSON instead of repairing by guessing`() {
        val bad = listOf(
            "not json", "[]", "{}", """{"mode":"ANSWER","mode":"ANSWER","requested_outcome":"q","response":"a"}""",
            """{"mode":null,"mode":"ANSWER","requested_outcome":"q","response":"a"}""",
            """{"mode":"ANSWER","requested_outcome":"q","response":"a"} {"mode":"ANSWER"}""",
            """{"mode":"ANSWER","requested_outcome":"q","response":"a","extra":true}""",
            """{"mode":"EXECUTE","requested_outcome":"q","invocations":[{"capability_id":"torch_on","arguments":{"other":true}}],"postconditions":{"device_asserts":["TORCH_ON"]}}""",
            """{"mode":"EXECUTE","requested_outcome":"q","invocations":[{"capability_id":"torch_on"}],"postconditions":{"device_asserts":["INVENTED"]}}"""
        )
        bad.forEach(::assertRejected)
    }

    @Test fun `entity references must be supplied by runtime and types must match schema`() {
        val cap = AniobCapability("lookup", "lookup", CapabilitySchema(listOf(CapabilityParameter("subject", CapabilityValueType.ENTITY)), listOf(setOf("subject"))), CapabilityRisk.LOW, emptySet(), "lookup", "observer", { CapabilityAvailability.Available }, CapabilityEffect.READ_ONLY)
        val entityRegistry = AniobCapabilityRegistry(listOf(cap))
        val ref = EntityReference("contact", "opaque-7")
        val json = """{"mode":"READ","requested_outcome":"Read","invocations":[{"capability_id":"lookup","arguments":{"subject":{"kind":"contact","id":"opaque-7"}}}],"entity_bindings":{"person":{"kind":"contact","id":"opaque-7"}},"postconditions":{"must_show_package":["com.example"]}}"""
        assertTrue(AniobStructuredOutput.parseTaskPlan(json, entityRegistry) is AniobStructuredOutput.TaskPlanParse.Rejected)
        val result = AniobStructuredOutput.parseTaskPlan(json, entityRegistry, setOf(ref)) as AniobStructuredOutput.TaskPlanParse.Accepted
        assertEquals(ref, (result.plan.invocations.single().arguments["subject"] as CapabilityValue.Entity).value)
        assertEquals(ref, result.plan.entityBindings["person"])
        val badType = json.replace("{\"kind\":\"contact\",\"id\":\"opaque-7\"}", "\"opaque-7\"")
        assertTrue(AniobStructuredOutput.parseTaskPlan(badType, entityRegistry, setOf(ref)) is AniobStructuredOutput.TaskPlanParse.Rejected)
    }

    @Test fun `device schemas require numeric and boolean values`() {
        val decimal = """{"mode":"EXECUTE","requested_outcome":"Set volume","invocations":[{"capability_id":"volume_set","arguments":{"percent":42.5}}],"postconditions":{"must_show_package":["com.example"]}}"""
        val boolean = """{"mode":"EXECUTE","requested_outcome":"Dismiss","invocations":[{"capability_id":"notification_dismiss","arguments":{"all":true}}],"postconditions":{"must_show_package":["com.example"]}}"""
        assertEquals(java.math.BigDecimal("42.5"), (accepted(decimal).invocations.single().arguments["percent"] as CapabilityValue.Decimal).value)
        assertEquals(true, (accepted(boolean).invocations.single().arguments["all"] as CapabilityValue.BooleanValue).value)
        assertRejected(decimal.replace("42.5", "\"42.5\""))
        assertRejected(decimal.replace("42.5", "1e9999"))
        assertRejected(boolean.replace("true", "\"true\""))
    }

    @Test fun `integer arguments retain precision and reject overflow or fractions`() {
        val cap = AniobCapability("measure", "measure", CapabilitySchema(listOf(CapabilityParameter("count", CapabilityValueType.INTEGER)), listOf(setOf("count"))), CapabilityRisk.LOW, emptySet(), "measure", "observer", { CapabilityAvailability.Available })
        val intRegistry = AniobCapabilityRegistry(listOf(cap))
        fun json(value: String) = """{"mode":"EXECUTE","requested_outcome":"Measure","invocations":[{"capability_id":"measure","arguments":{"count":$value}}],"postconditions":{"must_show_package":["com.example"]}}"""
        val result = AniobStructuredOutput.parseTaskPlan(json("9223372036854775807"), intRegistry) as AniobStructuredOutput.TaskPlanParse.Accepted
        assertEquals(Long.MAX_VALUE, (result.plan.invocations.single().arguments["count"] as CapabilityValue.Integer).value)
        assertTrue(AniobStructuredOutput.parseTaskPlan(json("9223372036854775808"), intRegistry) is AniobStructuredOutput.TaskPlanParse.Rejected)
        assertTrue(AniobStructuredOutput.parseTaskPlan(json("1.5"), intRegistry) is AniobStructuredOutput.TaskPlanParse.Rejected)
    }

    private fun accepted(json: String): AniobTaskPlan =
        (AniobStructuredOutput.parseTaskPlan(json, registry) as AniobStructuredOutput.TaskPlanParse.Accepted).plan
    private fun assertRejected(json: String) {
        assertTrue(AniobStructuredOutput.parseTaskPlan(json, registry) is AniobStructuredOutput.TaskPlanParse.Rejected)
    }
}
