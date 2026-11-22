package com.aniob.core.domain

/** Domain-owned, opaque identity. Display text must never be used to reconstruct this ID. */
data class EntityReference(val kind: String, val id: String) {
    init {
        require(kind.isNotBlank()) { "entity kind must not be blank" }
        require(id.isNotBlank()) { "entity id must not be blank" }
    }
}

/** Model/UI-safe metadata supplied by the domain adapter; it must not contain secret data. */
data class EntityCandidate(
    val reference: EntityReference,
    val label: String,
    val detail: String? = null
) {
    init { require(label.isNotBlank()) { "candidate label must not be blank" } }
}

sealed interface EntityResolution {
    data class Resolved(val candidate: EntityCandidate) : EntityResolution
    class MultipleCandidates(candidates: List<EntityCandidate>) : EntityResolution {
        val candidates: List<EntityCandidate> = java.util.Collections.unmodifiableList(candidates.toList())

        init {
            require(this.candidates.size >= 2) { "multiple candidates requires at least two choices" }
            require(this.candidates.map { it.reference }.distinct().size == this.candidates.size) {
                "candidate references must be unique"
            }
            require(this.candidates.map { it.reference.kind }.distinct().size == 1) {
                "candidate kinds must match"
            }
        }

        /** Only an exact offered reference is accepted; duplicate labels remain distinguishable. */
        fun select(reference: EntityReference): Resolved? =
            candidates.singleOrNull { it.reference == reference }?.let(::Resolved)
    }
    data object NotFound : EntityResolution
    data class PermissionRequired(val permission: String) : EntityResolution {
        init { require(permission.isNotBlank()) }
    }
    data class Unavailable(val reason: String) : EntityResolution {
        init { require(reason.isNotBlank()) }
    }
}

/** Platform implementations own the private data behind references and revalidate them on use. */
interface EntityResolutionPort {
    fun resolve(kind: String, query: String): EntityResolution
    fun resolveReference(reference: EntityReference): EntityResolution
}
