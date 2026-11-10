package com.aniob.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aniob.core.diff.AniobScreenDiff
import com.aniob.core.domain.*
import com.aniob.core.perception.AniobScreenCaptureSource
import com.aniob.core.perception.AniobFusedIndex
import com.aniob.core.perception.AniobPerceptionTier
import com.aniob.core.perception.CompactNode
import com.aniob.core.perception.ObserveSnapshot
import com.aniob.core.perception.ObserveTreeCompactor
import android.view.accessibility.AccessibilityWindowInfo

import com.aniob.core.execution.AniobActionExecutor
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.optimizer.AniobTokenOptimizer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ScreenshotCaptureResult(
    val bitmap: android.graphics.Bitmap? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val status: ScreenshotStatus
)

enum class ScreenshotStatus {
    SUCCESS,
    UNSUPPORTED_API,
    FAILED,
    RETRY_EXHAUSTED
}

/**
 * High-performance Accessibility Service for Aniob UI Automation.
 * References ARTEMIS (https://github.com/google/artemis) & PrivateAgent (https://github.com/deepakraj-18/mobileappagent).
 */
class AniobAccessibilityService : AccessibilityService(), AniobScreenCaptureSource {

    companion object {
        private const val TAG = "AniobAccessibility"
        private const val MAX_IDLE_WAIT_MS = 1000L
        private const val IDLE_POLL_INTERVAL_MS = 50L

        /** Hard cap on references retained from one compact pass (prompt nodes are <= 60). */
        private const val MAX_COMPACT_REFS = 400

        /** Time between focusing a field and committing text into it (the IME re-layout). */
        private const val FOCUS_SETTLE_MS = 100L

        /** Bounds on the scrollable-container walk used by the pre-observe scroll. */
        private const val SCROLL_SEARCH_DEPTH = 12
        private const val SCROLL_SEARCH_NODES = 8

        /**
         * Research switch for the compact observe pipeline. Static so a task started before the
         * service connects still gets the right behaviour on the first capture.
         */
        @Volatile
        var fastObservationEnabled: Boolean = false
            private set

        /** Set once per task by the ViewModel from the active ablation mode. */
        fun setFastObservation(enabled: Boolean) {
            fastObservationEnabled = enabled
        }

        /**
         * Research ablation switch for the event-driven capture skip (waterfall level 1) (M-3).
         * Companion field so tasks started before service binds get the correct flag.
         */
        @Volatile
        private var researchFastDeltaVerifyEnabled: Boolean = false

        fun setResearchFastDeltaVerify(enabled: Boolean) {
            researchFastDeltaVerifyEnabled = enabled
        }

        private val XML_CONTROL_CHARS_REGEX = Regex("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]")
        var instance: AniobAccessibilityService? = null
            private set
        var isServiceConnected: Boolean = false
            private set
        var isTaskActive: Boolean = false

        /** Answer of the last device command dispatched through this service (replays, skills). */
        @Volatile
        var lastDeviceResult: com.aniob.app.device.DeviceControlResult? = null

        /**
         * Fired when the service is destroyed, carrying whether a task was still running.
         * UX-6 turns this into the "Aniob lost access" recovery surface. Zero-cost when unset.
         */
        @Volatile
        var onServiceLost: ((wasTaskActive: Boolean) -> Unit)? = null

        @Volatile
        var isRecordingDemo: Boolean = false
        private val demoRecordedEvents = mutableListOf<com.aniob.core.skills.RecordedDemoEvent>()

        fun startDemoRecording() {
            synchronized(demoRecordedEvents) {
                demoRecordedEvents.clear()
            }
            isRecordingDemo = true
        }

        fun stopDemoRecording(): List<com.aniob.core.skills.RecordedDemoEvent> {
            isRecordingDemo = false
            return synchronized(demoRecordedEvents) {
                demoRecordedEvents.toList()
            }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val spotlightOverlay by lazy { com.aniob.app.overlay.AniobSpotlightOverlay(this) }
    private val floatingStatusOverlay by lazy {
        com.aniob.app.overlay.AniobFloatingStatusOverlay(this) {
            com.aniob.app.background.AniobBackgroundController.onStopCallback?.invoke()
        }
    }

    fun showFloatingOverlay(stepIndex: Int, narration: String) {
        floatingStatusOverlay.show(stepIndex, narration)
    }

    fun updateFloatingOverlay(stepIndex: Int, narration: String) {
        floatingStatusOverlay.update(stepIndex, narration)
    }

    fun hideFloatingOverlay() {
        floatingStatusOverlay.hide()
    }

    private var bridgeServer: com.aniob.app.bridge.AniobBridgeServer? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    @Volatile private var contentChangedSinceLastStep: Boolean = true
    @Volatile private var lastScreenState: AniobScreenState? = null

    /**
     * Class name of the last `TYPE_WINDOW_STATE_CHANGED` event, i.e. the activity or dialog that
     * owns the current window. Used only as the activity part of the structural screen hash; the
     * verification path keeps reading [AniobScreenState.activityName], which the platform capture
     * has never filled.
     */
    @Volatile private var lastWindowClassName: String = ""

    /** Monotonic window-event sequence recorded at the start of the previous capture (B-6). */
    @Volatile private var lastCapturedEventSeq: Long = -1L

    /**
     * Live node handles retained for the latest compact observation, keyed by prompt id.
     * Replaced (and the previous batch recycled) on every capture, so the map is always in step
     * with `lastScreenState`.
     */
    private val refLock = Any()
    private var latestNodeRefs: Map<Int, AccessibilityNodeInfo> = emptyMap()

    /** Serialises compact captures so one pass cannot recycle another pass's node handles. */
    private val compactCaptureLock = Any()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
        // Copy-paste flows: remember clipboard changes while this service is allowed to read them.
        com.aniob.app.device.AniobClipboardWatcher.start(this)
        notifySystemState()
        // Refresh the app registry off the main thread to avoid ANR on devices with hundreds of
        // apps. This is the *launchable* inventory (MAIN/LAUNCHER), hydrated at application start
        // by AniobAppBootstrap — never a getInstalledApplications() dump of packages that have no
        // launcher entry and therefore cannot be opened by the UI-automation loop.
        serviceScope.launch {
            try {
                val launchable = com.aniob.app.knowledge.AniobAppBootstrap.refresh(
                    this@AniobAccessibilityService,
                    force = com.aniob.core.knowledge.AniobAppRegistry.getInstance().isEmpty()
                )
                Log.i(TAG, "App registry ready: $launchable launchable apps")
            } catch (e: Exception) {
                Log.e(TAG, "App registry refresh failed", e)
            }
        }
        if (com.aniob.app.BuildConfig.DEBUG) {
            try {
                bridgeServer = com.aniob.app.bridge.AniobBridgeServer()
                bridgeServer?.start()
                Log.i(TAG, "AniobBridgeServer started on port 8765")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start AniobBridgeServer", e)
            }
        }
        Log.i(TAG, "AniobAccessibilityService successfully connected and ready.")
    }

    /**
     * Monotonic count of window-structure events. The post-action verifier polls this cheap
     * counter every 50 ms and reads the node tree only when it moved, instead of walking the
     * tree on every tick.
     */
    private val windowEventCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /** Current value of the window-event counter; any change means the window moved. */
    fun windowEventSequence(): Long = windowEventCounter.get()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            windowEventCounter.incrementAndGet()
        }
        if (isRecordingDemo) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val source = event.source
                    val pkg = event.packageName?.toString()
                    val text = (source?.text ?: event.text.firstOrNull())?.toString()
                    val desc = (source?.contentDescription ?: event.contentDescription)?.toString()
                    val resId = source?.viewIdResourceName
                    synchronized(demoRecordedEvents) {
                        demoRecordedEvents.add(
                            com.aniob.core.skills.RecordedDemoEvent(
                                actionType = "click",
                                resourceId = resId,
                                text = text,
                                contentDescription = desc,
                                packageName = pkg
                            )
                        )
                    }
                    source?.recycle()
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val source = event.source
                    val pkg = event.packageName?.toString()
                    val text = event.text.joinToString(" ")
                    val desc = source?.contentDescription?.toString()
                    val resId = source?.viewIdResourceName
                    if (text.isNotBlank()) {
                        synchronized(demoRecordedEvents) {
                            demoRecordedEvents.add(
                                com.aniob.core.skills.RecordedDemoEvent(
                                    actionType = "type_text",
                                    resourceId = resId,
                                    text = source?.text?.toString(),
                                    contentDescription = desc,
                                    inputText = text,
                                    packageName = pkg
                                )
                            )
                        }
                    }
                    source?.recycle()
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.takeIf { it.isNotBlank() }?.let { lastWindowClassName = it }
        }

        if (!isTaskActive) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                contentChangedSinceLastStep = true
                com.aniob.core.policy.AniobWaitForIdle.onContentChanged(event.className)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AniobAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        recycleLatestRefs()
        com.aniob.app.device.AniobClipboardWatcher.stop()
        floatingStatusOverlay.hide()
        instance = null
        isServiceConnected = false
        // Accessibility access dies mid-task: surface the loss so UX-6's recovery flow can react.
        AniobAccessibilityService.onServiceLost?.invoke(AniobAccessibilityService.isTaskActive)
        AniobAccessibilityService.isTaskActive = false
        try {
            bridgeServer?.stop()
            bridgeServer = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop bridgeServer", e)
        }
        serviceScope.cancel()
        notifySystemState()
    }

    /** Pushes the new connection truth into the reactive system state (UX-0 §1). */
    private fun notifySystemState() {
        try {
            com.aniob.app.AniobApplication.instance.systemState.onAccessibilityChanged(isServiceConnected)
        } catch (_: Exception) {
            // Application not initialised in this process (e.g. isolated test); safe to skip.
        }
    }

    /**
     * Reflex 5: onTrimMemory releases transient caches immediately (zero LLM).
     * Drops the cached screen so the next capture is fresh, and lets the
     * ViewModel (which owns the LiteRT engine) know we are under memory pressure.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            lastScreenState = null
            recycleLatestRefs()
            contentChangedSinceLastStep = true
            Log.i(TAG, "onTrimMemory level=$level -> cleared cached screen state (reflex, no LLM)")
        }
    }

    /**
     * Plan-1 implementation: multi-window traversal, batched IPC, rich semantics,
     * visibility parity with bounds clipping, and hard caps (75 depth / 8000 nodes).
     */
    override fun captureScreen(includeInvisible: Boolean): AniobScreenState {
        // The compact pipeline runs only for the two fast ablation conditions; baseline keeps the
        // existing raw traversal so the comparison measures pruning, not a refactor.
        if (fastObservationEnabled) return captureCompactScreen()
        return captureLegacyScreen(includeInvisible)
    }

    private fun captureLegacyScreen(includeInvisible: Boolean): AniobScreenState {
        val seqAtCaptureStart = windowEventSequence()
        recycleLatestRefs()
        val rawNodes = mutableListOf<AniobNode>()
        var primaryPackage = currentForegroundPackage().orEmpty()
        val displayMetrics = resources.displayMetrics
        val screenBounds = AniobRect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)

        val windowList = try {
            windows
        } catch (_: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }

        if (windowList.isNotEmpty()) {
            val appWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val targetWindows = if (appWindows.isNotEmpty()) {
                windowList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } else {
                windowList
            }

            for (w in targetWindows) {
                val root = w.root ?: continue
                if (primaryPackage.isEmpty() && root.packageName != null) {
                    primaryPackage = root.packageName.toString()
                }
                val winRect = Rect()
                w.getBoundsInScreen(winRect)
                val windowClip = AniobRect(winRect.left, winRect.top, winRect.right, winRect.bottom).intersect(screenBounds)
                traverseNodeBounded(
                    node = root,
                    list = rawNodes,
                    depth = 0,
                    clipBounds = windowClip,
                    includeInvisible = includeInvisible
                )
                root.recycle()
            }
        } else {
            val root = rootInActiveWindow
            if (root != null) {
                primaryPackage = root.packageName?.toString().orEmpty()
                traverseNodeBounded(
                    node = root,
                    list = rawNodes,
                    depth = 0,
                    clipBounds = screenBounds,
                    includeInvisible = includeInvisible
                )
                root.recycle()
            }
        }

        // Apply TokenOptimizer rule: Max 60 actionable nodes with Set-of-Mark tags
        val optimizedNodes = AniobTokenOptimizer.optimize(rawNodes)
        val fusedIndex = AniobFusedIndex(optimizedNodes)

        val seqNow = seqAtCaptureStart
        val hasCustomSurface = rawNodes.any {
            it.className.contains("WebView", ignoreCase = true) ||
                it.className.contains("SurfaceView", ignoreCase = true) ||
                it.className.contains("Canvas", ignoreCase = true)
        }
        val eventChanged = (lastCapturedEventSeq != -1L && seqNow != lastCapturedEventSeq) || hasCustomSurface
        lastCapturedEventSeq = seqNow

        val waterfall = AniobScreenDiff.evaluateWaterfall(
            hasAccessibilityContentChanged = eventChanged || !researchFastDeltaVerifyEnabled,
            currentNodes = optimizedNodes,
            previousScreen = lastScreenState
        )

        val structuralSignature = optimizedNodes.filter { it.isClickable || it.isEditable || it.isScrollable || it.isChecked }
            .joinToString("|") { "${it.className}#${it.viewId}" }
        val legacyScreenHash = com.aniob.core.diff.AniobScreenDiff.computeScreenHash64(
            primaryPackage,
            lastWindowClassName,
            structuralSignature
        )

        val screenState = AniobScreenState(
            packageName = primaryPackage,
            activityName = lastWindowClassName,
            nodes = optimizedNodes,
            fusedIndex = fusedIndex,
            perceptionTier = AniobPerceptionTier.FAST,
            treeHash = waterfall.treeHash,
            screenshotBase64 = null,
            changedRegion = waterfall.changedRegion,
            isEventDrivenSkip = waterfall.waterfallLevelUsed == 1,
            screenHash = legacyScreenHash,
            observationKind = "legacy",
            windowEventSeq = seqNow
        )
        lastScreenState = screenState
        return screenState
    }

    fun captureCurrentScreenState(): AniobScreenState = captureScreen(includeInvisible = false)

    // ---------------------------------------------------------------------------------------------
    // Compact observation (the two fast research conditions)
    // ---------------------------------------------------------------------------------------------

    /**
     * One hierarchical pass -> pure-JVM prune/merge -> sequential ids -> structural hash, keeping
     * a live node reference per surviving id. No bitmap is taken here; the caller decides with
     * `ObserveCapturePolicy` whether pixels are actually needed for this step.
     */
    private fun captureCompactScreen(): AniobScreenState =
        synchronized(compactCaptureLock) { captureCompactScreenLocked() }

    private fun captureCompactScreenLocked(): AniobScreenState {
        val seqAtCaptureStart = windowEventSequence()
        recycleLatestRefs()
        val refs = ArrayList<AccessibilityNodeInfo>(64)
        val roots = ArrayList<ObserveSnapshot>(2)
        var primaryPackage = currentForegroundPackage().orEmpty()
        val displayMetrics = resources.displayMetrics
        val screenBounds = AniobRect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)

        val windowList = try {
            windows
        } catch (_: Exception) {
            emptyList<AccessibilityWindowInfo>()
        }

        val rootsToRead = ArrayList<Pair<AccessibilityNodeInfo, AniobRect>>(2)
        if (windowList.isNotEmpty()) {
            val appWindows = windowList.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val targetWindows = if (appWindows.isNotEmpty()) {
                windowList.filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
            } else {
                windowList
            }
            for (w in targetWindows) {
                val root = w.root ?: continue
                if (primaryPackage.isEmpty()) root.packageName?.toString()?.let { primaryPackage = it }
                val winRect = Rect()
                w.getBoundsInScreen(winRect)
                val clip = AniobRect(winRect.left, winRect.top, winRect.right, winRect.bottom)
                    .intersect(screenBounds)
                rootsToRead.add(root to clip)
            }
        } else {
            rootInActiveWindow?.let { root ->
                primaryPackage = root.packageName?.toString().orEmpty()
                rootsToRead.add(root to screenBounds)
            }
        }

        try {
            for ((root, clip) in rootsToRead) {
                val snapshot = collectSnapshot(root, 0, clip, refs)
                if (snapshot != null) {
                    roots.add(snapshot)
                    if (snapshot.platformIndex < 0) recycleQuietly(root)
                } else {
                    recycleQuietly(root)
                }
            }

            // Activity identity comes from the last window-state event: it makes the hash
            // discriminate between screens that share a package and a structure.
            val observation = ObserveTreeCompactor.compact(roots, primaryPackage, lastWindowClassName)
            val used = HashSet<Int>(observation.nodes.size * 2)
            val byId = HashMap<Int, AccessibilityNodeInfo>(observation.nodes.size * 2)
            for (node in observation.nodes) {
                used.add(node.platformIndex)
                refs.getOrNull(node.platformIndex)?.let { byId[node.id] = it }
            }
            refs.forEachIndexed { index, ref -> if (index !in used) recycleQuietly(ref) }
            synchronized(refLock) { latestNodeRefs = byId }

            Log.d(
                TAG,
                "compact observe: ${observation.nodes.size} nodes (merged ${observation.mergedNodeCount}) " +
                    "hash=${observation.screenHash}"
            )
            val nodes = observation.nodes.map { it.toAniobNode() }
            val seqNow = seqAtCaptureStart
            val hasCustomSurface = nodes.any {
                it.className.contains("WebView", ignoreCase = true) ||
                    it.className.contains("SurfaceView", ignoreCase = true) ||
                    it.className.contains("Canvas", ignoreCase = true)
            } || observation.labeledInteractiveCount < 3
            val eventChanged = (lastCapturedEventSeq != -1L && seqNow != lastCapturedEventSeq) || hasCustomSurface
            lastCapturedEventSeq = seqNow

            val waterfall = AniobScreenDiff.evaluateWaterfall(
                hasAccessibilityContentChanged = eventChanged || !researchFastDeltaVerifyEnabled,
                currentNodes = nodes,
                previousScreen = lastScreenState
            )

            val screenState = AniobScreenState(
                packageName = primaryPackage,
                activityName = lastWindowClassName,
                nodes = nodes,
                fusedIndex = AniobFusedIndex(nodes),
                perceptionTier = AniobPerceptionTier.FAST,
                treeHash = waterfall.treeHash,
                screenshotBase64 = null,
                changedRegion = waterfall.changedRegion,
                isEventDrivenSkip = waterfall.waterfallLevelUsed == 1,
                screenHash = observation.screenHash,
                observationKind = "compact",
                windowEventSeq = seqNow
            )
            lastScreenState = screenState
            return screenState
        } catch (e: Exception) {
            // A half-finished pass must not leak platform references.
            refs.forEach(::recycleQuietly)
            synchronized(refLock) { latestNodeRefs = emptyMap() }
            Log.w(TAG, "Compact observation failed; falling back to the raw traversal", e)
            return captureLegacyScreen(includeInvisible = false)
        }
    }

    /**
     * Builds the platform-neutral snapshot for one node. Nodes that cannot be acted on and carry
     * no content are traversed but not retained, so the reference list stays small.
     */
    private fun collectSnapshot(
        node: AccessibilityNodeInfo,
        depth: Int,
        clipBounds: AniobRect,
        refs: MutableList<AccessibilityNodeInfo>
    ): ObserveSnapshot? {
        if (depth > 75 || refs.size >= MAX_COMPACT_REFS) return null
        if (!node.isVisibleToUser) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = AniobRect(rect.left, rect.top, rect.right, rect.bottom).intersect(clipBounds)
        val text = node.text?.toString().orEmpty().sanitizeForXml()
        val description = node.contentDescription?.toString().orEmpty().sanitizeForXml()
        val errorText = node.error?.toString().orEmpty().sanitizeForXml()
        val tooltip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.tooltipText?.toString().orEmpty().sanitizeForXml()
        } else ""
        val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.paneTitle?.toString().orEmpty().sanitizeForXml()
        } else ""
        val isHeading = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.isHeading else false

        val interactive = node.isClickable || node.isCheckable || node.isEditable || node.isScrollable
        val keep = interactive || text.isNotBlank() || description.isNotBlank() ||
            errorText.isNotBlank() || tooltip.isNotBlank() || paneTitle.isNotBlank() || isHeading

        val nextClip = if (node.isScrollable) bounds else clipBounds
        val children = ArrayList<ObserveSnapshot>(node.childCount)
        for (i in 0 until node.childCount) {
            val child = if (Build.VERSION.SDK_INT >= 33) {
                node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) ?: node.getChild(i)
            } else {
                node.getChild(i)
            } ?: continue
            val snapshot = collectSnapshot(child, depth + 1, nextClip, refs)
            if (snapshot != null) {
                children.add(snapshot)
                if (snapshot.platformIndex < 0) recycleQuietly(child)
            } else {
                recycleQuietly(child)
            }
        }

        val platformIndex = if (keep) {
            refs.add(node)
            refs.size - 1
        } else {
            -1
        }

        return ObserveSnapshot(
            className = node.className?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            text = text,
            contentDescription = description,
            bounds = bounds,
            isVisible = true,
            isClickable = node.isClickable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isSelected = node.isSelected,
            isEnabled = node.isEnabled,
            isPassword = node.isPassword,
            isHeading = isHeading,
            errorText = errorText,
            paneTitle = paneTitle,
            tooltip = tooltip,
            platformIndex = platformIndex,
            children = children
        )
    }

    private fun CompactNode.toAniobNode(): AniobNode = AniobNode(
        id = id,
        className = className,
        // The merged label is what the model sees, so text search and grounding both use it.
        text = label,
        viewId = viewId,
        bounds = bounds,
        contentDescription = contentDescription,
        isClickable = isClickable,
        isEditable = isEditable,
        isScrollable = isScrollable,
        isSelected = isSelected,
        isChecked = isChecked,
        isEnabled = isEnabled,
        isPassword = isPassword,
        errorText = errorText,
        isHeading = isHeading,
        paneTitle = paneTitle,
        tooltip = tooltip,
        markup = markup
    )

    /**
     * Runs [action] on the retained node behind [nodeId].
     *
     * Only a node that can actually perform the action is used; anything else (no compact capture,
     * a recycled handle, a non-clickable label) returns false so the caller falls back to the
     * coordinate gesture.
     */
    private fun isNodeOccluded(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val wins = try { windows } catch (ex: IllegalStateException) { null } catch (ex: SecurityException) { null } ?: return false
        val nodeWindowId = node.windowId
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        for (w in wins) {
            if (w.id == nodeWindowId) break
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val winRect = Rect()
                w.getBoundsInScreen(winRect)
                if (Rect.intersects(winRect, nodeRect)) {
                    return true
                }
            }
        }
        return false
    }

    private fun performNodeAction(nodeId: Int?, action: Int): Boolean {
        if (nodeId == null) return false
        val node = currentObservedNode(nodeId) ?: return false
        if (isNodeOccluded(node)) {
            Log.w(TAG, "performNodeAction on nodeId=$nodeId aborted: occluded by higher window (B-3)")
            return false
        }
        return try {
            val capable = when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> node.isClickable
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> node.isLongClickable
                else -> true
            }
            com.aniob.core.perception.ObserveDispatchPolicy
                .preferNodeAction(nodeId, capable, node.isEnabled) && node.performAction(action)
        } catch (e: Exception) {
            Log.w(TAG, "performAction($action) on id=$nodeId failed: ${e.message}")
            false
        }
    }

    /** The live node behind the most recent compact id, when one was retained. */
    private fun currentObservedNode(id: Int): AccessibilityNodeInfo? =
        synchronized(refLock) { latestNodeRefs[id] }

    private fun recycleLatestRefs() {
        val previous = synchronized(refLock) {
            val current = latestNodeRefs
            latestNodeRefs = emptyMap()
            current
        }
        previous.values.forEach(::recycleQuietly)
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: Exception) {
            // Already recycled or the platform no longer owns it; nothing to do.
        }
    }

    private fun traverseNodeBounded(
        node: AccessibilityNodeInfo,
        list: MutableList<AniobNode>,
        depth: Int,
        clipBounds: AniobRect,
        includeInvisible: Boolean
    ) {
        if (depth > 75 || list.size >= 8000) return

        val isVisible = node.isVisibleToUser
        if (!isVisible && !includeInvisible) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val rawBounds = AniobRect(rect.left, rect.top, rect.right, rect.bottom)
        val clippedBounds = rawBounds.intersect(clipBounds)

        // Extract rich semantics (Plan-1.6)
        val errorText = node.error?.toString().orEmpty().sanitizeForXml()
        val tooltip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.tooltipText?.toString().orEmpty().sanitizeForXml() else ""
        val paneTitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.paneTitle?.toString().orEmpty().sanitizeForXml() else ""
        val isHeading = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.isHeading else false

        val sanitizedText = node.text?.toString().orEmpty().sanitizeForXml()
        val sanitizedDesc = node.contentDescription?.toString().orEmpty().sanitizeForXml()

        val nextClip = if (node.isScrollable) clippedBounds else clipBounds

        val aniobNode = AniobNode(
            id = list.size + 1,
            className = node.className?.toString().orEmpty(),
            text = sanitizedText,
            contentDescription = sanitizedDesc,
            viewId = node.viewIdResourceName.orEmpty(),
            bounds = clippedBounds,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isSelected = node.isSelected,
            isChecked = node.isChecked,
            isEnabled = node.isEnabled,
            isVisibleToUser = isVisible,
            isPassword = node.isPassword,
            errorText = errorText,
            isHeading = isHeading,
            paneTitle = paneTitle,
            tooltip = tooltip
        )
        list.add(aniobNode)

        for (i in 0 until node.childCount) {
            // Batched IPC hint: on API 33+ descendants can be prefetched
            val child = if (Build.VERSION.SDK_INT >= 33) {
                node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) ?: node.getChild(i)
            } else {
                node.getChild(i)
            } ?: continue
            traverseNodeBounded(child, list, depth + 1, nextClip, includeInvisible)
            child.recycle()
        }
    }

    private fun String.sanitizeForXml(): String =
        replace(XML_CONTROL_CHARS_REGEX, "").trim()

    /**
     * Real screenshot capture on Android 30+ using AccessibilityService.takeScreenshot.
     * Guarded with a bounded retry (max 1 retry after 300ms).
     * HardwareBuffer is released promptly.
     */
    suspend fun captureScreenshotAsync(): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotCaptureResult(status = ScreenshotStatus.UNSUPPORTED_API)
        }
        var result = takeScreenshotInternal()
        if (result.status != ScreenshotStatus.SUCCESS) {
            delay(300L)
            result = takeScreenshotInternal()
            if (result.status != ScreenshotStatus.SUCCESS) {
                return ScreenshotCaptureResult(status = ScreenshotStatus.RETRY_EXHAUSTED)
            }
        }
        return result
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotInternal(): ScreenshotCaptureResult =
        suspendCancellableCoroutine { continuation ->
            val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    bgExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val timestamp = screenshot.timestamp
                                val wrapped = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val copy = try {
                                    if (wrapped != null) {
                                        val maxDim = maxOf(wrapped.width, wrapped.height)
                                        if (maxDim > 1120) {
                                            val scale = 1120f / maxDim
                                            val targetW = (wrapped.width * scale).toInt().coerceAtLeast(1)
                                            val targetH = (wrapped.height * scale).toInt().coerceAtLeast(1)
                                            val scaled = android.graphics.Bitmap.createScaledBitmap(wrapped, targetW, targetH, true)
                                            val softwareCopy = scaled.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                            if (scaled != softwareCopy) scaled.recycle()
                                            softwareCopy
                                        } else {
                                            wrapped.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                        }
                                    } else null
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Screenshot copy/downscale failed (BL-2): ${t.message}")
                                    null
                                } finally {
                                    wrapped?.recycle()
                                    hardwareBuffer.close()
                                }
                                if (copy != null) {
                                    if (continuation.isActive) continuation.resume(ScreenshotCaptureResult(copy, timestamp, ScreenshotStatus.SUCCESS))
                                    else copy.recycle()
                                } else {
                                    if (continuation.isActive) continuation.resume(ScreenshotCaptureResult(status = ScreenshotStatus.FAILED))
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "Screenshot callback error: ${t.message}")
                                if (continuation.isActive) continuation.resume(ScreenshotCaptureResult(status = ScreenshotStatus.FAILED))
                            } finally {
                                bgExecutor.shutdown()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot failed with code $errorCode")
                            try {
                                if (continuation.isActive) continuation.resume(ScreenshotCaptureResult(status = ScreenshotStatus.FAILED))
                            } finally {
                                bgExecutor.shutdown()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "takeScreenshot threw exception: ${t.message}")
                bgExecutor.shutdown()
                if (continuation.isActive) continuation.resume(ScreenshotCaptureResult(status = ScreenshotStatus.FAILED))
            }
        }

    fun findScrollableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findScrollableRecursive(root)
    }

    private fun findScrollableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableRecursive(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    /**
     * Executes an atomic action via dispatchGesture, node actions, or a system intent.
     *
     * Two contracts hold on every call:
     * 1. Gesture-bearing actions are gated on UI quiescence first — a 300ms Android animation is
     *    long enough for a follow-up tap to land on stale coordinates or be swallowed entirely.
     * 2. The target is grounded against a **fresh** capture taken right here, never the cached
     *    planning screen. A miss calls back `false` and logs `grounding_miss`; we never fall back
     *    to a guessed coordinate.
     */
    fun executeAction(action: AniobAction, expectedTreeHash: String? = null, callback: (Boolean) -> Unit) {
        gateOnUiQuiescence(action)

        val liveScreen = try {
            captureCurrentScreenState()
        } catch (_: Exception) {
            if (expectedTreeHash != null) {
                callback(false)
                return
            }
            lastScreenState
        } ?: AniobScreenState(packageName = currentForegroundPackage().orEmpty())

        if (expectedTreeHash != null) {
            val target = action.semanticTarget()
            if (target != null && com.aniob.core.execution.AniobActionExecutor.resolveTarget(action, liveScreen) == null) {
                // Target genuinely gone from live screen -> replan (B-9)
                callback(false)
                return
            }
        }

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        when (val command = AniobActionExecutor.planDispatch(action, liveScreen, width, height)) {
            is DispatchCommand.TapAt -> {
                spotlightOverlay.flash(command.x.toFloat(), command.y.toFloat())
                // Node action first (a retained handle from the compact observation), gesture at
                // the recorded centre as the fallback.
                if (performNodeAction(command.nodeId, AccessibilityNodeInfo.ACTION_CLICK)) {
                    callback(true)
                } else {
                    dispatchTap(command.x.toFloat(), command.y.toFloat(), callback)
                }
            }

            is DispatchCommand.LongPressAt -> {
                spotlightOverlay.flash(command.x.toFloat(), command.y.toFloat())
                if (performNodeAction(command.nodeId, AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    callback(true)
                } else {
                    dispatchLongPress(command.x.toFloat(), command.y.toFloat(), command.durationMs, callback)
                }
            }

            is DispatchCommand.SetText -> {
                val node = liveScreen.findNodeById(command.nodeId)
                if (node != null) {
                    spotlightOverlay.flash(Rect(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom))
                }
                setTextOnNode(command, liveScreen, callback)
            }

            is DispatchCommand.SwipeGesture -> {
                spotlightOverlay.flash(command.startX.toFloat(), command.startY.toFloat())
                dispatchGesturePath(command, callback)
            }

            is DispatchCommand.SystemIntent -> handleSystemIntent(action, command, callback)

            is DispatchCommand.NoOp -> {
                Log.w(TAG, "grounding_miss: ${command.reason}")
                callback(false)
            }
        }
    }

    /**
     * Sets text on the node behind [DispatchCommand.SetText.nodeId].
     *
     * Compact captures keep a live reference per prompt id, so the common path is a direct
     * `performAction(ACTION_SET_TEXT)` with no second tree walk. The bounds search stays as the
     * fallback for raw captures and for a reference that went away between capture and dispatch.
     */
    private fun setTextOnNode(
        command: DispatchCommand.SetText,
        liveScreen: AniobScreenState,
        callback: (Boolean) -> Unit
    ) {
        val cached = if (fastObservationEnabled) currentObservedNode(command.nodeId) else null
        if (cached != null) {
            applySetText(cached, command, callback)
            return
        }

        val root = rootInActiveWindow ?: return callback(false)
        try {
            val targetNode = liveScreen.findNodeById(command.nodeId) ?: return callback(false)
            val bounds = Rect(
                targetNode.bounds.left, targetNode.bounds.top,
                targetNode.bounds.right, targetNode.bounds.bottom
            )
            val viewId = targetNode.viewId
            applySetTextFresh({ findFreshNode(viewId, bounds) }, command, callback)
        } finally {
            recycleQuietly(root)
        }
    }

    /**
     * Commits [DispatchCommand.SetText] onto a field the way a person does it.
     *
     * Several apps ignore `ACTION_SET_TEXT` on a field that was never focused (the browser
     * omnibox, the dialer's search box, contact form inputs), which is why "type the query" steps
     * used to fail and then send the model hunting the field again. So: focus, click to raise the
     * IME, give the field a moment, set the text, and — when the action says so — press the IME
     * action so a search actually happens.
     */
    private fun applySetText(
        node: AccessibilityNodeInfo,
        command: DispatchCommand.SetText,
        callback: (Boolean) -> Unit
    ) {
        val alreadyFocused = try { node.isFocused } catch (ex: IllegalStateException) { false }
        focusField(node, command)

        var attempts = 0
        fun checkAndCommit() {
            attempts++
            val fresh = try { node.refresh() } catch (ex: IllegalStateException) { false }
            val focused = try { node.isFocused } catch (ex: IllegalStateException) { false }
            val hasIme = try {
                windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
            } catch (ex: IllegalStateException) { false } catch (ex: SecurityException) { false }

            if (fresh && (focused || hasIme || attempts >= 5)) {
                commitText(node, command, callback)
            } else if (attempts < 5) {
                mainHandler.postDelayed({ checkAndCommit() }, 100L)
            } else {
                Log.w(TAG, "field not ready after 500ms on '${command.targetDescription}' (B-7)")
                callback(false)
            }
        }

        if (alreadyFocused) {
            checkAndCommit()
        } else {
            mainHandler.postDelayed({ checkAndCommit() }, 100L)
        }
    }

    /**
     * Commits text on a node re-resolved inside the delay window; the handle is recycled here.
     *
     * The fallback path used to hand [applySetText] a node obtained from the live tree, which the
     * caller recycled as soon as that call returned — so the delayed write ran on a recycled object
     * on API 30-32 and reported false. Re-resolving after the settle keeps the handle ours.
     */
    private fun applySetTextFresh(
        locate: () -> AccessibilityNodeInfo?,
        command: DispatchCommand.SetText,
        callback: (Boolean) -> Unit
    ) {
        val probe = try { locate() } catch (_: Exception) { null }
        val alreadyFocused = try { probe?.isFocused == true } catch (_: Exception) { false }
        if (probe != null) focusField(probe, command)
        val settle = if (alreadyFocused) 0L else FOCUS_SETTLE_MS
        val work = Runnable {
            val node = try { locate() } catch (_: Exception) { null }
            if (node == null) {
                Log.w(TAG, "field vanished before the commit on '${command.targetDescription}'")
                callback(false)
                return@Runnable
            }
            try { commitText(node, command, callback) } finally { recycleQuietly(node) }
        }
        if (settle == 0L) work.run() else mainHandler.postDelayed(work, settle)
    }

    private fun focusField(node: AccessibilityNodeInfo, command: DispatchCommand.SetText) {
        try {
            if (command.clearFirst) {
                val clearArgs = Bundle()
                clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            }
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                // Only click when focus did not make it editable: a click on an omnibox-style widget
                // can navigate instead of just raising the keyboard.
                if (!node.isEditable) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "focus on '${command.targetDescription}' failed: ${e.message}")
        }
    }

    /** Fresh handle for a field across a delay: view id first, bounds as the fallback. */
    private fun findFreshNode(viewId: String, bounds: Rect): AccessibilityNodeInfo? {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        return try {
            val byId = if (viewId.isNotBlank()) {
                root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            } else null
            byId ?: findAccessibilityNodeByBounds(
                root,
                AniobRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            )
        } catch (_: Exception) {
            null
        } finally {
            recycleQuietly(root)
        }
    }

    private fun commitText(
        node: AccessibilityNodeInfo,
        command: DispatchCommand.SetText,
        callback: (Boolean) -> Unit
    ) {
        try {
            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                command.text
            )
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.w(TAG, "ACTION_SET_TEXT rejected on '${command.targetDescription}'")
                callback(false)
                return
            }
            if (command.submit && !pressImeAction(node)) {
                // The text is in; only the submit could not be delivered. The verifier judges the
                // screen instead of the typing being replayed.
                Log.w(TAG, "IME submit unavailable on '${command.targetDescription}'")
            }
            callback(true)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SET_TEXT failed: ${e.message}")
            callback(false)
        }
    }

    /**
     * Presses the field's own IME action (search / go / enter) on API 30+, where the platform
     * exposes it as an accessibility action. Returns false when the node does not support it; the
     * caller keeps the typed text either way.
     */
    private fun pressImeAction(node: AccessibilityNodeInfo): Boolean {
        val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        return try {
            val supported = try {
                node.actionList.any { it.id == imeEnter }
            } catch (_: Exception) {
                true
            }
            if (!supported) return false
            if (node.performAction(imeEnter)) return true
            // Some builds only honour the action after the field reports itself as focused.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(imeEnter)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_IME_ENTER failed: ${e.message}")
            false
        }
    }

    /**
     * One `ACTION_SCROLL_FORWARD` on the largest scrollable container.
     *
     * Used by the pre-observe scroll: when a step's target could not be grounded, scrolling once
     * and re-reading the hierarchy is cheaper than asking the model to decide to scroll. The walk
     * is depth- and count-bounded, and only enabled, visible, scrollable nodes are performed on.
     */
    fun scrollForwardOnce(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            val candidates = ArrayList<AccessibilityNodeInfo>(8)
            collectScrollable(root, candidates, 0)
            val target = candidates.maxByOrNull { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                bounds.width() * bounds.height()
            }
            val scrolled = target?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            if (scrolled) Log.i(TAG, "Pre-observe scroll: ACTION_SCROLL_FORWARD sent")
            scrolled
        } catch (e: Exception) {
            Log.w(TAG, "scrollForwardOnce failed: ${e.message}")
            false
        } finally {
            recycleQuietly(root)
        }
    }

    private fun collectScrollable(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > SCROLL_SEARCH_DEPTH || out.size >= SCROLL_SEARCH_NODES) return
        if (node.isScrollable && node.isEnabled && node.isVisibleToUser) out.add(node)
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            } ?: continue
            collectScrollable(child, out, depth + 1)
            if (child !in out) recycleQuietly(child)
        }
    }

    private fun dispatchGesturePath(command: DispatchCommand.SwipeGesture, callback: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(command.startX.toFloat(), command.startY.toFloat())
        path.lineTo(command.endX.toFloat(), command.endY.toFloat())
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            command.durationMs.coerceAtLeast(1L)
        )
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(false)
                }
            },
            null
        )
    }

    private fun handleSystemIntent(
        action: AniobAction,
        command: DispatchCommand.SystemIntent,
        callback: (Boolean) -> Unit
    ) {
        when (command.name) {
            "OPEN_APP" -> {
                val packageName = command.argument.orEmpty()
                val activityClass = command.component
                    ?: com.aniob.core.knowledge.AniobAppRegistry.getInstance()
                        .byPackage(packageName)?.launchActivity
                        ?.substringAfter('/', "")
                val intent = buildOpenAppIntent(packageName = packageName, activityClass = activityClass)
                if (intent != null) {
                    // The single execution authority decides whether this dispatch actually happens:
                    // identical requests fired within the duplicate window are dropped, and the
                    // launch is recorded against whoever asked for it.
                    val gateway = (application as? com.aniob.app.AniobApplication)?.deviceActionGateway
                    if (gateway == null) {
                        startActivity(intent)
                        com.aniob.core.knowledge.AniobAppRegistry.getInstance().noteLaunch(packageName)
                        callback(true)
                    } else {
                        val outcome = gateway.execute(
                            taskId = null,
                            originAgent = "ACCESSIBILITY",
                            signature = com.aniob.core.execution.DeviceActionGateway.openAppSignature(packageName, activityClass),
                            requestedAction = "open_app",
                            resolvedApplication = packageName
                        ) {
                            startActivity(intent)
                            com.aniob.core.execution.DeviceActionGateway.DispatchResult(true, "launched $packageName")
                        }
                        if (outcome.ok) {
                            com.aniob.core.knowledge.AniobAppRegistry.getInstance().noteLaunch(packageName)
                        }
                        callback(outcome.ok)
                    }
                } else {
                    callback(false)
                }
            }
            "SYSTEM_KEY" -> {
                val key = (action as? AniobAction.SystemKey)?.key ?: (action as? AniobAction.PressKey)?.key
                // VOLUME_UP/DOWN used to fall through to BACK and navigate away from the app instead.
                val volume = when (key) {
                    KeyType.VOLUME_UP -> DeviceCommand.VOLUME_UP
                    KeyType.VOLUME_DOWN -> DeviceCommand.VOLUME_DOWN
                    else -> null
                }
                if (volume != null) {
                    runDeviceCommand(volume, mapOf("steps" to "1"), callback)
                } else {
                    val globalAction = when (key) {
                        KeyType.HOME -> GLOBAL_ACTION_HOME
                        KeyType.RECENTS -> GLOBAL_ACTION_RECENTS
                        else -> GLOBAL_ACTION_BACK
                    }
                    callback(performGlobalAction(globalAction))
                }
            }
            "DEVICE_CONTROL" -> {
                val device = action as? AniobAction.DeviceControl
                if (device == null) callback(false) else runDeviceCommand(device.command, device.args, callback)
            }
            "CLIPBOARD" -> {
                val clip = action as? AniobAction.Clipboard
                if (clip?.operation == ClipboardOp.SET && !clip.text.isNullOrEmpty()) {
                    runDeviceCommand(DeviceCommand.CLIPBOARD_SET, mapOf("text" to clip.text.orEmpty()), callback)
                } else {
                    runDeviceCommand(DeviceCommand.CLIPBOARD_GET, emptyMap(), callback)
                }
            }
            "GET_NOTIFICATIONS" -> runDeviceCommand(
                DeviceCommand.NOTIFICATION_LIST,
                command.argument?.let { mapOf("package" to it) } ?: emptyMap(),
                callback
            )
            "CONFIRM_WITH_USER" -> callback(true)
            "FINISH", "WAIT" -> callback(true)
            "TAKE_SCREENSHOT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                screenshot.hardwareBuffer.close()
                                callback(true)
                            }
                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "TAKE_SCREENSHOT failed with code $errorCode")
                                callback(false)
                            }
                        }
                    )
                } else {
                    callback(false)
                }
            }
            "GET_INSTALLED_APPS" -> runDeviceCommand(DeviceCommand.INSTALLED_APPS, emptyMap(), callback)
            "GET_SCREEN_INFO", "GET_DEVICE_INFO" -> {
                Log.w(TAG, "Unsupported utility action: ${command.name}")
                callback(false)
            }
            "FAIL" -> callback(false)
            else -> callback(false)
        }
    }

    /**
     * Runs a direct device command off the UI loop's critical path and reports success. Commands that
     * drive this service (global actions, focused-field edits) run on the main thread; the rest
     * (file index, contacts) on the service's IO scope. The answer is parked in [lastDeviceResult].
     */
    private fun runDeviceCommand(command: DeviceCommand, args: Map<String, String>, callback: (Boolean) -> Unit) {
        val work = {
            val fingerprint = args.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
            var captured: com.aniob.app.device.DeviceControlResult? = null
            val gateway = (application as? com.aniob.app.AniobApplication)?.deviceActionGateway
            val executed = if (gateway != null) {
                val outcome = gateway.execute(
                    taskId = null,
                    originAgent = "ACCESSIBILITY:DEVICE_COMMAND",
                    signature = com.aniob.core.execution.DeviceActionGateway.deviceCommandSignature(command.wire, fingerprint),
                    requestedAction = command.wire
                ) {
                    val result = com.aniob.app.device.AniobDeviceControl.execute(this, command, args, this)
                    captured = result
                    com.aniob.core.execution.DeviceActionGateway.DispatchResult(result.ok, result.message.take(300))
                }
                captured ?: com.aniob.app.device.DeviceControlResult(
                    ok = outcome.ok,
                    message = if (outcome.deduplicated) "That action was already dispatched moments ago." else outcome.record.detail,
                    informational = true
                )
            } else {
                com.aniob.app.device.AniobDeviceControl.execute(this, command, args, this)
            }
            lastDeviceResult = executed
            callback(executed.ok)
        }
        if (command.needsAccessibility) mainHandler.post(work) else serviceScope.launch { work() }
    }

    /**
     * Builds the launch intent for an app.
     *
     * An explicit component from the registry is preferred: it is the only form of launch that no
     * resolver/chooser dialog can intercept, so the step never lands on a "Open with…" sheet that
     * the UI-automation verifier would then read as the wrong package. `getLaunchIntentForPackage`
     * remains the fallback (and the null check the caller depends on).
     */
    private fun buildOpenAppIntent(packageName: String, activityClass: String?): android.content.Intent? {
        if (packageName.isBlank()) return null
        if (!activityClass.isNullOrBlank()) {
            val explicit = android.content.Intent().setClassName(packageName, activityClass)
            val resolvable = runCatching {
                packageManager.resolveActivity(explicit, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull()
            if (resolvable != null) {
                explicit.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                return explicit
            }
        }
        val implicit = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: return null
        implicit.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return implicit
    }

    /**
     * Package currently owning the active window, read live from the accessibility root. Used to detect
     * an OEM background-kill that invalidated our cached session before we reuse it.
     */
    fun currentForegroundPackage(): String? =
        try {
            val root = rootInActiveWindow ?: return null
            val packageName = root.packageName?.toString()
            root.recycle()
            packageName
        } catch (_: Exception) {
            null
        }

    private fun gateOnUiQuiescence(action: AniobAction) {
        // Suspending waitForIdle runs in the ViewModel; avoid blocking Thread.sleep in the service (BL-5).
        return
    }

    private fun dispatchLongPress(x: Float, y: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(400L))
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }

    private fun dispatchTap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }

    private fun findAccessibilityNodeByBounds(root: AccessibilityNodeInfo, targetRect: AniobRect): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.left == targetRect.left && rect.top == targetRect.top && rect.right == targetRect.right && rect.bottom == targetRect.bottom) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findAccessibilityNodeByBounds(child, targetRect)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }
}
