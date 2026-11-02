package com.aniob.core.memory

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection

/**
 * Online zero-LLM replay cache for verified transitions.
 *
 * Key: `normalisedGoalKeywords | packageName | preActionScreenHash`.
 * Value: the actions that were verified from that screen, stored by stable identity.
 *
 * The cache is consulted right after the intent-shortcut layer and before any model call, so a
 * hit costs 0 LLM requests. A miss — wrong goal, wrong package, wrong screen or a widget that is
 * no longer present — returns null and the caller falls through to normal planning.
 *
 * Pure JVM: the JSON text is produced and consumed here, the app layer only owns the file.
 */
class RememberCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Entry(
        /** Template key: a goal with its values cut out, or the keyword form when it has none. */
        val goalKey: String,
        val packageName: String,
        val screenHash: String,
        val actions: List<RememberAction>,
        val updatedAtMs: Long,
        val hits: Int = 0
    ) {
        val key: String get() = keyOf(goalKey, packageName, screenHash)
    }

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int
        @Synchronized get() = entries.size

    /** Copy of the entries; the save path serialises this instead of the live map. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.values.toList()

    /**
     * Records one verified transition. Returns false when the action is not replayable (the
     * vocabulary has actions that are meaningless out of context, such as Finish).
     */
    @Synchronized
    fun record(
        goal: String,
        packageName: String,
        preActionScreenHash: String,
        actions: List<RememberAction>
    ): Boolean {
        val parsed = RememberTemplate.parse(goal)
        val normalized = RememberTemplate.keyOf(goal)
        if (normalized.isBlank() || packageName.isBlank() || screenKey(preActionScreenHash).isBlank()) {
            return false
        }
        val usable = actions.filter { it.isUsable || it.kind == RememberAction.BACK || it.kind == RememberAction.SCROLL }
        if (usable.isEmpty()) return false

        val key = keyOf(normalized, packageName, preActionScreenHash)
        val previous = entries[key]
        entries[key] = Entry(
            goalKey = normalized,
            packageName = packageName,
            screenHash = screenKey(preActionScreenHash),
            // A value that came from the prompt is stored as a placeholder, so the same trace can
            // be replayed for another time/number/name without re-learning the screens.
            actions = usable.map { it.parameterised(parsed) },
            updatedAtMs = clock(),
            hits = previous?.hits ?: 0
        )
        evictIfNeeded()
        return true
    }

    /**
     * Returns ready-to-dispatch actions when this exact (goal, package, screen) was solved
     * before and every target still resolves on [screen]. Null means "fall through to the model".
     */
    @Synchronized
    fun lookup(goal: String, screen: AniobScreenState): List<AniobAction>? {
        if (entries.isEmpty()) return null
        val parsed = RememberTemplate.parse(goal)
        val normalized = RememberTemplate.keyOf(goal)
        if (normalized.isBlank()) return null
        val hash = screenKey(screen.screenHash.ifBlank { screen.treeHash })
        if (hash.isBlank()) return null
        val entry = entries[keyOf(normalized, screen.packageName, hash)] ?: return null
        if (entry.actions.isEmpty()) return null

        // The trace is stored with {PARAM_n} for the values that came from the prompt; a goal that
        // cannot fill one of them is a different task and must miss rather than tap a stale value.
        val actions = entry.actions.map { it.injected(parsed.params) ?: return null }
        val resolved = actions.map { replayOf(it, screen) ?: return null }
        entries[entry.key] = entry.copy(hits = entry.hits + 1, updatedAtMs = clock())
        return resolved
    }

    /** True when an entry exists for the key regardless of whether its targets resolve. */
    @Synchronized
    fun contains(goal: String, packageName: String, screenHash: String): Boolean =
        entries.containsKey(keyOf(RememberTemplate.keyOf(goal), packageName, screenHash))

    @Synchronized
    fun clear() = entries.clear()

    fun toJson(): String = RememberJson.write(this)

    companion object {
        const val MAX_ENTRIES = 300
        const val FILE_NAME = "remember_cache.json"

        /** Separator is part of the on-disk key; never change it without a cache version bump. */
        const val KEY_SEPARATOR = "|"

        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "to", "for", "of", "in", "on", "at", "with", "by",
            "please", "my", "me", "i", "you", "it", "this", "that", "then", "from", "is", "are",
            "open", "go", "tap", "click", "press", "app"
        )

        fun keyOf(goalKeywords: String, packageName: String, screenHash: String): String =
            "$goalKeywords$KEY_SEPARATOR$packageName$KEY_SEPARATOR${screenKey(screenHash)}"

        /** The hash component of a key: the screen hash, or the tree hash when it is blank. */
        fun screenKey(hash: String): String = hash.trim()

        /**
         * Lowercased keyword set in a stable order, so "Open Wi-Fi settings" and "wifi settings
         * open" produce the same key. Stop words and punctuation carry no intent and are dropped.
         */
        fun normalizeGoal(goal: String, maxKeywords: Int = 12): String {
            val keywords = goal.lowercase()
                // "Wi-Fi" and "wi fi" are the same word; join before splitting on separators.
                .replace(Regex("[-_]+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .split(' ')
                .filter { it.length > 1 && it !in STOP_WORDS }
                .distinct()
                .sorted()
            if (keywords.isEmpty()) return ""
            return keywords.take(maxKeywords).joinToString("-")
        }

        fun fromJson(text: String): RememberCache {
            val cache = RememberCache()
            RememberJson.read(text).forEach { entry ->
                cache.entries[entry.key] = entry
            }
            return cache
        }

        /** Builds a canonical action from a cached descriptor and the live node it matches. */
        private fun replayOf(descriptor: RememberAction, screen: AniobScreenState): AniobAction? {
            if (descriptor.kind == RememberAction.BACK) {
                return AniobAction.PressKey(KeyType.BACK)
            }
            if (descriptor.kind == RememberAction.SCROLL) {
                val direction = runCatching { SwipeDirection.valueOf(descriptor.direction) }
                    .getOrDefault(SwipeDirection.UP)
                return AniobAction.Swipe(direction = direction, distancePx = 600)
            }
            if (descriptor.kind == RememberAction.OPEN_APP) {
                val pkg = descriptor.text
                return if (pkg.isBlank()) null else AniobAction.OpenApp(packageName = pkg)
            }

            val node = resolve(descriptor, screen) ?: return null
            if (!node.isEnabled || !node.isVisibleToUser) return null
            val target = SemanticTarget.SomIndex(node.id)
            return when (descriptor.kind) {
                RememberAction.TAP -> AniobAction.Tap(target)
                RememberAction.LONG_PRESS -> AniobAction.LongPress(target)
                RememberAction.INPUT -> descriptor.inputText.takeIf { it.isNotBlank() }?.let {
                    AniobAction.InputText(target, it, clearFirst = descriptor.clearFirst)
                }
                else -> null
            }
        }

        /**
         * Resolves a cached descriptor against the live screen.
         *
         * Identity is exclusive: when the descriptor knows a view id, only that id may match; a
         * missing id is a miss even if some other widget now sits at the same coordinates. The
         * same applies to text and description. Coordinates are therefore a last resort only for
         * widgets that had no identity when the transition was recorded — replaying onto whatever
         * happens to occupy the spot would be a wrong tap, which is worse than a cache miss.
         *
         * A label shared by several widgets resolves only when one of them is enabled and visible;
         * an ambiguous match aborts the replay instead of guessing.
         */
        fun resolve(descriptor: RememberAction, screen: AniobScreenState): AniobNode? {
            if (descriptor.viewId.isNotBlank()) {
                return pickUnique(screen.nodes.filter { it.viewId == descriptor.viewId })
            }
            if (descriptor.text.isNotBlank()) {
                return pickUnique(screen.nodes.filter { it.text.equals(descriptor.text, ignoreCase = true) })
            }
            if (descriptor.contentDescription.isNotBlank()) {
                return pickUnique(
                    screen.nodes.filter {
                        it.contentDescription.equals(descriptor.contentDescription, ignoreCase = true)
                    }
                )
            }
            if (descriptor.normalizedX < 0.0 || descriptor.normalizedY < 0.0) return null
            val (width, height) = RememberAction.viewportOf(screen)
            return screen.nodes.filter { it.isEnabled && it.isVisibleToUser }.minByOrNull { node ->
                val cx = (node.bounds.left + node.bounds.right) / 2.0 / width
                val cy = (node.bounds.top + node.bounds.bottom) / 2.0 / height
                kotlin.math.abs(cx - descriptor.normalizedX) + kotlin.math.abs(cy - descriptor.normalizedY)
            }?.takeIf { node ->
                val cx = (node.bounds.left + node.bounds.right) / 2.0 / width
                val cy = (node.bounds.top + node.bounds.bottom) / 2.0 / height
                kotlin.math.abs(cx - descriptor.normalizedX) <= COORDINATE_TOLERANCE &&
                    kotlin.math.abs(cy - descriptor.normalizedY) <= COORDINATE_TOLERANCE
            }
        }

        /** One usable candidate, or null when the label is ambiguous or every copy is dead. */
        private fun pickUnique(candidates: List<AniobNode>): AniobNode? {
            val usable = candidates.filter { it.isEnabled && it.isVisibleToUser }
            if (usable.size == 1) return usable.single()
            if (usable.size > 1) {
                val actionable = usable.filter { it.isClickable || it.isEditable }
                if (actionable.size == 1) return actionable.single()
            }
            return null
        }

        /** Fraction of the viewport the centre may drift and still count as the same widget. */
        const val COORDINATE_TOLERANCE = 0.06
    }

    private fun evictIfNeeded() {
        while (entries.size > maxEntries) {
            val oldest = entries.values.minByOrNull { it.updatedAtMs } ?: break
            entries.remove(oldest.key)
        }
    }
}
