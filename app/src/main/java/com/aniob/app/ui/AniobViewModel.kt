package com.aniob.app.ui

import com.aniob.core.providers.AniobModelFiles

import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.aniob.app.AniobApplication
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.background.AniobForegroundService
import com.aniob.app.db.SessionScoreEntity
import com.aniob.app.model.AniobModelDownloader
import com.aniob.app.provider.AniobOmniRouteProvider
import com.aniob.app.provider.AniobProviderConfig
import com.aniob.app.research.AblationMode
import com.aniob.app.research.ResearchRun
import com.aniob.app.research.ResearchSummaryRow
import com.aniob.app.research.StudyPass
import com.aniob.app.provider.OmniRouteConnectionStatus
import com.aniob.app.provider.testOmniRouteConnection
import com.aniob.app.device.AniobCapabilityProbe
import com.aniob.app.device.AniobDeviceControl
import com.aniob.app.device.DeviceControlResult
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.telemetry.AniobDeviceTelemetry
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.model.AniobExecutionEvents
import com.aniob.app.ui.model.ConfirmDecision
import com.aniob.app.ui.model.ConfirmRequest
import com.aniob.app.ui.model.ConfirmationRecord
import com.aniob.app.ui.model.EvidenceItem
import com.aniob.app.ui.model.MomentChip
import com.aniob.app.ui.model.NextAction
import com.aniob.app.ui.model.Outcome
import com.aniob.app.ui.model.SafetyLevel
import com.aniob.app.ui.model.StepEvent
import com.aniob.app.ui.model.TaskSummary
import com.aniob.core.narration.StepNarration
import com.aniob.core.domain.*
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.exec.ExecOutcome
import com.aniob.core.exec.ExecReport
import com.aniob.core.execution.AniobAppSessionManager
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.execution.StepPipeline
import com.aniob.core.execution.StepResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.aniob.core.external.AniobExternalAiTrigger
import com.aniob.core.grillme.AniobGrillMeEngine
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.grillme.AniobPromptGrillMeClient
import com.aniob.core.feasibility.ActionGate
import com.aniob.core.feasibility.AniobFeasibility
import com.aniob.core.feasibility.CapabilityEnvelope
import com.aniob.core.feasibility.Feasibility
import com.aniob.core.feasibility.PlanStep
import com.aniob.core.feasibility.Postcondition
import com.aniob.core.feasibility.Privilege
import com.aniob.core.narration.AgentPhase
import com.aniob.core.narration.PhaseSnapshot
import com.aniob.core.narration.ThinkSource
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.outcome.UserDecision
import com.aniob.core.understander.AniobPromptUnderstanding
import com.aniob.app.db.OutcomeStore
import com.aniob.core.intent.AniobIntent
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobExecutionRouter
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.AniobReflexResolver
import com.aniob.core.ladder.AppLaunchTarget
import com.aniob.core.ladder.AniobPlanningAgent
import com.aniob.core.ladder.ResolvedIntentShortcut
import com.aniob.core.ladder.TaskProgress
import com.aniob.core.memory.RememberAction
import com.aniob.core.memory.RememberCache
import com.aniob.core.verify.FastVerify
import com.aniob.core.logging.AniobExecutionTracker
import com.aniob.core.memory.AniobHippocampusTracker
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.memory.InMemorySharedKnowledgeStore
import com.aniob.core.policy.AniobObservationPolicy
import com.aniob.core.policy.AniobWaitForIdle
import com.aniob.core.providers.AniobGenerationResult
import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.providers.AniobStructuredOutput
import com.aniob.core.providers.AniobLocalActionResolver
import com.aniob.core.providers.AniobHybridEngineRouter
import com.aniob.core.providers.AniobLiteRtProvider
import com.aniob.core.providers.AniobLocalLlmEngine
import com.aniob.core.router.RouteTarget
import com.aniob.core.safety.AniobSafetyInterceptor
import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskPolicy
import com.aniob.core.safety.InterceptResult
import com.aniob.core.skills.AniobSemanticSkillMatcher
import com.aniob.core.tools.AniobScrollHelper
import com.aniob.core.tools.DevicePowerState
import com.aniob.core.verifier.AniobReflectionAgent
import com.aniob.core.verifier.AniobWatchdog
import com.aniob.core.verifier.DeterministicVerifier
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AniobUiState(
    val activeTask: AniobTask? = null,
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentStep: Int = 0,
    val grillMeResult: AniobGrillMeResult? = null,
    val showGrillMeSheet: Boolean = false,
    /** Audit: what the agent is doing right now, and whether a model is really involved. */
    val agentPhase: PhaseSnapshot = PhaseSnapshot(),
    /** Audit: the closed-set result of the last run, with its evidence and the user's thumbs. */
    val lastSealedOutcome: SealedOutcome? = null,
    val lastSealedTaskId: String? = null,
    val showTrackerSheet: Boolean = false,
    val trackerFilter: String = "ALL", // ALL, CORRECT, FAILED
    val steps: List<AniobStepRecord> = emptyList(),
    val stepEvents: List<StepEvent> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val omnirouteApiKey: String = "",
    val omnirouteModel: String = "auto",
    /** OmniRoute gateway base URL; blank means AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL. */
    val omnirouteBaseUrl: String = "",
    val omnirouteTesting: Boolean = false,
    val omnirouteConnectionStatus: String = "",
    val omnirouteDiscoveredModels: List<String> = emptyList(),
    val autoRouterMode: String = "auto", // auto, local-first, local-only, cloud-only, balanced
    val statusMessage: String = "Ready",
    val lastRoutingReason: String = "Ready",
    val lastProviderUsed: String = "NONE",
    val sessionScores: List<SessionScoreEntity> = emptyList(),
    val streamingBubbleText: String = "",
    val isStreaming: Boolean = false,
    /** UX-6: honest recovery state after a process death mid-task. */
    val interruptedSummary: TaskSummary? = null,
    /** UX-6: last completed task, rendered as a card on next open (Quiet Return). */
    val lastSummary: TaskSummary? = null,
    /** UX-6: accessibility access was revoked mid-run; recovery banner until the user re-enables. */
    val accessibilityRecoveryNeeded: Boolean = false,
    /** UX-4: pending approval the user must actually see. */
    val confirmRequest: ConfirmRequest? = null,
    /** UX-3: the last narration line, for the ticker and lock-adjacent surfaces. */
    val lastNarration: String = "",
    /** UX-2/UX-5: Remembered Moments chips are ephemeral and shown above the input. */
    val momentChips: List<MomentChip> = emptyList(),
    /** UX-2: grill questions already answered from the vault, rendered with a "saved ✓" badge. */
    val grillRememberedKeys: Set<String> = emptySet(),
    /** UX-4: Trust Center controls — real state, never placebo. */
    val safetyLevel: SafetyLevel = SafetyLevel.STANDARD,
    val fastPathEnabled: Boolean = true,
    /** UX-4: last 20 approval decisions with context. */
    val confirmationHistory: List<ConfirmationRecord> = emptyList(),
    /** UX-2: follow-up queued while a task runs (depth 1, honest label). */
    val queuedPrompt: String? = null,
    /** UX-5: names of skills the user has disabled; matcher skips these. */
    val disabledSkills: Set<String> = emptySet(),
    /** Staged draft from share or incoming intent that requires user confirmation/submission */
    val pendingInputPrefill: String? = null,
    /** Result of the last Cloud AI save / connection test, rendered as a banner in Settings. */
    val cloudTest: CloudTestUiState = CloudTestUiState(),
    /** Research: ablation condition that the next task will run under. */
    val ablationMode: AblationMode = AblationMode.DEFAULT,
    /** Research: study pass (warm-up vs measured). */
    val studyPass: StudyPass = StudyPass.MEASURED,
    /** Research: one-line status for the last finished task (`[MODE] Latency… | Calls… | Success…`). */
    val researchStatusLine: String? = null,
    /** Research: last finished run, shown with the manual override in the Stats screen. */
    val researchLastRun: ResearchRun? = null,
    /** Research: running averages for [ablationMode]. */
    val researchSummary: ResearchSummaryRow? = null
)

/** Outcomes of saving or testing the Cloud AI (OmniRoute) settings. */
enum class CloudTestResult { NONE, SAVED, SUCCESS, BAD_KEY, UNREACHABLE, INVALID_SETUP }

/**
 * Settings feedback for the Cloud AI card. [message] is short, user-facing copy that explains the
 * concrete next step; [testing] disables the buttons while the probe is in flight.
 */
data class CloudTestUiState(
    val testing: Boolean = false,
    val result: CloudTestResult = CloudTestResult.NONE,
    val message: String? = null
)

class AniobViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AniobUiState())

    /**
     * Zero-LLM replay cache. Declared with the other state (before [init]) so the asynchronous
     * file load can never race the property initialiser; until the load lands a lookup misses.
     */
    @Volatile
    private var rememberCache: RememberCache = RememberCache()
    private var rememberCacheLoadJob: kotlinx.coroutines.Job? = null

    /** Back-stack owned by the ViewModel (UX-0 §2). Survives rotation; root CHAT is re-derived. */
    private val _navBackStack = MutableStateFlow(restoreNavBackStack())
    val navBackStack: StateFlow<List<AniobRoute>> = _navBackStack.asStateFlow()

    private val _isReadyToRender = MutableStateFlow(false)
    val isReadyToRender: StateFlow<Boolean> = _isReadyToRender.asStateFlow()

    val currentRoute: AniobRoute get() = _navBackStack.value.lastOrNull() ?: AniobRoute.CHAT

    private fun restoreNavBackStack(): List<AniobRoute> {
        val saved = savedStateHandle.get<String>(KEY_NAV_BACKSTACK) ?: return listOf(AniobRoute.CHAT)
        val routes = saved.split(",").mapNotNull { name ->
            runCatching { AniobRoute.valueOf(name) }.getOrNull()
        }
        return routes.ifEmpty { listOf(AniobRoute.CHAT) }
    }

    private fun persistNavBackStack(stack: List<AniobRoute>) {
        savedStateHandle[KEY_NAV_BACKSTACK] = stack.joinToString(",") { it.name }
    }

    fun navigateTo(route: AniobRoute) {
        val stack = _navBackStack.value
        if (stack.lastOrNull() == route) return
        val updated = stack + route
        _navBackStack.value = updated
        persistNavBackStack(updated)
    }

    /** Pops one screen. Returns false when at the root (caller lets the system handle Back). */
    fun navigateBack(): Boolean {
        val stack = _navBackStack.value
        if (stack.size <= 1) return false
        val updated = stack.dropLast(1)
        _navBackStack.value = updated
        persistNavBackStack(updated)
        return true
    }

    fun resetToChat() {
        _navBackStack.value = listOf(AniobRoute.CHAT)
        persistNavBackStack(listOf(AniobRoute.CHAT))
    }

    /**
     * Deep-link entry point (UX-0 §2): `aniob://chat|tracker|confirm|result|skills`.
     * Returns true when the link was recognised and handled.
     */
    fun handleDeepLink(uri: String?): Boolean {
        if (uri.isNullOrBlank() || !uri.startsWith("aniob://")) return false
        val host = uri.removePrefix("aniob://").substringBefore('/').substringBefore('?').lowercase()
        return when (host) {
            "chat" -> { resetToChat(); true }
            "tracker" -> { resetToChat(); setShowTrackerSheet(true); true }
            "result" -> { resetToChat(); true }
            "skills" -> { navigateTo(AniobRoute.SKILLS); true }
            "confirm" -> true
            else -> false
        }
    }

    val uiState: StateFlow<AniobUiState> = _uiState.asStateFlow()

    private val semanticSkillMatcher = AniobSemanticSkillMatcher()
    private val grillMeEngine by lazy { AniobGrillMeEngine(llmClient = buildGrillLlmClient()) }
    private val planningAgent = AniobPlanningAgent()
    private val memoryStore = AniobEmbeddingStore()
    private val reflectionAgent = AniobReflectionAgent()
    internal val executionRouter = AniobExecutionRouter(
        semanticSkillMatcher = semanticSkillMatcher,
        planningAgent = planningAgent,
        memoryStore = memoryStore
    )
    /** The single learning pipeline; shares the router's FastPath engine and Room. */
    private val learningPipeline = com.aniob.app.learning.AniobLearningPipelineImpl(
        database = (application as AniobApplication).database,
        replayEngine = executionRouter.fastPathEngine
    )
    private val watchdog = AniobWatchdog(loopThreshold = 3)
    private val observationPolicy = AniobObservationPolicy(maxBurstSteps = 3)
    private val appSessionManager = AniobAppSessionManager()
    private val executionTracker = AniobExecutionTracker()
    private var localFailCount = 0

    // Tracker zero-capture guarantee: opening the sheet is a pure view over recorded state.
    private var trackerOpenCount = 0

    private val app = application as AniobApplication
    private val modelDownloader = AniobModelDownloader(application)
    private val configLoader = com.aniob.app.config.AniobConfigLoader(application)
    private val agentLimits: com.aniob.core.config.AgentLimits by lazy { configLoader.agentLimits() }
    private val hippocampusTracker by lazy {
        AniobHippocampusTracker(
            skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills"),
            assetSkillsDir = null // built-in library stays assets-synced; learned skills are runtime-only
        )
    }
    private val hybridEngineRouter by lazy { AniobHybridEngineRouter(modelsDir = modelDownloader.getModelsDir()) }

    /**
     * Durable knowledge vault (UX-2). Replaces InMemorySharedKnowledgeStore, whose contents died
     * with the process — "remember this answer" was a lie before this.
     */
    private val persistentKnowledgeStore = com.aniob.app.data.DataStoreKnowledgeStore(app)
    private val sharedKnowledgeStore: AniobSharedKnowledgeStore = persistentKnowledgeStore

    // Task ownership & generation guards
    private var activeTaskJob: kotlinx.coroutines.Job? = null
    @Volatile private var activeGeneration: Long = 0L

    // UX-3: cooperative pause; checked at the loop head and inside settle waits.
    @Volatile private var pauseRequested = false

    /** Convenience accessor so the UI can read safetyLevel without collecting uiState. */
    val safetyLevel: SafetyLevel get() = _uiState.value.safetyLevel

    /** Exposes the loaded skill list for the Skills screen. */
    fun getLoadedSkills(): List<com.aniob.core.skills.AniobSkill> =
        semanticSkillMatcher.getLoadedSkills()

    internal var disabledSkills: MutableSet<String> = mutableSetOf()

    /** Retry/miss memory never outlives its task id. */
    private val targetMissLedger = com.aniob.core.execution.TaskTargetMissLedger()

    /**
     * What the user approved when the current task started (payments, secrets, deletion, sending).
     * Collected once, up front; the run never asks again. Not persisted and never sent to the model.
     */
    @Volatile private var taskConsent: com.aniob.core.safety.TaskConsent = com.aniob.core.safety.TaskConsent.NONE

    /** Overlaps independent start-up work (engine, gateway) with the question stage. */
    private var warmupJob: kotlinx.coroutines.Job? = null

    /** Set once a mid-run question was answered by an assumption, so a second one ends the run. */
    private var assumedMidRunAsk = false

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
    private val localLlmProvider = AniobLiteRtProvider(
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
    private val localLlmClient: AniobLocalLlmEngine =
        com.aniob.core.providers.AniobLocalLlmEngineImpl(localLlmProvider)

    /** Explicit background preparation before routing; re-resolves downloads and model changes. */
    private suspend fun refreshLocalEngine() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        localLlmProvider.prepare(modelDownloader.resolveInstalledModelPath())
    }

    /** Counts full accessibility-tree captures; asserted to stay unchanged when the Tracker opens. */
    internal var screenCaptureCount: Int = 0
        private set

    fun getInstalledModelName(): String? {
        val id = modelDownloader.getDefaultModelId() ?: return null
        return AniobModelDownloader.ALL_MODELS.find { it.id == id }?.name ?: id
    }

    /** Human-readable active brain summary for the top-bar chip. */
    fun getActiveBrainLabel(): String {
        val installed = getInstalledModelName()
        val useAccount = cloudSource() == com.aniob.core.auth.AniobCloudSource.ACCOUNT
        val hasCloud = if (useAccount) app.accountAuth.isSignedIn else _uiState.value.omnirouteApiKey.isNotBlank()
        val cloudModel = if (useAccount) app.accountAuth.model else _uiState.value.omnirouteModel.ifBlank { "auto" }
        val mode = _uiState.value.autoRouterMode.lowercase()
        return when {
            mode == "cloud-only" -> if (hasCloud) "Cloud ($cloudModel)" else "Add Cloud Key"
            mode == "local-only" -> installed ?: "No model"
            installed != null && hasCloud -> "$installed + Cloud"
            installed != null -> installed
            hasCloud -> "Cloud ($cloudModel)"
            else -> "No model"
        }
    }

    /** Installed model size in whole GB, for actionable offline guidance. */
    private fun recommendedModelSizeGb(modelId: String): Int =
        AniobModelDownloader.ALL_MODELS.find { it.id == modelId }?.sizeGb?.toInt() ?: 2

    /** Richer routing rationale for the "Why this model?" dialog. */
    fun getModelChoiceReason(): String {
        val installed = getInstalledModelName() ?: "none installed"
        val hasCloud = _uiState.value.omnirouteApiKey.isNotBlank()
        val cloudDesc = if (hasCloud) {
            val gw = _uiState.value.omnirouteBaseUrl.ifBlank { "auto-gateway" }
            "active (model '${_uiState.value.omnirouteModel.ifBlank { "auto" }}' via $gw)"
        } else {
            "not configured"
        }
        val telemetry = AniobDeviceTelemetry.getRealState(app)
        val batteryToken = if (telemetry.isCharging) "charging" else "on battery ${telemetry.batteryPercent}%"
        val mode = _uiState.value.autoRouterMode
        val decision = _uiState.value.lastRoutingReason
        return "Battery $batteryToken. Mode '$mode' selected '$installed' " +
            "(local ${telemetry.totalRamGb}GB RAM device; Cloud API: $cloudDesc). " +
            "Last decision: $decision"
    }

    /**
     * Encrypted holder for the OmniRoute API key (Tink/AES256-GCM via the Android Keystore).
     * Replaces plaintext SharedPreferences, and migrates any key an older build left behind.
     */
    private val secretStore = com.aniob.app.data.AniobSecretStore(app)

    private fun isOmniRouteReady(): Boolean = isCloudConfigured()

    private fun getOmniRouteProvider(sessionId: String? = null): AniobOmniRouteProvider {
        return AniobOmniRouteProvider(
            apiKey = _uiState.value.omnirouteApiKey,
            baseUrl = AniobProviderConfig.normalizeBaseUrl(_uiState.value.omnirouteBaseUrl)
                .ifBlank { AniobProviderConfig.OMNIROUTE_DEFAULT_BASE_URL },
            model = AniobProviderConfig.sanitizeModel(_uiState.value.omnirouteModel),
            sessionId = sessionId,
            // Research metric #2: one count per outbound generation request. No-ops when no
            // instrumented task is running (for example a chat-only Q&A).
            onLlmRequest = { app.researchTelemetry.recordLlmCall() },
            onRateLimited = { waitMs ->
                setPhase(AgentPhase.RATE_LIMITED, ThinkSource.MODEL_CLOUD, retryAtMs = System.currentTimeMillis() + waitMs)
            }
        )
    }

    /**
     * A stored key and a valid address, or a keyless gateway on this device / the local network
     * (OmniRoute's default `REQUIRE_API_KEY=false` setup).
     */
    private fun isCloudConfigured(): Boolean = cloudSelection() is com.aniob.core.auth.AniobCloudSelection.Use

    /** The cloud the user picked in Settings; the gateway unless they chose their account. */
    private fun cloudSource(): com.aniob.core.auth.AniobCloudSource =
        com.aniob.core.auth.AniobCloudSource.fromPref(
            app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE).getString("cloud_source", null)
        )

    /**
     * One decision for "is there a usable cloud, and which". An account choice that is not signed in
     * is *unavailable*; it never silently swaps to the gateway (a different key and destination).
     * Whether the step may then drop on-device is still decided by the existing routing, and
     * `cloud-only` is never downgraded.
     */
    private fun cloudSelection(): com.aniob.core.auth.AniobCloudSelection =
        com.aniob.core.auth.AniobCloudSourceSelector.select(
            preference = cloudSource(),
            authState = app.accountAuth.state,
            accountFeatureAvailable = app.accountAuth.featureAvailable,
            omniRouteConfigured = AniobProviderConfig.isConfigured(
                _uiState.value.omnirouteApiKey, _uiState.value.omnirouteBaseUrl
            )
        )

    /** Q&A provider for the chosen cloud source. */
    private fun getCloudProvider(sessionId: String? = null): com.aniob.core.external.AniobCloudLlmProvider =
        if (cloudSource() == com.aniob.core.auth.AniobCloudSource.ACCOUNT) {
            app.accountAuth.newClient(sessionId) { app.researchTelemetry.recordLlmCall() }
        } else {
            getOmniRouteProvider(sessionId)
        }

    /** UI-step provider for the chosen cloud source. */
    private fun getActionStepProvider(sessionId: String? = null): com.aniob.core.external.AniobActionStepProvider =
        if (cloudSource() == com.aniob.core.auth.AniobCloudSource.ACCOUNT) {
            app.accountAuth.newClient(sessionId) { app.researchTelemetry.recordLlmCall() }
        } else {
            getOmniRouteProvider(sessionId)
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
        // Only developer builds may select experimental conditions. Release always starts in the
        // full-capability condition, even if this app data was previously used by a debug build.
        val researchControlsEnabled = com.aniob.app.AniobBuild.isDeveloperBuild(app)
        val researchMode = if (researchControlsEnabled) app.researchTelemetry.mode else AblationMode.DEFAULT.also {
            app.researchTelemetry.mode = it
        }
        val researchPass = if (researchControlsEnabled) app.researchTelemetry.studyPass else StudyPass.MEASURED.also {
            app.researchTelemetry.studyPass = it
        }
        val lastResearchRun = app.researchTelemetry.lastRunOrNull()
        val researchSummaryRow = lastResearchRun?.let { app.researchTelemetry.summaryFor(it.mode) }
        val savedQueued = prefs.getString("queued_prompt", null)
        localFailCount = 0 // Retry diagnostics are execution-scoped and never restored from disk.
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
        val isLaunchable: (String) -> Boolean = ::isLaunchablePackage
        executionRouter.appIsLaunchable = isLaunchable
        // Step 0: the app named at the start of the task ("open Clock and set an alarm").
        executionRouter.appLaunchResolver = { goal ->
            AniobLaunchHub.plan(goal, AniobAppRegistry.getInstance(), isLaunchable)?.let { plan ->
                AppLaunchTarget(plan.packageName, plan.label, plan.launchActivity)
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
                AppLaunchTarget(plan.packageName, plan.label, plan.launchActivity)
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
                fastPathEnabled = prefs.getBoolean("fastpath_enabled", true),
                disabledSkills = disabledSkills.toSet(),
                ablationMode = researchMode,
                studyPass = researchPass,
                researchLastRun = lastResearchRun,
                researchStatusLine = lastResearchRun?.let { app.researchTelemetry.hudLine(it) },
                researchSummary = researchSummaryRow
            )
        }

        // Research conditions never change release capabilities; the persisted ask posture is
        // applied exactly as chosen, including OFF, before the first task can start.
        applyAblationMode(_uiState.value.ablationMode)
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

        // Hydrate learned skills consolidated into the runtime skill_library/skills directory
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
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

        viewModelScope.launch {
            app.approvalController.confirmRequest.collect { req ->
                _uiState.update { it.copy(confirmRequest = req) }
            }
        }
    }

    fun clearChat() {
        _uiState.update {
            it.copy(
                chatMessages = emptyList(),
                steps = emptyList(),
                stepEvents = emptyList(),
                lastNarration = "",
                lastSummary = null,
                momentChips = emptyList(),
                streamingBubbleText = "",
                isStreaming = false,
                queuedPrompt = null
            )
        }
    }

    fun dismissInterruptedSummary() {
        _uiState.update { it.copy(interruptedSummary = null) }
    }

    fun dismissRecoveryBanner() {
        _uiState.update { it.copy(accessibilityRecoveryNeeded = false) }
    }

    fun clearLastSummary() {
        _uiState.update { it.copy(lastSummary = null) }
    }

    fun setTrackerFilter(filter: String) {
        _uiState.update { it.copy(trackerFilter = filter) }
    }

    fun setShowTrackerSheet(show: Boolean) {
        // Tracker is a pure view over already-recorded Room/EventLogger data: opening it must not
        // trigger a screen capture or a model call (asserted by TrackerZeroCaptureTest).
        if (show) trackerOpenCount++
        _uiState.update { it.copy(showTrackerSheet = show) }
    }

    /** Test/diagnostic surface for the zero-capture guarantee. */
    fun trackerOpenCount(): Int = trackerOpenCount

    /** Renders only the newest [CHAT_RENDER_WINDOW] messages; the data list keeps everything (U18). */
    fun renderWindow(): List<ChatMessage> {
        val messages = _uiState.value.chatMessages
        return if (messages.size <= CHAT_RENDER_WINDOW) messages
        else messages.takeLast(CHAT_RENDER_WINDOW)
    }

    /**
     * Builds an honest completion card. Evidence comes straight from the verifier's unmet list,
     * never invented; a STOPPED outcome is never reported as SUCCESS or FAILED.
     */
    internal fun buildSummary(
        prompt: String,
        outcome: Outcome,
        steps: Int,
        durationMs: Long,
        provider: String,
        evidence: List<EvidenceItem>,
        reason: String? = null
    ): TaskSummary {
        val next = when (outcome) {
            Outcome.SUCCESS -> listOf(NextAction.RUN_AGAIN, NextAction.VIEW_STEPS)
            Outcome.FAILED -> listOf(NextAction.RETRY, NextAction.EXPLORE_APP, NextAction.VIEW_STEPS)
            Outcome.UNVERIFIED -> listOf(NextAction.RETRY, NextAction.VIEW_STEPS)
            Outcome.STOPPED -> listOf(NextAction.VIEW_STEPS)
            Outcome.INTERRUPTED -> listOf(NextAction.VIEW_STEPS)
        }
        return TaskSummary(
            prompt = prompt,
            outcome = outcome,
            steps = steps,
            durationMs = durationMs,
            provider = provider,
            evidence = evidence,
            nextActions = next,
            reason = reason
        )
    }

    /** Persists active task marker in Room so process death can be honestly reviewed on next open. */
    private fun persistRunningMarker(task: AniobTask, stepIndex: Int, lastKnownEffect: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.database.activeTaskDao().setActiveTask(
                    com.aniob.app.db.ActiveTaskEntity(
                        taskId = task.id,
                        rawPrompt = task.rawPrompt,
                        currentStep = stepIndex,
                        lastKnownEffect = lastKnownEffect,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("AniobViewModel", "Failed to update running marker in database", e)
            }
        }
    }

    private fun clearRunningMarker() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.database.activeTaskDao().clearActiveTasks()
            } catch (e: Exception) {
                android.util.Log.w("AniobViewModel", "Failed to clear active tasks from database", e)
            }
        }
    }

    /**
     * Foundation Finding F5: Consolidate state transitions so task termination
     * always flows through a central state updater rather than manual scattered resets.
     * Plan-7.1: TerminationStage is the single owner of terminal exits.
     */
    internal val terminationStage = com.aniob.core.termination.TerminationStage()

    internal fun updateToIdle(transform: (AniobUiState) -> AniobUiState = { it }) {
        clearRunningMarker()
        AniobAccessibilityService.isTaskActive = false
        if (!terminationStage.isTerminated) {
            runCatching {
                terminationStage.terminate(
                    goal = _uiState.value.activeTask?.clarifiedGoal ?: _uiState.value.activeTask?.rawPrompt ?: "",
                    residue = com.aniob.core.termination.TerminationStage.DeviceResidue(),
                    didDo = emptyList(),
                    didNotDo = emptyList(),
                    userStopped = false
                )
            }
        }
        _uiState.update { current ->
            transform(current).copy(isRunning = false)
        }
    }

    /** Reads the persisted marker on cold start and surfaces it as an Interrupted card. */
    private fun restoreInterruptedSummary() {
        viewModelScope.launch(Dispatchers.IO) {
            // Check workflow repository first
            val recoveredList = try {
                app.workflowRepository.recoverAllOnStartup()
            } catch (_: Exception) { emptyList() }

            val interrupted = recoveredList.firstOrNull { it.state == "EffectUnknown" }
            if (interrupted != null) {
                val summary = buildSummary(
                    prompt = "Workflow ${interrupted.taskId}",
                    outcome = Outcome.INTERRUPTED,
                    steps = 1,
                    durationMs = 0L,
                    provider = "WORKFLOW",
                    evidence = emptyList(),
                    reason = "Interrupted during dispatch. Outcome in external app is unverified."
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (_uiState.value.isRunning) {
                        _uiState.update { it.copy(interruptedSummary = summary) }
                    } else {
                        updateToIdle { it.copy(interruptedSummary = summary) }
                    }
                }
            }

            val waiting = recoveredList.firstOrNull { it.state == "WaitingForUser" }
            if (waiting != null) {
                val dest = com.aniob.core.workflow.Destination(
                    handler = waiting.destinationKey,
                    url = waiting.destinationUrl,
                    category = com.aniob.core.workflow.DestinationCategory.WEBSITE
                )
                val identity = com.aniob.core.workflow.OperationIdentity(
                    taskId = waiting.taskId,
                    actionId = waiting.actionId,
                    generation = waiting.generation,
                    destinationKey = waiting.destinationKey,
                    approvedPayloadHash = waiting.payloadHash
                )
                app.workflowCoordinator.updateState(
                    com.aniob.core.workflow.WorkflowState.WaitingForUser(
                        identity = identity,
                        destination = dest,
                        brief = waiting.evidence
                    )
                )
            }

            if (interrupted != null) return@launch

            val marker = try {
                app.database.activeTaskDao().getActiveTask()
            } catch (_: Exception) { null } ?: return@launch

            // If a task is currently executing with this taskId, it is live, not an interrupted prior task
            if (_uiState.value.isRunning && _uiState.value.activeTask?.id == marker.taskId) {
                return@launch
            }

            val prompt = marker.rawPrompt
            val step = marker.currentStep
            val lastEffect = marker.lastKnownEffect
            val summary = buildSummary(
                prompt = prompt,
                outcome = Outcome.INTERRUPTED,
                steps = step,
                durationMs = 0L,
                provider = "NONE",
                evidence = emptyList(),
                reason = if (lastEffect.isNotBlank()) {
                    "Interrupted at step $step. Last known effect: $lastEffect. Review external effects before retrying."
                } else {
                    "Interrupted at step $step. Review external effects before retrying."
                }
            )
            clearRunningMarker()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (_uiState.value.isRunning) {
                    _uiState.update { it.copy(interruptedSummary = summary) }
                } else {
                    updateToIdle { it.copy(interruptedSummary = summary) }
                }
            }
        }
    }

    // =========================================================================================
    // UX-2 Smart Composer
    // =========================================================================================

    /** Queues a follow-up while a task runs (depth 1; single-owner queue). */
    fun queueFollowUp(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putString("queued_prompt", trimmed).apply()
        _uiState.update { it.copy(queuedPrompt = trimmed) }
    }

    fun cancelQueuedFollowUp() {
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().remove("queued_prompt").apply()
        _uiState.update { it.copy(queuedPrompt = null) }
    }

    fun startQueuedTask() {
        val nextPrompt = _uiState.value.queuedPrompt ?: return
        cancelQueuedFollowUp()
        submitTask(nextPrompt)
    }

    /** Remember a grill answer in the durable vault (previously in-memory only). */
    fun rememberGrillAnswer(key: String, value: String) {
        persistentKnowledgeStore.put(vaultKey(key), value)
        _uiState.update { it.copy(grillRememberedKeys = it.grillRememberedKeys + key) }
    }

    fun forgetGrillAnswer(key: String) {
        persistentKnowledgeStore.remove(vaultKey(key))
        _uiState.update { it.copy(grillRememberedKeys = it.grillRememberedKeys - key) }
    }

    fun grillRemembered(key: String): String? = sharedKnowledgeStore.get(vaultKey(key))

    private fun vaultKey(questionId: String) = "grill.$questionId"

    /** UX-5: the "What Aniob remembers" list, surfaced from the Trust Center / Skills screen. */
    fun vaultEntries(): Map<String, String> =
        sharedKnowledgeStore.getAll().filterKeys { it.startsWith("grill.") }

    fun forgetVaultEntry(key: String) {
        persistentKnowledgeStore.remove(key)
    }

    /** Plan-7.3: Support editing remembered preferences in the Trust Center. */
    fun editVaultEntry(key: String, newAnswer: String) {
        sharedKnowledgeStore.put(key, newAnswer)
        persistentKnowledgeStore.put(key, newAnswer)
    }

    private fun postMomentChip(label: String, kind: MomentChip.Kind) {
        val chip = MomentChip(id = "chip_${System.currentTimeMillis()}", label = label, kind = kind)
        _uiState.update { it.copy(momentChips = it.momentChips + chip) }
    }

    fun clearMomentChips() {
        _uiState.update { it.copy(momentChips = emptyList()) }
    }

    // =========================================================================================
    // UX-3 Glass Cockpit — narration, pause, takeover, stop budget
    // =========================================================================================

    /** Publishes a step event to the in-app ticker and tracker; progress is mirrored to the notification. */
    internal fun publishStep(
        stepIndex: Int,
        action: AniobAction,
        provider: String,
        latencyMs: Long,
        verified: Boolean,
        failureReason: String? = null,
        node: AniobNode? = null
    ): StepEvent {
        val narration = StepNarration.narrate(action, node)
        advancePhase(AgentPhase.ACTING, action.thought.orEmpty())
        val event = StepEvent(
            stepIndex = stepIndex,
            narration = narration,
            status = when {
                failureReason != null -> StepEvent.Status.FAILED
                verified -> StepEvent.Status.OK
                else -> StepEvent.Status.SKIPPED
            },
            provider = provider,
            latencyMs = latencyMs,
            failureReason = failureReason,
            technical = "${action.toolName} · ${provider.lowercase()}"
        )
        AniobExecutionEvents.publish(event)
        AniobBackgroundController.onStepNarrated(stepIndex, narration)
        _uiState.update {
            it.copy(stepEvents = it.stepEvents + event, lastNarration = narration)
        }
        return event
    }

    /** UX-3: cooperative pause. Resume re-captures a fresh screen; never resumes on a stale tree. */
    fun togglePause() {
        pauseRequested = !pauseRequested
        AniobBackgroundController.onTaskPaused(pauseRequested)
        _uiState.update {
            it.copy(
                isPaused = pauseRequested,
                statusMessage = if (pauseRequested) "Paused — do this step yourself, then Resume"
                else "Resuming with a fresh screen…"
            )
        }
    }

    /** Awaited at the loop head and inside settle waits so pause is honoured promptly. */
    internal suspend fun awaitIfPaused() {
        while (pauseRequested && _uiState.value.isRunning) {
            delay(PAUSE_POLL_MS)
        }
    }

    /**
     * Takeover Bridge (UX-3 §4): manual step performed by user during pause.
     * Resume cleanly without claiming an unconsented recording session has started.
     */
    fun onTakeoverCompleted() {
        _uiState.update {
            it.copy(
                statusMessage = "Manual step acknowledged. Ready to resume."
            )
        }
    }

    // =========================================================================================
    // UX-4 Trust & Safety
    // =========================================================================================

    fun setSafetyLevel(level: SafetyLevel) {
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putInt("safety_level", level.ordinal).apply()
        applyAskLevel(level)
        _uiState.update { it.copy(safetyLevel = level) }
    }

    private fun applyAskLevel(level: SafetyLevel) {
        AniobSafetyInterceptor.askLevel = when (level) {
            SafetyLevel.OFF -> com.aniob.core.safety.AskLevel.OFF
            SafetyLevel.STANDARD -> com.aniob.core.safety.AskLevel.BALANCED
            SafetyLevel.STRICT -> com.aniob.core.safety.AskLevel.CAREFUL
        }
    }

    fun isFastPathEnabled(): Boolean = _uiState.value.fastPathEnabled

    /** REAL switch: OFF gates the replay lookup — proven by a zero-replay-hits test. */
    fun setFastPathEnabled(enabled: Boolean) {
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("fastpath_enabled", enabled).apply()
        _uiState.update { it.copy(fastPathEnabled = enabled) }
        // Re-derive the effective replay flag: a mode without remember-replay keeps it off even
        // when the user switch is on.
        applyAblationMode(_uiState.value.ablationMode)
    }

    // =========================================================================================
    // Research: ablation selection, metrics HUD and manual verdict override
    // =========================================================================================

    /**
     * Switches the experimental condition for the next task.
     *
     * Persisted in the telemetry manager's prefs (not `aniob_prefs`) so the CSV and the mode that
     * produced it cannot drift apart. The change is applied to the real switches immediately.
     */
    fun setAblationMode(mode: AblationMode) {
        if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
        app.researchTelemetry.mode = mode
        applyAblationMode(mode)
        _uiState.update { it.copy(ablationMode = mode, researchStatusLine = null) }
        // The summary lives on disk; refresh it off the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val summary = app.researchTelemetry.summaryFor(mode)
            _uiState.update { it.copy(researchSummary = summary) }
        }
    }

    /**
     * Switches the study pass (warm-up vs measured) for the next task.
     */
    fun setStudyPass(pass: StudyPass) {
        if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
        app.researchTelemetry.studyPass = pass
        _uiState.update { it.copy(studyPass = pass) }
    }

    /**
     * Seeds `remember_cache.json` with the first hop of every benchmark task, from the settings
     * screen, so a warm `FULL_HUMAN_SPEED` batch can be measured without a cold run first.
     *
     * Only the app each task starts in is seeded (see `RememberPresets`); every later step is still
     * learned from a verified run, and a replayed hop is observed like any other step.
     */
    fun seedRememberPresets() {
        if (!com.aniob.app.AniobBuild.isDeveloperBuild(app) || _uiState.value.isRunning) return
        viewModelScope.launch(Dispatchers.IO) {
            val cache = app.rememberCacheFile.load()
            val isInstalled: (String) -> Boolean = { pkg ->
                runCatching { app.packageManager.getLaunchIntentForPackage(pkg) != null }
                    .getOrDefault(false)
            }
            val written = com.aniob.core.memory.RememberPresets.seed(cache, isInstalled)
            app.rememberCacheFile.save(cache)
            rememberCache = cache
            app.eventLogger.info(
                "AniobViewModel",
                "Remember presets seeded: $written/${com.aniob.core.memory.RememberPresets.studyBattery.size} tasks -> ${app.rememberCacheFile.path()}"
            )
            _uiState.update {
                it.copy(
                    researchStatusLine = "Remember cache seeded: $written of " +
                        "${com.aniob.core.memory.RememberPresets.studyBattery.size} benchmark first hops"
                )
            }
        }
    }

    /**
     * Applies a mode to the mechanisms that exist today. Called on init, on every mode change and
     * at the start of each task, so a stale switch can never survive into a measured run.
     */
    internal fun applyAblationMode(mode: AblationMode) {
        val effective = if (com.aniob.app.AniobBuild.isDeveloperBuild(app)) mode else AblationMode.DEFAULT
        executionRouter.intentShortcutsEnabled = effective.intentShortcuts
        executionRouter.skillsEnabled = effective.skillsEnabled
        executionRouter.fastPathEnabled = effective.rememberReplay && _uiState.value.fastPathEnabled
        observationPolicy.setBurstLimit(
            if (effective.actionBursts) OBSERVATION_BURST_LIMIT else OBSERVATION_BURST_LIMIT_SINGLE_STEP
        )
        // The release default is the full-capability condition; developer builds may select a
        // measured condition for the next task.
        AniobAccessibilityService.setFastObservation(effective.fastObservation)
        AniobAccessibilityService.setResearchFastDeltaVerify(effective.fastDeltaVerify)
        executionRouter.rememberReplayEnabled = effective.rememberReplay && _uiState.value.fastPathEnabled
    }

    /**
     * Manual verdict for the last finished run, used when the deterministic verifier cannot judge
     * an outcome. Rewrites the last CSV row (flagged as manual) and refreshes the HUD.
     */
    fun overrideResearchOutcome(success: Boolean) {
        if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = app.researchTelemetry.overrideLastOutcome(success)
            if (updated != null) {
                val line = app.researchTelemetry.hudLine(updated)
                _uiState.update { current ->
                    current.copy(
                        researchLastRun = updated,
                        researchStatusLine = line,
                        researchSummary = app.researchTelemetry.summaryFor(updated.mode)
                    )
                }
            }
        }
    }

    // =========================================================================================
    // Remember: zero-LLM replay cache
    // =========================================================================================

    /** One verified transition of the current run; persisted only when the task succeeds. */
    private data class RememberTransition(
        val screen: AniobScreenState,
        val action: AniobAction,
        val node: AniobNode?,
        val nextScreen: AniobScreenState? = null
    )

    /**
     * Stores this run's verified transitions, keyed by goal keywords, package and the screen the
     * action was planned on. [mode] is the condition the run started under, so a settings change
     * mid-task cannot seed a cache the run never read.
     */
    private fun persistRememberTransitions(
        mode: AblationMode,
        goal: String,
        transitions: List<RememberTransition>
    ) {
        if (transitions.isEmpty()) return
        if (!mode.rememberReplay || !_uiState.value.fastPathEnabled) return

        // Drop undone transitions (e.g. BACK key) to keep minimal path (F-1)
        val pruned = mutableListOf<RememberTransition>()
        for (t in transitions) {
            if (t.action is AniobAction.PressKey && t.action.key == com.aniob.core.domain.KeyType.BACK && pruned.isNotEmpty()) {
                pruned.removeAt(pruned.lastIndex)
            } else {
                pruned.add(t)
            }
        }

        var recorded = 0
        for (transition in pruned) {
            if (transition.screen.nodes.any { it.isPassword || it.className.contains("Password", ignoreCase = true) }) {
                continue
            }
            val descriptor = RememberAction.fromAction(
                action = transition.action,
                node = transition.node,
                screen = transition.screen
            ) ?: continue
            val nextHash = transition.nextScreen?.let { FastVerify.screenIdentity(it) }.orEmpty()
            val ok = rememberCache.record(
                goal = goal,
                packageName = transition.screen.packageName,
                preActionScreenHash = FastVerify.screenIdentity(transition.screen),
                actions = listOf(descriptor),
                expectedNextHash = nextHash
            )
            if (ok) recorded++
        }
        if (recorded == 0) return
        val snapshot = rememberCache
        viewModelScope.launch(Dispatchers.IO) {
            app.rememberCacheFile.save(snapshot)
        }
    }

    /**
     * Waits until the chat window is really in the background, by watching the foreground package
     * instead of sleeping a fixed 500 ms. Returns as soon as another app is in front; the cap keeps
     * a stubborn window manager from stalling the run.
     */
    private suspend fun awaitAppBackgrounded(service: AniobAccessibilityService?) {
        if (service == null) {
            delay(120)
            return
        }
        val deadline = System.currentTimeMillis() + BACKGROUND_WAIT_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            val foreground = runCatching { service.currentForegroundPackage() }.getOrNull()
            if (!foreground.isNullOrBlank() && foreground != app.packageName) return
            delay(40)
        }
    }

    /**
     * Warms DNS/TLS/HTTP-2 for the gateway. Never throws and never counts as a model call: the
     * request is a plain `GET /models`, so the first real action reuses the open connection.
     */
    private suspend fun prewarmGateway() {
        if (!isCloudConfigured()) return
        // Only the gateway has a prewarm; opening a connection to the account service is not free.
        if (cloudSource() != com.aniob.core.auth.AniobCloudSource.OMNIROUTE) return
        runCatching { getOmniRouteProvider().prewarm() }
    }

    /**
     * One bitmap for the tier-2 verify or for a visual Reason step. Returns null when the service
     * is gone or the capture fails; callers treat that as "no pixels", never as an error.
     */
    private suspend fun captureVerifyScreenshot(): String? {
        val service = AniobAccessibilityService.instance ?: return null
        return try {
            val snap = service.captureScreenshotAsync()
            snap.bitmap?.let { bmp ->
                try {
                    app.researchTelemetry.recordBitmapCapture()
                    val stream = java.io.ByteArrayOutputStream()
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                    android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                } finally {
                    bmp.recycle()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Publishes a run that the abnormal-exit safety net just closed (Stop, cancellation, crash of a
     * step) so the developer-only research status remains available. Normal completions publish in
     * [updateToIdle]; this is only for runs closed outside that path.
     */
    private fun publishResearchRun(run: ResearchRun?) {
        if (run == null) return
        val line = app.researchTelemetry.hudLine(run)
        val summary = app.researchTelemetry.summaryFor(run.mode)
        _uiState.update { current ->
            current.copy(
                researchLastRun = run,
                researchStatusLine = line,
                researchSummary = summary
            )
        }
    }

    /** Deletes stored research CSVs so the next batch starts clean. */
    fun clearResearchData() {
        if (!com.aniob.app.AniobBuild.isDeveloperBuild(app)) return
        viewModelScope.launch(Dispatchers.IO) {
            app.researchTelemetry.clearAll()
            _uiState.update {
                it.copy(
                    researchLastRun = null,
                    researchStatusLine = null,
                    researchSummary = null
                )
            }
        }
    }

    fun confirmationHistory(): List<ConfirmationRecord> = _uiState.value.confirmationHistory

    private fun recordConfirmation(request: ConfirmRequest, decision: ConfirmDecision) {
        val record = ConfirmationRecord(
            what = request.what,
            risk = request.risk,
            decision = decision,
            at = System.currentTimeMillis()
        )
        _uiState.update {
            it.copy(confirmationHistory = (listOf(record) + it.confirmationHistory).take(CONFIRMATION_HISTORY_LIMIT))
        }
    }

    /**
     * When the user is asked about consequential steps. Defaults to once, at the start of the task;
     * `ask_timing = MID_RUN` in `aniob_prefs` restores the old pause-and-wait behaviour.
     */
    private fun askTiming(): com.aniob.core.safety.AskTiming = runCatching {
        com.aniob.core.safety.AskTiming.valueOf(
            app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
                .getString("ask_timing", null) ?: com.aniob.core.safety.AskTiming.AT_START.name
        )
    }.getOrDefault(com.aniob.core.safety.AskTiming.AT_START)

    /** An approval decided from the start-of-task consent is still audited in the confirmation history. */
    private fun recordAutoResolution(
        action: AniobAction,
        node: AniobNode?,
        verdict: InterceptResult,
        decision: ConfirmDecision
    ) {
        val sensitive = AskPolicy.category(action, node) == AskCategory.SECRET || node?.isPassword == true
        val what = if (sensitive) "Use a protected value" else StepNarration.narrate(action, node).take(80)
        val record = ConfirmationRecord(
            what = what,
            risk = riskFromTier(verdict.riskTier, _uiState.value.safetyLevel),
            decision = decision,
            at = System.currentTimeMillis()
        )
        _uiState.update {
            it.copy(confirmationHistory = (listOf(record) + it.confirmationHistory).take(CONFIRMATION_HISTORY_LIMIT))
        }
    }

    /**
     * The ONE gate on a risky dispatch (UX-4 §1). Replaces the old fire-and-forget
     * dialog: this suspends the caller until a real decision exists, so a risky
     * action can never execute while the approval is still on screen.
     */
    internal suspend fun requestConfirmation(request: ConfirmRequest): ConfirmDecision {
        val currentTask = _uiState.value.activeTask
        val identity = com.aniob.core.workflow.OperationIdentity(
            taskId = request.taskId.ifBlank { currentTask?.id ?: "" },
            actionId = request.id,
            generation = activeGeneration,
            destinationKey = request.identityTarget.ifBlank { request.target },
            approvedPayloadHash = request.payload.takeIf { it.isNotBlank() }?.let {
                com.aniob.app.workflow.WorkflowPayloadStore.sha256(it.toByteArray(Charsets.UTF_8))
            }
        )
        _uiState.update { it.copy(confirmRequest = request) }
        AniobForegroundService.notifyConfirmation(app, request)
        val decision = app.approvalController.requestApproval(request, identity)
        recordConfirmation(request, decision)
        AniobForegroundService.clearConfirmation(app)
        _uiState.update { it.copy(confirmRequest = null) }
        return decision
    }

    /** Called by the ConfirmSheet / notification action with the user's real choice. */
    fun resolveConfirmation(decision: ConfirmDecision, taskId: String? = null, reqId: String? = null) {
        app.approvalController.resolveConfirmation(decision, taskId, reqId)
    }

    /**
     * Maps an interceptor verdict onto the frozen confirm contract.
     * The interceptor decides *whether* to ask; the user decides *whether to proceed*.
     */
    private fun buildConfirmRequest(
        what: String,
        why: String,
        risk: ConfirmRequest.Risk,
        details: String,
        taskId: String = _uiState.value.activeTask?.id ?: "",
        payload: String = "",
        target: String = "",
        identityTarget: String = target
    ) = ConfirmRequest(
        id = "confirm_${java.util.UUID.randomUUID()}",
        title = "Aniob needs your approval",
        what = what,
        why = why,
        risk = risk,
        details = details,
        taskId = taskId,
        payload = payload,
        target = target,
        identityTarget = identityTarget
    )

    private fun riskFromTier(tier: String, safetyLevel: SafetyLevel): ConfirmRequest.Risk = when {
        tier.equals("HIGH", true) -> ConfirmRequest.Risk.HIGH
        tier.equals("MEDIUM", true) -> ConfirmRequest.Risk.MEDIUM
        else -> ConfirmRequest.Risk.LOW
    }

    // =========================================================================================
    // UX-5 Learning, visible
    // =========================================================================================

    /** Teach prefill handoff; the full Teach producer lands with the functional track. */
    fun startTeachFlow(prompt: String?) {
        val seed = prompt?.trim().orEmpty()
        _uiState.update {
            it.copy(
                chatMessages = it.chatMessages + ChatMessage(
                    id = "msg_teach_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = if (seed.isBlank()) {
                        "Tell me the task you want to teach, and I'll record the steps as a skill."
                    } else {
                        "Teach mode: I'll record the steps for '$seed' and save it as a skill."
                    },
                    badge = "Teach",
                    provider = "TEACH",
                    retryPrompt = seed.ifBlank { null }
                )
            )
        }
    }

    /** REAL switch: a disabled skill is skipped by the matcher (proven by test). */
    fun setSkillEnabled(name: String, enabled: Boolean) {
        val disabled = disabledSkills.toMutableSet()
        if (enabled) disabled.remove(name) else disabled.add(name)
        disabledSkills = disabled
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .edit().putStringSet("disabled_skills", disabled).apply()
        executionRouter.setDisabledSkills(disabled)
        _uiState.update { it.copy(disabledSkills = disabled) }
    }

    fun deleteSkill(name: String) {
        // Learned skills are YAML files under the skill library; built-ins live in assets and are
        // not deletable, so a missing file is a no-op rather than an error.
        runCatching {
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
            File(dir, "$name.yaml").takeIf { it.exists() }?.delete()
            semanticSkillMatcher.removeSkill(name)
        }
    }

    // =========================================================================================
    // UX-6 survival helpers
    // =========================================================================================

    /** UX-6: last completed task as a card on next open (Quiet Return). */
    // ------------------------------------------------------------------------------------------
    // Audit patch: phase visibility, closed-set outcome, plan-time feasibility, grill-me wiring
    // ------------------------------------------------------------------------------------------

    /** Starts a new phase and says whether a model is really involved (RULES = no model). */
    internal fun setPhase(
        phase: AgentPhase,
        source: ThinkSource = ThinkSource.RULES,
        thought: String = "",
        retryAtMs: Long? = null,
        provider: String = ""
    ) {
        _uiState.update {
            it.copy(agentPhase = PhaseSnapshot(phase, source, System.currentTimeMillis(), thought.take(160), retryAtMs, provider))
        }
    }

    /** Moves to the next phase of the SAME step, keeping the source and provider already shown. */
    internal fun advancePhase(phase: AgentPhase, thought: String = "") {
        _uiState.update {
            it.copy(
                agentPhase = it.agentPhase.copy(
                    phase = phase,
                    sinceMs = System.currentTimeMillis(),
                    thoughtPreview = thought.take(160).ifBlank { it.agentPhase.thoughtPreview },
                    retryAtMs = null
                )
            )
        }
    }

    private val outcomeStore by lazy { OutcomeStore(app.database.taskOutcomeDao()) }

    /** The only way a run's result is stored: a SealedOutcome, which cannot be VERIFIED without evidence. */
    internal fun recordSealedOutcome(taskId: String, sealed: SealedOutcome) {
        _uiState.update { it.copy(lastSealedOutcome = sealed, lastSealedTaskId = taskId) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                outcomeStore.record(taskId, sealed)
            } catch (e: android.database.sqlite.SQLiteException) {
                // The SQL guard refused it (or the disk did). Never crash the UI over a result row.
                app.eventLogger.warn("AniobViewModel", "Outcome not stored: ${e.message}")
            }
        }
    }

    /** The user's thumbs on the last result. YES attests an unconfirmed run; NO demotes a claimed success. */
    fun onOutcomeDecision(decision: UserDecision) {
        val taskId = _uiState.value.lastSealedTaskId ?: return
        val current = _uiState.value.lastSealedOutcome ?: return
        if (current.userDecision != null) return
        recordSealedOutcome(taskId, current.withUserDecision(decision, System.currentTimeMillis()))
        if (decision == UserDecision.YES) {
            val trajectory = verifiedActions.toList()
            val taskPrompt = _uiState.value.activeTask?.rawPrompt.orEmpty()
            if (trajectory.isNotEmpty() && taskPrompt.isNotBlank()) {
                viewModelScope.launch {
                    learningPipeline.onTaskSuccess(
                        TaskContext(
                            instruction = taskPrompt,
                            successCriteria = SuccessCriteria(),
                            taskId = taskId,
                            lastScreenState = null
                        ),
                        trajectory
                    )
                }
            }
        } else if (decision == UserDecision.NO) {
            val taskPrompt = _uiState.value.activeTask?.rawPrompt.orEmpty()
            if (taskPrompt.isNotBlank()) {
                _uiState.update { it.copy(statusMessage = "Starting task again...") }
                submitTask(taskPrompt)
            }
        }
    }

    private fun buildGrillLlmClient(): AniobPromptGrillMeClient? {
        val systemPrompt = try {
            app.assets.open("aniob_config/prompts/grill_me_prompt.txt").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            return null
        }
        return AniobPromptGrillMeClient(systemPrompt = systemPrompt) { system, user ->
            // Never send the request to the cloud in local-only mode or without a configured gateway.
            if (!isCloudConfigured() || _uiState.value.autoRouterMode.lowercase() == "local-only") null
            else try {
                getOmniRouteProvider(sessionId = "grill").generate(user, system)
            } catch (e: java.io.IOException) {
                null
            }
        }
    }

    /** What this phone will let us do and OBSERVE right now. Shizuku/device-owner are not integrated, so never claimed. */
    private fun capabilityEnvelope(): CapabilityEnvelope {
        val state = AniobCapabilityProbe.snapshot(app)
        return CapabilityEnvelope(
            buildSet {
                add(Privilege.PACKAGE_QUERY) // launcher-visible packages via <queries>; see AndroidManifest
                if (state.accessibility) add(Privilege.ACCESSIBILITY)
                if (state.notificationAccess) add(Privilege.NOTIFICATION_LISTENER)
            }
        )
    }

    /** Conservative plan shape from the words of the request: only steps that are certainly system-gated. */
    private fun planStepsFor(prompt: String): List<PlanStep> {
        val words = com.aniob.core.text.AniobTextFold.tokens(prompt).toSet()
        return buildList {
            if ("apk" in words || "sideload" in words) {
                add(PlanStep("install the APK", ActionGate.SYSTEM_PACKAGE_DIALOG, setOf(Postcondition.PACKAGE_INSTALLED)))
            } else if ("uninstall" in words) {
                add(PlanStep("uninstall the app", ActionGate.SYSTEM_PACKAGE_DIALOG, setOf(Postcondition.PACKAGE_REMOVED)))
            }
        }
    }

    private fun finishDelegated(task: AniobTask, whatUserMustDo: String) {
        recordSealedOutcome(task.id, SealedOutcome.delegated(whatUserMustDo))
        setPhase(AgentPhase.DELEGATED)
        val note = ChatMessage(
            id = "msg_deleg_${System.currentTimeMillis()}",
            role = "assistant",
            content = whatUserMustDo,
            provider = "DELEGATED",
            badge = "\uD83E\uDD1D Your turn"
        )
        updateToIdle { it.copy(showGrillMeSheet = false, statusMessage = whatUserMustDo, chatMessages = it.chatMessages + note) }
    }

    internal fun postSummaryCard(summary: TaskSummary) {
        _uiState.update { it.copy(lastSummary = summary) }
    }

    fun setAutoRouterMode(mode: String) {
        val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("autorouter_mode", mode).apply()
        _uiState.update { it.copy(autoRouterMode = mode) }
    }

    /**
     * Persists the Cloud AI (OmniRoute) settings entered in Settings.
     *
     * The key goes to the Keystore-backed secret store; the gateway address and model are not
     * secrets and stay in the ordinary prefs file. Both a key and a valid address are required
     * before cloud routing is allowed, because `localhost` on a phone is the phone itself. Clearing
     * both fields is a real "turn Cloud AI off" action, not a validation failure.
     */
    fun saveCloudSettings(apiKey: String, model: String, baseUrl: String) {
        // A new address or key deserves a fresh chance: forget which gateways were marked down.
        com.aniob.core.external.AniobGatewayHealth.shared.reset()
        val address = AniobProviderConfig.normalizeBaseUrl(baseUrl)
        val key = apiKey.trim()

        if (key.isEmpty() && address.isEmpty()) {
            secretStore.setOmniRouteApiKey("")
            secretStore.setOmniRouteBaseUrl("")
            app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE).edit()
                .putString("omniroute_model", AniobProviderConfig.OMNIROUTE_MODEL).apply()
            _uiState.update {
                it.copy(
                    omnirouteApiKey = "",
                    omnirouteBaseUrl = "",
                    omnirouteModel = AniobProviderConfig.OMNIROUTE_MODEL,
                    omnirouteConnectionStatus = "Cloud API key cleared",
                    cloudTest = CloudTestUiState(
                        result = CloudTestResult.SAVED,
                        message = "Cloud AI is off. Add a key whenever you want it back."
                    )
                )
            }
            app.systemState.refresh()
            return
        }

        val validationError = when {
            address.isEmpty() -> "Add the address of the computer running OmniRoute."
            // A self-hosted gateway on this device or the local network usually has no key.
            key.isEmpty() && !AniobProviderConfig.isKeylessGatewayAllowed(address) ->
                "Add the key from your OmniRoute dashboard."
            else -> AniobProviderConfig.validateBaseUrl(address)
                ?: AniobProviderConfig.validateModel(model)
        }
        if (validationError != null) {
            _uiState.update {
                it.copy(
                    cloudTest = CloudTestUiState(
                        result = CloudTestResult.INVALID_SETUP,
                        message = validationError
                    )
                )
            }
            return
        }
        if (!secretStore.isAvailable) {
            _uiState.update {
                it.copy(
                    cloudTest = CloudTestUiState(
                        result = CloudTestResult.INVALID_SETUP,
                        message = "This device cannot store the key securely, so Cloud AI stays off."
                    )
                )
            }
            return
        }

        val cleanedModel = AniobProviderConfig.sanitizeModel(model)
        secretStore.setOmniRouteApiKey(key)
        secretStore.setOmniRouteBaseUrl(address)
        app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE).edit()
            .putString("omniroute_model", cleanedModel).apply()
        _uiState.update {
            it.copy(
                omnirouteApiKey = key,
                omnirouteBaseUrl = address,
                omnirouteModel = cleanedModel,
                omnirouteConnectionStatus = "Saved ✓ · Model: $cleanedModel",
                cloudTest = CloudTestUiState(
                    result = CloudTestResult.SAVED,
                    message = "Saved. Run Test connection to confirm the gateway answers."
                )
            )
        }
        app.systemState.refresh()
    }

    /**
     * Probes the entered gateway/key without saving them, so a typo never replaces a working setup.
     * Runs off the main thread via [testOmniRouteConnection] and publishes the outcome to the
     * Settings banner.
     */
    fun testCloudConnection(apiKey: String, baseUrl: String) {
        if (_uiState.value.cloudTest.testing) return
        com.aniob.core.external.AniobGatewayHealth.shared.reset()
        val key = apiKey.trim()
        val address = AniobProviderConfig.normalizeBaseUrl(baseUrl)
        val validationError = when {
            address.isEmpty() -> "Add the address of the computer running OmniRoute."
            key.isEmpty() && !AniobProviderConfig.isKeylessGatewayAllowed(address) ->
                "Add the key from your OmniRoute dashboard."
            else -> AniobProviderConfig.validateBaseUrl(address)
        }
        if (validationError != null) {
            _uiState.update {
                it.copy(
                    cloudTest = CloudTestUiState(
                        result = CloudTestResult.INVALID_SETUP,
                        message = validationError
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(cloudTest = CloudTestUiState(testing = true)) }
            val status = testOmniRouteConnection(key, address)
            val (result, message) = when (status) {
                OmniRouteConnectionStatus.SUCCESS ->
                    CloudTestResult.SUCCESS to "The gateway answered for this key. Save to keep it."
                OmniRouteConnectionStatus.BAD_KEY ->
                    CloudTestResult.BAD_KEY to "The gateway rejected this key. Copy it again from the OmniRoute dashboard."
                OmniRouteConnectionStatus.UNREACHABLE ->
                    CloudTestResult.UNREACHABLE to "No usable answer from that address. Check Wi-Fi and the address, confirm OmniRoute is running, and that it has at least one provider connected."
            }
            _uiState.update {
                it.copy(cloudTest = CloudTestUiState(result = result, message = message))
            }
        }
    }

    /** Clears stale feedback as soon as the user edits a field. */
    fun clearCloudTestResult() {
        if (_uiState.value.cloudTest != CloudTestUiState()) {
            _uiState.update { it.copy(cloudTest = CloudTestUiState()) }
        }
    }

    /** Backwards-compatible entry point that keeps the previously saved gateway address. */
    @JvmOverloads
    fun updateSettings(apiKey: String, model: String, baseUrl: String = _uiState.value.omnirouteBaseUrl) {
        saveCloudSettings(apiKey, model, baseUrl)
    }

    /** Sets the OmniRoute gateway base URL. Not a secret, so it is persisted in plaintext prefs. */
    fun setOmniRouteBaseUrl(baseUrl: String) {
        com.aniob.core.external.AniobGatewayHealth.shared.reset()
        val address = AniobProviderConfig.normalizeBaseUrl(baseUrl)
        secretStore.setOmniRouteBaseUrl(address)
        _uiState.update {
            it.copy(omnirouteBaseUrl = address, cloudTest = CloudTestUiState())
        }
    }

    /**
     * Saves the provided OmniRoute settings and performs a live diagnostic test against the gateway.
     */
    fun testOmniRouteSettings(
        apiKey: String = _uiState.value.omnirouteApiKey,
        baseUrl: String = _uiState.value.omnirouteBaseUrl,
        model: String = _uiState.value.omnirouteModel
    ) {
        updateSettings(apiKey = apiKey, model = model, baseUrl = baseUrl)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    omnirouteTesting = true,
                    omnirouteConnectionStatus = "Testing connection..."
                )
            }
            val diag = com.aniob.app.provider.verifyOmniRouteGateway(
                apiKey = apiKey.trim(),
                baseUrl = baseUrl.trim(),
                model = com.aniob.app.provider.AniobProviderConfig.coerceModel(model)
            )
            _uiState.update {
                it.copy(
                    omnirouteTesting = false,
                    omnirouteConnectionStatus = diag.message,
                    omnirouteDiscoveredModels = if (diag.availableModels.isNotEmpty()) diag.availableModels else it.omnirouteDiscoveredModels
                )
            }
        }
    }

    /**
     * Fetches the live `/v1/models` list from the configured OmniRoute gateway.
     */
    fun refreshOmniRouteModels(
        apiKey: String = _uiState.value.omnirouteApiKey,
        baseUrl: String = _uiState.value.omnirouteBaseUrl
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(omnirouteConnectionStatus = "Loading models from gateway...") }
            val models = com.aniob.app.provider.fetchOmniRouteModels(apiKey.trim(), baseUrl.trim())
            _uiState.update {
                it.copy(
                    omnirouteDiscoveredModels = models,
                    omnirouteConnectionStatus = if (models.isNotEmpty()) {
                        "Loaded ${models.size} models from gateway ✓"
                    } else {
                        "Could not load model list — check gateway address or key"
                    }
                )
            }
        }
    }

    fun handleIncomingShare(shared: com.aniob.app.workflow.ParsedIncomingContent) {
        val text = shared.text
        if (!text.isNullOrBlank()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > com.aniob.core.workflow.WorkflowLimits.TEXT_BRIEF_MAX_BYTES) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Shared text exceeds 64 KiB limit (${bytes.size} bytes). Cannot import.",
                        pendingInputPrefill = null
                    )
                }
            } else {
                _uiState.update { it.copy(pendingInputPrefill = text) }
            }
        }
    }

    fun consumePendingInputPrefill(): String? {
        val prefill = _uiState.value.pendingInputPrefill
        if (prefill != null) {
            _uiState.update { it.copy(pendingInputPrefill = null) }
        }
        return prefill
    }

    fun setPromptPrefill(text: String) {
        _uiState.update { it.copy(pendingInputPrefill = text) }
    }

    internal fun executeOwnedWorkflowAction(
        taskId: String,
        leaseGen: Long,
        prompt: String,
        action: com.aniob.core.workflow.WorkflowAction
    ) {
        val startTime = System.currentTimeMillis()
        val destName = when (action) {
            is com.aniob.core.workflow.WorkflowAction.OpenWebsite -> action.destination.handler
            is com.aniob.core.workflow.WorkflowAction.OpenApp -> action.destination.handler
            else -> "destination"
        }

        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            val currentJob = currentCoroutineContext()[kotlinx.coroutines.Job]!!
            app.taskOwner.attachJob(leaseGen, currentJob)

            _uiState.update {
                it.copy(
                    isRunning = true,
                    statusMessage = "Opening $destName...",
                    lastProviderUsed = "WORKFLOW"
                )
            }

            val result = app.workflowCoordinator.executeAction(action, taskId, leaseGen)
            when (result) {
                is com.aniob.core.workflow.WorkflowResult.WaitingForUser -> {
                    AniobForegroundService.notifyWaitingForUser(app, destName)
                    updateToIdle {
                        it.copy(
                            statusMessage = "Task waiting on $destName",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                is com.aniob.core.workflow.WorkflowResult.LaunchAccepted -> {
                    app.metricsCollector.recordWorkflowOutcome(taskId, prompt, result)
                    val summary = buildSummary(
                        prompt = prompt,
                        outcome = Outcome.SUCCESS,
                        steps = 1,
                        durationMs = System.currentTimeMillis() - startTime,
                        provider = "WORKFLOW",
                        evidence = listOf(EvidenceItem(label = "Opened $destName", met = true))
                    )
                    updateToIdle {
                        it.copy(
                            lastSummary = summary,
                            statusMessage = "Action launched: $destName",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                is com.aniob.core.workflow.WorkflowResult.Failed -> {
                    app.metricsCollector.recordWorkflowOutcome(taskId, prompt, result)
                    val summary = buildSummary(
                        prompt = prompt,
                        outcome = Outcome.FAILED,
                        steps = 1,
                        durationMs = System.currentTimeMillis() - startTime,
                        provider = "WORKFLOW",
                        evidence = emptyList(),
                        reason = result.errorReason
                    )
                    updateToIdle {
                        it.copy(
                            lastSummary = summary,
                            statusMessage = "Action failed: ${result.errorReason}",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                is com.aniob.core.workflow.WorkflowResult.Cancelled -> {
                    val summary = buildSummary(
                        prompt = prompt,
                        outcome = Outcome.STOPPED,
                        steps = 1,
                        durationMs = System.currentTimeMillis() - startTime,
                        provider = "WORKFLOW",
                        evidence = emptyList(),
                        reason = result.reason
                    )
                    updateToIdle {
                        it.copy(
                            lastSummary = summary,
                            statusMessage = "Cancelled by user",
                            lastProviderUsed = "WORKFLOW"
                        )
                    }
                }
                else -> {
                    updateToIdle()
                }
            }
        }
    }

    fun onDestinationChosen(destination: com.aniob.core.workflow.Destination, brief: String?) {
        val taskId = "task_chooser_${System.currentTimeMillis()}"
        val leaseGen = app.taskOwner.acquireLease(taskId)
        val action = if (destination.category == com.aniob.core.workflow.DestinationCategory.APP) {
            com.aniob.core.workflow.WorkflowAction.OpenApp(
                actionId = "action_${System.currentTimeMillis()}",
                destination = destination
            )
        } else {
            com.aniob.core.workflow.WorkflowAction.OpenWebsite(
                actionId = "action_${System.currentTimeMillis()}",
                destination = destination,
                briefText = brief
            )
        }
        executeOwnedWorkflowAction(
            taskId = taskId,
            leaseGen = leaseGen,
            prompt = "Open ${destination.label ?: destination.handler}",
            action = action
        )
    }

    fun onContinueInWebsite(taskId: String) {
        val ws = app.workflowCoordinator.workflowState.value
        val waiting = (ws as? com.aniob.core.workflow.WorkflowState.WaitingForUser)?.takeIf { it.identity.taskId == taskId }
        if (waiting != null) {
            val leaseGen = app.taskOwner.acquireLease(taskId)
            val action = com.aniob.core.workflow.WorkflowAction.OpenWebsite(
                actionId = "action_continue_${System.currentTimeMillis()}",
                destination = waiting.destination,
                briefText = waiting.brief
            )
            executeOwnedWorkflowAction(
                taskId = taskId,
                leaseGen = leaseGen,
                prompt = "Continue in ${waiting.destination.handler}",
                action = action
            )
        }
    }

    fun onCustomUrlChosen(url: String, brief: String?) {
        val validated = com.aniob.core.workflow.WorkflowRouter.validateAndSanitizeUrl(url)
        if (validated == null) {
            _uiState.update { it.copy(statusMessage = "Invalid URL entered.") }
            return
        }
        val host = com.aniob.core.workflow.WorkflowRouter.extractUrl(validated)?.let {
            validated.substringAfter("://").substringBefore('/')
        } ?: validated
        val dest = com.aniob.core.workflow.Destination(
            handler = host,
            url = validated,
            category = com.aniob.core.workflow.DestinationCategory.WEBSITE,
            provenance = com.aniob.core.workflow.DestinationProvenance.USER_INPUT
        )
        onDestinationChosen(dest, brief)
    }

    fun onWorkflowMarkComplete(taskId: String, userNotes: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val result = app.workflowCoordinator.completeWaitingTask(taskId, userNotes)
            val summary = buildSummary(
                prompt = "Workflow $taskId",
                outcome = Outcome.SUCCESS,
                steps = 1,
                durationMs = 0L,
                provider = "WORKFLOW",
                evidence = if (userNotes.isNotBlank()) listOf(EvidenceItem(label = userNotes, met = true)) else emptyList()
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                updateToIdle {
                    it.copy(
                        lastSummary = summary,
                        statusMessage = "Workflow marked complete",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
        }
    }

    fun onWorkflowCancel(taskId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = app.workflowCoordinator.cancelWorkflow(taskId)
            val summary = buildSummary(
                prompt = "Workflow $taskId",
                outcome = Outcome.STOPPED,
                steps = 1,
                durationMs = 0L,
                provider = "WORKFLOW",
                evidence = emptyList(),
                reason = "Cancelled by user"
            )
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                updateToIdle {
                    it.copy(
                        lastSummary = summary,
                        statusMessage = "Workflow cancelled",
                        lastProviderUsed = "WORKFLOW"
                    )
                }
            }
        }
    }

    /**
     * Entry point when user submits a natural language command.
     */
    fun submitTask(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return

        // Reject if another task is active to prevent double submission
        if (_uiState.value.isRunning && activeTaskJob?.isActive == true) {
            app.eventLogger.warn("AniobViewModel", "Cannot start new task while a task is running")
            return
        }

        val taskId = "task_${System.currentTimeMillis()}"
        val leaseGen = app.taskOwner.acquireLease(taskId)
        val generation = ++activeGeneration
        val task = AniobTask(id = taskId, rawPrompt = trimmed)

        // Start task-scoped safety and retry ledgers. OFF is a persisted user choice and stays OFF.
        app.approvalController.clearAll()
        targetMissLedger.beginTask(taskId)
        taskConsent = com.aniob.core.safety.TaskConsent.NONE
        assumedMidRunAsk = false
        warmupJob?.cancel()
        warmupJob = null
        terminationStage.reset()
        pauseRequested = false
        localFailCount = 0
        verifiedActions.clear()

        val userMessage = ChatMessage(
            id = "msg_user_${java.util.UUID.randomUUID()}",
            role = "user",
            content = trimmed
        )

        _uiState.update {
            it.copy(
                activeTask = task,
                isRunning = true,
                currentStep = 0,
                statusMessage = "Analyzing task...",
                chatMessages = it.chatMessages + userMessage,
                steps = emptyList(),
                stepEvents = emptyList(),
                lastNarration = "",
                confirmRequest = null,
                pendingInputPrefill = null
            )
        }

        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            app.taskOwner.attachJob(leaseGen, currentCoroutineContext()[kotlinx.coroutines.Job]!!)

            // Resolve the effective goal and direct device/app capabilities before any chooser,
            // clarification, Doctor advice, or model route.
            val effectivePrompt = resolveContextualGoal(trimmed)
            val effectiveTask = task.copy(clarifiedGoal = effectivePrompt)
            _uiState.update {
                it.copy(
                    activeTask = effectiveTask
                )
            }
            val resolvedShortcut = AniobIntentResolver.resolve(effectivePrompt, ::isLaunchablePackage)
            val workflowDecision = if (resolvedShortcut == null) {
                com.aniob.core.workflow.WorkflowRouter.route(
                    effectivePrompt,
                    isAppLaunchable = ::isLaunchablePackage
                )
            } else {
                com.aniob.core.workflow.WorkflowDecision.DelegateToExisting(effectivePrompt)
            }
            when (workflowDecision) {
                is com.aniob.core.workflow.WorkflowDecision.DirectAction -> {
                    executeOwnedWorkflowAction(
                        taskId = taskId,
                        leaseGen = leaseGen,
                        prompt = effectivePrompt,
                        action = workflowDecision.action
                    )
                    return@launch
                }
                is com.aniob.core.workflow.WorkflowDecision.NeedsChooser -> {
                    app.workflowCoordinator.updateState(
                        com.aniob.core.workflow.WorkflowState.NeedsChoice(workflowDecision.candidates, workflowDecision.prompt)
                    )
                    updateToIdle {
                        it.copy(
                            statusMessage = if (workflowDecision.candidates.isNotEmpty() &&
                                workflowDecision.candidates.all { it.category == com.aniob.core.workflow.DestinationCategory.APP }
                            ) "Please choose an installed app" else "Please choose a destination"
                        )
                    }
                    return@launch
                }
                is com.aniob.core.workflow.WorkflowDecision.NeedsClarification -> {
                    val asstMsg = ChatMessage(
                        id = "msg_clarify_${System.currentTimeMillis()}",
                        role = "assistant",
                        content = workflowDecision.question,
                        provider = "WORKFLOW"
                    )
                    updateToIdle {
                        it.copy(
                            chatMessages = it.chatMessages + asstMsg,
                            statusMessage = "Clarification needed"
                        )
                    }
                    return@launch
                }
                is com.aniob.core.workflow.WorkflowDecision.DelegateToExisting -> {
                    // Fallthrough to existing automation / Q&A
                }
            }

            // Step 0: Pre-route supported shortcuts before general Q&A classification.
            var intent = if (resolvedShortcut != null) {
                AniobIntent.DEVICE_AUTOMATION
            } else {
                AniobTaskIntentClassifier.classify(effectivePrompt)
            }

            // BUG-02 last line of defence: a hardware toggle that dodged both rungs must NEVER be
            // routed to the answer path — that path records generated text as an executed command.
            if (intent != AniobIntent.DEVICE_AUTOMATION &&
                AniobTaskIntentClassifier.looksLikeDeviceToggle(effectivePrompt)
            ) {
                app.eventLogger.warn(
                    "AniobViewModel",
                    "Hardware toggle escaped classification; re-routing to device automation: $trimmed"
                )
                intent = AniobIntent.DEVICE_AUTOMATION
            }

            // Fail CLOSED: a toggle with no device noun and no device in recent user context is
            // ambiguous. Answering it with prose would recreate exactly the report's false success,
            // so ask one question instead. Clarification is cheaper than confident fiction.
            if (intent != AniobIntent.DEVICE_AUTOMATION &&
                AniobTaskIntentClassifier.looksLikeBareToggle(trimmed) &&
                effectivePrompt == trimmed
            ) {
                val clarify = ChatMessage(
                    id = "msg_clarify_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = "Which device should I switch? I heard an on/off command but no device " +
                        "(flashlight, Wi-Fi, Bluetooth…).",
                    provider = "CLARIFY",
                    badge = "❓ Clarification"
                )
                updateToIdle {
                    it.copy(
                        chatMessages = it.chatMessages + clarify,
                        statusMessage = "Clarification needed",
                        lastRoutingReason = "Ambiguous device toggle — asked instead of guessing"
                    )
                }
                return@launch
            }

            if (intent != AniobIntent.DEVICE_AUTOMATION) {
                _uiState.update {
                    it.copy(
                        statusMessage = "Answering query...",
                        isStreaming = true,
                        streamingBubbleText = "",
                        lastProviderUsed = "EXTERNAL_AI"
                    )
                }
                // Do not initialize native memory for an explicitly cloud-only request.
                if (_uiState.value.autoRouterMode != "cloud-only") refreshLocalEngine()
                val trigger = AniobExternalAiTrigger(
                    cloudProvider = if (isCloudConfigured()) getCloudProvider(sessionId = taskId) else null,
                    localProvider = localLlmProvider,
                    sharedKnowledgeStore = sharedKnowledgeStore
                )
                val powerState = AniobDeviceTelemetry.getRealState(app)
                val prefs = app.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
                val autoMode = prefs.getString("autorouter_mode", "auto") ?: "auto"

                val result = trigger.query(
                    prompt = trimmed,
                    intent = intent,
                    powerState = powerState,
                    autoMode = autoMode,
                    onDelta = { delta ->
                        if (generation == activeGeneration) {
                            _uiState.update { it.copy(streamingBubbleText = it.streamingBubbleText + delta, isStreaming = true) }
                        }
                    }
                )
                if (generation != activeGeneration || !currentCoroutineContext().isActive) return@launch
                val answered = result is com.aniob.core.external.ExternalAiResult.Success
                val assistantMessage = ChatMessage(
                    id = "msg_asst_${java.util.UUID.randomUUID()}",
                    role = "assistant",
                    content = if (result is com.aniob.core.external.ExternalAiResult.Error && result.provider != "LOCAL_SLM")
                        "I couldn't get an answer. Check your connection or cloud settings, then try again."
                    else result.answer,
                    badge = if (answered) "💬 AI Answer" else "Answer unavailable",
                    provider = result.provider,
                    retryPrompt = if (answered) null else trimmed
                )
                updateToIdle {
                    it.copy(
                        isStreaming = false,
                        streamingBubbleText = "",
                        statusMessage = if (answered) "Ready" else "Could not answer — try again",
                        lastRoutingReason = if (answered) "Answered via ${result.provider}" else "Answer unavailable",
                        chatMessages = it.chatMessages + assistantMessage,
                        lastProviderUsed = result.provider
                    )
                }

                // Record Session Telemetry for analytics and audit
                val tokenEstimate = if (result.provider == "VAULT" || result.provider == "NO_PROVIDER") 0
                    else (result.answer.length / 4).coerceAtLeast(1)
                app.metricsCollector.recordSession(
                    taskId = taskId,
                    prompt = trimmed,
                    isSuccess = result is com.aniob.core.external.ExternalAiResult.Success,
                    steps = 0,
                    durationMs = result.latencyMs,
                    tokensUsed = tokenEstimate,
                    providerUsed = result.provider,
                    // BUG-01: this metric records ANSWER DELIVERY, never a device change — say so.
                    decisionReason = "Conversational answer (asserts no device change) for ${intent.name}"
                )
                recordSealedOutcome(
                    taskId,
                    if (answered) SealedOutcome.answered("Answered via ${result.provider}")
                    else SealedOutcome.failed("Answer unavailable")
                )
                return@launch
            }

            // Step 0.5 (audit): say what was understood, then check the plan is feasible BEFORE anything is dispatched.
            setPhase(AgentPhase.UNDERSTANDING)
            val understood = AniobPromptUnderstanding.understand(effectivePrompt)
            if (understood.needsEcho) {
                val echo = ChatMessage(
                    id = "msg_echo_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = understood.echo,
                    provider = "UNDERSTANDING",
                    badge = "\uD83D\uDC42 Understood"
                )
                _uiState.update { it.copy(chatMessages = it.chatMessages + echo) }
            }
            when (val feasibility = AniobFeasibility.assess(planStepsFor(effectivePrompt), capabilityEnvelope())) {
                is Feasibility.Infeasible -> {
                    val hint = feasibility.enable?.let { " To fix: $it." }.orEmpty()
                    recordSealedOutcome(task.id, SealedOutcome.failed(feasibility.reason + hint))
                    val note = ChatMessage(
                        id = "msg_infeasible_${System.currentTimeMillis()}",
                        role = "assistant",
                        content = feasibility.reason + hint,
                        provider = "FEASIBILITY",
                        badge = "\u26D4 Not started"
                    )
                    setPhase(AgentPhase.IDLE)
                    updateToIdle { it.copy(chatMessages = it.chatMessages + note, statusMessage = "Not started") }
                    return@launch
                }
                is Feasibility.DelegateToUser -> {
                    setPhase(AgentPhase.ASKING)
                    _uiState.update {
                        it.copy(
                            grillMeResult = AniobGrillMeResult(needsClarification = true, questions = listOf(feasibility.question)),
                            showGrillMeSheet = true,
                            statusMessage = feasibility.whatUserMustDo
                        )
                    }
                    return@launch
                }
                Feasibility.Feasible -> Unit
            }

            // Step 1: ask everything that is needed ONCE, before the task starts. A direct-intent task
            // never thinks (no model call); payments, secrets and deletion are still asked, because
            // that check is deterministic. Independent start-up work runs while the user answers.
            val intentBased = resolvedShortcut != null
            startRouteWarmups(intentBased)
            val (plan, doctorAtStart) = com.aniob.core.concurrency.AniobParallel.both(
                left = {
                    com.aniob.core.grillme.AniobUpfrontPlanner.plan(
                        prompt = effectivePrompt,
                        level = AniobSafetyInterceptor.askLevel,
                        rememberedAnswers = sharedKnowledgeStore.getAll(),
                        intentBased = intentBased
                    ) { p, remembered -> grillMeEngine.evaluateTask(p, remembered) }
                },
                right = { checkDoctorPreflight(effectivePrompt, resolvedShortcut) }
            )
            if (plan.needsAsk) {
                // Questions arrive before any step runs, so the user is looking at the chat: one inline
                // card above the chat bar, no notification, nothing is paused half-way.
                setPhase(AgentPhase.ASKING)
                taskConsent = com.aniob.core.safety.TaskConsent.fromAnswers(emptyMap(), plan.implicitConsent)
                _uiState.update {
                    it.copy(
                        grillMeResult = plan.toResult(),
                        showGrillMeSheet = true,
                        statusMessage = "A quick question before I start"
                    )
                }
                return@launch
            }
            taskConsent = com.aniob.core.safety.TaskConsent.fromAnswers(emptyMap(), plan.implicitConsent)

            // Step 1.5: Doctor Check (computed in parallel with the planning above)
            if (!runDoctorPreflight(effectiveTask, effectivePrompt, resolvedShortcut, doctorAtStart)) {
                return@launch
            }

            // Task is self-contained -> Proceed directly. The finally closes the research run when
            // the pipeline is cancelled (Stop) or throws before its own endTask call.
            try {
                executeTaskPipeline(effectiveTask, generation)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // Stop is a real stop, not a crash
            } catch (failure: Exception) {
                ensureTaskTerminated(effectiveTask, generation, failure)
            } finally {
                publishResearchRun(
                    app.researchTelemetry.endTaskIfActive(
                        failureReason = if (_uiState.value.isRunning) "interrupted" else "stopped"
                    )
                )
                ensureTaskTerminated(effectiveTask, generation, null)
            }
        }
    }

    private fun isLaunchablePackage(packageName: String): Boolean =
        runCatching { app.packageManager.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

    internal fun checkDoctorPreflight(prompt: String): com.aniob.core.tools.DoctorResult =
        checkDoctorPreflight(prompt, AniobIntentResolver.resolve(prompt, ::isLaunchablePackage))

    /**
     * @param resolvedShortcut the shortcut for [prompt], when the caller already resolved it.
     *
     * An **app** shortcut is not an "offline shortcut": it still needs the accessibility service to
     * observe the launch, so it must not be exempt from that preflight check. Only a reflex whose
     * dispatch *is* the outcome (torch, battery, dial, open URL) may skip it.
     */
    private fun checkDoctorPreflight(
        prompt: String,
        resolvedShortcut: ResolvedIntentShortcut?
    ): com.aniob.core.tools.DoctorResult {
        return com.aniob.core.tools.AniobDoctor.preflightCheck(
            taskPrompt = prompt,
            targetPackage = resolvedShortcut?.targetPackage,
            isAccessibilityConnected = AniobAccessibilityService.isServiceConnected,
            modelsDir = modelDownloader.getModelsDir(),
            installedModelId = modelDownloader.getDefaultModelId(),
            isNetworkAvailable = AniobDeviceTelemetry.getRealState(app).isNetworkAvailable,
            autoRouterMode = _uiState.value.autoRouterMode,
            totalRamGb = AniobDeviceTelemetry.getRealState(app).totalRamGb,
            isAppInstalled = { pkg -> app.packageManager.getLaunchIntentForPackage(pkg) != null },
            isOfflineShortcut = resolvedShortcut?.completesOnDispatch == true
        )
    }

    /**
     * Starts independent start-up work while the user reads and answers the questions, so the first
     * model step does not pay for it. Best effort and cancellable: it never fails or delays a task.
     * Direct-intent tasks need neither the gateway nor the on-device engine, so they skip it.
     */
    private fun startRouteWarmups(intentBased: Boolean) {
        warmupJob?.cancel()
        warmupJob = null
        if (intentBased) return
        warmupJob = viewModelScope.launch(Dispatchers.IO) {
            com.aniob.core.concurrency.AniobParallel.both(
                dispatcher = Dispatchers.IO,
                left = { com.aniob.core.concurrency.AniobParallel.bestEffort(WARMUP_BUDGET_MS, Unit) { prewarmGateway() } },
                right = { warmLocalEngineIfLikely() }
            )
        }
    }

    /**
     * Loading an on-device model costs memory and battery, so it is only warmed when the router is
     * likely to use it: local-only, or automatic with no cloud configured. `prepare` is idempotent
     * and locked, so a task that needs the engine meanwhile simply waits for the same load.
     */
    private suspend fun warmLocalEngineIfLikely() {
        val mode = _uiState.value.autoRouterMode
        val likelyLocal = mode == "local-only" || (mode != "cloud-only" && !isCloudConfigured())
        if (!likelyLocal || modelDownloader.getDefaultModelId() == null) return
        runCatching { refreshLocalEngine() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private fun runDoctorPreflight(
        task: AniobTask,
        prompt: String,
        resolvedShortcut: ResolvedIntentShortcut?,
        precomputed: com.aniob.core.tools.DoctorResult? = null
    ): Boolean {
        // Doctor is advisory: it describes unavailable routes but never blocks supported direct
        // intents, another model provider, or the ordinary StepPipeline.
        val doctorResult = precomputed ?: checkDoctorPreflight(prompt, resolvedShortcut)
        val failures = doctorResult.checks.filterNot { it.passed }
        if (failures.isEmpty()) return true

        val capabilityNotes = failures.mapNotNull { check ->
            when (check.name) {
                "accessibility_connected" ->
                    "Screen automation is unavailable because Aniob Accessibility is off. Supported direct intents may still work."
                "target_app_installed" ->
                    "The requested app is not installed or cannot be launched. I won't substitute a different app."
                "model_file_exists" ->
                    "The selected on-device model is unavailable. Aniob can still try supported direct capabilities or a configured cloud route."
                "network_available" ->
                    "You're offline, so cloud-only routes may not work. Supported on-device capabilities can still run."
                "ram_headroom" ->
                    "The selected on-device model may exceed this device's available memory."
                else -> check.reason.takeIf { it.isNotBlank() }
            }
        }.distinct()
        if (capabilityNotes.isNotEmpty()) {
            val note = ChatMessage(
                id = "msg_doctor_advisory_${System.currentTimeMillis()}",
                role = "assistant",
                content = "Capability note: ${capabilityNotes.joinToString(" ")}",
                badge = "Capability note",
                provider = "DOCTOR",
                retryPrompt = prompt
            )
            _uiState.update { it.copy(chatMessages = it.chatMessages + note) }
        }
        app.eventLogger.info(
            "AniobViewModel",
            "Doctor advisory for task ${task.id}: ${failures.joinToString { it.name }}"
        )
        return true
    }

    /**
     * The one hand-back from the question card. [allAnswers] holds clarification answers and consent
     * answers; they are split here so consent only ever feeds [taskConsent] (never the model prompt,
     * the success criteria or the vault) and clarifications go into the clarified goal.
     */
    fun onGrillAnswersSubmitted(allAnswers: Map<String, String>, rememberKeys: Set<String> = emptySet()) {
        val currentTask = _uiState.value.activeTask ?: return
        val (answers, consentAnswers) = com.aniob.core.safety.TaskConsent.split(allAnswers)
        val implicit = taskConsent.granted
        taskConsent = com.aniob.core.safety.TaskConsent.fromAnswers(consentAnswers, implicit)
        // Plan-time feasibility hand-off ("I can't tap the system dialog for you"): the choice is not a task slot.
        answers.entries.firstOrNull { it.key.startsWith("delegate.") }?.let { (_, choice) ->
            when (choice) {
                AniobFeasibility.OPT_CANCEL -> {
                    recordSealedOutcome(currentTask.id, SealedOutcome.stopped("Cancelled at the hand-off"))
                    cancelGrillMe()
                    return
                }
                AniobFeasibility.OPT_WAIT -> {
                    finishDelegated(currentTask, "I can set this up, but the last tap is yours. Tell me with \uD83D\uDC4D when it worked.")
                    return
                }
                else -> Unit // OPT_SKIP: continue with the rest of the plan, without the gated step
            }
        }
        // Persist the answers the user ticked so the question is skipped next time (UX-2).
        rememberKeys.forEach { key ->
            answers[key]?.let { rememberGrillAnswer(key, it) }
        }
        val clarifiedPrompt = currentTask.clarifiedGoal.ifBlank { currentTask.rawPrompt }
        val updatedTask = currentTask.copy(
            clarifiedGoal = clarifiedPrompt,
            grillAnswers = answers,
            status = TaskStatus.RUNNING
        )

        _uiState.update {
            it.copy(
                showGrillMeSheet = false,
                isRunning = true,
                activeTask = updatedTask,
                statusMessage = "Proceeding with clarified goal..."
            )
        }

        val gen = activeGeneration
        activeTaskJob = viewModelScope.launch(Dispatchers.Default) {
            val resolvedShortcut = AniobIntentResolver.resolve(clarifiedPrompt, ::isLaunchablePackage)
            if (!runDoctorPreflight(updatedTask, clarifiedPrompt, resolvedShortcut)) {
                return@launch
            }
            try {
                executeTaskPipeline(updatedTask, gen)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // Stop is a real stop, not a crash
            } catch (failure: Exception) {
                ensureTaskTerminated(updatedTask, gen, failure)
            } finally {
                publishResearchRun(
                    app.researchTelemetry.endTaskIfActive(
                        failureReason = if (_uiState.value.isRunning) "interrupted" else "stopped"
                    )
                )
                ensureTaskTerminated(updatedTask, gen, null)
            }
        }
    }

    /** Explicit cancel from the question card, not a silent dismiss. */
    fun cancelGrillMe() {
        val currentTask = _uiState.value.activeTask
        warmupJob?.cancel()
        warmupJob = null
        taskConsent = com.aniob.core.safety.TaskConsent.NONE
        if (currentTask != null) {
            app.taskOwner.stop(currentTask.id)
            activeTaskJob?.cancel()
            activeTaskJob = null
            app.approvalController.clearForTask(currentTask.id)
            targetMissLedger.clear()
            val prior = _uiState.value.lastSealedOutcome
            val alreadyStopped = _uiState.value.lastSealedTaskId == currentTask.id &&
                prior?.outcome == com.aniob.core.outcome.RunOutcome.STOPPED
            val reason = "Stopped while waiting for clarification. Earlier changes were not undone."
            if (!alreadyStopped) recordSealedOutcome(currentTask.id, SealedOutcome.stopped(reason))
            AniobBackgroundController.onTaskFinished(
                app,
                reason,
                isSuccess = false,
                openChat = false,
                wasStopped = true,
                resultTitle = com.aniob.app.outcome.AniobOutcomeCopy
                    .forTask(Outcome.STOPPED, reason).title
            )
        }
        updateToIdle {
            it.copy(
                showGrillMeSheet = false,
                isStreaming = false,
                streamingBubbleText = "",
                statusMessage = "Task stopped. Earlier changes were not undone."
            )
        }
    }

    fun dismissGrillMe() = cancelGrillMe()

    /**
     * A task ALWAYS ends.
     *
     * Whatever happens inside the pipeline — an unexpected throw, an early return, a route that
     * produced no outcome — this closes the run: the outcome is sealed, the ongoing notification is
     * removed and the chat stops showing the task as working. Without it a single exception left the
     * UI on "working" forever while nothing was running any more.
     *
     * It never touches a superseded generation (a newer task owns the UI) and never rewrites a run
     * the pipeline already closed properly.
     */
    private fun ensureTaskTerminated(task: AniobTask, generation: Long, failure: Throwable?) {
        if (generation != activeGeneration) return
        if (failure == null && !_uiState.value.isRunning) return
        val isCancelled = failure is kotlinx.coroutines.CancellationException
        val rawMessage = failure?.message ?: failure?.javaClass?.simpleName ?: ""
        val humanReason = when {
            isCancelled -> "Task was stopped"
            rawMessage.isNotBlank() -> com.aniob.core.narration.FailureReasonCopy.humanize(rawMessage)
            else -> "The task stopped without reporting a result."
        }
        app.eventLogger.warn("AniobViewModel", "Termination guard for ${task.id}: $humanReason (raw: $rawMessage)")
        if (_uiState.value.lastSealedTaskId != task.id) {
            recordSealedOutcome(task.id, SealedOutcome.failed(humanReason))
        }
        AniobBackgroundController.onTaskFinished(
            app,
            humanReason,
            isSuccess = false,
            openChat = true,
            resultTitle = com.aniob.app.outcome.AniobOutcomeCopy.forTask(Outcome.FAILED, humanReason).title
        )
        val alreadySealed = _uiState.value.lastSealedTaskId == task.id
        updateToIdle {
            it.copy(
                chatMessages = if (alreadySealed) it.chatMessages else it.chatMessages + ChatMessage(
                    id = "msg_terminated_${System.currentTimeMillis()}",
                    role = "assistant",
                    content = humanReason,
                    provider = "TERMINATION",
                    badge = if (isCancelled) "Stopped" else "Failed",
                    retryPrompt = if (isCancelled) null else task.rawPrompt
                ),
                isStreaming = false,
                streamingBubbleText = "",
                showGrillMeSheet = false,
                statusMessage = humanReason
            )
        }
    }

    /**
     * Executes the task through the ExecutionRouter ladder:
     * INTENT -> FASTPATH -> SKILL -> LOCAL_SLM -> OMNIROUTE_CLOUD
     */
    private suspend fun executeTaskPipeline(task: AniobTask, taskGeneration: Long = activeGeneration) {
        if (taskGeneration != activeGeneration) return
        // A release run always uses the full-capability condition; ablations are developer-only.
        val researchControlsEnabled = com.aniob.app.AniobBuild.isDeveloperBuild(app)
        val researchMode = if (researchControlsEnabled) app.researchTelemetry.mode else AblationMode.DEFAULT
        val researchPass = if (researchControlsEnabled) app.researchTelemetry.studyPass else StudyPass.MEASURED
        applyAblationMode(researchMode)
        rememberCacheLoadJob?.join()
        app.researchTelemetry.startTask(
            task.clarifiedGoal.ifBlank { task.rawPrompt },
            researchMode,
            researchPass
        )
        persistRunningMarker(task, 0, "Task started")

        watchdog.reset()
        observationPolicy.reset()
        executionTracker.clear()
        com.aniob.core.exec.AniobErrorTaxonomy.reset()

        // Honest completion (no fake SUCCESS): extract deterministic success criteria BEFORE step 0
        // so a provisional Finish can be verified against evidence. Without criteria a Finish could
        // be declared on an unchanged screen.
        val appCatalog = try {
            com.aniob.core.knowledge.AniobAppCatalog.getInstance().getAll()
                .filter { it.appName.isNotBlank() }
                .associate { entry ->
                    entry.appName.lowercase() to entry.packageName
                }
        } catch (_: Exception) {
            emptyMap()
        }
        // The goal the runner acts on MUST carry the resolved device noun; otherwise the criteria
        // extractor cannot see a torch/install assertion for a context-resolved bare toggle.
        val resolvedGoalPrompt = resolveContextualGoal(
            task.clarifiedGoal.ifBlank { task.rawPrompt }
        )
        val resolvedCriteria = task.successCriteria ?: AniobTaskIntentClassifier.extractCriteria(
            prompt = resolvedGoalPrompt,
            grillAnswers = task.grillAnswers,
            appCatalog = appCatalog
        )
        var taskContext = com.aniob.core.domain.TaskContext(
            instruction = resolvedGoalPrompt,
            successCriteria = resolvedCriteria,
            grillAnswers = task.grillAnswers,
            taskId = task.id
        )

        AniobAccessibilityService.isTaskActive = true
        val startTime = System.currentTimeMillis()
        var currentStep = 0
        var totalTokens = 0
        var primaryProvider = "INTENT"
        var decisionReason = "Direct intent shortcut"
        var screenReads = 0
        var actionsDispatched = 0
        var escalations = 0
        var finishRejections = 0
        verifiedActions.clear()

        // Prefrontal-cortex condensation: TaskProgress summary (not full history) is
        // threaded through the ladder so the LLM sees a pure-text progress summary.
        var taskProgress: TaskProgress? = null

        // Hippocampus: short-term episodic stream for this task ("hippocampus -> cortex" consolidation)
        hippocampusTracker.beginTask(task.id, task.clarifiedGoal)

        // Learned state (skills, FastPath trajectories) hydrates in parallel with the first
        // capture instead of in front of it. A cold cache must not delay step 0; the first task
        // after install simply plans from the model.
        val hydration = if (researchMode.skillsEnabled && _uiState.value.fastPathEnabled) {
            viewModelScope.async(Dispatchers.Default) {
                learningPipeline.awaitHydration(HYDRATION_BUDGET_MS)
            }
        } else {
            null
        }
        // Warm the gateway again at task start: the user may have been idle long enough for the
        // connection pool to drop the socket.
        viewModelScope.launch(Dispatchers.IO) { prewarmGateway() }

        // The progress notification starts at once, but the chat stays in front: the user is only
        // sent away once the agent has decided this task must be driven on another app's screen.
        val runPrompt = task.clarifiedGoal.ifBlank { task.rawPrompt }
        AniobBackgroundController.onTaskStarted(app, runPrompt)

        val accessibility = AniobAccessibilityService.instance
        // A direct intent or app launch dispatches its own foreground app (or completes on dispatch),
        // so it never needs Aniob's window out of the way and must not dump the user onto the home screen.
        val shortcut = AniobIntentResolver.resolve(runPrompt, ::isLaunchablePackage)
        val appPlan = AniobLaunchHub.plan(runPrompt, isLaunchable = ::isLaunchablePackage)
        if (shortcut == null && appPlan == null) {
            AniobBackgroundController.backgroundForAutomation(app)
            awaitAppBackgrounded(accessibility)
        }

        var screenBefore: AniobScreenState? = accessibility?.captureCurrentScreenState()

        fun recordTrajectory(
            stepIndex: Int,
            observation: String,
            action: AniobAction,
            provider: String,
            latencyMs: Long,
            screenHash: String,
            verified: Boolean,
            textEvidence: String? = null,
            targetBounds: AniobRect? = null
        ) {
            hippocampusTracker.recordStep(
                AniobHippocampusTracker.HippocampusStep(
                    stepIndex = stepIndex,
                    observation = observation,
                    thought = action.thought,
                    action = action,
                    latencyMs = latencyMs,
                    screenHash = screenHash,
                    provider = provider,
                    verified = verified,
                    textEvidence = textEvidence,
                    targetBounds = targetBounds
                )
            )
            executionTracker.recordEvent(
                AniobExecutionTracker.TrajectoryEvent(
                    stepIndex = stepIndex,
                    observationSummary = observation,
                    thought = action.thought,
                    toolName = action.toolName,
                    action = action,
                    latencyMs = latencyMs,
                    outcome = if (verified) "SUCCESS" else "FAILURE",
                    isVerified = verified,
                    textEvidence = textEvidence,
                    targetBounds = targetBounds
                )
            )
            app.eventLogger.logStep(
                com.aniob.app.metrics.EventLogger.TrajectoryStep(
                    stepIndex = stepIndex,
                    observation = observation,
                    thought = action.thought,
                    action = action,
                    latencyMs = latencyMs,
                    screenHash = screenHash,
                    provider = provider,
                    textEvidence = textEvidence,
                    targetBounds = targetBounds
                )
            )
        }

        val stepPipe = StepPipeline(
            limits = agentLimits,
            learning = learningPipeline,
            reflectionAgent = reflectionAgent,
            recordStep = { stepRecord ->
                val node = stepRecord.resolvedNode
                val obs = if (node != null) {
                    "node ${node.id} (${node.text.take(15)})"
                } else {
                    "screen ${screenBefore?.treeHash?.take(6) ?: "none"} after ${stepRecord.action.toolName}"
                }
                recordTrajectory(
                    stepIndex = stepRecord.stepIndex,
                    observation = obs,
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    screenHash = screenBefore?.treeHash ?: "",
                    verified = stepRecord.verified,
                    textEvidence = stepRecord.textEvidence,
                    targetBounds = stepRecord.targetBounds
                )
                publishStep(
                    stepIndex = stepRecord.stepIndex,
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    verified = stepRecord.verified,
                    failureReason = stepRecord.failureReason,
                    node = stepRecord.resolvedNode
                )
                val uiStep = AniobStepRecord(
                    stepIndex = stepRecord.stepIndex,
                    screenHash = screenBefore?.treeHash ?: "",
                    action = stepRecord.action,
                    provider = stepRecord.provider,
                    latencyMs = stepRecord.latencyMs,
                    tokensUsed = stepRecord.tokensUsed,
                    verifiedSuccess = stepRecord.verified,
                    failureReason = stepRecord.failureReason,
                    textEvidence = stepRecord.textEvidence,
                    targetBounds = stepRecord.targetBounds
                )
                addStepRecord(uiStep)
            },
            // Dispatch already polled for the transition (FastVerify); a second fixed wait here
            // would only re-add the latency this change removes.
            settle = { _, capture -> capture() },
            // Hardware/package evidence for Finish verification. Unknown probes stay inconclusive.
            deviceState = { criteria -> probeDeviceState(criteria) }
        )

        val maxSteps = agentLimits.maxStepsPerTask
        // A bitmap taken by the tier-2 verify is reused by the next Reason step instead of being
        // captured twice. The structural identity it belongs to is stored with it, so a bitmap
        // can never leak onto a different screen; cleared once consumed.
        var pendingVerifyScreenshot: Pair<String, String>? = null
        // Verified transitions of this run, persisted only if the task ends successfully.
        val rememberTransitions = mutableListOf<RememberTransition>()
        // Screen on which the last replay changed nothing; the cache is skipped there next time.
        val stalledReplayIdentities = mutableSetOf<String>()
        var finalOutcome: Outcome? = null
        var terminalReason: String = ""
        var stepResult: StepResult? = null
        // An intent shortcut that carried the whole task is a finished run: the criteria re-check
        // below must not turn "Wi-Fi settings opened" into an unverified or failed run.
        var completedByIntent = false
        // The verdict the shortcut was judged by, so a success carries the evidence it was
        // actually checked against instead of being re-evaluated on a later screen.
        var intentVerdict: List<com.aniob.core.domain.CriterionEvaluation>? = null
        // A shortcut this run switched off (an action the device does not have) is restored when
        // the run ends, so one failed dispatch cannot degrade every later task in the batch.
        val shortcutsWereEnabled = executionRouter.intentShortcutsEnabled
        // Screen identities that already spent their one pre-observe scroll in this run.
        val preObservedScreens = HashSet<String>()
        // Viewport merged after that scroll, handed to the next planning pass.
        var preObservedScreen: AniobScreenState? = null
        // Set once a burst's target vanished mid-burst. The screen in front is dynamic (search
        // results re-indexing, animated lists), so the rest of the run plans one action at a time
        // from a fresh observation instead of trusting a multi-action plan again.
        var burstsSuspended = false
        // The part of the last plan that named elements the current screen did not show yet.
        // Rendered into the next planning request as context, cleared as soon as it is used.
        var deferredPlanHint: String? = null
        // Consecutive plans that could not execute even their first member: the screen is not
        // settling, so the rest of the run plans one action at a time.
        var burstZeroProgressReplans = 0

        val setupDurationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        app.researchTelemetry.setSetupLatency(setupDurationMs)

        try {
            while (currentStep < maxSteps && _uiState.value.isRunning) {
                // Honest default for each step: no model, until a branch below really calls one.
                setPhase(AgentPhase.PLANNING, ThinkSource.RULES)
                if (taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
                    finalOutcome = Outcome.STOPPED
                    break
                }
                val wasPaused = pauseRequested
                awaitIfPaused()
                if (!_uiState.value.isRunning || taskGeneration != activeGeneration || !currentCoroutineContext().isActive) {
                    finalOutcome = Outcome.STOPPED
                    break
                }

                _uiState.update { it.copy(currentStep = currentStep + 1) }

                // Step 0 already has a capture from the moment the task started; re-reading the
                // same window only costs latency. Applies only while it is still fresh and the
                // pause/takeover path is not involved.
                // A pre-observe scroll already captured the viewport it revealed. Re-reading the
                // window here would pay for the same hierarchy twice and lose the merged view.
                val mergedObservation = preObservedScreen?.takeIf {
                    !wasPaused && System.currentTimeMillis() - it.timestamp < PRE_OBSERVE_FRESH_MS
                }
                preObservedScreen = null
                val reuseStartCapture = currentStep == 0 && !wasPaused && screenBefore != null &&
                    System.currentTimeMillis() - screenBefore.timestamp < START_CAPTURE_FRESH_MS
                // Reuse the last verified planning snapshot only when the observation policy allows;
                // every dispatch still re-reads/revalidates its target through the shared pipeline.
                val captured = if (mergedObservation != null) {
                    mergedObservation
                } else if (reuseStartCapture) {
                    // Step 0 already holds a capture from just before the prompt was typed.
                    screenBefore!!
                } else if (wasPaused || observationPolicy.shouldObserve() || screenBefore == null) {
                    screenReads++; screenCaptureCount++; app.researchTelemetry.recordScreenCapture()
                    val fresh = accessibility?.captureCurrentScreenState() ?: AniobScreenState(
                        packageName = "com.aniob.app",
                        treeHash = "mock_hash_${currentStep}",
                        nodes = listOf(
                            AniobNode(id = 1, className = "TextView", text = "Settings", isClickable = true, bounds = AniobRect(50, 100, 300, 180)),
                            AniobNode(id = 2, className = "Button", text = "Search", isClickable = true, bounds = AniobRect(50, 200, 300, 280))
                        )
                    )
                    if (wasPaused && screenBefore != null && fresh.treeHash != screenBefore.treeHash) {
                        onTakeoverCompleted()
                    }
                    fresh
                } else {
                    screenBefore
                }

                // Session validation: do not trust a cached app session blindly.
                val foregroundBefore = accessibility?.currentForegroundPackage()
                val reuseCheck = appSessionManager.ensureReusableOrInvalidate(
                    packageName = captured.packageName,
                    liveForegroundPackage = foregroundBefore,
                    liveScreenState = captured
                )
                var currentScreen = captured
                val liveService = accessibility
                if (!reuseCheck.valid && liveService != null) {
                    if (foregroundBefore == "com.aniob.app") {
                        val isOpeningApp = currentStep == 0 && (shortcut != null || appPlan != null)
                        if (!isOpeningApp) {
                            app.eventLogger.info("AniobViewModel", "Aniob is still foregrounded; minimizing to reveal target app")
                            AniobBackgroundController.currentActivity?.runOnUiThread {
                                AniobBackgroundController.currentActivity?.moveTaskToBack(true)
                            }
                            delay(600)
                            currentScreen = liveService.captureCurrentScreenState()
                            screenReads++; screenCaptureCount++; app.researchTelemetry.recordScreenCapture()
                        }
                    } else if (foregroundBefore != null && foregroundBefore != captured.packageName) {
                        app.eventLogger.info(
                            "AniobViewModel",
                            "Session invalid (${reuseCheck.reason}) - cold starting $foregroundBefore"
                        )
                        executeActionSync(liveService, AniobAction.OpenApp(packageName = foregroundBefore))
                        delay(800)
                        currentScreen = liveService.captureCurrentScreenState()
                        screenReads++; screenCaptureCount++; app.researchTelemetry.recordScreenCapture()
                    } else {
                        app.eventLogger.info(
                            "AniobViewModel",
                            "Session tree stale (${reuseCheck.reason}) - refreshing capture"
                        )
                        currentScreen = liveService.captureCurrentScreenState()
                        screenReads++; screenCaptureCount++; app.researchTelemetry.recordScreenCapture()
                    }
                }
                appSessionManager.onSessionScreenUpdated(currentScreen.packageName, currentScreen)

                // Evaluate Execution Ladder with real power state and on-device model status
                val powerState = AniobDeviceTelemetry.getRealState(app)
                val resolvedModelId = modelDownloader.getDefaultModelId()
                val resolvedFileExists = AniobModelFiles.isInstalled(modelDownloader.getModelsDir(), resolvedModelId)
                val effectiveModelId = resolvedModelId.takeIf { resolvedFileExists }
                val isModelFileMissing = !resolvedFileExists

                if (currentStep == 0) {
                    hydration?.await()
                }

                val userRouting = _uiState.value.autoRouterMode.lowercase()
                val planLadder = {
                    executionRouter.planStep(
                        taskPrompt = task.clarifiedGoal.ifBlank { task.rawPrompt },
                        screenState = currentScreen,
                        stepIndex = currentStep,
                        taskSignature = task.rawPrompt.lowercase(),
                        powerState = powerState,
                        installedModelId = effectiveModelId,
                        lastLocalFailCount = localFailCount,
                        isModelFileMissing = isModelFileMissing,
                        previousProgress = taskProgress,
                        userRoutingMode = userRouting,
                        lastObservedResult = if (currentStep > 0) "Step ${currentStep - 1} observed" else null
                    )
                }
                var ladderResult = planLadder()
                // A replay that left the screen exactly as it found it must not be replayed again
                // on that same screen: the cache would keep dispatching the same action. Re-plan
                // once with the cache off so the model gets the step (the documented fallback).
                if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.RememberReplay &&
                    FastVerify.screenIdentity(currentScreen) in stalledReplayIdentities
                ) {
                    app.eventLogger.info("AniobViewModel", "Remember replay made no progress; falling back to the model")
                    app.researchTelemetry.recordReplayDiverged()
                    executionRouter.rememberReplayEnabled = false
                    ladderResult = planLadder()
                    executionRouter.rememberReplayEnabled =
                        researchMode.rememberReplay && _uiState.value.fastPathEnabled
                }
                if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch) {
                    taskProgress = ladderResult.progress ?: taskProgress
                }

                var proposal: StepPipeline.Proposal
                var targetNode: AniobNode? = null
                // Filled by a model burst or a remember-cache replay; executed below.
                var plannedBurst: ActionBurst? = null
                when (ladderResult) {
                    is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                        primaryProvider = "INTENT"
                        decisionReason = ladderResult.reason
                        app.researchTelemetry.recordShortcutAttempt(attempted = true)
                        val shortcut = ladderResult.shortcut
                        _uiState.update { it.copy(statusMessage = "Opening with a system intent...") }
                        // The intent is fired before any model work. A partial shortcut (an alarm
                        // editor, a prefilled contact, a settings panel) leaves the agent on the
                        // screen it opened, so the next step plans from there instead of from the
                        // launcher. A shortcut that claims the whole task still has to be seen on
                        // the screen before it may close the run.
                        if (currentStep == 0) {
                            val beforePackage = accessibility?.currentForegroundPackage() ?: screenBefore?.packageName
                            val deviceCommand = AniobReflexResolver.commandOf(shortcut)
                            val deviceArgs = AniobReflexResolver.argsOf(shortcut)
                            val dispatched = if (deviceCommand != null) {
                                runDeviceShortcut(deviceCommand, deviceArgs)
                            } else {
                                dispatchSystemIntent(shortcut)
                            }
                            if (dispatched) {
                                actionsDispatched++
                                app.researchTelemetry.recordStep()
                                // A silent device command (volume, torch, pause...) changes nothing on
                                // screen: waiting for a window change would burn the whole settle budget.
                                val sequenceWaitMs = deviceArgs["then_delay_ms"]?.toLongOrNull() ?: 0L
                                val landed = if (deviceCommand != null && deviceCommand.completesOnDispatch) {
                                    // A decomposed sequence ("on … off after 5s") finishes AFTER the
                                    // dispatch. Judging it immediately would read the wrong state
                                    // and mislabel a correct run, so wait out the scheduled leg.
                                    if (sequenceWaitMs > 0L) delay(sequenceWaitMs + 400L)
                                    null
                                } else {
                                    awaitIntentScreen(beforePackage, INTENT_SETTLE_MS, shortcut.targetPackage)
                                }
                                screenBefore = landed ?: screenBefore
                                if (shortcut.requiresObservation) {
                                    if (landed != null) {
                                        app.researchTelemetry.markIntentShortcutUsed()
                                    } else {
                                        app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Screen did not land")
                                    }
                                    currentStep++
                                    continue
                                }
                                // The intent claims to be the whole task. Prove it on the screen: a
                                // success nobody looked at is not a success this app reports.
                                // `currentStep + 1` because this dispatch already happened; the
                                // criterion set includes a min-steps check that would fail otherwise.
                                val screenVerdict = if (resolvedCriteria?.hasObservableCheck == true) {
                                    landed?.let { resolvedCriteria.evaluateCriteria(it, currentStep + 1) }.orEmpty()
                                } else {
                                    emptyList()
                                }
                                // World-state assertions (torch) are probed against the hardware —
                                // no screen could ever prove them (BUG-06).
                                val verdict = screenVerdict + evalDeviceAssertions(resolvedCriteria)
                                when {
                                    verdict.isNotEmpty() && verdict.all {
                                        it.state == com.aniob.core.domain.CriterionState.PASSED
                                    } -> {
                                        app.researchTelemetry.markIntentShortcutUsed()
                                        completedByIntent = true
                                        intentVerdict = verdict
                                        finalOutcome = Outcome.SUCCESS
                                        terminalReason = shortcut.label?.let { "Opened $it" }
                                            ?: "System intent dispatched"
                                        break
                                    }
                                    verdict.isNotEmpty() -> {
                                        app.eventLogger.warn(
                                            "AniobViewModel",
                                            "Intent ${shortcut.action} landed but the criteria are not met; continuing"
                                        )
                                        app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Criteria not met")
                                        currentStep++
                                        continue
                                    }
                                    shortcut.completesOnDispatch && (deviceCommand?.completesOnDispatch == true || landed != null) && resolvedCriteria?.hasObservableCheck != true -> {
                                        // A system reflex (alarm/timer with SKIP_UI, torch, battery, dial,
                                        // URL, a settings panel that is the whole request): Android resolved
                                        // the intent or the system service accepted the call, and no
                                        // interactive screen is left to read. The dispatch IS the
                                        // verification, so the run is a verified success; recording it as
                                        // UNVERIFIED made research telemetry count a clean run as failed.
                                        app.researchTelemetry.markIntentShortcutUsed()
                                        completedByIntent = true
                                        finalOutcome = Outcome.SUCCESS
                                        terminalReason = shortcut.label?.let { "Dispatched $it" }
                                            ?: "System intent dispatched"
                                        break
                                    }
                                    else -> {
                                        // Nothing machine-checkable and not a finished reflex (an editor
                                        // that was only opened): honest, and never a claimed success.
                                        completedByIntent = true
                                        finalOutcome = Outcome.UNVERIFIED
                                        terminalReason = shortcut.label?.let { "Dispatched $it" }
                                            ?: "System intent dispatched without a checkable criterion"
                                        break
                                    }
                                }
                            }
                            app.eventLogger.warn(
                                "AniobViewModel",
                                "System intent ${shortcut.action} could not be dispatched; planning without shortcuts"
                            )
                            app.researchTelemetry.recordShortcutAttempt(attempted = true, degraded = true, reason = "Dispatch failed")
                            executionRouter.intentShortcutsEnabled = false
                            continue
                        }
                        proposal = StepPipeline.Proposal.DirectIntent(
                            action = AniobAction.Finish(summary = "Intent dispatched: ${shortcut.targetPackage}"),
                            label = "INTENT"
                        )
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.AppLaunch -> {
                        primaryProvider = "APP_LAUNCH"
                        decisionReason = ladderResult.reason
                        app.researchTelemetry.recordShortcutAttempt(attempted = true)
                        _uiState.update {
                            it.copy(
                                statusMessage = "Opening ${ladderResult.appName}...",
                                lastRoutingReason = ladderResult.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }
                        proposal = StepPipeline.Proposal.Model(
                            AniobAction.OpenApp(
                                packageName = ladderResult.packageName,
                                // Explicit component when the registry knows it: no chooser sheet,
                                // and the verifier can check the package it actually expects.
                                activityName = AniobLaunchHub.activityClassName(ladderResult.launchActivity),
                                thought = "Open ${ladderResult.appName}"
                            ),
                            primaryProvider
                        )
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.RememberReplay -> {
                        primaryProvider = "REMEMBER"
                        decisionReason = ladderResult.reason
                        app.researchTelemetry.recordRememberReplay(ladderResult.actions.size)
                        _uiState.update {
                            it.copy(
                                statusMessage = "Replaying ${ladderResult.actions.size} remembered action(s)...",
                                lastRoutingReason = ladderResult.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }
                        // The replay is dispatched like a burst: every action is re-grounded on the
                        // live window, and the last one keeps the normal step pipeline so Verify runs.
                        val replayActions = ladderResult.actions.take(ActionBurst.MAX_ACTIONS)
                        plannedBurst = ActionBurst(
                            thought = "remembered ${replayActions.size} action(s)",
                            isComplete = false,
                            actions = replayActions
                        )
                        proposal = StepPipeline.Proposal.Model(replayActions.last(), primaryProvider)
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.FastPathStep -> {
                        primaryProvider = "FASTPATH"
                        decisionReason = ladderResult.reason
                        app.researchTelemetry.recordFastpathStep()
                        _uiState.update { it.copy(statusMessage = "FastPath replay step ${currentStep + 1}...") }
                        proposal = StepPipeline.Proposal.FastPath(ladderResult.action)
                        targetNode = ladderResult.action.semanticTarget()?.let {
                            com.aniob.core.execution.AniobActionExecutor.resolveTarget(ladderResult.action, currentScreen)?.node
                        }
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution -> {
                        primaryProvider = "SKILL"
                        decisionReason = ladderResult.reason
                        app.researchTelemetry.recordSkillUsed(ladderResult.skill.name)
                        _uiState.update { it.copy(statusMessage = "Executing Skill '${ladderResult.skill.name}' step ${currentStep + 1}...") }
                        proposal = StepPipeline.Proposal.Skill(
                            action = ladderResult.action,
                            skillName = ladderResult.skill.name,
                            riskTier = ladderResult.skill.riskTier,
                            requiresConfirmation = ladderResult.skill.confirmationRequired
                        )
                        targetNode = ladderResult.action.semanticTarget()?.let {
                            com.aniob.core.execution.AniobActionExecutor.resolveTarget(ladderResult.action, currentScreen)?.node
                        }
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.CannotProceed -> {
                        primaryProvider = "NONE"
                        decisionReason = ladderResult.reason
                        _uiState.update {
                            it.copy(
                                statusMessage = "Cannot proceed: ${ladderResult.reason}",
                                lastRoutingReason = ladderResult.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }
                        proposal = StepPipeline.Proposal.Model(
                            AniobAction.Fail(ladderResult.reason),
                            primaryProvider
                        )
                    }
                    is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch -> {
                        // A plan that was held back last turn describes what the model expected to find
                        // after the actions that did run; it is handed to this planning pass only, and
                        // spent below so it can never describe a screen the model has moved past.
                        val carriedPlan = deferredPlanHint
                        val decision = ladderResult.decision
                        primaryProvider = when (decision.target) {
                            RouteTarget.OMNIROUTE_CLOUD -> "OMNIROUTE_CLOUD"
                            RouteTarget.LOCAL_SLM -> "LOCAL_SLM"
                            else -> "NONE"
                        }
                        decisionReason = decision.reason
                        _uiState.update {
                            it.copy(
                                statusMessage = "Routed to $primaryProvider: ${decision.reason}",
                                lastRoutingReason = decision.reason,
                                lastProviderUsed = primaryProvider
                            )
                        }
                        if (decision.target == RouteTarget.OMNIROUTE_CLOUD) escalations++

                        if (decision.target == RouteTarget.LOCAL_SLM) refreshLocalEngine()
                        val localCaps = if (decision.target == RouteTarget.LOCAL_SLM) localLlmClient.capabilities() else null
                        val localCanDrive = decision.target == RouteTarget.LOCAL_SLM &&
                            localLlmClient.isModelLoaded() && (localCaps?.canDriveActions == true)
                        if (decision.target == RouteTarget.LOCAL_SLM && !localCanDrive) {
                            app.eventLogger.warn("AniobViewModel", "Local engine quarantined: ${localCaps?.reason ?: "model not loaded"}")
                            decisionReason = "local_quarantined: ${localCaps?.reason ?: "model not loaded"}"
                            _uiState.update {
                                it.copy(lastProviderUsed = primaryProvider, lastRoutingReason = decisionReason)
                            }
                        }

                        // The cloud path can parse a burst; the local resolver is single-action, so
                        // the local prompt keeps the one-action shape.
                        val burstEnabled = researchMode.actionBursts && !localCanDrive && !burstsSuspended
                        val executorRequest = taskContext.buildExecutorRequest(
                            screen = currentScreen,
                            activeSubgoal = taskProgress?.summary,
                            lastAction = taskContext.trajectory.lastOrNull()?.describeAction(),
                            lastResult = taskContext.condensedHistory.lastOrNull(),
                            burstMode = burstEnabled,
                            // The hint describes a plan that was split across screens, which only the
                            // burst contract understands; a single-action reply would just read it as
                            // history. A suspended burst drops it instead of carrying it forward.
                            carriedPlanHint = carriedPlan.takeIf { burstEnabled }
                        )
                        val executorPrompt = executorRequest.toPromptString()
                        // A planning call spends the carried plan: it is context for exactly one
                        // request, and a second copy would describe a screen the model has left.
                        deferredPlanHint = null
                        val configuredPrompt = configLoader.prompt("system_prompt.txt") ?: "System: Android Agent. Reply with a single JSON tool call."
                        // The model is told, every run, that it is wired into this phone, which direct
                        // commands exist, and which of them are switched off right now. A small
                        // on-device model gets the terse catalogue; a cloud model the full one.
                        val capabilityState = AniobCapabilityProbe.snapshot(app)
                        fun withCapabilities(base: String, compact: Boolean): String =
                            base + "\n\n" + com.aniob.core.capability.AniobCapabilityManifest.render(capabilityState, compact)
                        val baseSystemPrompt = withCapabilities(configuredPrompt, compact = localCanDrive)
                        val systemPrompt = if (burstEnabled) {
                            // The bundled prompt insists on one action per reply; the plan contract
                            // is appended so it overrides that line instead of fighting it.
                            baseSystemPrompt +
                                "\n\nMULTI-ACTION PLANNING: ignore the single-action rule above. Reply with ONE " +
                                "compact JSON object and nothing else:\n" +
                                "{\"thought\": \"brief 10-word plan\", \"is_complete\": false, " +
                                "\"actions\": [{\"type\": \"tap|input|long_press|scroll|back|open_app|wait|device_control\", " +
                                "\"id\": 2, \"text\": \"only for input\", \"submit\": true}]}\n" +
                                "Plan the next ${ActionBurst.MIN_ACTIONS}-${ActionBurst.MAX_ACTIONS} grounded sequential actions of the task, in order. The FIRST one must name an " +
                                "element that is on the screen you were given; the ones after it may belong to the " +
                                "screen it opens. Actions whose elements are not visible yet are held back and " +
                                "replayed later, so plan the whole next stretch of the task instead of stopping at " +
                                "the first tap. For a field that is the only text box on screen you may omit \"id\". " +
                                "No pixel coordinates. Keep the reply compact.\n" +
                                "Exception: a chore that needs no screen (media, volume, flashlight, calls, codes, files, " +
                                "alarms...) is NOT a plan; reply with the single DEVICE_CONTROL JSON object instead."
                        } else if (baseSystemPrompt.contains("ACTION_JSON_SCHEMA")) {
                            baseSystemPrompt
                        } else {
                            "$baseSystemPrompt\n\nSchema:\n${com.aniob.core.domain.AniobActionSchema.ACTION_JSON_SCHEMA}"
                        }

                        // Plan-5.1: Route prompt generation through PromptAssembler for prefix caching
                        val promptAssembler = com.aniob.core.speed.PromptAssembler(systemPrompt)
                        val assembledPrompt = promptAssembler.assemble(
                            com.aniob.core.speed.PromptAssembler.Tail(
                                observation = executorPrompt
                            )
                        )
                        val activePrompt = assembledPrompt.text

                        // The plain one-action prompts, for any model that cannot read a burst contract.
                        fun singleActionPrompts(): Pair<String, String> {
                            if (!burstEnabled && primaryProvider != "LOCAL_SLM") return systemPrompt to activePrompt
                            // A step handed to the on-device model after a cloud failure gets the terse catalogue.
                            val singleBase = if (primaryProvider == "LOCAL_SLM") {
                                withCapabilities(configuredPrompt, compact = true)
                            } else {
                                baseSystemPrompt
                            }
                            val singleSystem = if (singleBase.contains("ACTION_JSON_SCHEMA")) {
                                singleBase
                            } else {
                                "$singleBase\n\nSchema:\n${com.aniob.core.domain.AniobActionSchema.ACTION_JSON_SCHEMA}"
                            }
                            val singleObservation = taskContext.buildExecutorRequest(
                                screen = currentScreen,
                                activeSubgoal = taskProgress?.summary,
                                lastAction = taskContext.trajectory.lastOrNull()?.describeAction(),
                                lastResult = taskContext.condensedHistory.lastOrNull(),
                                burstMode = false
                            ).toPromptString()
                            val singleTail = com.aniob.core.speed.PromptAssembler.Tail(observation = singleObservation)
                            return singleSystem to com.aniob.core.speed.PromptAssembler(singleSystem).assemble(singleTail).text
                        }

                        // Resilient provider ladder. A cloud step that comes back unusable (HTTP 4xx/5xx,
                        // an HTML page where JSON belongs, gateway down, no assistant message) must not
                        // end the task: the same step is retried on the on-device model. Only when that
                        // cannot produce a real action does the step fail, with a reason the user can act
                        // on. A cloud-only routing choice is honoured and never downgraded.
                        suspend fun degradeAfterCloudFailure(error: Throwable, allowLocal: Boolean): AniobAction {
                            val kind = com.aniob.core.external.AniobGatewayClassifier.kindOf(error)
                            val detail = if (kind == com.aniob.core.external.GatewayFailureKind.RATE_LIMIT) {
                                com.aniob.core.narration.FailureReasonCopy.gatewayRateLimited(
                                    retryAfterMs = (error as? com.aniob.core.external.AniobGatewayException)?.retryAfterMs,
                                    willRetryHere = false,
                                    localFallbackReady = allowLocal && userRouting != "cloud-only"
                                )
                            } else {
                                error.message ?: "network error"
                            }
                            val cloudFailure = "Cloud action generation failed: $detail"
                            app.eventLogger.warn("AniobViewModel", "Cloud step failed (${kind.name}): $detail")
                            if (!allowLocal || userRouting == "cloud-only") return AniobAction.Fail(cloudFailure)
                            refreshLocalEngine()
                            val caps = localLlmClient.capabilities()
                            if (!localLlmClient.isModelLoaded() || !caps.canDriveActions) {
                                return AniobAction.Fail(
                                    "$cloudFailure. No on-device model is ready (${caps.reason}): install one in " +
                                        "Models, or check the gateway address in Settings."
                                )
                            }
                            primaryProvider = "LOCAL_SLM"
                            decisionReason = "Downgraded to the on-device model after a cloud failure (${kind.name})"
                            _uiState.update {
                                it.copy(
                                    statusMessage = "Cloud unavailable. Continuing on the on-device model...",
                                    lastProviderUsed = primaryProvider,
                                    lastRoutingReason = decisionReason
                                )
                            }
                            val (localSystem, localObservation) = singleActionPrompts()
                            app.researchTelemetry.recordLocalInference()
                            val gen = localLlmClient.generateStepResult(localSystem, localObservation)
                            val localPromptEst = (localSystem.length + localObservation.length) / 4
                            app.researchTelemetry.recordPromptTokens(localPromptEst)
                            totalTokens += localPromptEst.coerceAtLeast(80)
                            val structured = AniobLocalActionResolver.resolveStructured(gen, currentScreen)
                            if (structured.usedFallback) {
                                app.eventLogger.warn(
                                    "AniobViewModel",
                                    "On-device fallback produced no schema-valid action: ${structured.reason}"
                                )
                                return AniobAction.Fail(
                                    "$cloudFailure. The on-device model could not plan this step either (${structured.reason})."
                                )
                            }
                            return structured.action
                        }

                        suspend fun runOmniRouteStep(
                            allowLocalFallback: Boolean = true
                        ): AniobAction {
                            val omniroute = getActionStepProvider(sessionId = task.id)
                            val promptAsksForVisualInspection = (localFailCount > 0) || task.rawPrompt.contains(
                                Regex("(?i)look|see|inspect|read|image|photo|visual")
                            )
                            val isSensitiveScreen = currentScreen.nodes.any {
                                it.isPassword || it.viewId.contains(Regex("(?i)password|pwd|otp|pin|cvv|card_number"))
                            }
                            // Observe cost: the bitmap is the expensive half of perception, so the
                            // compact conditions only pay for it when the tree is sparse or the
                            // previous verify failed. Baseline pays on every step by design.
                            val labeledInteractive = com.aniob.core.perception.ObserveCapturePolicy
                                .labeledInteractiveCount(currentScreen.nodes)
                            val previousStepFailed = stepResult?.verification?.isSuccessful == false
                            // A tier-2 verify only spends a bitmap when nothing in the tree moved,
                            // which is exactly the visual-popup / no-op-tap case: hand those pixels
                            // to this Reason step instead of dropping them.
                            val screenIdentityNow = com.aniob.core.verify.FastVerify.screenIdentity(currentScreen)
                            val reusedScreenshot = pendingVerifyScreenshot
                                ?.takeIf { it.first == screenIdentityNow }
                                ?.second
                            pendingVerifyScreenshot = null
                            val wantsPixels = reusedScreenshot != null ||
                                com.aniob.core.perception.ObserveCapturePolicy
                                    .shouldCaptureScreenshot(
                                        fastObservation = researchMode.fastObservation,
                                        labeledInteractiveNodes = labeledInteractive,
                                        previousStepFailed = previousStepFailed,
                                        visionRequested = promptAsksForVisualInspection
                                    )
                            val screenshotBase64 = if (wantsPixels && !isSensitiveScreen && accessibility != null) {
                                reusedScreenshot ?: captureVerifyScreenshot()
                            } else null
                            // A burst reply is a handful of tokens; a tight budget keeps the
                            // decode short and stops a chatty model from padding the plan.
                            // Streaming + early parse: the action leaves the device the moment the
                            // reply's JSON object closes, instead of waiting for the model to stop
                            // talking, for the socket to close and for a full body read.
                            setPhase(AgentPhase.PLANNING, ThinkSource.MODEL_CLOUD, provider = "OmniRoute")
                            val raw = omniroute.getNextActionRawStreaming(
                                systemPrompt = systemPrompt,
                                userPrompt = activePrompt,
                                screenshotBase64 = screenshotBase64,
                                maxTokens = if (burstEnabled) BURST_MAX_OUTPUT_TOKENS else 512,
                                onFirstToken = { advancePhase(AgentPhase.STREAMING) }
                            )
                            val promptEstTokens = (systemPrompt.length + activePrompt.length) / 4
                            app.researchTelemetry.recordPromptTokens(promptEstTokens)
                            totalTokens += promptEstTokens.coerceAtLeast(150)
                            return raw.fold(
                                onSuccess = { text ->
                                    totalTokens += (text.length / 4).coerceAtLeast(1)
                                    // Burst reply: several actions for the screen we just observed.
                                    val burst = if (burstEnabled) {
                                        AniobStructuredOutput.parseBurstOrRepair(
                                            raw = text,
                                            screenState = currentScreen,
                                            goal = task.clarifiedGoal
                                        )
                                    } else {
                                        null
                                    }
                                    if (burst != null) {
                                        plannedBurst = burst
                                        app.researchTelemetry.recordBurstPlanned()
                                        if (burst.isComplete) {
                                            AniobAction.Finish(burst.thought.ifBlank { "Burst complete" })
                                        } else {
                                            burst.actions.last()
                                        }
                                    } else {
                                        val executorDecode = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR)
                                        var structured = AniobStructuredOutput.parseOrRepair(
                                            raw = text,
                                            screenState = currentScreen,
                                            decode = executorDecode
                                        )
                                        if (structured.usedFallback) {
                                            val repairMsg = "${AniobStructuredOutput.repairPrompt(text)}\n\nOriginal Request:\n$executorPrompt"
                                            val repaired = runCatching {
                                                omniroute.getNextActionRaw(
                                                    systemPrompt,
                                                    repairMsg,
                                                    null
                                                ).getOrNull()
                                            }.getOrNull()
                                            if (repaired != null) {
                                                val repairPromptTokens = (systemPrompt.length + repairMsg.length) / 4
                                                app.researchTelemetry.recordPromptTokens(repairPromptTokens)
                                                totalTokens += ((repairMsg.length + repaired.length) / 4).coerceAtLeast(1)
                                                structured = AniobStructuredOutput.parseOrRepair(
                                                    raw = repaired,
                                                    screenState = currentScreen,
                                                    decode = executorDecode
                                                )
                                            }
                                        }
                                        if (structured.usedFallback) {
                                            app.eventLogger.warn("AniobViewModel", "Cloud output unusable: ${structured.reason}")
                                        }
                                        structured.action
                                    }
                                },
                                onFailure = { error -> degradeAfterCloudFailure(error, allowLocalFallback) }
                            )
                        }

                        val canEscalateToCloud = userRouting != "local-only" && isCloudConfigured() && powerState.isNetworkAvailable

                        val plannedAction = if (localCanDrive) {
                            setPhase(AgentPhase.PLANNING, ThinkSource.MODEL_LOCAL)
                            app.researchTelemetry.recordLocalInference()
                            val gen = localLlmClient.generateStepResult(
                                systemPrompt,
                                activePrompt
                            )
                            val localPromptEst = (systemPrompt.length + activePrompt.length) / 4
                            app.researchTelemetry.recordPromptTokens(localPromptEst)
                            totalTokens += localPromptEst.coerceAtLeast(80)
                            if (gen is AniobGenerationResult.Ready) {
                                android.util.Log.i("AniobViewModel", "Local SLM generated output (len=${gen.fullText.length})")
                            }
                            val structured = AniobLocalActionResolver.resolveStructured(gen, currentScreen)
                            if (structured.usedFallback) {
                                app.eventLogger.warn("AniobViewModel", "Local SLM produced no schema-valid action: ${structured.reason}")
                                if (canEscalateToCloud) {
                                    primaryProvider = "OMNIROUTE_CLOUD"
                                    escalations++
                                    decisionReason = "Escalated to OmniRoute Cloud after local output fallback"
                                    _uiState.update {
                                        it.copy(
                                            statusMessage = "Escalating to Cloud...",
                                            lastProviderUsed = primaryProvider,
                                            lastRoutingReason = decisionReason
                                        )
                                    }
                                    runOmniRouteStep(allowLocalFallback = false)
                                } else {
                                    structured.action
                                }
                            } else {
                                structured.action
                            }
                        } else if (decision.target == RouteTarget.LOCAL_SLM && canEscalateToCloud) {
                            primaryProvider = "OMNIROUTE_CLOUD"
                            escalations++
                            decisionReason = "Escalated to OmniRoute Cloud (${localCaps?.reason ?: "local model not loaded"})"
                            _uiState.update {
                                it.copy(
                                    statusMessage = "Escalating to Cloud...",
                                    lastProviderUsed = primaryProvider,
                                    lastRoutingReason = decisionReason
                                )
                            }
                            runOmniRouteStep(allowLocalFallback = false)
                        } else if (decision.target == RouteTarget.LOCAL_SLM) {
                            AniobAction.Fail("Local model is not capable or ready: ${localCaps?.reason ?: "model not loaded"}")
                        } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD && isOmniRouteReady()) {
                            runOmniRouteStep()
                        } else if (decision.target == RouteTarget.OMNIROUTE_CLOUD) {
                            AniobAction.Fail("Cloud AI is not set up — add your OmniRoute key and gateway address in Settings")
                        } else {
                            AniobAction.Fail("No capable provider available: ${decision.reason}")
                        }

                        // Watchdog check
                        val watchdogKey = currentScreen.screenHash.ifBlank { currentScreen.treeHash }
                        watchdog.record(watchdogKey, plannedAction)
                        val loopDetected = watchdog.isLoopDetected()
                        val loopAction = if (loopDetected) {
                            watchdog.reset()
                            AniobAction.PressKey(com.aniob.core.domain.KeyType.BACK)
                        } else {
                            plannedAction
                        }

                        val targetSom = loopAction.semanticTarget() as? SemanticTarget.SomIndex
                        val effectiveAction = if (targetSom != null && currentScreen.nodes.none { it.id == targetSom.index }) {
                            AniobAction.Fail("Node id ${targetSom.index} not present on current screen")
                        } else {
                            loopAction
                        }

                        proposal = StepPipeline.Proposal.Model(effectiveAction, primaryProvider)
                        targetNode = effectiveAction.semanticTarget()?.let {
                            com.aniob.core.execution.AniobActionExecutor.resolveTarget(effectiveAction, currentScreen)?.node
                        }
                    }
                }

                persistRunningMarker(task, currentStep + 1, proposal.action.describeAction())

                val captureLambda: suspend () -> AniobScreenState = {
                    screenReads++; screenCaptureCount++; app.researchTelemetry.recordScreenCapture()
                    accessibility?.captureCurrentScreenState() ?: currentScreen
                }

                // The screen the action was planned on: the two-tier verifier compares against it.
                var dispatchOrigin: AniobScreenState = currentScreen
                var burstDispatchTreeHash: String? = null

                /**
                 * Waits for the dispatched action to show up, then returns the screen to verify
                 * against. Tier 1 polls window events every 50 ms (max 800 ms) and stops on the
                 * first structural change or expected text; only a timeout buys a bitmap, which is
                 * kept for the next Reason step because an unchanged tree is exactly what a visual
                 * popup or a no-op tap looks like.
                 */
                suspend fun awaitTransition(act: AniobAction): AniobScreenState? {
                    if (accessibility == null) return null
                    // Opening an app that is already in front has nothing to wait for; using the
                    // launch budget here would idle for seconds before the next step.
                    if (act is AniobAction.OpenApp &&
                        dispatchOrigin.packageName.equals(act.packageName, ignoreCase = true)
                    ) {
                        return dispatchOrigin
                    }
                    // A cold app launch can take seconds on a busy phone; every other action keeps
                    // the 800 ms budget. The wait is still event-driven, so a fast launch returns
                    // as soon as the package actually changes.
                    val budget = if (act is AniobAction.OpenApp) {
                        APP_LAUNCH_VERIFY_MS
                    } else {
                        com.aniob.core.verify.FastVerify.MAX_WAIT_MS
                    }
                    val fast = com.aniob.core.verify.FastVerify.await(
                        preAction = dispatchOrigin,
                        expectedTexts = com.aniob.core.verify.FastVerify.expectedTexts(act, resolvedCriteria),
                        timeoutMs = budget,
                        windowEvents = { accessibility.windowEventSequence() },
                        capturePoll = { accessibility.captureCurrentScreenState() },
                        screenshot = { captureVerifyScreenshot() }
                    )
                    fast.screenshotBase64?.let { bitmap ->
                        val id = com.aniob.core.verify.FastVerify
                            .screenIdentity(fast.screen ?: dispatchOrigin)
                        if (id.isNotBlank()) pendingVerifyScreenshot = id to bitmap
                    }
                    return fast.screen ?: accessibility.captureCurrentScreenState()
                }

                val dispatchLambda: suspend (AniobAction) -> StepPipeline.DispatchOutcome = { act ->
                    when {
                        proposal is StepPipeline.Proposal.DirectIntent && ladderResult is AniobExecutionRouter.ExecutionPlanResult.DirectIntent -> {
                            val ok = dispatchSystemIntent(ladderResult.shortcut)
                            val screenAfter = awaitTransition(act)
                            actionsDispatched++
                            StepPipeline.DispatchOutcome(dispatched = ok, reason = act.describeAction(), screenAfter = screenAfter)
                        }
                        act is AniobAction.DeviceControl -> {
                            val result = runDeviceCommand(act.command, act.args)
                            postDeviceResult(result)
                            actionsDispatched++
                            // The result text is what the model reads next; a silent command leaves the
                            // screen untouched, so the planning screen is reused instead of a capture.
                            val after = when {
                                !result.ok -> null
                                act.command.completesOnDispatch -> dispatchOrigin
                                else -> awaitTransition(act)
                            }
                            StepPipeline.DispatchOutcome(dispatched = result.ok, reason = result.message, screenAfter = after)
                        }
                        act is AniobAction.Finish -> {
                            StepPipeline.DispatchOutcome(dispatched = true, reason = act.summary)
                        }
                        act is AniobAction.Fail -> {
                            StepPipeline.DispatchOutcome(dispatched = false, reason = act.reason)
                        }
                        else -> {
                            // A target that is not on the current screen is not a failure yet: if
                            // the screen scrolls, one forward scroll usually reveals it. Doing that
                            // here, before the model is asked again, is the difference between one
                            // round trip and three.
                            val plannedMiss = act.semanticTarget() != null &&
                                com.aniob.core.execution.AniobActionExecutor
                                    .planDispatch(act, dispatchOrigin) is DispatchCommand.NoOp
                            var ok = if (accessibility != null) executeActionSync(accessibility, act, burstDispatchTreeHash ?: dispatchOrigin.screenHash.ifBlank { dispatchOrigin.treeHash }) else true
                            actionsDispatched++
                            var screenAfter = awaitTransition(act)
                            if (!ok && burstDispatchTreeHash == null && plannedMiss && accessibility != null &&
                                com.aniob.core.perception.PreObserveScroll
                                    .shouldScroll(dispatchOrigin, preObservedScreens)
                            ) {
                                preObservedScreens += FastVerify.screenIdentity(dispatchOrigin)
                                if (accessibility.scrollForwardOnce()) {
                                    delay(com.aniob.core.perception.PreObserveScroll.SETTLE_MS)
                                    val revealed = accessibility.captureCurrentScreenState()
                                    // Retry through a stable identity (view id -> text -> content
                                    // description): replaying a SoM index against the tree the
                                    // scroll just created can address a different element.
                                    val retry = com.aniob.core.perception.PreObserveScroll
                                        .retargetForRetry(act, dispatchOrigin)
                                    if (retry == null) {
                                        app.eventLogger.info(
                                            "AniobViewModel",
                                            "Pre-observe scroll: target has no stable identity, re-planning"
                                        )
                                    } else {
                                        ok = executeActionSync(accessibility, retry)
                                        screenAfter = awaitTransition(retry) ?: revealed
                                    }
                                    if (ok) {
                                        app.eventLogger.info(
                                            "AniobViewModel",
                                            "Pre-observe scroll revealed the target of ${act.describeAction()}"
                                        )
                                    } else {
                                        // Still missing: give the next planning pass both viewports
                                        // so it does not spend a call rediscovering the first one.
                                        preObservedScreen = com.aniob.core.perception.PreObserveScroll
                                            .merge(dispatchOrigin, revealed)
                                        app.eventLogger.info(
                                            "AniobViewModel",
                                            "Pre-observe scroll: merged ${revealed.nodes.size} new nodes for the next plan"
                                        )
                                    }
                                }
                            }
                            StepPipeline.DispatchOutcome(dispatched = ok, screenAfter = screenAfter)
                        }
                    }
                }
                val confirmLambda: suspend (AniobAction, AniobNode?, InterceptResult) -> Boolean = confirm@{ approvedAction, approvedNode, verdict ->
                    val category = AskPolicy.category(approvedAction, approvedNode)
                    // Approvals are collected once, at the start. Mid-run the gate only checks them: a family
                    // the user approved goes ahead, one they were never asked about is skipped (fail closed).
                    // Nothing here pauses the task, shows a sheet or posts a notification.
                    when (val resolution = com.aniob.core.safety.MidRunApproval.resolve(
                        timing = askTiming(),
                        level = AniobSafetyInterceptor.askLevel,
                        category = category,
                        consent = taskConsent
                    )) {
                        is com.aniob.core.safety.MidRunApproval.Resolution.Approve -> {
                            recordAutoResolution(approvedAction, approvedNode, verdict, ConfirmDecision.APPROVE_FOR_TASK)
                            return@confirm true
                        }
                        is com.aniob.core.safety.MidRunApproval.Resolution.Decline -> {
                            recordAutoResolution(approvedAction, approvedNode, verdict, ConfirmDecision.DENY)
                            val skipped = ChatMessage(
                                id = "msg_skipped_${System.currentTimeMillis()}",
                                role = "assistant",
                                content = resolution.why,
                                provider = "ASK_POLICY",
                                badge = "Skipped"
                            )
                            _uiState.update { it.copy(chatMessages = it.chatMessages + skipped) }
                            return@confirm false
                        }
                        is com.aniob.core.safety.MidRunApproval.Resolution.Prompt -> Unit
                    }
                    val sensitive = category == AskCategory.SECRET || approvedNode?.isPassword == true
                    val humanWhat = when {
                        sensitive -> "Use a protected value"
                        approvedAction is AniobAction.InputText -> "Type into the text field"
                        else -> StepNarration.narrate(approvedAction, approvedNode).take(80)
                    }
                    val humanWhy = verdict.reason.ifBlank { "Action requires user confirmation" }
                    val computedRisk = riskFromTier(verdict.riskTier, _uiState.value.safetyLevel)
                    val details = when {
                        sensitive -> "The protected value and field contents are hidden."
                        approvedAction is AniobAction.InputText -> "Typed content is hidden and bound to this approval."
                        else -> approvedAction.describeAction()
                    }
                    val confirmReq = buildConfirmRequest(
                        what = humanWhat,
                        why = humanWhy,
                        risk = computedRisk,
                        details = details,
                        taskId = task.id,
                        payload = com.aniob.app.workflow.ApprovalFingerprint.payload(approvedAction, approvedNode),
                        target = com.aniob.app.workflow.ApprovalFingerprint.displayTarget(
                            approvedAction, approvedNode, sensitive
                        ),
                        identityTarget = com.aniob.app.workflow.ApprovalFingerprint.target(approvedAction, approvedNode)
                    )
                    val decision = requestConfirmation(confirmReq)
                    decision == ConfirmDecision.APPROVE_ONCE || decision == ConfirmDecision.APPROVE_FOR_TASK
                }

                // ---------------------------------------------------------------------------
                // Act: run the bundled plan one member at a time (ActionBurstRunner).
                // The screen is re-read and the next member re-grounded after every action, and a
                // member that no longer fits the screen closes the plan instead of failing the task.
                // ---------------------------------------------------------------------------
                var burstAbort: String? = null
                // A plan whose target vanished is not a failed task: it was written for a screen
                // that has since moved (Files/Search re-index and hide nodes after the first tap).
                // The rest of the plan is dropped and the next step is planned from a fresh screen.
                var burstReplan: String? = null
                // True once a plan member ran: this step's verification is then the plan's, and
                // running the pipeline again would repeat an action that already happened.
                var planConsumedStep = false
                var lastPlanResult: StepResult? = null
                if (plannedBurst != null) {
                    val burst = plannedBurst!!
                    // Everything the model planned for screens this one does not show yet. It is
                    // context for the next planning pass, never an instruction to act blind.
                    com.aniob.core.perception.BurstPlanScope.renderDeferredHint(burst.deferred)
                        ?.let { deferredPlanHint = it }
                    val plan = com.aniob.core.domain.ActionBurst(
                        thought = burst.thought,
                        isComplete = burst.isComplete,
                        actions = burst.head + listOfNotNull(burst.tail),
                        deferred = burst.deferred
                    )
                    if (plan.actions.isNotEmpty()) {
                        // One runner for a model plan and for a remembered replay: every member goes
                        // through the same safety, dispatch and verification pipeline, and the screen
                        // is re-read (and the member re-grounded) after every action.
                        val burstRunner = com.aniob.core.execution.ActionBurstRunner(
                            capture = {
                                accessibility?.captureCurrentScreenState()?.also {
                                    screenReads++; screenCaptureCount++
                                    app.researchTelemetry.recordScreenCapture()
                                }
                            },
                            executeMember = { member, memberCtx, planningScreen, target ->
                                val dispatchable = target?.let {
                                    com.aniob.core.perception.BurstTargetGuard.retarget(member, it)
                                } ?: member
                                dispatchOrigin = planningScreen
                                // A member is dispatched against the tree it was re-grounded on, so a
                                // screen that re-indexes between the check and the tap cannot make
                                // the tap land on a different widget.
                                burstDispatchTreeHash =
                                    com.aniob.core.diff.AniobScreenDiff.computeTreeHash(planningScreen.nodes)
                                val step = try {
                                    stepPipe.executeStep(
                                        ctx = memberCtx,
                                        planningScreen = planningScreen,
                                        proposal = StepPipeline.Proposal.Model(dispatchable, primaryProvider),
                                        capture = { planningScreen },
                                        dispatch = dispatchLambda,
                                        // Every member uses the same approval gate as a single
                                        // action. A sensitive early member must pause for approval,
                                        // not be silently treated as a denial merely because it is
                                        // inside a burst.
                                        confirm = confirmLambda,
                                        targetNode = target
                                    )
                                } finally {
                                    burstDispatchTreeHash = null
                                }
                                if (step.verification?.isSuccessful == true ||
                                    step.terminalState == StepResult.TerminalState.SUCCESS
                                ) {
                                    recordTargetSuccess(task.id, step.action, step.resolvedNode ?: target)
                                }
                                app.researchTelemetry.recordBurstMemberRun()
                                app.researchTelemetry.recordStep()
                                // Only verified members are remembered; a failed member teaches
                                // nothing and must never seed the replay cache.
                                if (step.verification?.isSuccessful == true &&
                                    step.terminalState != StepResult.TerminalState.FAIL
                                ) {
                                    rememberTransitions.add(
                                        RememberTransition(screen = planningScreen, action = step.action, node = target, nextScreen = step.nextCtx.lastScreenState)
                                    )
                                }
                                step
                            }
                        )
                        val run = burstRunner.run(
                            plan = plan,
                            origin = currentScreen,
                            ctx = taskContext,
                            shouldContinue = {
                                taskGeneration == activeGeneration && _uiState.value.isRunning
                            }
                        )
                        taskContext = run.ctx
                        currentScreen = run.screen
                        lastPlanResult = run.lastResult
                        val missedAction = run.missedAction
                        val missedNode = run.missedNode
                        val hasGroundingMiss = missedAction != null
                        if (missedAction != null) {
                            val misses = recordTargetMiss(task.id, missedAction, missedNode)
                            if (misses != null && misses >= 2) {
                                finalOutcome = Outcome.FAILED
                                terminalReason = targetMissReason(missedAction, missedNode)
                                break
                            }
                        }
                        // Only a plan that ended for its own reasons speaks for this step. A plan
                        // closed early still owes the loop one action on the screen it revealed, and
                        // that action gets its own step record.
                        planConsumedStep = run.ran.isNotEmpty() &&
                            (run.stop == com.aniob.core.execution.ActionBurstRunner.Stop.NONE ||
                                run.stop == com.aniob.core.execution.ActionBurstRunner.Stop.BLOCKED ||
                                run.stop == com.aniob.core.execution.ActionBurstRunner.Stop.ABORTED)
                        when (run.stop) {
                            com.aniob.core.execution.ActionBurstRunner.Stop.NONE -> Unit

                            com.aniob.core.execution.ActionBurstRunner.Stop.TARGET_GONE,
                            com.aniob.core.execution.ActionBurstRunner.Stop.NAVIGATED,
                            com.aniob.core.execution.ActionBurstRunner.Stop.NO_CAPTURE -> {
                                // What really happened is kept; the rest of the plan is closed and
                                // the loop continues from the screen the plan revealed. This is the
                                // normal end of a plan that spans screens, not an error.
                                app.eventLogger.info("AniobViewModel", "Plan closed early: ${run.detail}")
                                burstReplan = run.detail
                            }

                            com.aniob.core.execution.ActionBurstRunner.Stop.BLOCKED -> {
                                if (hasGroundingMiss) {
                                    burstReplan = run.detail
                                } else {
                                    burstAbort = run.detail
                                }
                            }

                            com.aniob.core.execution.ActionBurstRunner.Stop.ABORTED -> {
                                burstAbort = run.detail
                            }
                        }
                        if (run.completed && run.lastResult?.terminalState == StepResult.TerminalState.CONTINUE) {
                            // Every member ran and none of them was a Finish: the plan says the goal
                            // is met, so carry a provisional finish into verification rather than
                            // asking the model for another action on a screen it already finished.
                            proposal = StepPipeline.Proposal.Model(
                                AniobAction.Finish(burst.thought.ifBlank { "Planned actions completed" }),
                                primaryProvider
                            )
                            targetNode = null
                            planConsumedStep = false
                        }
                    }
                    plannedBurst = null
                }
                if (burstAbort == null && burstReplan != null) {
                    app.eventLogger.info(
                        "AniobViewModel",
                        "Plan target invalidated ($burstReplan); dropping the rest and planning from a fresh screen"
                    )
                    // A plan that spans screens ends this way by design, so batching stays on. Only
                    // repeated plans that cannot even start mean the screen is thrashing, and then
                    // the rest of the run plans one action at a time. Anything that did run before
                    // the plan went stale resets the counter.
                    burstZeroProgressReplans = if (planConsumedStep) 0 else burstZeroProgressReplans + 1
                    if (burstZeroProgressReplans >= BURST_MAX_ZERO_PROGRESS_REPLANS) {
                        burstsSuspended = true
                        app.researchTelemetry.recordBurstSuspended()
                    }
                    delay(BURST_REPLAN_SETTLE_MS)
                    accessibility?.captureCurrentScreenState()?.let { fresh ->
                        screenReads++; screenCaptureCount++; app.researchTelemetry.recordScreenCapture()
                        screenBefore = fresh
                    }
                    _uiState.update { it.copy(statusMessage = "The screen changed. Planning the next step from what is on screen...") }
                    currentStep++
                    continue
                }
                if (burstAbort != null) {
                    app.eventLogger.warn("AniobViewModel", "Plan stopped: $burstAbort")
                    val userReason = if (burstAbort.contains("stopped")) {
                        "I stopped before finishing. Actions that already ran were not undone."
                    } else {
                        "I stopped before finishing because an action could not be safely completed or verified. Check the screen before trying again; earlier changes were not undone."
                    }
                    _uiState.update { it.copy(statusMessage = userReason) }
                    proposal = StepPipeline.Proposal.Model(
                        AniobAction.Fail(userReason),
                        primaryProvider
                    )
                    targetNode = null
                    planConsumedStep = false
                }

                // A plan that ran to its end already produced this step's verified result. Running
                // the remaining action now would repeat work that really happened, so the plan's own
                // last result is the step result.
                dispatchOrigin = currentScreen
                val res = if (planConsumedStep) {
                    lastPlanResult ?: stepPipe.executeStep(
                        ctx = taskContext,
                        planningScreen = currentScreen,
                        proposal = proposal,
                        capture = captureLambda,
                        dispatch = dispatchLambda,
                        confirm = confirmLambda,
                        targetNode = targetNode
                    )
                } else {
                    stepPipe.executeStep(
                        ctx = taskContext,
                        planningScreen = currentScreen,
                        proposal = proposal,
                        capture = captureLambda,
                        dispatch = dispatchLambda,
                        confirm = confirmLambda,
                        targetNode = targetNode
                    )
                }
                stepResult = res
                val targetMissCountForStep = if (planConsumedStep) null else
                    recordStepTargetOutcome(task.id, res, currentScreen)
                app.researchTelemetry.recordStep()
                if (ladderResult is AniobExecutionRouter.ExecutionPlanResult.RememberReplay) {
                    val postScreen = res.nextCtx.lastScreenState ?: currentScreen
                    val isStalled = FastVerify.screenIdentity(postScreen) == FastVerify.screenIdentity(currentScreen)
                    val replayEntry = rememberCache.entryFor(task.clarifiedGoal.ifBlank { task.rawPrompt }, currentScreen)
                    val expectedReached = replayEntry?.expectedNextHash.orEmpty().let {
                        it.isBlank() || it == FastVerify.screenIdentity(postScreen)
                    }
                    if (res.verification?.isSuccessful == true && !isStalled && expectedReached) {
                        replayEntry?.let { rememberCache.recordSuccess(it.key) }
                        app.researchTelemetry.recordReplayValidated()
                    } else {
                        replayEntry?.let { rememberCache.recordFailure(it.key) }
                        stalledReplayIdentities.add(FastVerify.screenIdentity(currentScreen))
                        app.researchTelemetry.recordReplayDiverged()
                    }
                    viewModelScope.launch(Dispatchers.IO) { app.rememberCacheFile.save(rememberCache) }
                }
                if (proposal is StepPipeline.Proposal.Model && proposal.action is AniobAction.OpenApp && primaryProvider == "APP_LAUNCH") {
                    if (res.verification?.isSuccessful == true) {
                        app.researchTelemetry.markIntentShortcutUsed()
                    }
                }
                // A plan member recorded its own transition against the screen it ran on; adding
                // the same action again here would key it to the screen it produced.
                if (!planConsumedStep &&
                    (res.verification?.isSuccessful == true || res.terminalState == StepResult.TerminalState.SUCCESS)
                ) {
                    rememberTransitions.add(
                        RememberTransition(
                            screen = currentScreen,
                            action = res.action,
                            node = targetNode,
                            nextScreen = res.nextCtx.lastScreenState
                        )
                    )
                }
                taskContext = res.nextCtx
                observationPolicy.recordActionOutcome(proposal.action, currentScreen.packageName, res.verification?.isSuccessful == true)

                if (primaryProvider == "LOCAL_SLM") {
                    if (res.verification?.isSuccessful == true) localFailCount = 0 else localFailCount++
                }

                if (res.terminalState == StepResult.TerminalState.SUCCESS) {
                    finalOutcome = Outcome.SUCCESS
                    terminalReason = res.verification?.reason ?: "Verified successfully"
                    break
                } else if (res.terminalState == StepResult.TerminalState.FAIL) {
                    if (targetMissCountForStep != null) {
                        if (targetMissCountForStep >= 2) {
                            finalOutcome = Outcome.FAILED
                            terminalReason = targetMissReason(
                                res.action,
                                res.resolvedNode ?: com.aniob.core.execution.AniobActionExecutor
                                    .resolveTarget(res.action, currentScreen)?.node
                            )
                            break
                        }
                        deferredPlanHint = null
                        val fresh = accessibility?.captureCurrentScreenState()
                        if (fresh != null) {
                            screenReads++; screenCaptureCount++
                            app.researchTelemetry.recordScreenCapture()
                            screenBefore = fresh
                        } else {
                            screenBefore = res.nextCtx.lastScreenState ?: currentScreen
                        }
                        _uiState.update {
                            it.copy(statusMessage = "I couldn't find that control. I'll re-check the current screen before asking again.")
                        }
                        currentStep++
                        continue
                    }
                    // Everything needed was asked before the task started. A question the model still raises
                    // mid-run never pauses the task or notifies the user: it carries on with a stated
                    // assumption, or ends with the one missing detail so a re-run can supply it.
                    val parsedAsk = com.aniob.core.grillme.AniobAskUser.parse((res.action as? AniobAction.Fail)?.reason)
                    if (parsedAsk != null) {
                        val midRun = com.aniob.core.grillme.AniobAskUser.resolveWithoutUser(
                            grillMeEngine.sanitizeQuestion(parsedAsk),
                            alreadyAssumed = assumedMidRunAsk
                        )
                        when (midRun) {
                            is com.aniob.core.grillme.AniobAskUser.MidRunDecision.Assume -> {
                                assumedMidRunAsk = true
                                taskContext = taskContext.withSuccess().copy(
                                    grillAnswers = taskContext.grillAnswers + (midRun.question.id to midRun.answer)
                                )
                                val assumed = ChatMessage(
                                    id = "msg_assumed_${System.currentTimeMillis()}",
                                    role = "assistant",
                                    content = "I didn't stop to ask. For \"${midRun.question.question}\" I went with " +
                                        "\"${midRun.answer}\".",
                                    provider = "ASSUMPTION",
                                    badge = "Assumed"
                                )
                                _uiState.update { it.copy(chatMessages = it.chatMessages + assumed) }
                                screenBefore = res.nextCtx.lastScreenState ?: currentScreen
                                currentStep++
                                continue
                            }
                            is com.aniob.core.grillme.AniobAskUser.MidRunDecision.CannotContinue -> {
                                finalOutcome = Outcome.FAILED
                                terminalReason = midRun.message
                                break
                            }
                        }
                    }
                    val failReason = (res.action as? AniobAction.Fail)?.reason ?: res.verification?.reason.orEmpty()
                    if (failReason.contains("no-effect", ignoreCase = true) || failReason.contains("unchanged", ignoreCase = true)) {
                        _uiState.update {
                            it.copy(
                                isPaused = true,
                                statusMessage = "Nothing changed on screen. Please perform this step manually, then tap Resume."
                            )
                        }
                        pauseRequested = true
                        AniobBackgroundController.onTaskPaused(true)
                        currentStep++
                        continue
                    }
                    finalOutcome = Outcome.FAILED
                    terminalReason = failReason.ifBlank { "Step failed or rejected" }
                    break
                }

                screenBefore = res.nextCtx.lastScreenState ?: currentScreen
                currentStep++
            }
        } finally {
            // A shortcut disabled mid-run because this device could not dispatch it is a property
            // of the run, not of the session.
            executionRouter.intentShortcutsEnabled = shortcutsWereEnabled
            AniobAccessibilityService.isTaskActive = false
            learningPipeline.onTaskEnd(task.id)
        }

        if (taskGeneration != activeGeneration) {
            // A newer task took ownership; record this one honestly but do not touch the live UI.
            app.researchTelemetry.endTask(isSuccess = false, failureReason = "superseded")
            return
        }

        // A confirmed SUCCESS was checked against the screen the pipeline verified. A fresh capture
        // here is the user's *next* screen (or Aniob's own window) and used to flip good runs to
        // "not done". Every other outcome still reports against the live screen.
        val finalScreen = if (stepResult?.terminalState == StepResult.TerminalState.SUCCESS) {
            stepResult.nextCtx.lastScreenState ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
        } else {
            accessibility?.captureCurrentScreenState() ?: screenBefore ?: AniobScreenState(packageName = "com.aniob.app")
        }
        // A run closed by a shortcut keeps the verdict it was actually judged by. Otherwise the
        // common path re-checks the final screen; a completion with nothing checkable has nothing
        // to re-check at all.
        // Device evidence is merged into EVERY closing path (intent, pipeline, model Finish).
        // v1 only wired it into the intent branch, leaving the common ladder path unguarded.
        setPhase(AgentPhase.VERIFYING)
        val finalDeviceState = probeDeviceState(resolvedCriteria)
        val deviceEvaluations = evalDeviceAssertions(resolvedCriteria, finalDeviceState)
        val criteriaEvaluations = (
            when {
                intentVerdict != null -> intentVerdict!!
                completedByIntent -> emptyList()
                else -> resolvedCriteria?.evaluateCriteria(finalScreen, currentStep, finalDeviceState) ?: emptyList()
            } + deviceEvaluations
        ).distinctBy { it.description }
        val evidenceItems = criteriaEvaluations.map { ev ->
            EvidenceItem(
                label = ev.description,
                met = ev.state == com.aniob.core.domain.CriterionState.PASSED,
                detail = ev.detail,
                state = ev.state
            )
        }

        if (finalOutcome == null) {
            finalOutcome = when {
                !_uiState.value.isRunning -> Outcome.STOPPED
                currentStep >= maxSteps -> {
                    terminalReason = "Maximum steps ($maxSteps) reached"
                    Outcome.FAILED
                }
                stepResult?.terminalState == StepResult.TerminalState.SUCCESS ->
                    if (resolvedCriteria?.hasObservableCheck == true) Outcome.SUCCESS else Outcome.UNVERIFIED
                stepResult?.terminalState == StepResult.TerminalState.FAIL -> Outcome.FAILED
                else -> Outcome.FAILED
            }
        } else if (!completedByIntent && finalOutcome == Outcome.SUCCESS &&
            resolvedCriteria?.hasObservableCheck != true
        ) {
            // Confirmed with no machine-checkable criterion: honest "unverified", never a failure.
            finalOutcome = Outcome.UNVERIFIED
        }

        // FINAL GATE (BUG-01/06): SUCCESS may not survive an unmet or unprobed criterion — even
        // when a pipeline step or a model Finish claimed it. This is the single place every run
        // passes through, so no future code path can quietly opt out of device evidence.
        if (finalOutcome == Outcome.SUCCESS) {
            val unsupported = criteriaEvaluations.filter {
                it.state != com.aniob.core.domain.CriterionState.PASSED && it.description.startsWith("Device state:")
            }
            if (unsupported.isNotEmpty()) {
                finalOutcome = Outcome.UNVERIFIED
                terminalReason = "Completion not supported by device evidence: " +
                    unsupported.joinToString("; ") { "${it.description} — ${it.detail}" }
            }
        }
        if (finalOutcome == Outcome.SUCCESS &&
            resolvedCriteria?.requireInstallConfirmation == true &&
            resolvedCriteria.mustBeInstalled.isEmpty()
        ) {
            // "install <unknown app>" only reached a store SEARCH. There is no package id to query,
            // so the run cannot prove an install happened — honest UNVERIFIED, never SUCCESS.
            finalOutcome = Outcome.UNVERIFIED
            terminalReason = "Store search opened, but the install target is unresolved; " +
                "PackageManager cannot confirm completion."
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val isSuccess = (finalOutcome == Outcome.SUCCESS)
        val outcomeCopy = com.aniob.app.outcome.AniobOutcomeCopy.forTask(
            outcome = finalOutcome ?: Outcome.FAILED,
            reason = terminalReason.ifBlank { "Unmet criteria" },
            taskLabel = task.rawPrompt,
            completedChecks = evidenceItems.filter { it.met }.map { it.label },
            outstandingChecks = evidenceItems.filter { !it.met }.map { it.label }
        )
        val resultSummary = outcomeCopy.message

        // Research metric closure: latency comes from elapsedRealtime inside the manager, LLM calls
        // and steps were counted as they happened, and success is the deterministic verdict above.
        // endTask appends the row, rewrites the summary table and logs RESEARCH_METRICS.
        val researchFailureReason = when {
            isSuccess -> ""
            finalOutcome == Outcome.STOPPED -> "stopped"
            finalOutcome == Outcome.INTERRUPTED -> "interrupted"
            finalOutcome == Outcome.UNVERIFIED -> terminalReason.ifBlank { "unverified" }
            else -> terminalReason.ifBlank { "unmet_criteria" }
        }
        if (isSuccess) {
            persistRememberTransitions(
                mode = researchMode,
                goal = task.clarifiedGoal.ifBlank { task.rawPrompt },
                transitions = rememberTransitions
            )
        }
        val researchRun = app.researchTelemetry.endTask(isSuccess, researchFailureReason)
        val researchLine = researchRun?.let { app.researchTelemetry.hudLine(it) }
        val researchSummaryRow = researchRun?.let { app.researchTelemetry.summaryFor(it.mode) }

        val consolidatedSkill = if (isSuccess && researchMode.skillsEnabled) {
            hippocampusTracker.consolidateOnSuccess(
                taskId = task.id,
                prompt = task.clarifiedGoal,
                screenReads = screenReads,
                actions = actionsDispatched,
                escalations = escalations,
                elapsedMs = totalDuration
            )
        } else {
            hippocampusTracker.onFailure()
            null
        }
        if (consolidatedSkill != null) {
            runCatching {
                val skillsDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills")
                semanticSkillMatcher.loadAndIndexDirectory(skillsDir)
            }
            app.eventLogger.info("AniobHippocampus", "Consolidated learned skill '$consolidatedSkill'")
        }
        app.eventLogger.recordSessionCounters(
            screenReads = screenReads,
            actions = actionsDispatched,
            escalations = escalations,
            elapsedMs = totalDuration,
            stateTrace = hippocampusTracker.currentStateTrace(),
            providerDecisionReason = decisionReason,
            lastRoutingReason = _uiState.value.lastRoutingReason
        )

        val execOutcome = when (finalOutcome) {
            Outcome.SUCCESS -> ExecOutcome.SUCCESS
            Outcome.STOPPED -> ExecOutcome.STOPPED
            Outcome.INTERRUPTED -> ExecOutcome.INTERRUPTED
            Outcome.UNVERIFIED -> ExecOutcome.UNVERIFIED
            Outcome.FAILED -> ExecOutcome.FAILED_VERIFICATION
        }
        ExecReport(
            outcome = execOutcome,
            reason = resultSummary,
            screenReads = screenReads,
            actions = actionsDispatched,
            escalations = escalations,
            elapsedMs = totalDuration,
            stateTrace = hippocampusTracker.currentStateTrace(),
            providerUsed = primaryProvider,
            decisionReason = decisionReason
        ).toMetricSnapshot(taskId = task.id, stepIndex = currentStep + 1).also { snapshot ->
            app.eventLogger.info(
                "AniobMetrics",
                "SNAPSHOT{task=${snapshot.taskId},provider=${snapshot.provider}," +
                    "screenReads=${snapshot.screenReads},actions=${snapshot.actions}," +
                    "escalations=${snapshot.escalations},routing=${snapshot.lastRoutingReason}}"
            )
        }

        val summaryCard = buildSummary(
            prompt = task.rawPrompt,
            outcome = finalOutcome,
            steps = currentStep + 1,
            durationMs = totalDuration,
            provider = primaryProvider,
            evidence = evidenceItems,
            reason = terminalReason.ifBlank { null }
        )
        postSummaryCard(summaryCard)
        // Step 1 invariant: SUCCESS survives only with observer-made evidence; otherwise UNVERIFIED.
        recordSealedOutcome(
            task.id,
            AniobOutcomeBridge.seal(
                outcome = finalOutcome ?: Outcome.FAILED,
                reason = terminalReason.ifBlank { resultSummary },
                evaluations = criteriaEvaluations
            )
        )
        setPhase(AgentPhase.IDLE)
        clearRunningMarker()

        AniobBackgroundController.onTaskFinished(
            app,
            resultSummary,
            isSuccess = isSuccess,
            unverified = finalOutcome == Outcome.UNVERIFIED,
            openChat = finalOutcome == Outcome.FAILED || finalOutcome == Outcome.UNVERIFIED,
            wasStopped = finalOutcome == Outcome.STOPPED,
            resultTitle = outcomeCopy.title
        )

        val chatBadge = when (finalOutcome) {
            Outcome.SUCCESS -> "Success"
            Outcome.UNVERIFIED -> "Unverified"
            Outcome.STOPPED -> "Stopped"
            Outcome.INTERRUPTED -> "Interrupted"
            Outcome.FAILED -> "Failed"
        }
        val assistantMessage = ChatMessage(
            id = "msg_asst_${java.util.UUID.randomUUID()}",
            role = "assistant",
            content = outcomeCopy.message,
            badge = chatBadge,
            stepIndex = currentStep + 1
        )

        viewModelScope.launch {
            app.metricsCollector.recordSession(
                taskId = task.id,
                prompt = task.rawPrompt,
                isSuccess = isSuccess,
                steps = currentStep + 1,
                durationMs = totalDuration,
                tokensUsed = totalTokens,
                providerUsed = primaryProvider,
                decisionReason = decisionReason
            )
            // A shortcut completion has no step trajectory to learn from; recording an empty one
            // would teach the pipeline nothing and pollute the store.
            if (isSuccess && !completedByIntent && stepResult?.terminalState != StepResult.TerminalState.SUCCESS) {
                val trajectory = verifiedActions.toList()
                learningPipeline.onTaskSuccess(
                    TaskContext(
                        instruction = task.rawPrompt,
                        successCriteria = resolvedCriteria,
                        taskId = task.id,
                        lastScreenState = finalScreen
                    ),
                    trajectory
                )
            }
        }

        updateToIdle {
            it.copy(
                statusMessage = resultSummary,
                lastProviderUsed = primaryProvider,
                chatMessages = it.chatMessages + assistantMessage,
                researchStatusLine = researchLine ?: it.researchStatusLine,
                researchLastRun = researchRun ?: it.researchLastRun,
                researchSummary = researchSummaryRow ?: it.researchSummary
            )
        }
    }

    private fun addStepRecord(step: AniobStepRecord) {
        if (step.verifiedSuccess) verifiedActions.add(step.action)
        _uiState.update { it.copy(steps = it.steps + step) }
    }

    /** Actions of steps that verified, in order — the input to the learning pipeline. */
    private val verifiedActions = mutableListOf<AniobAction>()

    /**
     * Evidence gate for a provisional `Finish`.
     *
     * Shared by every ladder branch so none of them can declare success on an unverified screen.
     * An empty criteria set carries no evidence, so it is reported unverified — the caller then
     * treats the Finish as rejected rather than silently accepting it.
     */
    private fun verifyProvisionalFinish(
        finish: AniobAction.Finish,
        finalScreen: AniobScreenState,
        steps: Int,
        criteria: SuccessCriteria?
    ): DeterministicVerifier.VerificationResult =
        DeterministicVerifier.verifyFinish(criteria, finalScreen, steps)

    private suspend fun executeActionSync(accessibility: AniobAccessibilityService?, action: AniobAction, expectedTreeHash: String? = null): Boolean {
        // Block until the UI is quiescent so we never dispatch into a running animation.
        AniobWaitForIdle.waitForIdle(1000)
        if (accessibility == null) return false
        val deferred = CompletableDeferred<Boolean>()
        accessibility.executeAction(action, expectedTreeHash) { success ->
            deferred.complete(success)
        }
        return withTimeoutOrNull(SWIPE_CALLBACK_TIMEOUT_SECONDS * 1000L) { deferred.await() } ?: false
    }

    private data class VerifiedStep(
        val screenAfter: AniobScreenState,
        val executedAction: AniobAction,
        val verification: DeterministicVerifier.VerificationResult,
        val scrollRecovered: Boolean
    )

    /**
     * Executes an action, verifies it, and - when the target appears to have been off-screen -
     * scrolls the scrollable container to reveal it and retries once. This is what stops the
     * long-list failure mode where a valid element below the fold is reported as not found.
     */
    private suspend fun executeVerifyAndRecover(
        accessibility: AniobAccessibilityService?,
        action: AniobAction,
        taskPrompt: String,
        currentScreen: AniobScreenState
    ): VerifiedStep {
        executeActionSync(accessibility, action)
        // Same two-tier wait the step pipeline uses: no fixed settle delay here either.
        val fast = FastVerify.await(
            preAction = currentScreen,
            expectedTexts = FastVerify.expectedTexts(action, null),
            windowEvents = { accessibility?.windowEventSequence() },
            capturePoll = { accessibility?.captureCurrentScreenState() },
            screenshot = { captureVerifyScreenshot() }
        )
        val screenAfter = fast.screen ?: accessibility?.captureCurrentScreenState() ?: currentScreen
        val verification = DeterministicVerifier.verify(action, currentScreen, screenAfter)

        val recoverable = action.semanticTarget() != null && isTargetLikelyMissing(verification)
        if (!recoverable) return VerifiedStep(screenAfter, action, verification, false)

        return scrollRecoverAndRetry(accessibility, action, taskPrompt, currentScreen)
            ?: VerifiedStep(screenAfter, action, verification, false)
    }

    private fun isTargetLikelyMissing(verification: DeterministicVerifier.VerificationResult): Boolean {
        if (verification.isSuccessful) return false
        val reason = verification.reason.lowercase()
        return reason.contains("no-effect") || reason.contains("no effect") ||
            reason.contains("not found") || reason.contains("element") ||
            reason.contains("observable effect") || reason.contains("failed")
    }

    private fun targetMissKey(action: AniobAction, node: AniobNode? = null): String? {
        val target = action.semanticTarget() ?: return null
        fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase()
        val stableNode = node?.let {
            when {
                it.isPassword -> "protected:${normalize(it.className)}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}"
                it.viewId.isNotBlank() -> "id:${normalize(it.viewId)}"
                it.text.isNotBlank() -> "text:${normalize(it.text)}"
                it.contentDescription.isNotBlank() -> "desc:${normalize(it.contentDescription)}"
                else -> "node:${normalize(it.className)}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}"
            }
        }
        return (stableNode ?: when (target) {
            is SemanticTarget.ResourceId -> "id:${normalize(target.id)}"
            is SemanticTarget.Text -> "text:${normalize(target.text)}"
            is SemanticTarget.ContentDesc -> "desc:${normalize(target.desc)}"
            is SemanticTarget.SomIndex -> "som:${target.index}"
            is SemanticTarget.NormalizedCoordinate -> "coord:${target.normX},${target.normY}"
            is SemanticTarget.BoundingBox -> "box:${target.left},${target.top},${target.right},${target.bottom}"
        }).takeIf { it.length > 4 }
    }

    private fun recordStepTargetOutcome(
        taskId: String,
        result: StepResult,
        planningScreen: AniobScreenState
    ): Int? {
        val groundedNode = result.resolvedNode ?: com.aniob.core.execution.AniobActionExecutor
            .resolveTarget(result.action, planningScreen)?.node
        val key = targetMissKey(result.action, groundedNode) ?: return null
        if (result.verification?.isSuccessful == true || result.terminalState == StepResult.TerminalState.SUCCESS) {
            targetMissLedger.recordSuccess(taskId, key)
            return null
        }
        val noOp = result.dispatch as? com.aniob.core.execution.DispatchCommand.NoOp ?: return null
        return if (noOp.reason.startsWith("grounding_miss:")) {
            targetMissLedger.recordMiss(taskId, key)
        } else {
            null
        }
    }

    private fun recordTargetMiss(taskId: String, action: AniobAction, node: AniobNode? = null): Int? {
        val key = targetMissKey(action, node) ?: return null
        return targetMissLedger.recordMiss(taskId, key)
    }

    private fun recordTargetSuccess(taskId: String, action: AniobAction, node: AniobNode? = null) {
        targetMissKey(action, node)?.let { targetMissLedger.recordSuccess(taskId, it) }
    }

    /**
     * What to tell the user when a control could not be found twice. The task ends with this reason
     * (and a one-tap retry) instead of pausing mid-run to wait for a reply.
     */
    private fun targetMissReason(action: AniobAction, node: AniobNode? = null): String {
        val target = action.semanticTarget()
        val protectedTarget = node?.isPassword == true ||
            AskPolicy.category(action, node) == AskCategory.SECRET
        val label = if (protectedTarget) {
            "protected field"
        } else (
            node?.text?.takeIf { it.isNotBlank() }
                ?: node?.contentDescription?.takeIf { it.isNotBlank() }
                ?: when (target) {
                    is SemanticTarget.ResourceId -> target.id.substringAfterLast('/').ifBlank { "that control" }
                    is SemanticTarget.Text -> target.text
                    is SemanticTarget.ContentDesc -> target.desc
                    is SemanticTarget.SomIndex -> if (target.index == ActionBurst.UNNAMED_TARGET) "the text field" else "that control"
                    else -> "that control"
                }
            ).replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "that control" }
        return "I couldn't find '$label' on the screen after trying twice. Tell me what it is called, or where it is, and I'll try again."
    }

    private suspend fun scrollRecoverAndRetry(
        accessibility: AniobAccessibilityService?,
        action: AniobAction,
        taskPrompt: String,
        currentScreen: AniobScreenState
    ): VerifiedStep? {
        val service = accessibility ?: return null
        val keywords = promptKeywords(taskPrompt)

        fun matches(node: AniobNode): Boolean {
            val target = action.semanticTarget()
            if (target is SemanticTarget.SomIndex && node.id == target.index) return true
            return keywords.any { kw ->
                node.text.contains(kw, ignoreCase = true) ||
                    node.contentDescription.contains(kw, ignoreCase = true)
            }
        }

        val scrollResult = AniobScrollHelper.scrollUntilFound(
            screenState = currentScreen,
            targetPredicate = ::matches,
            taskPrompt = taskPrompt,
            capture = { service.captureCurrentScreenState() },
            executeSwipe = { swipe -> executeActionBlocking(service, swipe) }
        )
        if (!scrollResult.found) return null

        // Re-capture so node ids line up with the scrolled layout, then locate the target again.
        val revealedScreen = service.captureCurrentScreenState()
        val revealedNode = revealedScreen.nodes.firstOrNull(::matches) ?: scrollResult.node ?: return null
        val revealedTarget = SemanticTarget.SomIndex(revealedNode.id)
        val retryAction = when (action) {
            is AniobAction.InputText -> action.copy(target = revealedTarget)
            is AniobAction.LongPress -> action.copy(target = revealedTarget)
            else -> AniobAction.Tap(target = revealedTarget, thought = action.thought)
        }

        app.eventLogger.info(
            "AniobViewModel",
            "Scroll-until-found revealed node ${revealedNode.id} after ${scrollResult.swipes} swipes"
        )
        executeActionSync(accessibility, retryAction)
        val retryWait = FastVerify.await(
            preAction = revealedScreen,
            expectedTexts = FastVerify.expectedTexts(retryAction, null),
            windowEvents = { service.windowEventSequence() },
            capturePoll = { service.captureCurrentScreenState() },
            screenshot = { captureVerifyScreenshot() }
        )
        val screenAfter = retryWait.screen ?: service.captureCurrentScreenState()
        val verification = DeterministicVerifier.verify(retryAction, revealedScreen, screenAfter)
        return VerifiedStep(screenAfter, retryAction, verification, true)
    }

    private fun executeActionBlocking(service: AniobAccessibilityService, action: AniobAction): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        service.executeAction(action) { result ->
            success = result
            latch.countDown()
        }
        latch.await(SWIPE_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return success
    }

    private fun promptKeywords(taskPrompt: String): List<String> =
        taskPrompt.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in SCROLL_STOPWORDS }
            .distinct()

    /**
     * Waits for the window an intent opens, up to [timeoutMs], instead of a fixed delay: a cold
     * app start easily outlives 300 ms, and planning on the launcher costs a whole round trip.
     */
    private suspend fun awaitIntentScreen(beforePackage: String?, timeoutMs: Long, expectedPackage: String? = null): AniobScreenState? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val service = AniobAccessibilityService.instance
        val chooserPackages = setOf("android", "com.android.intentresolver", "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.android.packageinstaller")
        while (System.currentTimeMillis() < deadline) {
            val screen = service?.captureCurrentScreenState()
            if (screen != null) {
                val isDifferent = beforePackage == null || !screen.packageName.equals(beforePackage, ignoreCase = true)
                val matchesExpected = expectedPackage != null && screen.packageName.equals(expectedPackage, ignoreCase = true)
                if (matchesExpected || (expectedPackage == null && isDifferent)) {
                    val isChooser = screen.packageName in chooserPackages ||
                        screen.activityName.contains("Resolver", ignoreCase = true) ||
                        screen.activityName.contains("Chooser", ignoreCase = true)
                    if (isChooser && expectedPackage != null && !screen.packageName.equals(expectedPackage, ignoreCase = true)) {
                        return null
                    }
                    return screen
                }
            }
            delay(120)
        }
        return null
    }

    /** Runs one direct device command on the thread it needs (accessibility calls: main; queries: IO). */
    private suspend fun runDeviceCommand(command: DeviceCommand, args: Map<String, String>): DeviceControlResult =
        withContext(if (command.needsAccessibility) Dispatchers.Main else Dispatchers.IO) {
            AniobDeviceControl.execute(app, command, args, AniobAccessibilityService.instance)
        }

    /**
     * Resolves a bare toggle's device noun from the last USER messages only. Assistant prose often
     * mentions hardware ("the flashlight is off") and must never be treated as user intent.
     */
    private fun resolveContextualGoal(prompt: String): String {
        if (!AniobTaskIntentClassifier.looksLikeBareToggle(prompt)) return prompt
        val implied = _uiState.value.chatMessages.asReversed()
            .filter { it.role == "user" }
            .take(6)
            .firstNotNullOfOrNull { AniobTaskIntentClassifier.impliedDeviceNoun(it.content) }
            ?: return prompt
        return "$prompt $implied"
    }

    /**
     * Live device snapshot for success criteria: torch mode from CameraManager's callback mirror,
     * installed state from PackageManager for exactly the packages the criteria mention.
     */
    @Suppress("DEPRECATION")
    private fun probeDeviceState(criteria: SuccessCriteria?): DeviceStateSnapshot {
        val installed = criteria?.mustBeInstalled.orEmpty().associateWith { pkg ->
            runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
        return DeviceStateSnapshot(
            torchOn = AniobDeviceControl.currentTorchState(app),
            installedPackages = installed
        )
    }

    /** Device-side criteria evaluated against hardware/package state. Unknown is never success. */
    private fun evalDeviceAssertions(
        criteria: SuccessCriteria?,
        device: DeviceStateSnapshot = probeDeviceState(criteria)
    ): List<CriterionEvaluation> = criteria?.evaluateDeviceCriteria(device).orEmpty()

    /** Reflex path: 0 tokens. A command that cannot run says which setting to enable, then planning continues. */
    private suspend fun runDeviceShortcut(command: DeviceCommand, args: Map<String, String>): Boolean {
        val result = runDeviceCommand(command, args)
        app.eventLogger.info("AniobViewModel", "Device reflex ${command.wire}: ok=${result.ok}")
        postDeviceResult(result)
        return result.ok
    }

    /**
     * Shows answers ("12 GB free") and actionable blocks ("turn on notification access") in the chat.
     * Silent successes stay silent. Results never contain a verification code, so this is safe to show.
     */
    private fun postDeviceResult(result: DeviceControlResult) {
        if (!result.informational && result.blockedBy == null) return
        val message = ChatMessage(
            id = "msg_device_${System.currentTimeMillis()}",
            role = "assistant",
            content = (if (result.ok) "\uD83D\uDCF1 " else "\u26A0\uFE0F ") + result.message,
            stepIndex = 0
        )
        _uiState.update { it.copy(chatMessages = it.chatMessages + message) }
    }

    private suspend fun dispatchSystemIntent(shortcut: ResolvedIntentShortcut): Boolean {
        val systemAction = shortcut.extras[AniobIntentResolver.EXTRA_SYSTEM_ACTION]
        return try {
            when (systemAction) {
                AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT -> {
                    // Route through the VERIFIED device-control path: the torch state is read back
                    // after dispatch, so an unconfirmed toggle can no longer masquerade as success.
                    val state = shortcut.extras["state"] ?: shortcut.extras["enabled"]
                    val turnOff = state.equals("off", ignoreCase = true) || state.equals("false", ignoreCase = true) ||
                        shortcut.action.contains("off", ignoreCase = true)
                    val cmd = if (turnOff) DeviceCommand.TORCH_OFF else DeviceCommand.TORCH_ON
                    runDeviceCommand(cmd, emptyMap()).ok
                }
                AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO -> {
                    val state = AniobDeviceTelemetry.getRealState(app)
                    app.eventLogger.info(
                        "AniobViewModel",
                        "Device info: battery ${state.batteryPercent}% charging=${state.isCharging} " +
                            "thermal=${state.isThermalThrottled} network=${state.isNetworkAvailable} ram=${state.totalRamGb}GB"
                    )
                    // Surface the answer right back in the running transcript for M0-ExternalAI style tasks.
                    val summary = "Battery ${state.batteryPercent}% (charging: ${if (state.isCharging) "yes" else "no"}). " +
                        "RAM ${state.totalRamGb}GB. Thermal throttled: ${state.isThermalThrottled}. " +
                        "Network available: ${state.isNetworkAvailable}."
                    val deviceMsg = ChatMessage(
                        id = "msg_device_${System.currentTimeMillis()}",
                        role = "assistant",
                        content = "📱 $summary",
                        stepIndex = 0
                    )
                    _uiState.update { it.copy(chatMessages = it.chatMessages + deviceMsg) }
                    true
                }
                else -> {
                    val launchCtx = AniobAccessibilityService.instance ?: AniobBackgroundController.currentActivity ?: app
                    val intent = Intent(shortcut.action).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        // A URI-addressed intent (dial, view, contact insert) must not be pinned to a
                        // package: the system picks the handler that can open it.
                        if (shortcut.uriString == null) {
                            // launchActivity is stored flattened ("<pkg>/<activity>");
                            // setClassName wants the class name only, and a flattened one builds a
                            // component that cannot be resolved.
                            val activityClass = AniobLaunchHub.activityClassName(shortcut.launchActivity)
                            val targetPkg = shortcut.targetPackage
                            if (activityClass != null && targetPkg != null) {
                                setClassName(targetPkg, activityClass)
                            } else {
                                targetPkg?.let { setPackage(it) }
                            }
                        }
                        shortcut.uriString?.let { data = Uri.parse(it) }
                        shortcut.category?.let { addCategory(it) }
                        shortcut.dataType?.let { type = it }
                        shortcut.extras.forEach { (key, value) -> putShortcutExtra(this, key, value) }
                    }
                    // "open linkedin in chrome": the browser is a preference. Try it pinned first; if it
                    // cannot take the URL (not installed, disabled) the system default still can.
                    val preferred = shortcut.preferredPackage
                    if (preferred != null && shortcut.uriString != null) {
                        if (startActivitySafely(launchCtx, Intent(intent).setPackage(preferred))) return true
                        app.eventLogger.info(
                            "AniobViewModel",
                            "$preferred could not open the URL; using the system default browser"
                        )
                    }
                    if (startActivitySafely(launchCtx, intent)) return true
                    // Play Store missing/disabled: open the web storefront so the install task
                    // still lands on a terminal page instead of failing silently (BUG-05).
                    shortcut.extras[AniobIntentResolver.EXTRA_INSTALL_PACKAGE]?.let { installPkg ->
                        val web = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$installPkg")
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        if (startActivitySafely(launchCtx, web)) return true
                    }
                    // Some launchers only resolve with the package's own launch intent.
                    val pkg = shortcut.targetPackage ?: return false
                    val relaunch = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
                    relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivitySafely(launchCtx, relaunch)
                }
            }
        } catch (e: Exception) {
            app.eventLogger.error("AniobViewModel", "Failed to launch system intent: ${e.message}")
            false
        }
    }

    private fun startActivitySafely(context: android.content.Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            app.eventLogger.warn("AniobViewModel", "startActivity failed (${e.message})")
            false
        }

    /**
     * Extra values arrive as strings from the pure-JVM resolver; the clock and contact intents are
     * typed, and passing "7" where an Int is expected makes the target app ignore the extra.
     */
    private fun putShortcutExtra(intent: Intent, key: String, value: String) {
        when (key) {
            AniobIntentResolver.EXTRA_ALARM_HOUR,
            AniobIntentResolver.EXTRA_ALARM_MINUTES,
            AniobIntentResolver.EXTRA_ALARM_LENGTH -> value.toIntOrNull()?.let { intent.putExtra(key, it) }
            AniobIntentResolver.EXTRA_ALARM_SKIP_UI -> intent.putExtra(key, value.toBoolean())
            else -> intent.putExtra(key, value)
        }
    }

    fun stopCurrentTask() {
        val stoppingGen = ++activeGeneration
        val currentTask = _uiState.value.activeTask
        app.taskOwner.stop(currentTask?.id)
        activeTaskJob?.cancel()
        activeTaskJob = null

        app.approvalController.clearForTask(currentTask?.id ?: "")
        targetMissLedger.clear()
        warmupJob?.cancel()
        warmupJob = null

        AniobAccessibilityService.isTaskActive = false
        AniobForegroundService.clearConfirmation(app)
        hippocampusTracker.onFailure()

        val stoppedSummary = buildSummary(
            prompt = currentTask?.rawPrompt ?: "Task",
            outcome = Outcome.STOPPED,
            steps = _uiState.value.currentStep,
            durationMs = 0L,
            provider = _uiState.value.lastProviderUsed.ifBlank { "NONE" },
            evidence = emptyList(),
            reason = "Stopped. Earlier changes were not undone."
        )
        postSummaryCard(stoppedSummary)
        AniobBackgroundController.onTaskFinished(
            app,
            "Stopped. Earlier changes were not undone.",
            isSuccess = false,
            openChat = false,
            wasStopped = true,
            resultTitle = com.aniob.app.outcome.AniobOutcomeCopy
                .forTask(Outcome.STOPPED, "Stopped. Earlier changes were not undone.").title
        )
        updateToIdle { it.copy(statusMessage = "Stopped. Earlier changes were not undone.", isStreaming = false, streamingBubbleText = "") }
    }

    override fun onCleared() {
        super.onCleared()
        app.onMemoryTrimListener = null
        AniobAccessibilityService.onServiceLost = null
        AniobForegroundService.onStopRequestedFromNotification = null
        AniobForegroundService.onPauseToggleFromNotification = null
        AniobForegroundService.onConfirmDecisionWithIds = null
        runCatching { localLlmProvider.release() }
    }

    companion object {
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

        /** Plans that cannot execute even one member before the run stops batching. */
        const val BURST_MAX_ZERO_PROGRESS_REPLANS = 2

        /**
         * Budget for hydrating learned state before step 0 yields to the model. Hydration keeps
         * running in the background; a cold cache must not add seconds to the first action.
         */
        const val HYDRATION_BUDGET_MS = 300L

        /** Upper bound for best-effort start-up warm-ups (gateway connection); never blocks a task. */
        const val WARMUP_BUDGET_MS = 4_000L

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
