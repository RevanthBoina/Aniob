package com.aniob.core.device

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.execution.AniobActionExecutor
import com.aniob.core.execution.DispatchCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The wire contract an LLM writes against: DEVICE_CONTROL, its aliases, its arguments, its errors. */
class DeviceControlSchemaTest {

    private fun parse(json: String) = AniobActionSchema.parseActionJson(json) as AniobAction.DeviceControl

    @Test
    fun nestedArgsAreParsed() {
        val a = parse("""{"thought":"set volume","tool":"DEVICE_CONTROL","command":"volume_set","args":{"percent":"70","stream":"music"}}""")
        assertEquals(DeviceCommand.VOLUME_SET, a.command)
        assertEquals("70", a.args["percent"])
        assertEquals("music", a.args["stream"])
    }

    @Test
    fun numericAndBooleanArgsAreAccepted() {
        val a = parse("""{"tool":"DEVICE_CONTROL","command":"call","args":{"contact":"Mom","speaker":true}}""")
        assertEquals("true", a.args["speaker"])
        val v = parse("""{"tool":"DEVICE_CONTROL","command":"volume_set","args":{"percent":70}}""")
        assertEquals("70", v.args["percent"])
    }

    @Test
    fun flatArgumentsAreAcceptedForSmallModels() {
        val a = parse("""{"tool":"DEVICE_CONTROL","command":"volume_set","percent":40}""")
        assertEquals("40", a.args["percent"])
    }

    @Test
    fun toolNamedAsTheCommandStillResolves() {
        assertEquals(DeviceCommand.TORCH_ON, parse("""{"thought":"light","tool":"TORCH_ON"}""").command)
        assertEquals(DeviceCommand.MEDIA_NEXT, parse("""{"tool":"media_next"}""").command)
        assertEquals(DeviceCommand.TORCH_ON, parse("""{"tool":"FLASHLIGHT_ON"}""").command)
    }

    @Test
    fun missingRequiredArgumentIsAFieldLevelError() {
        try {
            parse("""{"tool":"DEVICE_CONTROL","command":"volume_set","args":{}}""")
            fail("expected a schema error")
        } catch (e: AniobActionSchema.ActionSchemaException) {
            assertTrue(e.message!!.contains("percent"))
        }
    }

    @Test
    fun alternativeRequirementsAreHonoured() {
        assertEquals("90", parse("""{"tool":"DEVICE_CONTROL","command":"timer_set","args":{"seconds":"90"}}""").args["seconds"])
        assertEquals("15", parse("""{"tool":"DEVICE_CONTROL","command":"timer_set","args":{"minutes":"15"}}""").args["minutes"])
        try {
            parse("""{"tool":"DEVICE_CONTROL","command":"timer_set","args":{"label":"pasta"}}""")
            fail("expected a schema error")
        } catch (_: AniobActionSchema.ActionSchemaException) {
        }
    }

    @Test
    fun unknownCommandListsTheValidOnes() {
        try {
            parse("""{"tool":"DEVICE_CONTROL","command":"hack_the_planet"}""")
            fail("expected a schema error")
        } catch (e: AniobActionSchema.ActionSchemaException) {
            assertTrue(e.message!!.contains("volume_set"))
        }
    }

    @Test
    fun deviceControlDispatchesAsSystemIntent() {
        val action = parse("""{"tool":"DEVICE_CONTROL","command":"torch_on"}""")
        val command = AniobActionExecutor.planDispatch(action, com.aniob.core.domain.AniobScreenState(packageName = "x"))
        assertTrue(command is DispatchCommand.SystemIntent)
        assertEquals("DEVICE_CONTROL", (command as DispatchCommand.SystemIntent).name)
        assertEquals("torch_on", command.argument)
    }

    @Test
    fun roundTripsThroughToActionJson() {
        val original = AniobAction.DeviceControl(DeviceCommand.FILE_FIND, mapOf("kind" to "pdf", "within_hours" to "24"))
        val back = parse(AniobActionSchema.toActionJson(original))
        assertEquals(original.command, back.command)
        assertEquals(original.args, back.args)
    }

    @Test
    fun schemaTextAdvertisesTheTool() {
        assertTrue(AniobActionSchema.ACTION_JSON_SCHEMA.contains("DEVICE_CONTROL"))
    }
}
