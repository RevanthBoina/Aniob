package com.aniob.core.capability.pattern

import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.DeviceCommand

/**
 * The pattern engine: prompt text is matched against DECLARATIVE capability grammar (verbs + slot
 * shapes), then slots are resolved generically. There is no `when (appName)` and no per-task patch.
 *
 * Properties enforced here and covered by UniversalPatternTest:
 *  1. Every emitted [PlanAtom] carries a checkable [Postcondition] (enforced by PlanAtom's init).
 *  2. Any entity value works: app names/places/numbers/messages are slots, not enum members.
 *  3. Any compositional goal is split generically; unresolved clauses become clarifications.
 *  4. `UniversalOutcomePolicy` can only return Completed when every atom was PROVEN.
 */
object CapabilityRegistry {

    private val TORCH_NOUN = Regex("""\b(?:flashlight|torch|torchlight)\b""")
    private val ON_CUE = Regex("""\b(?:on|enable|activate|switch\s+on|turn\s+on)\b""")
    private val OFF_CUE = Regex("""\b(?:off|disable|deactivate|switch\s+off|turn\s+off)\b""")
    private val INSTALL = Regex("""^(?:install|download|get)\s+(?:(?:the|an?)\s+)?(.+?)(?:\s+app)?$""")
    private val OPEN = Regex("""^(?:open|launch|start)\s+(?:(?:the|an?)\s+)?(.+?)$""")
    private val MESSAGE = Regex(
        """^(?:send\s+)?(?:an?\s+)?(sms|text|message|whatsapp|telegram|email|mail)\s+to\s+(.+?)(?:\s+(?:saying|with(?: message)?|that says|about|:)\s*(.+))?$"""
    )
    private val CALL = Regex("""^(?:call|dial|phone)\s+(.+)$""")
    private val NAVIGATE = Regex("""^(?:navigate|directions|drive|go|take\s+me)\s+(?:to\s+)?(.+)$""")
    private val VOLUME_SET = Regex("""^(?:set\s+)?(?:the\s+)?(?:media\s+|ring\s+|alarm\s+)?volume\s+(?:to\s+)?(\d{1,3})\s*%?$""")
    private val DND = Regex("""^(?:turn\s+)?(?:on|off|enable|disable)\s+(?:the\s+)?(?:do\s+not\s+disturb|dnd)$|^(?:do\s+not\s+disturb|dnd)\s+(?:on|off)$""")

    val specs: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            id = CapabilityId.INSTALL_APP,
            description = "Install any store app by name; the terminal state is the package being installed.",
            verbs = setOf("install", "download", "get")
        ) match@{ text ->
            val m = INSTALL.find(text) ?: return@match null
            val target = m.groupValues[1].trim()
            // "download"/"get" only mean install when the object is an app; "install" is explicit.
            if (!text.startsWith("install") && !Regex("""\bapp$""").containsMatchIn(text)) return@match null
            if (target.isBlank()) null else Slots(target = target)
        },
        CapabilitySpec(
            id = CapabilityId.TORCH,
            description = "Any torch/flashlight toggle, any word order; on+off becomes a sequence.",
            verbs = setOf("turn on", "turn off", "switch on", "switch off", "enable", "disable", "on", "off")
        ) match@{ text ->
            if (!TORCH_NOUN.containsMatchIn(text)) return@match null
            val on = ON_CUE.containsMatchIn(text)
            val off = OFF_CUE.containsMatchIn(text)
            if (!on && !off) return@match null
            when {
                on && off -> Slots(on = null, secondary = "sequence")   // ON, then OFF
                off -> Slots(on = false)
                else -> Slots(on = true)
            }
        },
        CapabilitySpec(
            id = CapabilityId.OPEN_APP,
            description = "Open any installed app by label; the terminal state is its foreground package.",
            verbs = setOf("open", "launch", "start")
        ) match@{ text ->
            val m = OPEN.find(text) ?: return@match null
            Slots(target = m.groupValues[1].trim())
        },
        CapabilitySpec(
            id = CapabilityId.SEND_MESSAGE,
            description = "Message any recipient on a channel; recipient and payload are free text.",
            verbs = setOf("send", "message", "text", "whatsapp", "telegram", "email")
        ) match@{ text ->
            val m = MESSAGE.find(text) ?: return@match null
            Slots(
                channel = m.groupValues[1],
                target = m.groupValues[2].trim(),
                payload = m.groupValues[3].trim().ifBlank { null }
            )
        },
        CapabilitySpec(
            id = CapabilityId.CALL,
            description = "Dial any number (contacts resolve through the device port when available).",
            verbs = setOf("call", "dial", "phone")
        ) match@{ text ->
            val m = CALL.find(text) ?: return@match null
            Slots(target = m.groupValues[1].trim())
        },
        CapabilitySpec(
            id = CapabilityId.NAVIGATE,
            description = "Navigate to any place; the place is a free-form slot encoded into a geo URI.",
            verbs = setOf("navigate", "directions", "drive", "go")
        ) match@{ text ->
            val m = NAVIGATE.find(text) ?: return@match null
            Slots(target = m.groupValues[1].trim())
        },
        CapabilitySpec(
            id = CapabilityId.SET_VOLUME,
            description = "Set volume to any percentage.",
            verbs = setOf("volume")
        ) match@{ text ->
            val m = VOLUME_SET.find(text) ?: return@match null
            val pct = m.groupValues[1].toIntOrNull() ?: return@match null
            Slots(percent = pct.coerceIn(0, 100))
        },
        CapabilitySpec(
            id = CapabilityId.DND,
            description = "Do-not-disturb on/off, either word order.",
            verbs = setOf("dnd", "do not disturb")
        ) match@{ text ->
            if (!DND.containsMatchIn(text)) return@match null
            val off = Regex("""\b(?:off|disable)\b""").containsMatchIn(text)
            Slots(on = !off)
        }
    )

    fun specFor(id: CapabilityId): CapabilitySpec? = specs.firstOrNull { it.id == id }

    /** Registry self-check: a capability without grammar is dead code and must fail CI. */
    fun validate(): List<String> = buildList {
        specs.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("duplicate capability spec: $it") }
        specs.filter { it.verbs.isEmpty() }.forEach { add("${it.id} declares no verb ontology") }
        specs.filter { it.description.isBlank() }.forEach { add("${it.id} has no description") }
    }
}

object UniversalPlanner {

    private const val MAX_CLAUSES = 8
    private const val MAX_DELAY_MS = 3_600_000L
    private val CONNECTOR = Regex("""\s*(?:,|;|\bthen\b|\band\s+then\b|\bafter\s+that\b|\band\b)\s*""")
    private val DELAY = Regex("""\b(?:after|in)\s+(\d{1,4})\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\b""")

    fun plan(
        raw: String,
        resolver: GenericEntityResolver = GenericEntityResolver(),
        /**
         * Optional conversation context (user messages only). Used ONLY to give a bare toggle its
         * device noun — never to invent intent. Without context a bare toggle is a clarification.
         */
        context: String? = null
    ): Plan {
        var text = normalize(raw)
        if (text.isBlank()) return Plan.clarify(raw, "What would you like me to do?", "empty prompt")
        if (isBareToggle(text) && !context.isNullOrBlank()) {
            val inherited = normalize(context)
            val noun = listOf("flashlight", "wi-fi", "bluetooth", "hotspot").firstOrNull {
                Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(inherited)
            }
            if (noun != null) text = "$text $noun"
        }
        val delayMs = parseDelay(text)

        // 1) Whole utterance as one capability (handles payloads containing "and").
        atomic(text, resolver)?.let { atoms ->
            return finalizePlan(raw, applyDelay(atoms, delayMs), notes = listOf("atomic match"))
        }

        // 2) Generic decomposition. No capability-specific splitter exists.
        val clauses = text.split(CONNECTOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (clauses.size in 2..MAX_CLAUSES) {
            val atoms = mutableListOf<PlanAtom>()
            val unresolved = mutableListOf<String>()
            for (clause in clauses) {
                val planned = atomic(clause, resolver)
                if (planned == null) unresolved += clause else atoms += planned
            }
            if (unresolved.isEmpty() && atoms.isNotEmpty()) {
                return finalizePlan(raw, applyDelay(atoms, delayMs), notes = listOf("decomposed ${clauses.size} clauses"))
            }
            if (atoms.isNotEmpty()) {
                return Plan(
                    prompt = raw,
                    atoms = atoms,
                    clarifications = unresolved.map { "I could not resolve: \"$it\". What should happen there?" },
                    notes = listOf("partial decomposition; failed closed")
                )
            }
        }

        // 3) Fail closed with a targeted question — never a chat answer in the device domain.
        return Plan.clarify(raw, describeUnresolved(text), "no capability matched")
    }

    /** A toggle cue with no device noun: ambiguous unless context supplies one. */
    private fun isBareToggle(text: String): Boolean {
        val hasCue = Regex("\\b(?:on|off|enable|disable|toggle)\\b").containsMatchIn(text)
        val hasNoun = Regex("\\b(?:flashlight|torch|wi-fi|bluetooth|hotspot|dnd|volume)\\b").containsMatchIn(text)
        return hasCue && !hasNoun
    }

    /**
     * True when a second capability's verb appears in the clause. Prevents a greedy grammar rule
     * (torch/open) from swallowing "… and call …" compounds before the generic splitter sees them.
     */
    private fun hasForeignGoal(text: String, owner: CapabilityId): Boolean {
        val ignore = setOf("turn", "switch", "enable", "disable", "toggle", "on", "off", "the", "to")
        return CapabilityRegistry.specs.any { spec ->
            spec.id != owner && spec.verbs.any { verb ->
                verb !in ignore && Regex("\\b" + Regex.escape(verb) + "\\b").containsMatchIn(text)
            }
        }
    }

    private fun finalizePlan(raw: String, atoms: List<PlanAtom>, notes: List<String>): Plan {
        if (atoms.isEmpty()) return Plan.clarify(raw, describeUnresolved(raw), "empty plan")
        return Plan(prompt = raw, atoms = atoms, notes = notes)
    }

    private fun applyDelay(atoms: List<PlanAtom>, delayMs: Long?): List<PlanAtom> {
        if (delayMs == null || atoms.isEmpty()) return atoms
        // Temporal modifiers attach to the transition that follows: "on, then off after 5 s".
        val last = atoms.last()
        return atoms.dropLast(1) + last.copy(delayAfterMs = delayMs)
    }

    private fun parseDelay(text: String): Long? {
        val m = DELAY.find(text) ?: return null
        val value = m.groupValues[1].toLongOrNull() ?: return null
        val unitMs = when (m.groupValues[2].first()) {
            'h' -> 3_600_000L
            'm' -> 60_000L
            else -> 1_000L
        }
        return (value * unitMs).coerceIn(0L, MAX_DELAY_MS)
    }

    /** One clause → zero or more atoms, or null when no capability owns it. */
    private fun atomic(text: String, resolver: GenericEntityResolver): List<PlanAtom>? {
        // Torch first: its grammar is order-free and may produce a sequence.
        torch(text)?.let { return it }

        CapabilityRegistry.specFor(CapabilityId.INSTALL_APP)?.match?.invoke(text)?.let { slots ->
            val target = slots.target ?: return@let
            val resolved = resolver.resolveApp(target)
            val pkg = when (resolved) {
                is AppResolution.Installed -> resolved.app.packageName
                is AppResolution.Installable -> resolved.hintedPackage
                else -> null
            }
            val label = when (resolved) {
                is AppResolution.Installed -> resolved.app.label
                is AppResolution.Installable -> resolved.query
                is AppResolution.Ambiguous -> resolved.query
                is AppResolution.Unknown -> target
            }
            return listOf(
                PlanAtom(
                    capability = CapabilityId.INSTALL_APP,
                    summary = "Open the store listing for $label",
                    postcondition = Postcondition.PackageInstalled(pkg),
                    intentUri = GenericDeepLinks.store(label, pkg),
                    preferredPackage = GenericDeepLinks.PLAY_STORE,
                    requiresObservation = true,
                    args = buildMap {
                        pkg?.let { put("install_package", it) }
                        put("store_query", label)
                    }
                )
            )
        }
        return when {
            CapabilityRegistry.specFor(CapabilityId.OPEN_APP)?.match?.invoke(text) != null &&
                !hasForeignGoal(text, CapabilityId.OPEN_APP) ->
                openApp(text, resolver, CapabilityRegistry.specFor(CapabilityId.OPEN_APP)!!.match!!)

            CapabilityRegistry.specFor(CapabilityId.SEND_MESSAGE)?.match?.invoke(text) != null ->
                message(CapabilityRegistry.specFor(CapabilityId.SEND_MESSAGE)!!.match!!(text)!!)

            CapabilityRegistry.specFor(CapabilityId.CALL)?.match?.invoke(text)?.let { call(it) } != null ->
                call(CapabilityRegistry.specFor(CapabilityId.CALL)!!.match!!(text)!!)

            CapabilityRegistry.specFor(CapabilityId.NAVIGATE)?.match?.invoke(text) != null ->
                navigate(CapabilityRegistry.specFor(CapabilityId.NAVIGATE)!!.match!!(text)!!)

            CapabilityRegistry.specFor(CapabilityId.SET_VOLUME)?.match?.invoke(text)?.let { volume(it) } != null ->
                volume(CapabilityRegistry.specFor(CapabilityId.SET_VOLUME)!!.match!!(text)!!)

            CapabilityRegistry.specFor(CapabilityId.DND)?.match?.invoke(text)?.let { dnd(it) } != null ->
                dnd(CapabilityRegistry.specFor(CapabilityId.DND)!!.match!!(text)!!)

            Regex("""^(?:take|capture|grab)\s+(?:a\s+)?(?:screen\s?shot|screenshot)(?:\s+now)?$|^screenshot(?:\s+now)?$""").containsMatchIn(text) ->
                listOf(
                    PlanAtom(
                        CapabilityId.SCREENSHOT, "Take a screenshot",
                        Postcondition.OsAcknowledged(DeviceCommand.SCREENSHOT.wire),
                        deviceCommand = DeviceCommand.SCREENSHOT
                    )
                )

            else -> null
        }
    }

    private fun torch(text: String): List<PlanAtom>? {
        val slots = CapabilityRegistry.specFor(CapabilityId.TORCH)?.match?.invoke(text) ?: return null
        // "turn on flashlight and call mom" must decompose, not become a torch-only plan.
        if (hasForeignGoal(text, CapabilityId.TORCH)) return null
        val onAtom = PlanAtom(
            CapabilityId.TORCH, "Turn the flashlight on",
            Postcondition.TorchIs(true),
            deviceCommand = DeviceCommand.TORCH_ON
        )
        val offAtom = PlanAtom(
            CapabilityId.TORCH, "Turn the flashlight off",
            Postcondition.TorchIs(false),
            deviceCommand = DeviceCommand.TORCH_OFF
        )
        return when {
            slots.secondary == "sequence" -> listOf(onAtom, offAtom)
            slots.on == false -> listOf(offAtom)
            else -> listOf(onAtom)
        }
    }

    private fun openApp(text: String, resolver: GenericEntityResolver, matcher: (String) -> Slots?): List<PlanAtom>? {
        val slots = matcher(text) ?: return null
        val target = slots.target ?: return null
        val resolved = resolver.resolveApp(target)
        if (resolved !is AppResolution.Installed) return null
        return listOf(
            PlanAtom(
                capability = CapabilityId.OPEN_APP,
                summary = "Launch ${resolved.app.label}",
                postcondition = Postcondition.ForegroundPackage(resolved.app.packageName),
                targetPackage = resolved.app.packageName,
                requiresObservation = true,
                args = mapOf("package" to resolved.app.packageName)
            )
        )
    }

    private fun message(slots: Slots): List<PlanAtom>? {
        val recipient = slots.target ?: return null
        val channel = slots.channel ?: return null
        val payload = slots.payload
        val digits = recipient.filter { it.isDigit() || it == '+' }.trim()
        val isEmail = recipient.contains("@")
        val uri: String = when {
            channel == "email" || channel == "mail" ->
                if (!isEmail) return null else GenericDeepLinks.email(recipient, body = payload)
            isEmail -> GenericDeepLinks.email(recipient, body = payload)
            channel == "whatsapp" -> if (digits.length < 5) return null else GenericDeepLinks.whatsapp(digits, payload)
            channel == "telegram" -> return null   // needs a username intent; unsupported is a clarification
            digits.length < 5 -> return null       // contact name: resolve via contacts port in a later tier
            else -> GenericDeepLinks.sms(digits, payload)
        }
        return listOf(
            PlanAtom(
                CapabilityId.SEND_MESSAGE, "Compose a $channel message to $recipient",
                Postcondition.OsAcknowledged("message:${uri.substringBefore(':')}"),
                intentUri = uri, requiresObservation = true
            )
        )
    }

    private fun call(slots: Slots): List<PlanAtom>? {
        val digits = (slots.target ?: return null).filter { it.isDigit() || it == '+' }
        if (digits.length < 3) return null
        return listOf(
            PlanAtom(
                CapabilityId.CALL, "Dial $digits",
                Postcondition.OsAcknowledged("tel:$digits"),
                intentUri = GenericDeepLinks.call(digits)
            )
        )
    }

    private fun navigate(slots: Slots): List<PlanAtom>? {
        val place = slots.target ?: return null
        return listOf(
            PlanAtom(
                CapabilityId.NAVIGATE, "Navigate to $place",
                Postcondition.OsAcknowledged("geo"),
                intentUri = GenericDeepLinks.place(place), requiresObservation = true
            )
        )
    }

    private fun volume(slots: Slots): List<PlanAtom> {
        val pct = slots.percent ?: 50
        return listOf(
            PlanAtom(
                CapabilityId.SET_VOLUME, "Set media volume to $pct%",
                Postcondition.OsAcknowledged(DeviceCommand.VOLUME_SET.wire),
                deviceCommand = DeviceCommand.VOLUME_SET,
                args = mapOf("percent" to pct.toString(), "stream" to "music")
            )
        )
    }

    private fun dnd(slots: Slots): List<PlanAtom> {
        val on = slots.on ?: true
        val cmd = if (on) DeviceCommand.DND_ON else DeviceCommand.DND_OFF
        return listOf(
            PlanAtom(
                CapabilityId.DND, if (on) "Enable do not disturb" else "Disable do not disturb",
                Postcondition.OsAcknowledged(cmd.wire), deviceCommand = cmd
            )
        )
    }

    private fun describeUnresolved(text: String): String {
        if (isBareToggle(text)) {
            return "Which device should I switch? I heard an on/off command but no device."
        }
        val open = CapabilityRegistry.specFor(CapabilityId.OPEN_APP)!!.match!!(text)?.target
        if (open != null) return "\"$open\" is not installed. Install it, or name an installed app."
        return "I couldn't map that to a device action. What should change on the phone?"
    }

    /** Lexical repair only — never target rewriting. */
    internal fun normalize(raw: String): String =
        raw.lowercase()
            .replace(Regex("""\[[^\]]+\]"""), "")
            .replace(Regex("""\bflash\s*light\b"""), "flashlight")
            .replace(Regex("""\btourch\b"""), "torch")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.', '?', '!', ',', ';')
}

/** Completion is a pure function of the plan and the evidence — never of dispatch success. */
object UniversalOutcomePolicy {

    fun evaluate(plan: Plan, evidence: Evidence): OutcomeDecision {
        if (!plan.isExecutable) {
            return OutcomeDecision.NeedsClarification(
                plan.clarifications.firstOrNull() ?: "What should change on the phone?"
            )
        }
        val checks = plan.atoms.map { atom -> check(atom, evidence) }
        checks.firstOrNull { it.verdict == AtomVerdict.DISPROVEN }?.let {
            return OutcomeDecision.Failed("Contradicted by device state: ${it.detail}", checks)
        }
        val unknown = checks.filter { it.verdict == AtomVerdict.UNKNOWN }
        if (unknown.isNotEmpty()) {
            return OutcomeDecision.NotYet(
                "No proof yet for: " + unknown.joinToString("; ") { it.atom.postcondition.human },
                checks
            )
        }
        return OutcomeDecision.Completed(checks)
    }

    private fun check(atom: PlanAtom, evidence: Evidence): AtomCheck {
        val pc = atom.postcondition
        return when (pc) {
            is Postcondition.TorchIs -> when (val seen = evidence.torchOn) {
                null -> AtomCheck(atom, AtomVerdict.UNKNOWN, "torch state not observable")
                pc.on -> AtomCheck(atom, AtomVerdict.PROVEN, "flashlight reported ${if (seen) "ON" else "OFF"}")
                else -> AtomCheck(atom, AtomVerdict.DISPROVEN, "flashlight is ${if (seen) "ON" else "OFF"}")
            }
            is Postcondition.PackageInstalled -> when {
                pc.pkg == null -> AtomCheck(atom, AtomVerdict.UNKNOWN, "package could not be named from the prompt")
                evidence.installed[pc.pkg] == null -> AtomCheck(atom, AtomVerdict.UNKNOWN, "${pc.pkg} was not probed")
                evidence.installed[pc.pkg] == true -> AtomCheck(atom, AtomVerdict.PROVEN, "${pc.pkg} is installed")
                else -> AtomCheck(atom, AtomVerdict.DISPROVEN, "${pc.pkg} is not installed")
            }
            is Postcondition.ForegroundPackage -> when {
                pc.pkg == null -> AtomCheck(atom, AtomVerdict.UNKNOWN, "package could not be named")
                evidence.foregroundPackage == null -> AtomCheck(atom, AtomVerdict.UNKNOWN, "foreground package not observed")
                evidence.foregroundPackage == pc.pkg -> AtomCheck(atom, AtomVerdict.PROVEN, "${pc.pkg} is foreground")
                else -> AtomCheck(atom, AtomVerdict.DISPROVEN, "${evidence.foregroundPackage} is foreground instead")
            }
            is Postcondition.OsAcknowledged ->
                if (pc.operation in evidence.acknowledged) AtomCheck(atom, AtomVerdict.PROVEN, pc.operation)
                else AtomCheck(atom, AtomVerdict.UNKNOWN, "${pc.operation} was not acknowledged")
            Postcondition.Unprovable -> AtomCheck(atom, AtomVerdict.UNKNOWN, "unprovable")
        }
    }
}

/** Bridges a pattern-level plan into the existing SuccessCriteria contract. */
object PatternCriteriaBridge {

    data class Derived(
        val mustShowPackage: List<String> = emptyList(),
        val mustBeInstalled: List<String> = emptyList(),
        val deviceAsserts: List<DeviceAssertion> = emptyList(),
        val requireInstall: Boolean = false
    ) {
        val isEmpty: Boolean
            get() = mustShowPackage.isEmpty() && mustBeInstalled.isEmpty() &&
                deviceAsserts.isEmpty() && !requireInstall
    }

    fun derive(
        prompt: String,
        resolver: GenericEntityResolver = GenericEntityResolver(),
        context: String? = null
    ): Derived {
        val plan = UniversalPlanner.plan(prompt, resolver, context)
        if (!plan.isExecutable) return Derived()
        val show = mutableListOf<String>()
        val installed = mutableListOf<String>()
        val asserts = mutableListOf<DeviceAssertion>()
        var requireInstall = false

        // Only the TERMINAL torch state may be asserted: "on then off" proves OFF, not ON.
        val finalTorch = plan.atoms.lastOrNull { it.capability == CapabilityId.TORCH }

        plan.atoms.forEach { atom ->
            when (atom.capability) {
                CapabilityId.INSTALL_APP -> {
                    requireInstall = true
                    GenericDeepLinks.PLAY_STORE.let { if (it !in show) show += it }
                    val pkg = atom.args["install_package"]
                    if (pkg != null && pkg !in installed) installed += pkg
                }
                CapabilityId.OPEN_APP -> atom.targetPackage?.let { if (it !in show) show += it }
                CapabilityId.TORCH -> when {
                    atom !== finalTorch -> Unit
                    atom.postcondition is Postcondition.TorchIs -> {
                        val on = (atom.postcondition as Postcondition.TorchIs).on
                        asserts += (if (on) DeviceAssertion.TORCH_ON else DeviceAssertion.TORCH_OFF)
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
        return Derived(
            mustShowPackage = show,
            mustBeInstalled = installed,
            deviceAsserts = asserts.distinct(),
            requireInstall = requireInstall
        )
    }
}
