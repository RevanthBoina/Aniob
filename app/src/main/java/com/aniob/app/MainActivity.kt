package com.aniob.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.ui.AniobMainScreen
import com.aniob.app.ui.AniobViewModel
import com.aniob.app.ui.theme.AniobTheme
import com.aniob.core.providers.AniobLocalLlmClient

class MainActivity : ComponentActivity() {

    private val viewModel: AniobViewModel by viewModels()

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.isReadyToRender.value }

        val app = application as AniobApplication
        AniobBackgroundController.registerActivity(this)
        app.workflowActionHost.attach(this)

        // UX-3: wire Stop/Pause notification actions.
        com.aniob.app.background.AniobForegroundService.onStopRequestedFromNotification = {
            viewModel.stopCurrentTask()
        }
        com.aniob.app.background.AniobForegroundService.onPauseToggleFromNotification = {
            viewModel.togglePause()
        }
        com.aniob.app.background.AniobForegroundService.onGrillAnswerFromNotification = { qid, answer ->
            viewModel.onGrillAnswersSubmitted(mapOf(qid to answer))
        }
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            AniobTheme {
                // Expose Compose test tags to external accessibility automation.
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    AniobMainScreen(viewModel = viewModel)
                }
            }
        }
    }

    /**
     * Catches result/pill deep links while the activity is alive.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val app = application as AniobApplication
        val rawPrompt = intent.getStringExtra("prompt") ?: intent.getStringExtra("com.aniob.app.PROMPT")
        android.util.Log.i("MainActivity", "handleIncomingIntent: promptLen=${rawPrompt?.length ?: 0} action=${intent.action}")
        val promptExtra = rawPrompt?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")?.trim()
        if (!promptExtra.isNullOrBlank()) {
            val rawMode = intent.getStringExtra("mode") ?: intent.getStringExtra("com.aniob.app.ROUTER_MODE")
            val modeExtra = rawMode?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")?.trim()
            if (!modeExtra.isNullOrBlank()) {
                android.util.Log.i("MainActivity", "Setting autoRouterMode: $modeExtra")
                viewModel.setAutoRouterMode(modeExtra)
            }
            android.util.Log.i("MainActivity", "Submitting prompt (len=${promptExtra.length})")
            viewModel.submitTask(promptExtra)
            return
        }
        val sharedContent = app.incomingContentReader.parse(intent)
        if (sharedContent != null) {
            viewModel.handleIncomingShare(sharedContent)
        } else {
            viewModel.handleDeepLink(intent.dataString)
        }
    }

    /** Every return from Settings re-reads permission state so banners never go stale (U2). */
    override fun onResume() {
        super.onResume()
        (application as AniobApplication).systemState.refresh()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory safety: release local SLM native memory only under severe memory pressure,
        // never merely because the UI is hidden (which happens on every background task start).
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            (application as? AniobApplication)?.onMemoryTrimListener?.invoke(level)
            AniobLocalLlmClient.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.aniob.app.background.AniobForegroundService.onStopRequestedFromNotification = null
        com.aniob.app.background.AniobForegroundService.onPauseToggleFromNotification = null
        (application as AniobApplication).workflowActionHost.detach()
        AniobBackgroundController.unregisterActivity(this)
    }
}
