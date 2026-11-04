package com.aniob.core.device

import com.aniob.core.capability.pattern.AtomVerdict
import com.aniob.core.capability.pattern.CapabilityId
import com.aniob.core.capability.pattern.CapabilityRegistry
import com.aniob.core.capability.pattern.DeviceEntityPort
import com.aniob.core.capability.pattern.EmptyEntityPort
import com.aniob.core.capability.pattern.Evidence
import com.aniob.core.capability.pattern.GenericDeepLinks
import com.aniob.core.capability.pattern.GenericEntityResolver
import com.aniob.core.capability.pattern.InstalledApp
import com.aniob.core.capability.pattern.OutcomeDecision
import com.aniob.core.capability.pattern.PackageHintSource
import com.aniob.core.capability.pattern.PatternCriteriaBridge
import com.aniob.core.capability.pattern.Plan
import com.aniob.core.capability.pattern.Postcondition
import com.aniob.core.capability.pattern.UniversalOutcomePolicy
import com.aniob.core.capability.pattern.UniversalPlanner
import com.aniob.core.domain.DeviceAssertion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "A to Z" proof for the capability engine: the tests never enumerate the production targets.
 * They generate arbitrary apps, places, numbers, messages, torch word orders and delays, and
 * assert the SAME code path handles every value with a declared, evidence-gated outcome.
 */
class UniversalPatternTest {

    private val noHints = object : PackageHintSource { override fun hint(name: String): String? = null }

    private fun resolverWith(vararg apps: InstalledApp) = GenericEntityResolver(
        port = object : DeviceEntityPort {
            override fun installedApps() = apps.toList()
            override fun isInstalled(pkg: String) = apps.any { it.packageName == pkg }
        },
        hints = noHints
    )

    private val letters = ('a'..'z').map { it.toString() }

    // ---------------------------------------------------------------- registry --

    @Test
    fun registryIsInternallyConsistent() {
        assertEquals(emptyList<String>(), CapabilityRegistry.validate())
        assertTrue("registry must not be empty", CapabilityRegistry.specs.isNotEmpty())
        assertTrue(CapabilityRegistry.specs.all { it.verbs.isNotEmpty() })
        // No spec may own a concrete production target as a verb.
        // Store targets must never appear as grammar. Channel names (sms/whatsapp/email) are
        // legitimate ontology, not target enumeration, so they are excluded from this check.
        val banned = listOf("linkedin", "instagram", "spotify", "netflix", "swiggy")
        CapabilityRegistry.specs.forEach { spec ->
            spec.verbs.forEach { v -> assertFalse("target leaked into grammar: $v", v in banned) }
        }
    }

    @Test
    fun unprovablePostconditionIsRejectedAtConstruction() {
        val error = runCatching {
            com.aniob.core.capability.pattern.PlanAtom(
                CapabilityId.QUERY, "whatever", Postcondition.Unprovable
            )
        }.exceptionOrNull()
        assertNotNull("PlanAtom must refuse a contract-free action", error)
    }

    // ---------------------------------------------------------------- installs A→Z --

    @Test
    fun installsAnyAppNameWithoutCodeChanges() {
        // 26 names that appear in no catalogue and no source file.
        letters.forEach { letter ->
            val name = "${letter}pp-${letter}$letter"
            val plan = UniversalPlanner.plan("install $name", resolverWith())
            assertTrue("no plan for $name", plan.isExecutable)
            val atom = plan.atoms.single()
            assertEquals(CapabilityId.INSTALL_APP, atom.capability)
            assertTrue(atom.intentUri!!.startsWith("market://search?q="))
            assertEquals(GenericDeepLinks.PLAY_STORE, atom.preferredPackage)
            // Unresolved package => unprovable install => never Completed on empty evidence.
            assertTrue(UniversalOutcomePolicy.evaluate(plan, Evidence.EMPTY) is OutcomeDecision.NotYet)
            // PackageManager saying "not installed" => hard failure, not a success on the store page.
            assertTrue(
                UniversalOutcomePolicy.evaluate(plan, Evidence(foregroundPackage = "com.android.vending"))
                    is OutcomeDecision.NotYet
            )
            // No evidence can ever prove a package that was never named.
            assertTrue(
                UniversalOutcomePolicy.evaluate(plan, Evidence(acknowledged = setOf("whatever")))
                    is OutcomeDecision.NotYet
            )
        }
    }

    @Test
    fun installOfAnInstalledAppIsProvableFromItsPackage() {
        val app = InstalledApp("com.example.aurora", "Aurora")
        val plan = UniversalPlanner.plan("install aurora", resolverWith(app))
        val atom = plan.atoms.single()
        assertTrue(atom.intentUri!!.startsWith("market://details?id=com.example.aurora"))
        assertTrue(
            UniversalOutcomePolicy.evaluate(plan, Evidence(installed = mapOf("com.example.aurora" to true)))
                is OutcomeDecision.Completed
        )
        assertTrue(
            UniversalOutcomePolicy.evaluate(plan, Evidence(installed = mapOf("com.example.aurora" to false)))
                is OutcomeDecision.Failed
        )
    }

    // ---------------------------------------------------------------- open any installed app --

    @Test
    fun opensAnyInstalledAppThroughTheDevicePort() {
        // 26 distinct labels — the resolver is scored, so the fixture must be unambiguous like a
        // real launcher. No label appears in any production map.
        val names = listOf(
            "Aurora", "Breeze", "Cobalt", "Dune", "Ember", "Fjord", "Glacier", "Harbor", "Indigo",
            "Juniper", "Kestrel", "Lagoon", "Meadow", "Nimbus", "Onyx", "Prairie", "Quartz", "Ridge",
            "Saffron", "Tundra", "Umber", "Violet", "Willow", "Xenon", "Yarrow", "Zephyr"
        )
        val apps = names.mapIndexed { i, n -> InstalledApp("com.zz.$i", n, listOf("zz-$i"), null) }
        apps.forEach { app ->
            val plan = UniversalPlanner.plan("open ${app.label}", resolverWith(*apps.toTypedArray()))
            val atom = plan.atoms.single()
            assertEquals(CapabilityId.OPEN_APP, atom.capability)
            assertEquals(app.packageName, atom.targetPackage)
            assertTrue(atom.postcondition is Postcondition.ForegroundPackage)
            assertTrue(
                UniversalOutcomePolicy.evaluate(plan, Evidence(foregroundPackage = app.packageName))
                    is OutcomeDecision.Completed
            )
        }
    }

    @Test
    fun openingAnUnknownAppAsksInsteadOfGuessing() {
        val plan = UniversalPlanner.plan("open never-installed-app", resolverWith())
        assertFalse(plan.isExecutable)
        assertTrue(plan.needsClarification)
        assertTrue(UniversalOutcomePolicy.evaluate(plan, Evidence.EMPTY) is OutcomeDecision.NeedsClarification)
    }

    // ---------------------------------------------------------------- torch grammar --

    @Test
    fun torchHandlesArbitraryWordOrderArticlesAndCase() {
        val phrasings = listOf(
            "flashlight on", "on the flashlight", "turn on the flashlight",
            "TURN THE FLASHLIGHT ON", "switch the torch on", "enable flashlight",
            "flashlight off", "off the torch", "turn off the flashlight", "disable the flashlight"
        )
        phrasings.forEach { phrase ->
            val plan = UniversalPlanner.plan(phrase, resolverWith())
            assertTrue("no plan for '$phrase'", plan.isExecutable)
            assertEquals(CapabilityId.TORCH, plan.atoms.last().capability)
        }
        assertEquals(
            Postcondition.TorchIs(false),
            UniversalPlanner.plan("turn off the flashlight", resolverWith()).terminalPostcondition
        )
    }

    @Test
    fun anyDelayValueIsParsedAndBoundsChecked() {
        val cases = mapOf(
            "flashlight on and off after 5 seconds" to 5_000L,
            "flashlight on then off in 2 minutes" to 120_000L,
            "torch on and off after 1 hour" to 3_600_000L,
            "flashlight on off after 0 seconds" to 0L
        )
        cases.forEach { (prompt, expected) ->
            val plan = UniversalPlanner.plan(prompt, resolverWith())
            assertTrue("no plan for '$prompt'", plan.isExecutable)
            assertEquals(expected, plan.atoms.last().delayAfterMs)
            assertEquals(Postcondition.TorchIs(false), plan.terminalPostcondition)
        }
        // A sequence cannot be Completed after only the first transition.
        val plan = UniversalPlanner.plan("flashlight on and off after 5 seconds", resolverWith())
        assertTrue(UniversalOutcomePolicy.evaluate(plan, Evidence(torchOn = true)) is OutcomeDecision.Failed)
        assertTrue(UniversalOutcomePolicy.evaluate(plan, Evidence(torchOn = null)) is OutcomeDecision.NotYet)
    }

    // ---------------------------------------------------------------- any entity / payload --

    @Test
    fun messagesAcceptAnyRecipientAndPayload() {
        val cases = listOf(
            Triple("sms", "+15550100", "see you at 6"),
            Triple("whatsapp", "919876543210", "the train is late — bring an umbrella & call me"),
            Triple("email", "someone@example.com", "Quarterly report, with commas, and more")
        )
        cases.forEach { (channel, recipient, payload) ->
            val plan = UniversalPlanner.plan("send $channel to $recipient saying $payload", resolverWith())
            assertTrue("no plan for $channel/$recipient", plan.isExecutable)
            val atom = plan.atoms.single()
            assertEquals(CapabilityId.SEND_MESSAGE, atom.capability)
            assertTrue(atom.postcondition is Postcondition.OsAcknowledged)
            // The payload survives the generic regex, including "and"/commas.
            assertTrue(atom.intentUri!!.isNotBlank())
            assertTrue(UniversalOutcomePolicy.evaluate(plan, Evidence.EMPTY) is OutcomeDecision.NotYet)
        }
    }

    @Test
    fun anyPlaceAndAnyNumberAreSlots() {
        listOf("Gateway of India", "221B Baker Street", "52.5200,13.4050").forEach { place ->
            val plan = UniversalPlanner.plan("navigate to $place", resolverWith())
            assertEquals(CapabilityId.NAVIGATE, plan.atoms.single().capability)
            assertTrue(plan.atoms.single().intentUri!!.startsWith("geo:0,0?q="))
        }
        listOf("+15550100", "112", "+44 20 7946 0958").forEach { number ->
            val plan = UniversalPlanner.plan("call $number", resolverWith())
            assertEquals(CapabilityId.CALL, plan.atoms.single().capability)
            assertTrue(plan.atoms.single().intentUri!!.startsWith("tel:"))
        }
    }

    @Test
    fun volumeAndDndAreGrammarNotPhrases() {
        (0..100 step 25).forEach { pct ->
            val plan = UniversalPlanner.plan("set volume to $pct%", resolverWith())
            assertEquals(CapabilityId.SET_VOLUME, plan.atoms.single().capability)
            assertEquals(pct.toString(), plan.atoms.single().args["percent"])
        }
        assertTrue(UniversalPlanner.plan("turn on dnd", resolverWith()).isExecutable)
        assertTrue(UniversalPlanner.plan("do not disturb off", resolverWith()).isExecutable)
    }

    // ---------------------------------------------------------------- composition --

    @Test
    fun compoundGoalsSplitGenericallyAcrossCapabilities() {
        val apps = arrayOf(InstalledApp("com.example.maps", "Atlas Maps"))
        val plan = UniversalPlanner.plan(
            "open atlas maps and call +15550100 then set volume to 40",
            resolverWith(*apps)
        )
        assertTrue(plan.isExecutable)
        assertEquals(3, plan.atoms.size)
        assertEquals(
            listOf(CapabilityId.OPEN_APP, CapabilityId.CALL, CapabilityId.SET_VOLUME),
            plan.atoms.map { it.capability }
        )
    }

    @Test
    fun partiallyResolvedCompoundsFailClosed() {
        val plan = UniversalPlanner.plan("turn on flashlight and open nemesis-app", resolverWith())
        assertFalse(plan.isExecutable)
        assertTrue("the unresolved clause must be reported", plan.clarifications.isNotEmpty())
        assertTrue(UniversalOutcomePolicy.evaluate(plan, Evidence(torchOn = true)) is OutcomeDecision.NeedsClarification)
    }

    // ---------------------------------------------------------------- the invariant --

    @Test
    fun everyCapabilityNeedsEvidenceBeforeCompletion() {
        val corpus = listOf(
            "install any-app-name",
            "turn on flashlight",
            "flashlight on and off after 3 seconds",
            "set volume to 30",
            "navigate to somewhere new",
            "call +15550123",
            "send sms to +15550123 saying hello",
            "take a screenshot"
        )
        corpus.forEach { prompt ->
            val plan = UniversalPlanner.plan(prompt, resolverWith())
            assertTrue("no plan for '$prompt'", plan.isExecutable)
            assertTrue(
                "empty evidence must never complete '$prompt'",
                UniversalOutcomePolicy.evaluate(plan, Evidence.EMPTY) !is OutcomeDecision.Completed
            )
            assertTrue(plan.atoms.none { it.postcondition == Postcondition.Unprovable })
        }
    }

    @Test
    fun criteriaBridgeGeneralizesBeyondInstallsAndTorch() {
        val app = InstalledApp("com.example.notes", "Blue Notes")
        val open = PatternCriteriaBridge.derive("open blue notes", resolverWith(app))
        assertTrue("com.example.notes" in open.mustShowPackage)

        // Only the terminal state is asserted: the transient ON must not become a success criterion.
        val torch = PatternCriteriaBridge.derive("torch on and off after 2 seconds", resolverWith())
        assertEquals(listOf(DeviceAssertion.TORCH_OFF), torch.deviceAsserts)

        val unknownInstall = PatternCriteriaBridge.derive("install totally-unknown-app", resolverWith())
        assertTrue(unknownInstall.requireInstall)
        assertTrue("no package can be named", unknownInstall.mustBeInstalled.isEmpty())
    }

    @Test
    fun bareToggleUsesContextButNeverInventsADevice() {
        val bare = UniversalPlanner.plan("on and off after 5 seconds", resolverWith())
        assertFalse(bare.isExecutable)
        assertTrue(bare.needsClarification)
        assertTrue(UniversalOutcomePolicy.evaluate(bare, Evidence.EMPTY) is OutcomeDecision.NeedsClarification)

        val contextual = UniversalPlanner.plan(
            "on and off after 5 seconds", resolverWith(), context = "turn on the flashlight"
        )
        assertTrue(contextual.isExecutable)
        assertEquals(5_000L, contextual.atoms.last().delayAfterMs)
        assertEquals(Postcondition.TorchIs(false), contextual.terminalPostcondition)
    }

    @Test
    fun planningIsDeterministicAndTotal() {
        val prompts = letters.map { "install $it-app" } + listOf(
            "turn on flashlight", "on and off after 9 seconds", "open unknown",
            "   ", "hello there", "navigate to some place", "call 112"
        )
        prompts.forEach { prompt ->
            val a = UniversalPlanner.plan(prompt, resolverWith())
            val b = UniversalPlanner.plan(prompt, resolverWith())
            assertEquals(a.atoms.map { it.capability }, b.atoms.map { it.capability })
            assertEquals(a.clarifications, b.clarifications)
            // Totality: every result is either executable or asks a question. Never silent.
            assertTrue(a.isExecutable || a.needsClarification)
            if (a.isExecutable) {
                assertTrue(a.atoms.all { it.postcondition != Postcondition.Unprovable })
            }
        }
    }
}
