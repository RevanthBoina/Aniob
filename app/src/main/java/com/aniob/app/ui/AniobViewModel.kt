package com.aniob.app.ui

import com.aniob.core.domain.*
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.aniob.app.AniobApplication
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.provider.AniobProviderConfig
import com.aniob.app.research.FeatureSelection
import com.aniob.app.research.StudyPass
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.SafetyLevel
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.execution.AniobAppSessionManager
import com.aniob.core.grillme.AniobGrillMeEngine
import com.aniob.app.db.OutcomeStore
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.AppLaunchTarget
import com.aniob.core.ladder.AniobPlanningAgent
import com.aniob.core.run.RunLane
import com.aniob.core.memory.RememberCache
import com.aniob.core.logging.AniobExecutionTracker
import com.aniob.core.memory.AniobHippocampusTracker
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.memory.InMemorySharedKnowledgeStore
import com.aniob.core.policy.AniobObservationPolicy
import com.aniob.core.providers.AniobHybridEngineRouter
import com.aniob.core.providers.AniobLiteRtProvider
import com.aniob.core.providers.AniobLocalLlmEngine
import com.aniob.core.router.RouteTarget
import com.aniob.core.skills.AniobSemanticSkillMatcher
import com.aniob.core.verifier.AniobReflectionAgent
import com.aniob.core.verifier.AniobWatchdog
import com.aniob.core.verifier.DeterministicVerifier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
class AniobViewModel(
    application: Application,
    internal val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    internal val _uiState = MutableStateFlow(AniobUiState())

    /**
     * Zero-LLM replay cache. Declared with the other state (before [init]) so the asynchronous
     * file load can never race the property initialiser; until the load lands a lookup misses.
     */
    @Volatile
    internal var rememberCache: RememberCache = RememberCache()
    internal var rememberCacheLoadJob: kotlinx.coroutines.Job? = null

    /** Back-stack owned by the ViewModel (UX-0 §2). Survives rotation; root CHAT is re-derived. */
    internal val _navBackStack = MutableStateFlow(restoreNavBackStack())
    val navBackStack: StateFlow<List<AniobRoute>> = _navBackStack.asStateFlow()

    internal val _isReadyToRender = MutableStateFlow(false)
    val isReadyToRender: StateFlow<Boolean> = _isReadyToRender.asStateFlow()

    val currentRoute: AniobRoute get() = _navBackStack.value.lastOrNull() ?: AniobRoute.CHAT

    val uiState: StateFlow<AniobUiState> = _uiState.asStateFlow()

    internal var skillHydrationJob: kotlinx.coroutines.Job? = null
    internal val semanticSkillMatcher = AniobSemanticSkillMatcher()
    internal val grillMeEngine by lazy { AniobGrillMeEngine(llmClient = buildGrillLlmClient()) }
    internal val planningAgent = AniobPlanningAgent()
    internal val memoryStore = AniobEmbeddingStore()
    internal val reflectionAgent = AniobReflectionAgent()
    internal val executionRouter = AniobExecutionRouter(
        semanticSkillMatcher = semanticSkillMatcher,
        planningAgent = planningAgent,
        memoryStore = memoryStore
    )
    /** The single learning pipeline; shares the router's FastPath engine and Room. */
    internal val learningPipeline = com.aniob.app.learning.AniobLearningPipelineImpl(
        database = (application as AniobApplication).database,
        replayEngine = executionRouter.fastPathEngine
    )
    internal val watchdog = AniobWatchdog(loopThreshold = 3)
    internal val observationPolicy = AniobObservationPolicy(maxBurstSteps = 3)
    internal val appSessionManager = AniobAppSessionManager()
    internal val executionTracker = AniobExecutionTracker()
    internal var localFailCount = 0
    /** Per-task provider lock: once local or cloud has really worked for this task, stay there. */
    internal var taskStickyTarget: RouteTarget? = null

    // Tracker zero-capture guarantee: opening the sheet is a pure view over recorded state.
    internal var trackerOpenCount = 0

    internal val app = application as AniobApplication
    internal val modelDownloader = AniobModelDownloader(application)
    internal val configLoader = com.aniob.app.config.AniobConfigLoader(application)
    internal val agentLimits: com.aniob.core.config.AgentLimits by lazy { configLoader.agentLimits() }
    internal val hippocampusTracker by lazy {
        AniobHippocampusTracker(
            skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills"),
            assetSkillsDir = null // built-in library stays assets-synced; learned skills are runtime-only
        )
    }
    internal val hybridEngineRouter by lazy { AniobHybridEngineRouter(modelsDir = modelDownloader.getModelsDir()) }

    /**
     * Durable knowledge vault (UX-2). Replaces InMemorySharedKnowledgeStore, whose contents died
     * with the process — "remember this answer" was a lie before this.
     */
    internal val persistentKnowledgeStore = com.aniob.app.data.DataStoreKnowledgeStore(app)
    internal val sharedKnowledgeStore: AniobSharedKnowledgeStore = persistentKnowledgeStore

    // Task ownership & generation guards
    internal var activeTaskJob: kotlinx.coroutines.Job? = null
    @Volatile internal var activeGeneration: Long = 0L

    /** Question raised by the last device command that failed because it needed a choice. */
    @Volatile internal var pendingDeviceClarify: com.aniob.core.grillme.GrillQuestion? = null

    // Conversation memory state. Declared above `init`, which restores the last open chat.
    internal val conversationStore by lazy {
        com.aniob.app.db.ConversationStore(app.database.conversationDao())
    }
    internal val conversationPrefs by lazy { app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE) }

    /** Id of the chat thread the next answer is filed under. Stable across restarts. */
    @Volatile internal var conversationId: String = ""

    // UX-3: cooperative pause; checked at the loop head and inside settle waits.
    @Volatile internal var pauseRequested = false

    /** Convenience accessor so the UI can read safetyLevel without collecting uiState. */
    val safetyLevel: SafetyLevel get() = _uiState.value.safetyLevel

    internal var disabledSkills: MutableSet<String> = mutableSetOf()

    /** Retry/miss memory never outlives its task id. */
    internal val targetMissLedger = com.aniob.core.execution.TaskTargetMissLedger()
    internal var pendingTargetClarificationKey: Pair<String, String>? = null
    internal val notificationGrillAnswers = mutableMapOf<String, MutableMap<String, String>>()

    /**
     * On-device provider, wired to the real LiteRT-LM engine and to the model the downloader
     * actually installs.
     *
     * This used to be `AniobLiteRtProvider()` with no arguments, so `modelPath` fell back to its
     * default `/data/local/tmp/qwen2.5-1.5b-gpu.bin` — an adb-push path from early development,
     * wholly disconnected from `modelDownloader.getModelsDir()`. No downloaded model could ever be
     * picked up. The default `engineFactory` also produced `core-agent`'s `AniobLiteRtEngine`, whose
     * `System.loadLibrary("litertlm")` had no bundled `.so`, so it threw `UnsatisfiedLinkError`
     * unconditionally. Both halves are fixed here: the path resolves through the downloader and the
     * engine is `AniobLiteRtLmEngine`, backed by the LiteRT-LM Maven artifact.
     */
    internal val localLlmProvider = AniobLiteRtProvider(
        modelPath = modelDownloader.resolveInstalledModelPath(),
        useGpu = false,
        engineFactory = { _ -> com.aniob.app.model.AniobLiteRtLmEngine(cacheDir = app.cacheDir) }
    ).apply {
        // Reflex 5: release the LiteRT engine on memory pressure, no LLM involved.
        app.onMemoryTrimListener = { _ ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    this@apply.release()
                } catch (e: Exception) {
                    android.util.Log.w("AniobViewModel", "Failed to release LiteRT engine on memory trim", e)
                }
            }
        }
    }

    // Both answers and actions share the same provider and native model ownership.
    internal val localLlmClient: AniobLocalLlmEngine =
        com.aniob.core.providers.AniobLocalLlmEngineImpl(localLlmProvider)

    /** Counts full accessibility-tree captures; asserted to stay unchanged when the Tracker opens. */
    internal var screenCaptureCount: Int = 0
        internal set

    /**
     * Encrypted holder for the OmniRoute API key (Tink/AES256-GCM via the Android Keystore).
     * Replaces plaintext SharedPreferences, and migrates any key an older build left behind.
     */
    internal val secretStore = com.aniob.app.data.AniobSecretStore(app)

    /** One gateway provider per session (task id, conversation id, "grill"), not one per step. */
    internal val omniRouteProviders = com.aniob.app.provider.AniobProviderRegistry { sessionId ->
        buildOmniRouteProvider(sessionId)
    }

    /** One account client per task for UI steps. Released when the task ends. */
    internal val accountStepClients = com.aniob.app.provider.AniobProviderRegistry { sessionId ->
        app.accountAuth.newClient(sessionId) { app.researchTelemetry.recordLlmCall() }
    }

    init {
        val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        // Key comes from encrypted storage (which migrates and then deletes any plaintext copy an
        // older build wrote). The model is only sanitised: OmniRoute accepts "auto", auto/<mode>
        // and provider/model ids, so a stored value is honoured unless it is unusable.
        val savedApiKey = secretStore.getOmniRouteApiKey()
        val savedModel = AniobProviderConfig.sanitizeModel(
            prefs.getString("omniroute_model", "auto") ?: "auto"
        )
        val savedBaseUrl = secretStore.getOmniRouteBaseUrl()
        val savedMode = prefs.getString("autorouter_mode", "auto") ?: "auto"
        // Execution features are user capabilities; only the research pass controls are developer-only.
        val researchControlsEnabled = com.aniob.app.AniobBuild.isDeveloperBuild(app)
        val researchMode = app.researchTelemetry.featureSelection.mode
        val researchPass = if (researchControlsEnabled) app.researchTelemetry.studyPass else StudyPass.MEASURED.also {
            app.researchTelemetry.studyPass = it
        }
        val lastResearchRun = app.researchTelemetry.lastRunOrNull()
        val researchSummaryRow = lastResearchRun?.let { app.researchTelemetry.summaryFor(it.mode) }
        val savedQueued = prefs.getString("queued_prompt", null)
        localFailCount = 0 // Retry diagnostics are execution-scoped and never restored from disk.
        taskStickyTarget = null
        disabledSkills = (prefs.getStringSet("disabled_skills", emptySet()) ?: emptySet()).toMutableSet()
        executionRouter.setDisabledSkills(disabledSkills)
        // Zero-LLM replay: the ladder asks this callback before any model rung. The cache object
        // is loaded off the main thread; until then a lookup simply misses.
        executionRouter.rememberLookup = { goal, screen ->
            rememberCache.lookup(goal, screen)
        }
        // App launches are a LOOKUP, never a model call. Both hooks read the same registry the
        // intent resolver reads, so "open clock" means the same package everywhere in the ladder.
        // The platform's launchable check keeps a stale cache entry from opening a removed app.
        val isLaunchable: (String) -> Boolean = this::isLaunchablePackage
        executionRouter.appIsLaunchable = isLaunchable
        // Step 0: the app named at the start of the task ("open Clock and set an alarm").
        executionRouter.appLaunchResolver = { goal ->
            AniobLaunchHub.plan(goal, AniobAppRegistry.getInstance(), isLaunchable)?.let { plan ->
                AppLaunchTarget(plan.packageName, plan.label, plan.launchActivity, plan.narration)
            }
        }
        // Mid-task: an app named later in the task ("… then open Instagram"), skipping the apps this
        // task already opened and the one already in front. Without this, a multi-step goal keeps
        // re-launching the app it started in.
        executionRouter.midTaskLaunchResolver = { goal ->
            AniobLaunchHub.planMidTask(
                prompt = goal,
                registry = AniobAppRegistry.getInstance(),
                isLaunchable = isLaunchable,
                currentPackage = runCatching {
                    AniobAccessibilityService.instance?.currentForegroundPackage()
                }.getOrNull(),
                exclude = AniobLaunchHub.launchedPackages()
            )?.let { plan ->
                AppLaunchTarget(plan.packageName, plan.label, plan.launchActivity, plan.narration)
            }
        }
        rememberCacheLoadJob = viewModelScope.launch(Dispatchers.IO) {
            rememberCache = app.rememberCacheFile.load()
        }
        // Warm the gateway connection (DNS + TLS + HTTP/2) while the user is still typing, so the
        // first action does not pay for a cold handshake.
        viewModelScope.launch(Dispatchers.IO) { prewarmGateway() }

        _uiState.update {
            it.copy(
                omnirouteApiKey = savedApiKey,
                omnirouteModel = savedModel,
                omnirouteBaseUrl = savedBaseUrl,
                autoRouterMode = savedMode,
                queuedPrompt = savedQueued,
                // UX-4: switches are REAL state, restored from disk — never placebo remembr.
                safetyLevel = SafetyLevel.entries.getOrElse(
                    prefs.getInt("safety_level", SafetyLevel.STANDARD.ordinal)
                ) { SafetyLevel.STANDARD },
                fastPathEnabled = app.researchTelemetry.featureSelection.rememberReplay,
                disabledSkills = disabledSkills.toSet(),
                ablationMode = researchMode,
                featureSelection = app.researchTelemetry.featureSelection,
                studyPass = researchPass,
                researchLastRun = lastResearchRun,
                researchStatusLine = lastResearchRun?.let { app.researchTelemetry.hudLine(it) },
                researchSummary = researchSummaryRow
            )
        }

        // Research conditions never change release capabilities; the persisted ask posture is
        // applied exactly as chosen, including OFF, before the first task can start.
        applyFeatureSelection(_uiState.value.featureSelection)
        applyAskLevel(_uiState.value.safetyLevel)

        _isReadyToRender.value = true

        // UX-2: load remembered answers so "Remember this answer" survives process death.
        viewModelScope.launch {
            runCatching { persistentKnowledgeStore.hydrate() }
                .onFailure { app.eventLogger.warn("AniobViewModel", "Knowledge vault hydration failed: ${it.message}") }
        }

        // Collect session scores from Room database
        viewModelScope.launch {
            app.metricsCollector.getSessionScores().collect { scores ->
                _uiState.update { it.copy(sessionScores = scores) }
            }
        }

        // Cold-start hydration (finding #5): restore FastPath trajectories from Room into the
        // replay engine before the first task, so "zero-token replay" survives process death.
        viewModelScope.launch {
            runCatching { learningPipeline.hydrate() }
                .onSuccess { loaded ->
                    if (loaded > 0) {
                        app.eventLogger.info("AniobViewModel", "Hydrated $loaded FastPath trajectories from Room")
                    }
                }
                .onFailure { app.eventLogger.warn("AniobViewModel", "FastPath hydration failed: ${it.message}") }
        }

        // Hydrate packaged and learned skills once; skills-only tasks await this same job.
        skillHydrationJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
                app.assets.list("aniob_skill_library/skills").orEmpty().filter { it.endsWith(".yaml") || it.endsWith(".yml") || it.endsWith(".json") }.forEach { name ->
                    app.assets.open("aniob_skill_library/skills/$name").use { semanticSkillMatcher.loadAndIndexStream(it) }
                }
                semanticSkillMatcher.loadAndIndexDirectory(skillsDir)
            }
        }

        // Add initial system greeting
        _uiState.update {
            it.copy(
                chatMessages = listOf(
                    ChatMessage(
                        id = "msg_init",
                        role = "assistant",
                        content = "Hello! I am Aniob, your autonomous Android UI agent. Ask me to perform any action on your device."
                    )
                )
            )
        }

        restoreActiveConversation()

        // UX-6: a marker still present at cold start means the OS killed us mid-task. Report it
        // honestly instead of letting the run look like it silently succeeded.
        restoreInterruptedSummary()

        // UX-6: if the accessibility service dies while a task is active, surface the recovery door.
        AniobAccessibilityService.onServiceLost = { wasTaskActive ->
            if (wasTaskActive) {
                AniobBackgroundController.onAccessibilityLost(app)
                updateToIdle {
                    it.copy(
                        statusMessage = "Aniob lost accessibility access",
                        accessibilityRecoveryNeeded = true
                    )
                }
            }
        }

        AniobForegroundService.onStopRequestedFromNotification = {
            stopCurrentTask()
        }
        AniobForegroundService.onPauseToggleFromNotification = {
            togglePause()
        }
        AniobForegroundService.onConfirmDecisionWithIds = { decision, taskId, reqId ->
            if (!taskId.isNullOrBlank() && !reqId.isNullOrBlank()) {
                resolveConfirmation(decision, taskId, reqId)
            } else {
                app.eventLogger.warn("AniobViewModel", "Ignored ID-less confirmation from notification")
            }
        }
        AniobForegroundService.onGrillReplyWithIds = { taskId, questionId, answer ->
            resolveGrillAnswerFromNotification(taskId, questionId, answer, requireOption = false)
        }
        AniobForegroundService.onGrillChoiceWithIds = { taskId, questionId, answer ->
            resolveGrillAnswerFromNotification(taskId, questionId, answer, requireOption = true)
        }
        AniobForegroundService.onGrillCancelForTask = { taskId ->
            if (_uiState.value.activeTask?.id == taskId) cancelGrillMe()
        }

        viewModelScope.launch {
            app.approvalController.confirmRequest.collect { req ->
                _uiState.update { it.copy(confirmRequest = req) }
            }
        }
    }

    // ---- Conversation memory -------------------------------------------------------------
    // The answer path sends the earlier turns of the active conversation to the model and saves
    // each answered exchange, so a follow-up question has context and a chat survives a restart.

    /** Saved chats for the previous-chats sheet, pinned first, then newest. */
    val conversations: kotlinx.coroutines.flow.Flow<List<com.aniob.app.db.ConversationEntity>>
        get() = conversationStore.observeAll()

    /**
     * Foundation Finding F5: Consolidate state transitions so task termination
     * always flows through a central state updater rather than manual scattered resets.
     * Plan-7.1: TerminationStage is the single owner of terminal exits.
     */
    internal val terminationStage = com.aniob.core.termination.TerminationStage()

    // =========================================================================================
    // Remember: zero-LLM replay cache
    // =========================================================================================

    /** One verified transition of the current run; persisted only when the task succeeds. */
    internal data class RememberTransition(
        val screen: AniobScreenState,
        val action: AniobAction,
        val node: AniobNode?,
        val nextScreen: AniobScreenState? = null
    )

    internal val outcomeStore by lazy { OutcomeStore(app.database.taskOutcomeDao()) }

    internal data class CompletedLearningSnapshot(
        val taskId: String,
        val prompt: String,
        val criteria: SuccessCriteria?,
        val screen: AniobScreenState?,
        val actions: List<AniobAction>,
        val transitions: List<RememberTransition>,
        val mode: FeatureSelection,
        val episode: List<AniobHippocampusTracker.HippocampusStep>
    )
    internal var lastCompletedLearningSnapshot: CompletedLearningSnapshot? = null

    /**
     * Executes the task through the ExecutionRouter ladder:
     * INTENT -> FASTPATH -> SKILL -> LOCAL_SLM -> OMNIROUTE_CLOUD
     */
    internal val questionsBeforeStart = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile internal var activeRunMode: FeatureSelection = FeatureSelection.FULL_SPEED

    /** Lane chosen for the most recent submitted task; read by tests and diagnostics only. */
    @Volatile internal var lastRunLane: RunLane = RunLane.STANDARD

    /** Actions of steps that verified, in order — the input to the learning pipeline. */
    internal val verifiedActions = mutableListOf<AniobAction>()

    internal data class VerifiedStep(
        val screenAfter: AniobScreenState,
        val executedAction: AniobAction,
        val verification: DeterministicVerifier.VerificationResult,
        val scrollRecovered: Boolean
    )

    override fun onCleared() {
        super.onCleared()
        app.onMemoryTrimListener = null
        AniobAccessibilityService.onServiceLost = null
        AniobForegroundService.onStopRequestedFromNotification = null
        AniobForegroundService.onPauseToggleFromNotification = null
        AniobForegroundService.onConfirmDecisionWithIds = null
        AniobForegroundService.onGrillReplyWithIds = null
        AniobForegroundService.onGrillChoiceWithIds = null
        AniobForegroundService.onGrillCancelForTask = null
        runCatching { localLlmProvider.release() }
    }

    companion object {
        internal const val KEY_ACTIVE_CONVERSATION = "active_conversation_id"
        /** Upper bound for a single gesture callback before we give up on it. */
        const val SWIPE_CALLBACK_TIMEOUT_SECONDS = 5L

        /** SavedStateHandle key for the nav back-stack (UX-0 §2). */
        const val KEY_NAV_BACKSTACK = "nav_back_stack"

        /** SavedStateHandle key for the in-flight task marker (UX-6 process-death honesty). */
        const val KEY_RUNNING_MARKER = "running_task_marker"

        /** How often a paused task re-checks the resume flag (UX-3 stop/pause responsiveness). */
        const val PAUSE_POLL_MS = 100L

        /**
         * Output budget for a bundled plan (the schema asks for < 80 tokens).
         *
         * Three actions plus a thought do not fit in the 96 tokens a one-action cap allowed: the
         * reply was cut mid-array, the plan failed to parse, and the step fell back to a single
         * action — the batching was paid for and never used.
         */
        const val BURST_MAX_OUTPUT_TOKENS = 160

        /** Lets a dynamic screen finish animating before the post-plan re-observation. */
        const val BURST_REPLAN_SETTLE_MS = 250L

        /** Cap on waiting for the chat window to reveal the app behind it (was a fixed sleep). */
        const val SESSION_REVEAL_WAIT_MS = 600L

        /** Cap on waiting for a cold-started session app to draw (was a fixed sleep). */
        const val SESSION_RELAUNCH_WAIT_MS = 800L

        /** Plans that cannot execute even one member before the run stops batching. */
        const val BURST_MAX_ZERO_PROGRESS_REPLANS = 2

        /**
         * Budget for hydrating learned state before step 0 yields to the model. Hydration keeps
         * running in the background; a cold cache must not add seconds to the first action.
         */
        const val HYDRATION_BUDGET_MS = 300L

        /** Ceiling for waiting on the chat window to minimise (it used to be a fixed 500 ms). */
        const val BACKGROUND_WAIT_MAX_MS = 500L

        /** A start-of-task capture younger than this is reused for step 0 instead of re-read. */
        const val START_CAPTURE_FRESH_MS = 1_500L

        /** A cold app launch is allowed longer than a tap: the wait ends on the package change. */
        const val APP_LAUNCH_VERIFY_MS = 4_000L

        /**
         * Budget for the window an intent opens. A cold Clock/Settings start outlives 300 ms, and
         * planning on the launcher costs a whole round trip plus a wrong-screen step.
         */
        const val INTENT_SETTLE_MS = 2_500L

        /** How long a merged pre-observe viewport stays worth planning on. */
        const val PRE_OBSERVE_FRESH_MS = 2_000L

        /** Observe-burst ceiling for burst modes (the shipping value). */
        const val OBSERVATION_BURST_LIMIT = 3

        /** Ceiling that forces a full observation before every action (BASELINE conditions). */
        const val OBSERVATION_BURST_LIMIT_SINGLE_STEP = 1

        /** Trust Center keeps the last 20 decisions (UX-4). */
        const val CONFIRMATION_HISTORY_LIMIT = 20

        /** Render window for the chat timeline; full history stays in the data list (U18). */
        const val CHAT_RENDER_WINDOW = 200

        /** Prompt words too generic to identify a target node (they describe intent, not label). */
        val SCROLL_STOPWORDS = setOf(
            "the", "and", "for", "with", "tap", "click", "press", "open", "scroll",
            "button", "this", "that", "then", "please", "from", "into", "your", "you"
        )
    }
}
