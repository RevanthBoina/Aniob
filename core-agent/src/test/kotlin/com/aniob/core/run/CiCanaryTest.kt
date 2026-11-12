package com.aniob.core.run

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import com.aniob.core.ladder.AniobIntentResolver
import org.junit.Assert.fail
import org.junit.Test

/** TEMPORARY: proves CI compiles and runs this module's tests. Reverted in the next commit. */
class CiCanaryTest {
    @Test
    fun canary() {
        AniobAppRegistry.resetForTest()
        AniobAppRegistry.getInstance().hydrate(
            listOf(RegisteredApp("com.whatsapp", label = "WhatsApp"), RegisteredApp("com.google.android.deskclock", label = "Clock"))
        )
        val report = listOf(
            "Open Clock", "Open WhatsApp and send hi to Mom", "open clock then set an alarm for 7:30",
            "turn on flashlight", "set an alarm for 7:30", "call 5551234", "open wifi settings",
            "open whatsapp chat with mom", "uninstall whatsapp", "search for cats"
        ).joinToString(" | ") { p ->
            val s = AniobIntentResolver.resolve(p)
            "$p => ${AniobFastLane.decide(p, s, true)} [${s?.action?.substringAfterLast('.')},obs=${s?.requiresObservation},pkg=${s?.targetPackage}]"
        }
        AniobAppRegistry.resetForTest()
        fail("CANARY-CORE $report")
    }
}
