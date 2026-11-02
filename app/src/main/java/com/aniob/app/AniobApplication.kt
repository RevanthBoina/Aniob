package com.aniob.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.aniob.app.db.AniobDatabase
import com.aniob.app.knowledge.AniobAppBootstrap
import com.aniob.app.metrics.EventLogger
import com.aniob.app.memory.RememberCacheFile
import com.aniob.app.metrics.MetricsCollector
import com.aniob.app.research.ResearchTelemetryManager
import com.aniob.app.system.AniobSystemState

class AniobApplication : Application(), ComponentCallbacks2 {

    lateinit var database: AniobDatabase
        private set
    lateinit var eventLogger: EventLogger
        private set
    lateinit var metricsCollector: MetricsCollector
        private set

    /** The single reactive source of permission/service truth (UX-0 §1). */
    lateinit var systemState: AniobSystemState
        private set

    val workflowRepository: com.aniob.app.workflow.WorkflowRepository by lazy {
        com.aniob.app.workflow.WorkflowRepository(database)
    }
    val taskOwner: com.aniob.app.workflow.TaskOwner by lazy {
        com.aniob.app.workflow.TaskOwner()
    }
    val approvalController: com.aniob.app.workflow.ApprovalController by lazy {
        com.aniob.app.workflow.ApprovalController()
    }
    val destinationPreferences: com.aniob.app.workflow.DestinationPreferences by lazy {
        com.aniob.app.workflow.DestinationPreferences(this)
    }
    val workflowPayloadStore: com.aniob.app.workflow.WorkflowPayloadStore by lazy {
        com.aniob.app.workflow.WorkflowPayloadStore(this)
    }
    val platformTools: com.aniob.app.workflow.PlatformTools by lazy {
        com.aniob.app.workflow.PlatformTools(this)
    }
    val workflowActionHost: com.aniob.app.workflow.WorkflowActionHost by lazy {
        com.aniob.app.workflow.WorkflowActionHost(platformTools)
    }
    val incomingContentReader: com.aniob.app.workflow.IncomingContentReader by lazy {
        com.aniob.app.workflow.IncomingContentReader(this)
    }
    val workflowCoordinator: com.aniob.app.workflow.WorkflowCoordinator by lazy {
        com.aniob.app.workflow.WorkflowCoordinator(
            repository = workflowRepository,
            taskOwner = taskOwner,
            approvalController = approvalController,
            platformTools = platformTools,
            payloadStore = workflowPayloadStore,
            actionHost = workflowActionHost,
            destinationPreferences = destinationPreferences
        )
    }

    /**
     * Paper metrics (latency, LLM calls, success rate) and the active ablation mode.
     * One instance per process: the per-task counters live here, and the CSVs are shared by the
     * Settings selector, the task pipeline and the Stats screen.
     */
    val researchTelemetry: ResearchTelemetryManager by lazy {
        ResearchTelemetryManager(this)
    }

    /**
     * Zero-LLM replay cache for verified transitions (`filesDir/remember_cache.json`).
     * The in-memory copy lives in the ViewModel; this only owns the file.
     */
    val rememberCacheFile: RememberCacheFile by lazy {
        RememberCacheFile.forContext(this)
    }

    /**
     * Registered by AniobViewModel so the LiteRT engine can be released on
     * memory pressure without the Application holding an Android runtime dependency.
     */
    var onMemoryTrimListener: ((level: Int) -> Unit)? = null

    companion object {
        lateinit var instance: AniobApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AniobDatabase.getDatabase(this)
        eventLogger = EventLogger(database)
        metricsCollector = MetricsCollector(database)
        systemState = AniobSystemState(this).also { it.start() }

        // Learn every launchable app + the platform's role answers (clock, calculator, browser…)
        // once, at startup. The cache is read synchronously so the first task already resolves app
        // names; the PackageManager probe and the rewrite happen on a background thread.
        AniobAppBootstrap.install(this)

        eventLogger.info("AniobApplication", "Aniob Agent Engine initialized successfully.")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            eventLogger.info("AniobApplication", "onTrimMemory level=$level -> reflex releasing LiteRT engine")
            onMemoryTrimListener?.invoke(level)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onLowMemory() {
        eventLogger.info("AniobApplication", "onLowMemory -> reflex releasing LiteRT engine")
        onMemoryTrimListener?.invoke(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
