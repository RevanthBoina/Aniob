package com.aniob.core.memory

import com.aniob.core.study.StudyBattery

/**
 * Seed trajectories for the benchmark battery, so a warm replay can be measured immediately.
 *
 * A cold batch costs a full model run per task before anything can be replayed. This seeds the
 * cache with the one hop that is knowable without seeing a device: *this task starts by opening
 * this app*. The entry is stored with [RememberCache.WILDCARD_SCREEN] (it applies to whichever
 * screen the app happens to open on) and with the goal's template key, so "compute 12 times 8" and
 * "compute 145 plus 55" share one entry despite their different numbers.
 *
 * What this deliberately does **not** do is invent the taps that follow. Anything past the first
 * hop is learned from a real, verified run — a seeded trace is a head start, never evidence that
 * the task succeeded, and every replayed step still has to resolve on the live screen.
 */
object RememberPresets {

    /** One benchmark task and the app it starts in, first installed package wins. */
    data class Preset(val goal: String, val packages: List<String>)

    private const val CLOCK = "com.google.android.deskclock"
    private const val CONTACTS = "com.google.android.contacts"
    private const val SETTINGS = "com.android.settings"
    private const val CALCULATOR = "com.google.android.calculator"
    private const val CHROME = "com.android.chrome"

    /** Ordered fallbacks: a stock build may ship the AOSP package instead of the Google one. */
    private val CLOCK_PACKAGES = listOf(CLOCK, "com.android.deskclock", "com.sec.android.app.clockpackage")
    private val CONTACT_PACKAGES = listOf(CONTACTS, "com.android.contacts")
    private val SETTINGS_PACKAGES = listOf(SETTINGS)
    private val CALCULATOR_PACKAGES = listOf(CALCULATOR, "com.android.calculator2", "com.sec.android.app.popupcalculator")
    private val CHROME_PACKAGES = listOf(CHROME, "com.android.browser")

    /** The fifteen study tasks, in study order, with the app each one starts in. */
    val studyBattery: List<Preset> = StudyBattery.tasks.map { task ->
        val packages = when {
            task.id.startsWith("study_01") || task.id.startsWith("study_02") ||
                task.id.startsWith("study_03") -> CLOCK_PACKAGES
            task.id.startsWith("study_04") || task.id.startsWith("study_05") ||
                task.id.startsWith("study_06") -> CONTACT_PACKAGES
            task.id.startsWith("study_07") || task.id.startsWith("study_08") ||
                task.id.startsWith("study_09") -> SETTINGS_PACKAGES
            task.id.startsWith("study_10") || task.id.startsWith("study_11") ||
                task.id.startsWith("study_12") -> CALCULATOR_PACKAGES
            else -> CHROME_PACKAGES
        }
        Preset(task.prompt, packages)
    }

    /**
     * Records one app-open hop per preset into [cache]. Returns how many entries were written.
     * [isInstalled] keeps a preset whose app is absent on this device from shadowing the model.
     */
    fun seed(cache: RememberCache, isInstalled: (String) -> Boolean = { true }): Int {
        var written = 0
        for (preset in studyBattery) {
            val packageName = preset.packages.firstOrNull(isInstalled) ?: continue
            val opened = cache.record(
                goal = preset.goal,
                packageName = packageName,
                preActionScreenHash = RememberCache.WILDCARD_SCREEN,
                actions = listOf(RememberAction(kind = RememberAction.OPEN_APP, text = packageName))
            )
            if (opened) written++
        }
        return written
    }
}
