package com.aniob.core.audit

import com.aniob.core.device.MediaExpectation
import com.aniob.core.device.MediaExpectation.Op
import com.aniob.core.device.MediaExpectation.Snap
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.external.AniobGatewayClassifier
import com.aniob.core.external.AniobGatewayCircuitBreaker
import com.aniob.core.external.AniobRateLimitBackoff
import com.aniob.core.external.AniobRetryAfter
import com.aniob.core.external.GatewayFailureKind
import com.aniob.core.feasibility.*
import com.aniob.core.grillme.AniobGrillMeEngine
import com.aniob.core.grillme.AniobPromptGrillMeClient
import com.aniob.core.grounding.AniobTargetMatchPrompt
import com.aniob.core.grounding.AniobTargetMatcher
import com.aniob.core.grounding.AniobTargetResolution
import com.aniob.core.outcome.*
import com.aniob.core.text.AniobTextFold
import com.aniob.core.understander.AniobPromptUnderstanding
import com.aniob.core.verification.Evidence
import com.aniob.core.verification.EvidenceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AuditPatchTest {
    private var n = 0
    private fun node(text: String = "", desc: String = "", clickable: Boolean = true, editable: Boolean = false, cls: String = "android.widget.Button") =
        AniobNode(id = ++n, className = cls, text = text, contentDescription = desc, isClickable = clickable, isEditable = editable,
            bounds = AniobRect(0, n * 100, 500, n * 100 + 90))

    // ---------------- U-01 ----------------
    @Test fun kotlinLowercaseIsAlreadyLocaleInvariant_butTurkishCapitalIIsStillBroken() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("install", "INSTALL".lowercase())               // the claimed bug does NOT exist
            assertNotEquals("install", "\u0130NSTALL".lowercase())       // the real bug: İNSTALL -> i̇nstall
            assertEquals("install", AniobTextFold.fold("\u0130NSTALL"))  // fixed
            assertEquals("install", AniobTextFold.fold("INSTALL"))
        } finally { Locale.setDefault(old) }
    }
    @Test fun foldHandlesStylizedUnicodeAndDiacritics() {
        assertEquals("install", AniobTextFold.fold("\uFF29\uFF2E\uFF33\uFF34\uFF21\uFF2C\uFF2C"))                       // full-width
        assertEquals("install", AniobTextFold.fold("\uD835\uDC08\uD835\uDC0D\uD835\uDC12\uD835\uDC13\uD835\uDC00\uD835\uDC0B\uD835\uDC0B")) // math bold
        assertEquals("install", AniobTextFold.fold("\u24BE\u24C3\u24C8\u24C9\u24B6\u24C1\u24C1"))                       // circled
        assertEquals("reglages", AniobTextFold.fold("R\u00E9glages"))
        assertEquals("search", AniobTextFold.compact("[Search]..."))
        assertEquals("\u0C2E\u0C3E\u0C2A\u0C4D", AniobTextFold.fold("\u0C2E\u0C3E\u0C2A\u0C4D")) // Telugu marks untouched
    }
    @Test fun matcherTiers() {
        val settings = node("Settings"); val dl = node("Download"); val search = node(desc = "Search query", clickable = true, cls = "android.widget.ImageButton")
        val checkout = node("Checkout"); val label = node("Settings", clickable = false, cls = "android.widget.TextView")
        fun m(q: String, vararg ns: AniobNode) = AniobTargetMatcher.match(q, "button_click", ns.toList())
        assertEquals(settings, (m("setings", settings, dl) as AniobTargetMatcher.Result.Match).node)
        assertEquals(dl, (m("dwnload", settings, dl) as AniobTargetMatcher.Result.Match).node)
        assertEquals(search, (m("serch", settings, search) as AniobTargetMatcher.Result.Match).node)
        assertEquals(search, (m("Search", settings, search) as AniobTargetMatcher.Result.Match).node)
        assertEquals(checkout, (m("Buy", settings, checkout) as AniobTargetMatcher.Result.Match).node)
        assertEquals(settings, (m("SETTINGS", label, settings) as AniobTargetMatcher.Result.Match).node) // clickable beats static label
        assertEquals(AniobTargetMatcher.Result.None, m("Wallet", settings, dl))                           // no guessing
        assertEquals(AniobTargetMatcher.Result.None, m("on", node("Notifications")))                      // 2-letter query never substring-matches
        assertTrue(m("Settings", node("Settings"), node("Settings")) is AniobTargetMatcher.Result.Ambiguous)
        assertEquals(AniobTargetMatcher.Result.None, m("cab", node("Vocabulary")))                        // whole word only
    }
    @Test fun llmMatcherCannotHallucinate() = runBlocking<Unit> {
        val a = node("Pay"); val b = node("Cancel")
        val ok = AniobTargetResolution.resolve("confirm purchase", "button_click", listOf(a, b)) { """```json {"matchedId": ${a.id}, "confidence": 0.9, "reason": "pay confirms"} ```""" }
        assertEquals(a, ok?.node); assertEquals(AniobTargetResolution.Via.LLM, ok?.via)
        assertNull(AniobTargetResolution.resolve("confirm purchase", "button_click", listOf(a, b)) { """{"matchedId": 999, "confidence": 0.99, "reason": "x"}""" })
        assertNull(AniobTargetResolution.resolve("confirm purchase", "button_click", listOf(a, b)) { """{"matchedId": ${a.id}, "confidence": 0.3, "reason": "x"}""" })
        assertNull(AniobTargetResolution.resolve("confirm purchase", "button_click", listOf(a, b)) { "I think Pay" })
        // deterministic hit never calls the model
        var called = false
        AniobTargetResolution.resolve("Pay", "button_click", listOf(a, b)) { called = true; null }
        assertFalse(called)
        assertTrue(AniobTargetMatchPrompt.build("Buy", "button_click", listOf(a)).contains("No Guessing"))
    }

    // ---------------- Step 1 ----------------
    @Test fun verifiedNeedsEvidence() {
        assertThrows(IllegalArgumentException::class.java) { SealedOutcome.verified("done", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { SealedOutcome.verified("done", listOf(EvidenceRef(EvidenceRef.Origin.PROBE, "", ""))) }
        assertEquals(RunOutcome.UNVERIFIED, SealedOutcome.verifiedOrDowngrade("done", emptyList()).outcome)
        val ev = EvidenceRef(EvidenceRef.Origin.PROBE, "probe_media", "state=paused")
        val v = SealedOutcome.verified("Paused", listOf(ev))
        assertTrue(v.isMachineVerified); assertNotNull(v.evidenceRef)
        // the executor's own step log can never become evidence
        assertNull(EvidenceRef.from(Evidence(EvidenceKind.RECORDED_STEP, "step 3", "tapped Install")))
        assertNull(EvidenceRef.from(Evidence(EvidenceKind.NOTE_EXCERPT, "note:x", "it worked")))
        assertNotNull(EvidenceRef.from(Evidence(EvidenceKind.PROBE_OUTPUT, "probe_battery", "level=80")))
    }
    @Test fun storedVerifiedWithoutEvidenceIsDowngradedOnRead() {
        assertEquals(RunOutcome.UNVERIFIED, SealedOutcome.fromStored("VERIFIED", "x", null, null)!!.outcome)
        assertNull(SealedOutcome.fromStored("SUCCESS", "x", null, null))
        val ev = EvidenceRef(EvidenceRef.Origin.SCREEN_OBSERVATION, "screen#4", "text 'Sent' visible", 4)
        val round = SealedOutcome.fromStored("VERIFIED", "ok", ev.encode(), null)!!
        assertEquals(RunOutcome.VERIFIED, round.outcome)
        assertEquals(ev, EvidenceRef.decode(ev.encode()))
    }
    @Test fun thumbs() {
        val un = SealedOutcome.unverified("could not check")
        val up = un.withUserDecision(UserDecision.YES, 5)
        assertEquals(RunOutcome.VERIFIED, up.outcome); assertTrue(up.isUserAttested); assertFalse(up.isMachineVerified)
        assertEquals(RunOutcome.FAILED, un.withUserDecision(UserDecision.NO).outcome)
        val v = SealedOutcome.verified("ok", listOf(EvidenceRef(EvidenceRef.Origin.PROBE, "p", "e")))
        assertEquals(RunOutcome.FAILED, v.withUserDecision(UserDecision.NO).outcome)   // user disputes a "verified"
        assertEquals(RunOutcome.ANSWERED, SealedOutcome.answered("hi").withUserDecision(UserDecision.NO).outcome)
        assertEquals(UserDecision.NO, SealedOutcome.answered("hi").withUserDecision(UserDecision.NO).userDecision)
    }

    // ---------------- U-02 ----------------
    @Test fun mediaExpectations() {
        val playing = Snap(MediaExpectation.STATE_PLAYING, "a", 10_000); val paused = Snap(MediaExpectation.STATE_PAUSED, "a", 10_000)
        assertTrue(MediaExpectation.satisfied(Op.PAUSE, playing, paused))
        assertFalse(MediaExpectation.satisfied(Op.PAUSE, playing, playing))          // dispatched but nothing changed => failure
        assertTrue(MediaExpectation.satisfied(Op.TOGGLE, playing, paused))
        assertFalse(MediaExpectation.satisfied(Op.TOGGLE, playing, playing))
        assertTrue(MediaExpectation.satisfied(Op.PLAY, paused, playing))
        assertFalse(MediaExpectation.satisfied(Op.NEXT, playing, playing))
        assertTrue(MediaExpectation.satisfied(Op.NEXT, playing, playing.copy(trackKey = "b")))
        assertTrue(MediaExpectation.satisfied(Op.PREVIOUS, playing, playing.copy(positionMs = 100)))
        assertFalse(MediaExpectation.satisfied(Op.PREVIOUS, playing.copy(positionMs = 1000), playing.copy(positionMs = 900)))
    }

    // ---------------- U-03 ----------------
    @Test fun rateLimitIsClassifiedAndHonoursRetryAfter() {
        val e = AniobGatewayClassifier.classifyHttp(429, "text/html", "<html><title>Too Many</title></html>", "http://gw", "7")!!
        assertEquals(GatewayFailureKind.RATE_LIMIT, e.kind); assertEquals(7_000L, e.retryAfterMs)
        assertTrue(e.message!!.contains("(429)"))
        assertEquals(GatewayFailureKind.CLIENT_ERROR, AniobGatewayClassifier.classifyHttp(400, "application/json", "{}", "x")!!.kind)
        var now = 0L
        val cb = AniobGatewayCircuitBreaker { now }
        cb.recordFailure("gw", e.kind, e.retryAfterMs)
        assertTrue(cb.isOpen("gw")); assertEquals(7_000L, cb.remainingMs("gw"))
        now = 7_001; assertFalse(cb.isOpen("gw"))
        cb.recordFailure("gw", GatewayFailureKind.RATE_LIMIT, null)              // no header => 30s fallback, not 0
        assertTrue(cb.isOpen("gw"))
        cb.recordFailure("gw2", GatewayFailureKind.RATE_LIMIT, 9_999_999L)       // absurd header is clamped
        assertEquals(120_000L, cb.remainingMs("gw2"))
        assertEquals(0L, AniobGatewayClassifier.classifyHttp(429, null, "", null)!!.retryAfterMs ?: 0L)
    }
    @Test fun retryAfterParsing() {
        assertEquals(3_000L, AniobRetryAfter.parseMs("3"))
        assertNull(AniobRetryAfter.parseMs("soon")); assertNull(AniobRetryAfter.parseMs("-4")); assertNull(AniobRetryAfter.parseMs(null))
        assertEquals(5_000L, AniobRetryAfter.parseMs("Wed, 21 Oct 2015 07:28:05 GMT", java.time.ZonedDateTime.parse("2015-10-21T07:28:00Z").toInstant().toEpochMilli()))
    }
    @Test fun backoffIsBounded() {
        assertEquals(2_000L, AniobRateLimitBackoff.waitMs(0, 2_000))
        assertNull(AniobRateLimitBackoff.waitMs(0, 30_000))               // too long: degrade instead of sleeping
        assertNull(AniobRateLimitBackoff.waitMs(2, 100))                  // attempts exhausted
        assertNull(AniobRateLimitBackoff.waitMs(1, 6_000, waitedSoFarMs = 5_000))
        assertEquals(1_500L, AniobRateLimitBackoff.waitMs(0, null))
        assertEquals(3_000L, AniobRateLimitBackoff.waitMs(1, null))
    }

    // ---------------- Step 3 ----------------
    @Test fun feasibility() {
        val install = PlanStep("install app.apk", ActionGate.SYSTEM_PACKAGE_DIALOG, setOf(Postcondition.PACKAGE_INSTALLED))
        val a11y = CapabilityEnvelope(setOf(Privilege.ACCESSIBILITY, Privilege.PACKAGE_QUERY))
        val d = AniobFeasibility.assess(listOf(install), a11y) as Feasibility.DelegateToUser
        assertEquals(AniobFeasibility.OPT_WAIT, d.question.defaultOption)
        assertEquals(Feasibility.Feasible, AniobFeasibility.assess(listOf(install), a11y.copy(held = a11y.held + Privilege.SHIZUKU_ADB)))
        val blind = AniobFeasibility.assess(listOf(install), CapabilityEnvelope(setOf(Privilege.ACCESSIBILITY)))
        assertTrue(blind is Feasibility.Infeasible)                      // cannot observe => never dispatched
        val pay = PlanStep("pay", ActionGate.PAYMENT_CONFIRM)
        assertTrue(AniobFeasibility.assess(listOf(pay), CapabilityEnvelope(Privilege.values().toSet())) is Feasibility.DelegateToUser) // no privilege bypasses payment
        assertEquals(Feasibility.Feasible, AniobFeasibility.assess(listOf(PlanStep("torch", postconditions = setOf(Postcondition.HARDWARE_CALLBACK))), CapabilityEnvelope(emptySet())))
    }
    @Test fun delegationWatcher() = runBlocking<Unit> {
        var calls = 0
        val ev = AniobDelegationWatcher.await(timeoutMs = 2_000, pollMs = 10) { if (++calls >= 3) EvidenceRef(EvidenceRef.Origin.PROBE, "package_manager", "installed") else null }
        assertNotNull(ev); assertEquals(3, calls)
        assertNull(AniobDelegationWatcher.await(timeoutMs = 60, pollMs = 10) { null })
    }

    // ---------------- Step 0 / Grill-Me ----------------
    @Test fun understanding() {
        val u = AniobPromptUnderstanding.understand("Can you open settings and then turn on the flashlight?")
        assertEquals(2, u.goals.size); assertTrue(u.needsEcho); assertEquals(AniobPromptUnderstanding.SpeechAct.COMMAND, u.speechAct)
        assertEquals(AniobPromptUnderstanding.SpeechAct.QUESTION, AniobPromptUnderstanding.understand("what is the vocabulary of a cab driver").speechAct)
        assertTrue(AniobPromptUnderstanding.understand("uninstall all apps").risks.contains("affects many items"))
        assertTrue(AniobPromptUnderstanding.understand("buy milk").risks.contains("spends money"))
        assertEquals(1, AniobPromptUnderstanding.understand("read the text on the screen").goals.size)
        assertEquals(AniobPromptUnderstanding.SpeechAct.UNCLEAR, AniobPromptUnderstanding.understand("hmm").speechAct)
    }
    @Test fun grillMeIsWordBasedAndCancellable() = runBlocking<Unit> {
        val engine = AniobGrillMeEngine()
        assertFalse(engine.evaluateTask("explain the vocabulary of this page").needsClarification)       // was: taxi questions
        assertFalse(engine.evaluateTask("summarise the context of this chat").needsClarification)        // was: message questions
        assertTrue(engine.evaluateTask("book a cab").needsClarification)
        assertFalse(engine.evaluateTask("please open settings").needsClarification)
        assertTrue(engine.evaluateTask("open settings and send a message").needsClarification)           // was: bypassed by startsWith
        val cancelling = AniobGrillMeEngine(llmClient = object : com.aniob.core.grillme.GrillMeLlmClient {
            override suspend fun generateClarificationQuestions(taskPrompt: String): com.aniob.core.grillme.AniobGrillMeResult = throw CancellationException("stop")
        })
        assertThrows(CancellationException::class.java) { runBlocking { cancelling.evaluateTask("book a cab") } }
    }
    @Test fun promptGrillClientValidatesModelOutput() = runBlocking<Unit> {
        val c = AniobPromptGrillMeClient("sys") { _, _ -> """x {"needsClarification": true, "questions": [
          {"id":"dest","question":"Where to?","options":["Home","Office","A","B","C"],"defaultOption":"Mars"},
          {"id":"","question":"","options":[]},
          {"question":"Which app?","options":["Uber"],"allowFreeText":false,"defaultOption":"Uber"},
          {"id":"q9","question":"third","options":[]}]} y""" }
        val r = c.generateClarificationQuestions("book a cab")
        assertTrue(r.needsClarification); assertEquals(2, r.questions.size)
        assertEquals(4, r.questions[0].options.size); assertNull(r.questions[0].defaultOption)         // default not in options => dropped
        assertEquals("Uber", r.questions[1].defaultOption)
        assertFalse(AniobPromptGrillMeClient("s") { _, _ -> """{"needsClarification": false, "questions": []}""" }.generateClarificationQuestions("x").needsClarification)
        assertThrows(IllegalStateException::class.java) { runBlocking { AniobPromptGrillMeClient("s") { _, _ -> "sure!" }.generateClarificationQuestions("x") } }
        // the engine falls back to deterministic questions when the client fails
        val engine = AniobGrillMeEngine(llmClient = AniobPromptGrillMeClient("s") { _, _ -> null })
        assertTrue(engine.evaluateTask("book a cab").needsClarification)
    }

    @Test fun grillMeModelIsOnlyCalledWhenThereIsAmbiguity() = runBlocking<Unit> {
        var calls = 0
        val client = object : com.aniob.core.grillme.GrillMeLlmClient {
            override suspend fun generateClarificationQuestions(taskPrompt: String): com.aniob.core.grillme.AniobGrillMeResult {
                calls++; return com.aniob.core.grillme.AniobGrillMeResult(false)
            }
        }
        val engine = AniobGrillMeEngine(llmClient = client)
        engine.evaluateTask("open youtube"); assertEquals(0, calls)          // no round-trip for a clear command
        engine.evaluateTask("book a cab"); assertEquals(1, calls)            // deterministic ladder smells ambiguity
        engine.evaluateTask("hmm sort my life out"); assertEquals(2, calls)  // understanding says UNCLEAR
    }

    @Test fun modelCanAskThroughFailPrefix() {
        val q = com.aniob.core.grillme.AniobAskUser.parse("ASK_USER: Which Spotify account? | Personal | Work | Family | Kids")!!
        assertEquals("Which Spotify account?", q.question); assertEquals(listOf("Personal", "Work", "Family"), q.options)
        assertNull(com.aniob.core.grillme.AniobAskUser.parse("Could not find the button"))
        assertNull(com.aniob.core.grillme.AniobAskUser.parse("ASK_USER:   "))
    }

    // ---------------- thinking visibility ----------------
    @Test fun thinkingStatus() {
        val s = com.aniob.core.narration.PhaseSnapshot(com.aniob.core.narration.AgentPhase.PLANNING, com.aniob.core.narration.ThinkSource.MODEL_CLOUD, sinceMs = 1_000)
        assertFalse(com.aniob.core.narration.ThinkingStatus.isStalled(s, 5_000)); assertTrue(com.aniob.core.narration.ThinkingStatus.isStalled(s, 9_500))
        assertTrue(com.aniob.core.narration.ThinkingStatus.headline(s, 9_500).startsWith("Still waiting"))
        val rules = s.copy(source = com.aniob.core.narration.ThinkSource.RULES)
        assertFalse(com.aniob.core.narration.ThinkingStatus.isStalled(rules, 99_000))
        assertTrue(com.aniob.core.narration.ThinkingStatus.badge(rules).contains("no model"))
        assertEquals("Rate limited — retrying in 4s", com.aniob.core.narration.ThinkingStatus.headline(
            s.copy(phase = com.aniob.core.narration.AgentPhase.RATE_LIMITED, retryAtMs = 13_500), 10_000))
    }
}
