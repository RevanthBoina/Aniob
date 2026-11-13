package com.aniob.core.memory

/** Conversation facts never read another chat's facts or the legacy cross-chat vault. */
class ConversationKnowledgeStore(private val backing: AniobSharedKnowledgeStore, sessionId: String) : AniobSharedKnowledgeStore {
    init { require(sessionId.isNotBlank() && !sessionId.contains(':')) }
    private val prefix = "conversation:$sessionId:"
    override fun put(key: String, value: String) = backing.put(prefix + key, value)
    override fun get(key: String): String? = backing.get(prefix + key)
    override fun getAll(): Map<String, String> = backing.getAll().filterKeys { it.startsWith(prefix) }
        .mapKeys { it.key.removePrefix(prefix) }
    override fun remove(key: String) = backing.remove(prefix + key)
    override fun clear() { getAll().keys.forEach(::remove) }
}
