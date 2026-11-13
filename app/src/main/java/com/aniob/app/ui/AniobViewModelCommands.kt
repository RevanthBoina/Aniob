package com.aniob.app.ui

import androidx.lifecycle.viewModelScope
import com.aniob.app.db.ConversationAuditEntity
import com.aniob.app.device.AniobCapabilityProbe
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.core.audit.AuditTask
import com.aniob.core.audit.ConversationAuditor
import com.aniob.core.capability.AniobCapabilityManifest
import com.aniob.core.skills.CapabilitySkillDiscovery
import com.aniob.core.skills.AniobSkillCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Explicit commands, never device macros. Each result is bound to the originating conversation. */
internal fun AniobViewModel.handleConversationCommand(text: String): Boolean {
    if (!text.startsWith("/")) return false
    if (_uiState.value.isRunning) return true
    val command = text.substringBefore(' ').lowercase()
    val argument = text.substringAfter(' ', "").trim()
    if (command == "/new") {
        clearChat()
        val id = activeConversationId()
        viewModelScope.launch(Dispatchers.IO) { conversationStore.create(id, argument.ifBlank { "New chat" }) }
        return true
    }
    if (command == "/switch") {
        commandReplyAsync(text) {
            val found = conversationStore.all().filter { it.id.startsWith(argument) }
            when {
                argument.isBlank() -> "Usage: /switch <unique chat ID prefix>"
                found.size != 1 -> "Chat ID must match exactly one conversation. Use /list."
                else -> {
                    val selected = found.single()
                    val messages = savedConversationMessages(selected.id)
                    conversationStore.close(activeConversationId(), "User switched to another chat")
                    setActiveConversation(selected.id)
                    _uiState.update { it.copy(chatMessages = messages, streamingBubbleText = "", isStreaming = false,
                        steps = emptyList(), stepEvents = emptyList(), lastSummary = null) }
                    "Opened ${selected.title}"
                }
            }
        }
        return true
    }
    commandReplyAsync(text) {
        when (command) {
            "/help" -> "/new [title], /list, /switch <id>, /close [reason], /audit [id|all], /learn [goal], /skills. Stop any active task before changing chats. /learn creates unverified drafts for review."
            "/list" -> conversationStore.all().joinToString("\n") { "${it.id} | ${it.title} | ${it.status}" }.ifBlank { "No saved chats." }
            "/close" -> {
                conversationStore.close(activeConversationId(), argument.ifBlank { "User closed chat" })
                // Next ordinary request gets a new conversation, but retain the closed transcript on screen.
                conversationId = ""
                conversationPrefs.edit().remove(AniobViewModel.KEY_ACTIVE_CONVERSATION).apply()
                "Chat closed. The next request will start a new chat."
            }
            "/skills" -> semanticSkillMatcher.getLoadedSkills().joinToString("\n") { "${it.name} [${it.status}]: ${it.description}" }.ifBlank { "No loaded skills." }
            "/audit" -> auditConversations(argument)
            "/learn" -> {
                check(isCloudConfigured()) { "Configure the selected cloud provider in Settings before learning." }
                val index = app.assets.open("capability-index.tsv").bufferedReader().use { it.readText() }
                val draft = CapabilitySkillDiscovery.discover(index,
                    AniobCapabilityManifest.render(AniobCapabilityProbe.snapshot(app)), argument,
                    getCloudProvider("learn_${activeConversationId()}"))
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "skill_library/skills").apply { mkdirs() }
                // Proposals never overwrite an existing active or draft skill, even on name collision.
                val unique = draft.copy(name = "${draft.name}_${java.util.UUID.randomUUID().toString().take(8)}")
                val file = File(dir, "${unique.name}.json")
                val temporary = File(dir, "${unique.name}.pending")
                temporary.writeText(AniobSkillCodec.encode(unique))
                check(temporary.renameTo(file)) { "Could not persist draft skill" }
                check(semanticSkillMatcher.loadAndIndexSingleSkill(file)) { "Could not load persisted draft" }
                "Created draft ${unique.name} from the build-generated source index and runtime capabilities. " +
                    "It has not been executed or verified and cannot auto-run. Review it in Skills.\n${unique.description}"
            }
            else -> "Unknown command. Use /help."
        }
    }
    return true
}

private fun AniobViewModel.commandReplyAsync(input: String, block: suspend AniobViewModel.() -> String) {
    val id = activeConversationId()
    val generation = ++activeGeneration
    _uiState.update { it.copy(isRunning = true, statusMessage = "Processing command…",
        chatMessages = it.chatMessages + ChatMessage(id = "cmd_${java.util.UUID.randomUUID()}", role = "user", content = input)) }
    activeTaskJob = viewModelScope.launch(Dispatchers.IO) {
        try {
            conversationStore.create(id)
            val response = block()
            conversationStore.appendExchange(id, input, response, "COMMAND")
            if (generation == activeGeneration && (id == conversationId || conversationId.isBlank())) _uiState.update {
                it.copy(chatMessages = it.chatMessages + ChatMessage(id = "cmd_${java.util.UUID.randomUUID()}", role = "assistant", content = response, provider = "COMMAND"))
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: java.io.IOException) {
            showCommandFailure(generation, "Command failed: storage or selected provider transport unavailable. No fallback provider was used.")
        } catch (_: IllegalArgumentException) {
            showCommandFailure(generation, "Command failed: invalid response or request. No skill was activated.")
        } catch (_: IllegalStateException) {
            showCommandFailure(generation, "Command unavailable. Check selected provider settings and device storage. No skill was activated.")
        } finally {
            if (generation == activeGeneration) _uiState.update { it.copy(isRunning = false, statusMessage = "Ready") }
        }
    }
}

private fun AniobViewModel.showCommandFailure(generation: Long, message: String) {
    if (generation == activeGeneration) _uiState.update { it.copy(chatMessages = it.chatMessages +
        ChatMessage(id = "cmd_error_${java.util.UUID.randomUUID()}", role = "assistant", content = message)) }
}

private suspend fun AniobViewModel.auditConversations(target: String): String {
    val all = conversationStore.all()
    val selected = if (target.isBlank() || target == "all") all.filter { it.status in setOf("stopped", "errored") }
        else all.filter { it.id.startsWith(target) }.also { require(it.size == 1) { "Ambiguous or missing conversation ID" } }
    if (selected.isEmpty()) return "No stopped or errored conversations to audit."
    // Sequential explicit requests only, never an automatic background upload of all history.
    val reports = mutableListOf<String>()
    for (conversation in selected) {
        val tasks = app.database.conversationJournalDao().tasks(conversation.id)
        val transcript = conversationStore.messages(conversation.id).joinToString("\n") { "${it.role}: ${it.content}" }
        val result = ConversationAuditor.audit(conversation.status, conversation.stopReason,
            tasks.map { AuditTask(it.taskId, it.prompt, it.outcome, it.reason) }, transcript,
            if (isCloudConfigured()) getCloudProvider("audit_${conversation.id}") else null)
        app.database.conversationJournalDao().saveAudit(ConversationAuditEntity(
            conversation.id, System.currentTimeMillis(), result.text, result.source))
        reports += "Audit: ${conversation.title} (${conversation.id})\n${result.text}"
    }
    return reports.joinToString("\n\n")
}
