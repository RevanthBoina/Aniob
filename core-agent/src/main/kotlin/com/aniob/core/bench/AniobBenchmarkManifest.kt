package com.aniob.core.bench

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SuccessCriteria

/**
 * Controlled Model Benchmark Manifest for Aniob (Phase 1).
 *
 * Separates:
 * A. Deterministic shortcut tests (Level 0 Intent / Level 1 FastPath).
 * B. Tasks that actually invoke local model generation (Level 2 SLM).
 *
 * Initial screens are illustrative snapshots, never a reset proof. Controlled cases supply a
 * separately measured BenchmarkStart and an independent oracle for the intended effect.
 *
 * Invariant: Shortcut-only successes are strictly NOT counted as model capability.
 */
object AniobBenchmarkManifest {

    enum class Track {
        DETERMINISTIC_SHORTCUT,
        MODEL_GENERATION
    }

    data class BenchmarkTask(
        val taskId: String,
        val track: Track,
        val goal: String,
        val initialScreenState: AniobScreenState,
        val allowedActionTypes: Set<String>,
        val requiresConfirmation: Boolean = false,
        val observableCriteria: SuccessCriteria,
        val timeoutSeconds: Int = 30,
        val maxSteps: Int = 6
    )

    // Helper node generator
    private fun n(id: Int, text: String, viewId: String = "", clickable: Boolean = true, editable: Boolean = false) = AniobNode(
        id = id,
        className = if (editable) "android.widget.EditText" else if (clickable) "android.widget.Button" else "android.widget.TextView",
        text = text,
        viewId = viewId,
        bounds = AniobRect(20, id * 80, 700, id * 80 + 60),
        isClickable = clickable,
        isEditable = editable
    )

    // Fixed 10 Harmless Benchmark Tasks
    val TASKS: List<BenchmarkTask> = listOf(
        // TRACK A: Deterministic shortcut tests
        BenchmarkTask(
            taskId = "bench_01_open_settings_shortcut",
            track = Track.DETERMINISTIC_SHORTCUT,
            goal = "Open Settings",
            initialScreenState = AniobScreenState(
                packageName = "com.aniob.app",
                activityName = "com.aniob.app.MainActivity",
                treeHash = "home_screen_hash",
                nodes = listOf(n(1, "Aniob Home"), n(2, "Chat"), n(3, "Tracker"))
            ),
            allowedActionTypes = setOf("open_app", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustShowPackage = listOf("com.android.settings")
            ),
            timeoutSeconds = 15,
            maxSteps = 2
        ),

        BenchmarkTask(
            taskId = "bench_02_open_clock_shortcut",
            track = Track.DETERMINISTIC_SHORTCUT,
            goal = "Open Clock",
            initialScreenState = AniobScreenState(
                packageName = "com.aniob.app",
                activityName = "com.aniob.app.MainActivity",
                treeHash = "home_screen_hash",
                nodes = listOf(n(1, "Aniob Home"), n(2, "Chat"))
            ),
            allowedActionTypes = setOf("open_app", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustShowPackage = listOf("com.google.android.deskclock")
            ),
            timeoutSeconds = 15,
            maxSteps = 2
        ),

        BenchmarkTask(
            taskId = "bench_03_open_calculator_shortcut",
            track = Track.DETERMINISTIC_SHORTCUT,
            goal = "Open Calculator",
            initialScreenState = AniobScreenState(
                packageName = "com.aniob.app",
                activityName = "com.aniob.app.MainActivity",
                treeHash = "home_screen_hash",
                nodes = listOf(n(1, "Aniob Home"), n(2, "Chat"))
            ),
            allowedActionTypes = setOf("open_app", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustShowPackage = listOf("com.google.android.calculator")
            ),
            timeoutSeconds = 15,
            maxSteps = 2
        ),

        // TRACK B: Tasks that actually invoke local model generation
        BenchmarkTask(
            taskId = "bench_04_find_battery_setting",
            track = Track.MODEL_GENERATION,
            goal = "Find Battery setting without changing it",
            initialScreenState = AniobScreenState(
                packageName = "com.android.settings",
                activityName = "com.android.settings.SettingsActivity",
                treeHash = "settings_main_hash_1",
                nodes = listOf(
                    n(1, "Search settings", "com.android.settings:id/search_action_bar", clickable = true, editable = false),
                    n(2, "Network & internet", clickable = true),
                    n(3, "Connected devices", clickable = true),
                    n(4, "Apps", clickable = true),
                    n(5, "Notifications", clickable = true),
                    n(6, "Battery", "com.android.settings:id/battery_pref", clickable = true),
                    n(7, "Storage", clickable = true),
                    n(8, "Sound & vibration", clickable = true),
                    n(9, "Display", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "swipe", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("Battery")
            ),
            timeoutSeconds = 30,
            maxSteps = 5
        ),

        BenchmarkTask(
            taskId = "bench_05_find_display_brightness",
            track = Track.MODEL_GENERATION,
            goal = "Navigate to Display settings to view Brightness level",
            initialScreenState = AniobScreenState(
                packageName = "com.android.settings",
                activityName = "com.android.settings.SettingsActivity",
                treeHash = "settings_main_hash_2",
                nodes = listOf(
                    n(1, "Search settings", clickable = true),
                    n(2, "Network & internet", clickable = true),
                    n(3, "Connected devices", clickable = true),
                    n(4, "Apps", clickable = true),
                    n(5, "Battery", clickable = true),
                    n(6, "Display", "com.android.settings:id/display_pref", clickable = true),
                    n(7, "Wallpaper", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "swipe", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("Display", "Brightness")
            ),
            timeoutSeconds = 30,
            maxSteps = 5
        ),

        BenchmarkTask(
            taskId = "bench_06_locate_about_phone",
            track = Track.MODEL_GENERATION,
            goal = "Scroll down in Settings and locate About Phone",
            initialScreenState = AniobScreenState(
                packageName = "com.android.settings",
                activityName = "com.android.settings.SettingsActivity",
                treeHash = "settings_main_top_hash",
                nodes = listOf(
                    n(1, "Network & internet", clickable = true),
                    n(2, "Apps", clickable = true),
                    n(3, "Battery", clickable = true),
                    n(4, "Storage", clickable = true),
                    n(5, "Sound & vibration", clickable = true),
                    n(6, "Display", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "swipe", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("About phone")
            ),
            timeoutSeconds = 40,
            maxSteps = 6
        ),

        BenchmarkTask(
            taskId = "bench_07_view_installed_apps",
            track = Track.MODEL_GENERATION,
            goal = "In Settings, select Apps and locate All apps list",
            initialScreenState = AniobScreenState(
                packageName = "com.android.settings",
                activityName = "com.android.settings.SettingsActivity",
                treeHash = "settings_apps_entry_hash",
                nodes = listOf(
                    n(1, "Network & internet", clickable = true),
                    n(2, "Connected devices", clickable = true),
                    n(3, "Apps", "com.android.settings:id/apps_pref", clickable = true),
                    n(4, "Notifications", clickable = true),
                    n(5, "Battery", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "swipe", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("Apps", "All apps")
            ),
            timeoutSeconds = 40,
            maxSteps = 6
        ),

        BenchmarkTask(
            taskId = "bench_08_navigate_alarm_tab",
            track = Track.MODEL_GENERATION,
            goal = "In Clock, switch to the Alarm tab without modifying alarms",
            initialScreenState = AniobScreenState(
                packageName = "com.google.android.deskclock",
                activityName = "com.android.deskclock.DeskClock",
                treeHash = "clock_main_hash",
                nodes = listOf(
                    n(1, "Alarm", "com.google.android.deskclock:id/tab_alarm", clickable = true),
                    n(2, "Clock", "com.google.android.deskclock:id/tab_clock", clickable = true),
                    n(3, "Timer", "com.google.android.deskclock:id/tab_timer", clickable = true),
                    n(4, "Stopwatch", "com.google.android.deskclock:id/tab_stopwatch", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("Alarm")
            ),
            timeoutSeconds = 25,
            maxSteps = 4
        ),

        BenchmarkTask(
            taskId = "bench_09_find_sound_vibration",
            track = Track.MODEL_GENERATION,
            goal = "Locate Sound and vibration settings",
            initialScreenState = AniobScreenState(
                packageName = "com.android.settings",
                activityName = "com.android.settings.SettingsActivity",
                treeHash = "settings_sound_hash",
                nodes = listOf(
                    n(1, "Search settings", clickable = true),
                    n(2, "Network & internet", clickable = true),
                    n(3, "Notifications", clickable = true),
                    n(4, "Battery", clickable = true),
                    n(5, "Storage", clickable = true),
                    n(6, "Sound & vibration", "com.android.settings:id/sound_pref", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "swipe", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("Sound")
            ),
            timeoutSeconds = 30,
            maxSteps = 5
        ),

        BenchmarkTask(
            taskId = "bench_10_search_setting_navigation",
            track = Track.MODEL_GENERATION,
            goal = "Tap Search settings, type Wi-Fi, and view search result",
            initialScreenState = AniobScreenState(
                packageName = "com.android.settings",
                activityName = "com.android.settings.SettingsActivity",
                treeHash = "settings_search_hash",
                nodes = listOf(
                    n(1, "Search settings", "com.android.settings:id/search_action_bar", clickable = true),
                    n(2, "Network & internet", clickable = true),
                    n(3, "Connected devices", clickable = true),
                    n(4, "Battery", clickable = true)
                )
            ),
            allowedActionTypes = setOf("tap", "input_text", "finish"),
            requiresConfirmation = false,
            observableCriteria = SuccessCriteria(
                mustContainText = listOf("Wi-Fi")
            ),
            timeoutSeconds = 45,
            maxSteps = 6
        )
    )

    fun getShortcutTasks(): List<BenchmarkTask> = TASKS.filter { it.track == Track.DETERMINISTIC_SHORTCUT }
    fun getModelGenerationTasks(): List<BenchmarkTask> = TASKS.filter { it.track == Track.MODEL_GENERATION }
}
