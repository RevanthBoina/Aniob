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
        com.aniob.app.workflow.WorkflowActionHost(platformTools).also { host ->
            host.onAudit = { line ->
                runCatching { eventLogger.info("WorkflowActionHost", line) }
            }
        }
    }

    /**
     * File-backed ledger store for cross-process idempotency: the bounded gateway ledger survives
     * process death so a retried executionId is never dispatched twice, and PENDING/EXECUTING
     * leftovers are reconciled to INTERRUPTED instead of being read as success.
     */
    private inner class DeviceActionLedgerFileStore : com.aniob.core.execution.DeviceActionGateway.DeviceActionLedgerStore {
        private val file = java.io.File(filesDir, "device_action_ledger.txt")
        private val tmp = java.io.File(filesDir, "device_action_ledger.tmp")
        override fun load(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
        override fun save(snapshot: String) {
            // Write-temp-then-rename so a torn write never leaves a half-parsed ledger.
            runCatching {
                tmp.writeText(snapshot)
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }

    /**
     * The single authority that executes device actions (app launches, system intents, device
     * commands). Holds the execution lock, dedupes near-simultaneous identical dispatches and keeps
     * a bounded ledger so retries and audits are possible. Lazily created; [eventLogger] is already
     * initialized in onCreate before any lazy is first touched.
     */
    val deviceActionGateway: com.aniob.core.execution.DeviceActionGateway by lazy {
        com.aniob.core.execution.DeviceActionGateway(
            ledgerStore = DeviceActionLedgerFileStore(),
            logSink = { entry ->
                val line = buildString {
                    append("executionId=").append(entry.executionId)
                    entry.taskId?.let { append(" taskId=").append(it) }
                    append(" origin=").append(entry.originAgent)
                    append(" action=").append(entry.requestedAction)
                    append(" signature=").append(entry.signature)
                    entry.resolvedApplication?.let { append(" resolvedApp=").append(it) }
                    append(" state=").append(entry.state)
                    append(" detail=").append(entry.detail.take(200))
                }
                runCatching { eventLogger.info("DeviceActionGateway", line) }
            }
        )
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

    /** "Sign in with an account" cloud source. Lazy: costs nothing unless configured and used. */
    val accountAuth: com.aniob.app.auth.AniobAccountContainer by lazy {
        com.aniob.app.auth.AniobAccountContainer(this)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
    override fun onLowMemory() {
        super.onLowMemory()
        eventLogger.info("AniobApplication", "onLowMemory -> reflex releasing LiteRT engine")
        onMemoryTrimListener?.invoke(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
