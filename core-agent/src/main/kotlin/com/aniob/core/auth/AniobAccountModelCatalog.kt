package com.aniob.core.auth

/**
 * Models offered for the signed-in account, read from `configs/account_models.yaml`. The list is
 * a convenience, not a gate: any id the user types is accepted if it looks like a model id.
 */
data class AniobAccountModel(val id: String, val label: String)

class AniobAccountModelCatalog(val models: List<AniobAccountModel>, val defaultModelId: String) {

    fun labelFor(id: String): String = models.firstOrNull { it.id == id }?.label ?: id

    /** The id to use: the user's choice when valid, else the catalog default. */
    fun resolve(requested: String?): String {
        val trimmed = requested?.trim().orEmpty()
        return if (validate(trimmed) == null) trimmed else defaultModelId
    }

    companion object {
        private val MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,119}$")
        const val FALLBACK_MODEL = "gpt-6-sol"

        fun validate(id: String): String? =
            if (MODEL_ID.matches(id.trim())) null
            else "Use letters, numbers, dots, dashes and underscores."

        /** The tiny subset of YAML the file uses: `default:` plus a `models:` list of `id`/`label`. */
        fun parse(text: String): AniobAccountModelCatalog {
            var default: String? = null
            val models = mutableListOf<AniobAccountModel>()
            var id: String? = null
            var label: String? = null
            fun flush() {
                val i = id
                if (i != null && validate(i) == null) models += AniobAccountModel(i, label?.takeIf { it.isNotBlank() } ?: i)
                id = null; label = null
            }
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trimEnd()
                if (line.isBlank()) continue
                val t = line.trim().removePrefix("- ").trim()
                when {
                    t.startsWith("default:") -> default = unquote(t.removePrefix("default:"))
                    t.startsWith("id:") -> { if (line.trimStart().startsWith("- ")) flush(); id = unquote(t.removePrefix("id:")) }
                    t.startsWith("label:") -> label = unquote(t.removePrefix("label:"))
                }
            }
            flush()
            val chosen = default?.takeIf { d -> models.any { it.id == d } } ?: models.firstOrNull()?.id ?: FALLBACK_MODEL
            return AniobAccountModelCatalog(models, chosen)
        }

        private fun unquote(v: String) = v.trim().trim('"', '\'')
    }
}
