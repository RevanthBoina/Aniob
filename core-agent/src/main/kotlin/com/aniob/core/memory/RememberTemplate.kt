package com.aniob.core.memory

/**
 * Parameterised goal templates for the `Remember` replay cache.
 *
 * Exact string matching on the goal made the cache miss on every variation of the same task: a
 * verified "set alarm for 7:30" taught the agent nothing about "set alarm for 8:15", and the second
 * task paid the full model cost for an identical screen. So the goal is reduced to a template —
 * the words that carry the *shape* of the task — and the concrete values are kept aside as
 * parameters, in the order they appeared:
 *
 * ```
 * "set alarm for 7:30"       -> template "set alarm for {TIME}", params ["7:30"]
 * "calculate 25 plus 40"     -> template "calculate {NUM} plus {NUM}", params ["25", "40"]
 * "add contact "John Study"" -> template "add contact {NAME}", params ["John Study"]
 * ```
 *
 * The parameters are also what the cached action trace is allowed to vary on: a stored input text
 * or button label that *is* one of them is written as `{PARAM_1}`, `{PARAM_2}`… and filled back in
 * from the new prompt at replay time.
 *
 * Pure JVM, no state, unit tested.
 */
object RememberTemplate {

    /** One parsed goal: the template to key on, and the values that were cut out of it. */
    data class Parsed(
        val template: String,
        val params: List<String>,
        val kinds: List<Kind>
    ) {
        val hasParams: Boolean get() = params.isNotEmpty()

        /** 1-based index of the first parameter whose value equals [value], else null. */
        fun indexOfValue(value: String): Int? {
            if (value.isBlank()) return null
            val index = params.indexOfFirst { it.equals(value.trim(), ignoreCase = true) }
            return if (index >= 0) index + 1 else null
        }

        companion object {
            val EMPTY = Parsed("", emptyList(), emptyList())
        }
    }

    enum class Kind { TIME, NUM, NAME }

    private val NAME_STOP_WORDS = setOf(
        "Text", "Join", "Using", "From", "Her", "His", "Their", "The", "Saying", "Named", "Number",
        "Contact", "Save", "Add", "To", "Message", "Send", "Open", "App", "Wifi", "Settings"
    )

    private const val TRIGGER = "((?:[Nn]amed|[Cc]ontact|[Ss]ave|[Aa]dd|[Tt]o|[Tt]ext|[Mm]essage|[Ss]end|[Ff]or|[Ww]ith)\\s+)"
    private const val NAME_PATTERN = "([A-Z][a-zA-Z'.-]+(?:\\s+[A-Z][a-zA-Z'.-]+){0,2})"

    /**
     * Quoted name, triggered name, phone number, clock time, or number — in that order,
     * so names, phone numbers and times take precedence over bare integers.
     */
    private val PARAMETER = Regex(
        "\"([^\"]{1,60})\"" +
            "|$TRIGGER$NAME_PATTERN" +
            "|(\\+\\d[\\d\\s.-]{5,}\\d)" +
            "|(\\d{1,2}[:.]\\d{2}\\s*(?:[aA]\\.?[mM]\\.?|[pP]\\.?[mM]\\.?)?)" +
            "|\\b(\\d+(?:[.,]\\d+)?)\\b"
    )

    private val NOISE = Regex("[^a-z0-9 ]+")
    private val SPACES = Regex("\\s+")

    /** Splits a goal into its template and the parameter values that were cut out of it. */
    fun parse(goal: String): Parsed {
        if (goal.isBlank()) return Parsed.EMPTY

        val builder = StringBuilder()
        val params = ArrayList<String>(3)
        val kinds = ArrayList<Kind>(3)
        var last = 0

        for (match in PARAMETER.findAll(goal)) {
            val quoted = match.groupValues[1]
            val trigger = match.groupValues[2]
            val triggeredName = match.groupValues[3]
            val phone = match.groupValues[4]
            val time = match.groupValues[5]
            val number = match.groupValues[6]

            if (triggeredName.isNotEmpty()) {
                val words = triggeredName.split(" ")
                if (words.any { it in NAME_STOP_WORDS }) {
                    continue
                }
            }

            builder.append(clean(goal.substring(last, match.range.first)))
            when {
                quoted.isNotEmpty() -> {
                    params += quoted
                    kinds += Kind.NAME
                    builder.append(PARAM_NAME)
                }
                triggeredName.isNotEmpty() -> {
                    builder.append(clean(trigger))
                    params += triggeredName
                    kinds += Kind.NAME
                    builder.append(PARAM_NAME)
                }
                phone.isNotEmpty() -> {
                    params += phone
                    kinds += Kind.NUM
                    builder.append(PARAM_NUM)
                }
                time.isNotEmpty() -> {
                    params += time.trim()
                    kinds += Kind.TIME
                    builder.append(PARAM_TIME)
                }
                number.isNotEmpty() -> {
                    params += number
                    kinds += Kind.NUM
                    builder.append(PARAM_NUM)
                }
            }
            last = match.range.last + 1
        }
        builder.append(clean(goal.substring(last)))

        return Parsed(collapse(builder.toString()), params, kinds)
    }

    /** The cache key for a goal: a template with parameters, otherwise the keyword form. */
    fun keyOf(goal: String): String {
        val parsed = parse(goal)
        return if (parsed.hasParams) parsed.template else RememberCache.normalizeGoal(goal)
    }

    const val PARAM_TIME = "{TIME}"
    const val PARAM_NUM = "{NUM}"
    const val PARAM_NAME = "{NAME}"

    /** Placeholder written into a cached action whose value came from the prompt. */
    fun placeholder(index: Int): String = "{PARAM_$index}"

    private val PLACEHOLDER = Regex("\\{PARAM_(\\d+)\\}")

    /** Rewrites `{PARAM_n}` occurrences with [params] (1-based); null when one has no value. */
    fun inject(value: String, params: List<String>): String? {
        if (value.isEmpty() || !value.contains("{PARAM_")) return value
        var missing = false
        val replaced = PLACEHOLDER.replace(value) { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return@replace match.value
            val parameter = params.getOrNull(index - 1)
            if (parameter == null) {
                missing = true
                match.value
            } else {
                parameter
            }
        }
        return if (missing) null else replaced
    }

    /** True when [value] is a `{PARAM_n}` placeholder, i.e. it varies with the prompt. */
    fun isPlaceholder(value: String): Boolean = value.contains("{PARAM_")

    /** Lowercased, punctuation-free words, padded so a placeholder never fuses with a word. */
    private fun clean(segment: String): String {
        if (segment.isBlank()) return ""
        val lowered = segment.lowercase().replace(NOISE, " ").replace(SPACES, " ").trim()
        return if (lowered.isEmpty()) "" else " $lowered "
    }

    private fun collapse(text: String): String = text.replace(SPACES, " ").trim()
}
