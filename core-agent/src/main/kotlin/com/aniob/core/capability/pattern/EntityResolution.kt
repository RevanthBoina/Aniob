package com.aniob.core.capability.pattern

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AniobStoreCatalog
import java.net.URLEncoder

/** The phone's own view of its installed apps. Supplied by the app layer (or a fake in tests). */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val aliases: List<String> = emptyList(),
    val launchActivity: String? = null
)

/** Port for device-owned entities. The core stays pure JVM; Android supplies the implementation. */
interface DeviceEntityPort {
    fun installedApps(): List<InstalledApp>
    /** true/false when known, null when the platform cannot answer. */
    fun isInstalled(pkg: String): Boolean? = null
}

/** Adapter over the existing device app registry (already hydrated with PackageManager data). */
class RegistryEntityPort(
    private val registry: AniobAppRegistry = AniobAppRegistry.getInstance()
) : DeviceEntityPort {
    override fun installedApps(): List<InstalledApp> = registry.all().map {
        InstalledApp(it.packageName, it.label, it.aliases, it.launchActivity)
    }
    override fun isInstalled(pkg: String): Boolean? =
        registry.all().any { it.packageName == pkg }
}

object EmptyEntityPort : DeviceEntityPort {
    override fun installedApps(): List<InstalledApp> = emptyList()
}

/**
 * Optional name→package knowledge. This is a CACHE for precision (a known store listing), never a
 * requirement: every unknown name still resolves to a store search, so new apps need no code.
 */
interface PackageHintSource { fun hint(name: String): String? }

object CatalogHintSource : PackageHintSource {
    override fun hint(name: String): String? = AniobStoreCatalog.packageFor(name)
}

sealed class AppResolution {
    data class Installed(val app: InstalledApp, val how: String) : AppResolution()
    /** Not installed: the store is the correct terminal surface for an install request. */
    data class Installable(val query: String, val hintedPackage: String?, val how: String) : AppResolution()
    data class Ambiguous(val query: String, val candidates: List<InstalledApp>) : AppResolution()
    data class Unknown(val query: String) : AppResolution()
}

/**
 * Generic entity resolution: score against whatever is installed, then fall back to a store query.
 * No app names are stored here — "install <anything>" is the same code path.
 */
class GenericEntityResolver(
    private val port: DeviceEntityPort = RegistryEntityPort(),
    private val hints: PackageHintSource = CatalogHintSource
) {
    fun resolveApp(rawQuery: String): AppResolution {
        val query = clean(rawQuery)
        if (query.isBlank()) return AppResolution.Unknown(query)
        val installed = port.installedApps()
        val scored = installed.mapNotNull { app ->
            val score = bestScore(query, app)
            if (score > 0) app to score else null
        }.sortedByDescending { it.second }
        if (scored.isNotEmpty()) {
            val top = scored.first()
            val ties = scored.filter { it.second == top.second }
            if (ties.size > 1 && top.second >= 60) {
                return AppResolution.Ambiguous(query, ties.map { it.first })
            }
            return AppResolution.Installed(top.first, "label/alias match (score ${top.second})")
        }
        val hint = hints.hint(query)
        return AppResolution.Installable(query, hint, if (hint != null) "cached listing id" else "store search")
    }

    private fun bestScore(query: String, app: InstalledApp): Int {
        val names = (listOf(app.label) + app.aliases + app.packageName)
            .map { normalize(it) }
            .filter { it.isNotBlank() }
        val needle = normalize(query)
        var best = 0
        for (name in names) {
            val score = when {
                name == needle -> 100
                name.startsWith(needle) || needle.startsWith(name) -> 80
                name.split(" ").containsAll(needle.split(" ")) -> 60
                needle.split(" ").any { it.length > 1 && name.split(" ").contains(it) } -> 40
                else -> 0
            }
            best = maxOf(best, score)
        }
        return best
    }

    private fun normalize(text: String) =
        text.lowercase().replace(Regex("""[^a-z0-9 ]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/**
 * Deep links are derived from the ENTITY KIND, not from an app table:
 * installs → store, places → geo, calls → tel, messages → sms/mailto, web → https.
 * Anything Java can encode works, which is the point: no per-task routing code.
 */
object GenericDeepLinks {
    const val PLAY_STORE = "com.android.vending"

    fun store(query: String, pkg: String?): String =
        if (pkg != null) "market://details?id=$pkg"
        else "market://search?q=${encode(query)}&c=apps"

    fun storeWeb(query: String, pkg: String?): String =
        if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg"
        else "https://play.google.com/store/search?q=${encode(query)}&c=apps"

    fun place(query: String): String = "geo:0,0?q=${encode(query)}"

    fun call(number: String): String = "tel:" + number.filter { it.isDigit() || it == '+' }

    fun sms(number: String, body: String?): String =
        "smsto:" + number.filter { it.isDigit() || it == '+' } +
            (body?.takeIf { it.isNotBlank() }?.let { "?body=${encode(it)}" } ?: "")

    fun email(address: String, subject: String? = null, body: String? = null): String =
        "mailto:${encode(address)}" + listOfNotNull(
            subject?.takeIf { it.isNotBlank() }?.let { "subject=${encode(it)}" },
            body?.takeIf { it.isNotBlank() }?.let { "body=${encode(it)}" }
        ).joinToString(prefix = if (subject.isNullOrBlank() && body.isNullOrBlank()) "" else "?", separator = "&")

    fun whatsapp(number: String, body: String?): String =
        "https://wa.me/" + number.filter { it.isDigit() } +
            (body?.takeIf { it.isNotBlank() }?.let { "?text=${encode(it)}" } ?: "")

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

internal fun clean(raw: String): String =
    raw.lowercase()
        .replace(Regex("""\b(?:the|a|an|my|please|now|just)\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('.', '?', '!', ',', ';')
