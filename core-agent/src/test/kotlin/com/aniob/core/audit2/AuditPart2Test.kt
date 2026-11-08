package com.aniob.core.audit2

import com.aniob.core.dialogue.AppAffinity
import com.aniob.core.dialogue.AppOps
import com.aniob.core.dialogue.ConversationState
import com.aniob.core.dialogue.LocalQueryTools
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.execution.SystemNavTargets
import com.aniob.core.knowledge.AppCatalogEntry
import com.aniob.core.lexicon.TaskLexicon
import com.aniob.core.memory.RememberTemplate
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.text.SpokenNumbers
import com.aniob.core.verifier.DeterministicVerifier
import com.aniob.core.verifier.ScreenEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the generalised fixes: none of these assertions names one specific task, so a
 * passing suite means the CLASS of report is handled (any spoken value, any installed app, any
 * navigation control, any device-inventory question).
 */
class AuditPart2Test {

    private val apps = listOf(
        AppCatalogEntry("com.example.recorder", "Voice Recorder"),
        AppCatalogEntry("com.example.discord", "Discord"),
        AppCatalogEntry("com.example.linkedin", "LinkedIn"),
        AppCatalogEntry("com.android.settings", "Settings", isSystemApp = true)
    )

    @After fun restorePosture() { AniobSafetyInterceptor.policy = AniobSafetyInterceptor.Policy.ASSIST }

    private fun screen(vararg texts: String, pkg: String = "com.example.app", ime: Boolean = false, cls: String = "android.widget.TextView") =
        AniobScreenState(
            packageName = pkg,
            nodes = texts.mapIndexed { i, t -> AniobNode(id = i, className = cls, text = t) },
            hasImeWindow = ime
        )

    // ---------- spoken values (any number word, any clock idiom) ----------
    @Test fun spokenValuesBecomeTheDigitsAppsDisplay() {
        assertEquals("set alarm for 10 am", SpokenNumbers.normalize("set alarm for ten am"))
        assertEquals("alarm 10:30 pm", SpokenNumbers.normalize("alarm ten thirty pm"))
        assertEquals("timer 25 minutes", SpokenNumbers.normalize("timer twenty five minutes"))
        assertEquals("10:30", SpokenNumbers.normalize("half past ten"))
        assertEquals("which one of the two options", SpokenNumbers.normalize("which one of the two options"))
        assertTrue("10:00" in SpokenNumbers.timeVariants("ten am"))
        assertTrue("7:30" in SpokenNumbers.timeVariants("7:30 am"))
    }

    @Test fun aSpokenAlarmSetsTheIntentAndNotAScreenCrawl() {
        val criteria = com.aniob.core.intent.AniobTaskIntentClassifier.extractCriteria("set alarm for ten am")
        assertFalse("the spoken words must not become a screen-text assertion", criteria.mustContainText.contains("ten am"))
    }

    @Test fun installIsRecognisedWhateverWrapsTheVerb() {
        assertEquals("ludo", com.aniob.core.knowledge.AniobStoreCatalog.installTarget("can you install ludo"))
        assertEquals("ludo", com.aniob.core.knowledge.AniobStoreCatalog.installTarget("please download the ludo app"))
        assertEquals("ludo", com.aniob.core.knowledge.AniobStoreCatalog.installTarget("install ludo"))
        assertNull(com.aniob.core.knowledge.AniobStoreCatalog.installTarget("open ludo"))
    }

    // ---------- perception: content, not a cached hash ----------
    @Test fun contentDiffSeesChangesAHashCannot() {
        val before = screen("Home", "Search")
        assertTrue(ScreenEffect.between(before, screen("Home", "Search", "Results", "Item")).changed)
        assertTrue(ScreenEffect.between(before, screen("Home", "Search", ime = true)).changed)
        assertTrue(ScreenEffect.between(before, screen("Home", "Search", pkg = "com.other.app")).changed)
        assertFalse(ScreenEffect.between(before, screen("Home", "Search")).changed)
    }

    @Test fun aCustomSurfaceNeverProvesANoOp() {
        val before = screen("Player", cls = "android.webkit.WebView")
        val verdict = DeterministicVerifier.verify(
            AniobAction.Tap(SemanticTarget.Text("Play")), before, screen("Player", cls = "android.webkit.WebView")
        )
        assertTrue(verdict.isExpected)
    }

    @Test fun aTapIsVerifiedByContentEvenWhenBothHashesAgree() {
        val before = screen("Home", "Search").copy(treeHash = "same", screenHash = "same")
        val after = screen("Home", "Search", "Suggestions").copy(treeHash = "same", screenHash = "same")
        assertTrue(DeterministicVerifier.verify(AniobAction.Tap(SemanticTarget.Text("Search")), before, after).isExpected)
    }

    // ---------- system navigation is a key, whatever the vendor calls it ----------
    @Test fun navigationControlsBecomeGlobalKeys() {
        fun tap(id: String) = SystemNavTargets.rewrite(AniobAction.Tap(SemanticTarget.ResourceId(id))) as AniobAction.PressKey
        assertEquals(KeyType.HOME, tap("com.android.systemui:id/home").key)
        assertEquals(KeyType.BACK, tap("com.android.systemui:id/back").key)
        assertEquals(KeyType.RECENTS, tap("com.android.systemui:id/recent_apps").key)
        val node = AniobNode(id = 4, className = "android.widget.FrameLayout", viewId = "com.android.systemui:id/home", contentDescription = "Home")
        assertEquals(
            KeyType.HOME,
            (SystemNavTargets.rewriteWithNode(AniobAction.Tap(SemanticTarget.SomIndex(4)), node) as AniobAction.PressKey).key
        )
    }

    // ---------- dialogue state ----------
    @Test fun stateSurvivesClarificationPronounsAndConfirmations() {
        val s = ConversationState()
        s.resolve("subscribe mr beast on youtube")
        assertEquals("subscribe mr beast on youtube", s.resolve("subscribe it").prompt)
        s.expectAnswer("subscribe mr beast", "Is this the channel?", listOf("Yes, that one", "No"))
        assertEquals("subscribe mr beast", s.resolve("yes").prompt)
        s.expectAnswer("turn on torch", "Which device?")
        assertTrue(s.resolve("no").cancelled)
    }

    @Test fun aShortAnswerCompletesThePendingCommand() {
        val s = ConversationState()
        s.expectAnswer("on voice recording", "Which device should I switch?", listOf("Voice Recorder", "Torch"))
        assertEquals("turn on Voice Recorder", s.resolve("Voice Recorder").prompt)
        s.expectAnswer("on recorder", "Which one?")
        assertEquals("turn on recorder app", s.resolve("recorder app").prompt)
    }

    // ---------- device-inventory answers ----------
    @Test fun questionsAboutTheDeviceAreAnsweredFromTheInventory() {
        assertNotNull(LocalQueryTools.answer("list all the available apps in my system", apps))
        val count = LocalQueryTools.answer("how many apps do i have", apps)
        assertTrue(count != null && "4" in count!!)
        assertTrue(LocalQueryTools.answer("is discord installed", apps)!!.contains("Yes"))
        assertNull("unrelated prose must fall through to the model", LocalQueryTools.answer("what is the capital of France", apps))
    }

    @Test fun appRolesResolveAgainstInstalledLabels() {
        assertEquals("com.example.recorder", AppAffinity.findInstalled("voice recording", apps).first().packageName)
        assertEquals("com.example.discord", AppOps.resolve(AppOps.parse("delete discord")!!, apps)!!.packageName)
        assertEquals(AppOps.Op.UNINSTALL, AppOps.parse("can you uninstall the voice recorder app")!!.op)
    }

    @Test fun anImpliedAppIsTakenFromTheVerbNotFromAFixedPicker() {
        assertEquals("linkedin", AppAffinity.forPrompt("send connect request to bill gates")!!.role)
        assertEquals("com.example.linkedin", AppAffinity.resolve(AppAffinity.forPrompt("send connect request to bill")!!, apps)!!.packageName)
        assertTrue(AppAffinity.messagingOptions(apps).all { it in apps.map { a -> a.appName } })
    }

    // ---------- safety posture ----------
    @Test fun assistPostureAsksInsteadOfAborting() {
        val r = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.SomIndex(1), thought = "complete the payment now"),
            targetNode = null,
            screenState = screen("Pay"),
            screenFingerprint = "fp_assist"
        )
        assertTrue(r.isAllowed)
        assertTrue(r.requiresConfirmation)
        assertFalse("a keyword must not blacklist the screen", AniobSafetyInterceptor.isFingerprintBlocked("fp_assist"))
    }

    @Test fun paymentSchemesStayBlockedInEveryPosture() {
        val r = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.Tap(SemanticTarget.Text("upi://pay")),
            targetNode = null,
            screenState = screen(),
            screenFingerprint = "fp_upi"
        )
        assertFalse(r.isAllowed)
        AniobSafetyInterceptor.clearBlockedFingerprints()
    }

    @Test fun plainDigitsInAnEditableFieldAreNotAnOtp() {
        val field = AniobNode(id = 7, className = "EditText", isEditable = true, viewId = "com.example.app:id/alarm_hour")
        val r = AniobSafetyInterceptor.evaluateAction(
            action = AniobAction.InputText(SemanticTarget.SomIndex(7), text = "1000"),
            targetNode = field,
            screenState = screen(),
            screenFingerprint = "fp_digits"
        )
        assertTrue(r.isAllowed)
    }

    // ---------- replay cache keys ----------
    @Test fun replayCacheKeysSurviveChangingNumbersAndNames() {
        assertEquals(RememberTemplate.keyOf("set alarm for ten am"), RememberTemplate.keyOf("set alarm for 7 am"))
        assertEquals(RememberTemplate.keyOf("call john"), RememberTemplate.keyOf("call maria"))
        assertEquals(RememberTemplate.keyOf("open wifi settings"), RememberTemplate.keyOf("wifi settings open"))
    }

    // ---------- n identical actions in one turn ----------
    @Test fun aRepeatRequestBecomesOneTurnOfNVerifiedMembers() {
        assertEquals(3, com.aniob.core.speed.RepeatRequest.count("tap the bell three times"))
        assertEquals(2, com.aniob.core.speed.RepeatRequest.count("press it twice"))
        assertEquals(5, com.aniob.core.speed.RepeatRequest.count("swipe up 5 times"))
        assertNull(com.aniob.core.speed.RepeatRequest.count("open the clock"))
        val tap = AniobAction.Tap(SemanticTarget.Text("Bell"))
        assertEquals(3, com.aniob.core.speed.RepeatRequest.expandActions(tap, "tap the bell three times").size)
        assertTrue(com.aniob.core.speed.RepeatRequest.expandActions(tap, "tap it ninety times").isEmpty())
    }

    @Test fun theBurstPolicyAcceptsReGroundedAndRepeatedMembersButNotBlindBatches() {
        val tap = AniobAction.Tap(SemanticTarget.Text("Bell"))
        val verified = com.aniob.core.speed.ActionBurst.validate(
            listOf(tap, AniobAction.InputText(SemanticTarget.Text("Search"), "mr beast")).map {
                com.aniob.core.speed.ActionBurst.Member(it, "grounded live", verifiedBeforeDispatch = true)
            }
        )
        assertTrue(verified.explain(), verified.isAllowed)
        val repeats = com.aniob.core.speed.ActionBurst.validate(
            com.aniob.core.speed.RepeatRequest.members(
                com.aniob.core.speed.RepeatRequest.expandActions(tap, "tap the bell 4 times"), "repeat"
            )
        )
        assertTrue(repeats.explain(), repeats.isAllowed)
        val blind = com.aniob.core.speed.ActionBurst.validate(
            listOf(tap, tap).map { com.aniob.core.speed.ActionBurst.Member(it, "faster") }
        )
        assertFalse(blind.isAllowed)
    }

    // ---------- toggle vocabulary ----------
    @Test fun togglePhrasesShareOneParser() {
        assertEquals("on" to "voice recording", TaskLexicon.splitToggle("on voice recording"))
        assertEquals("off" to "recorder", TaskLexicon.splitToggle("please turn off the recorder"))
        assertEquals("on" to "torch", TaskLexicon.splitToggle("switch on torch"))
        assertNull(TaskLexicon.splitToggle("open settings"))
    }
}
