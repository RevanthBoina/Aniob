package com.aniob.app.device

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import com.aniob.core.domain.EntityCandidate
import com.aniob.core.domain.EntityReference
import com.aniob.core.domain.EntityResolution
import com.aniob.core.domain.EntityResolutionPort
import java.security.MessageDigest
import java.util.UUID

/** Android-only contact data. Neither phone number nor provider row ID is sent to the model. */
internal data class ContactEntityRow(
    val rowId: Long,
    val displayName: String,
    val number: String
) {
    override fun toString(): String = "ContactEntityRow(rowId=$rowId, displayName=$displayName, number=[redacted])"
}

internal interface ContactEntitySource {
    fun hasPermission(): Boolean
    /** null means the provider could not be queried, not that there were zero results. */
    fun find(query: String): List<ContactEntityRow>?
    fun findById(rowId: Long): List<ContactEntityRow>?
}

/**
 * One instance belongs to one task. Choice IDs are opaque and remain stable for the life of that
 * task; they never encode the phone number. A restored task with no resolver must resolve again.
 * Every selected row is re-read and compared with its original number fingerprint before dispatch.
 */
internal class AniobContactEntityResolver(private val source: ContactEntitySource) : EntityResolutionPort {
    constructor(context: Context) : this(AndroidContactEntitySource(context.applicationContext))

    private data class Binding(val rowId: Long, val fingerprint: String)
    private val bindings = mutableMapOf<String, Binding>()
    private val idsByBinding = mutableMapOf<Binding, String>()

    override fun resolve(kind: String, query: String): EntityResolution {
        if (kind != KIND) return EntityResolution.Unavailable("Unsupported entity kind")
        if (query.isBlank()) return EntityResolution.NotFound
        return guarded {
            val rows = source.find(query.trim()) ?: return@guarded EntityResolution.Unavailable("Contacts provider unavailable")
            val candidates = rows.asSequence()
                .filter { it.number.isNotBlank() }
                .distinctBy { it.rowId }
                .map(::candidate)
                .toList()
            when (candidates.size) {
                0 -> EntityResolution.NotFound
                1 -> EntityResolution.Resolved(candidates.single())
                else -> EntityResolution.MultipleCandidates(candidates)
            }
        }
    }

    override fun resolveReference(reference: EntityReference): EntityResolution = guarded {
        when (val result = selectedRow(reference)) {
            is SelectedRow.Found -> EntityResolution.Resolved(candidate(result.row))
            SelectedRow.NotFound -> EntityResolution.NotFound
            SelectedRow.Unavailable -> EntityResolution.Unavailable("Contacts provider unavailable")
        }
    }

    /** Private dispatch data: never include this result in model-facing evidence or UI labels. */
    internal fun numberFor(reference: EntityReference): ContactNumberResult = try {
        if (!source.hasPermission()) ContactNumberResult.PermissionRequired
        else when (val result = selectedRow(reference)) {
            is SelectedRow.Found -> ContactNumberResult.Resolved(result.row.number)
            SelectedRow.NotFound -> ContactNumberResult.NotFound
            SelectedRow.Unavailable -> ContactNumberResult.Unavailable
        }
    } catch (_: SecurityException) {
        ContactNumberResult.PermissionRequired
    } catch (_: IllegalArgumentException) {
        ContactNumberResult.Unavailable
    } catch (_: IllegalStateException) {
        ContactNumberResult.Unavailable
    }

    private inline fun guarded(block: () -> EntityResolution): EntityResolution = try {
        if (!source.hasPermission()) EntityResolution.PermissionRequired(Manifest.permission.READ_CONTACTS)
        else block()
    } catch (_: SecurityException) {
        EntityResolution.PermissionRequired(Manifest.permission.READ_CONTACTS)
    } catch (_: IllegalArgumentException) {
        EntityResolution.Unavailable("Contacts provider unavailable")
    } catch (_: IllegalStateException) {
        EntityResolution.Unavailable("Contacts provider unavailable")
    }

    private fun candidate(row: ContactEntityRow): EntityCandidate {
        val binding = Binding(row.rowId, fingerprint(row.number))
        val id = synchronized(bindings) {
            idsByBinding.getOrPut(binding) { UUID.randomUUID().toString().also { bindings[it] = binding } }
        }
        val digits = row.number.filter(Char::isDigit)
        val display = row.displayName.trim()
        val displayDigits = display.filter(Char::isDigit)
        // Provider display names can themselves contain a phone number.
        val label = display.takeIf {
            it.isNotEmpty() && displayDigits.length < 6 && (digits.isEmpty() || !displayDigits.contains(digits))
        } ?: "Contact"
        val detail = if (digits.length > 4) "Phone ending in ••${digits.takeLast(2)}" else "Phone"
        return EntityCandidate(EntityReference(KIND, id), label, detail)
    }

    private sealed interface SelectedRow {
        data class Found(val row: ContactEntityRow) : SelectedRow
        data object NotFound : SelectedRow
        data object Unavailable : SelectedRow
    }

    private fun selectedRow(reference: EntityReference): SelectedRow {
        if (reference.kind != KIND) return SelectedRow.NotFound
        val binding = synchronized(bindings) { bindings[reference.id] } ?: return SelectedRow.NotFound
        val rows = source.findById(binding.rowId) ?: return SelectedRow.Unavailable
        val row = rows.singleOrNull() ?: return SelectedRow.NotFound
        return if (row.rowId == binding.rowId && row.number.isNotBlank() && fingerprint(row.number) == binding.fingerprint) {
            SelectedRow.Found(row)
        } else SelectedRow.NotFound
    }

    private fun fingerprint(number: String): String = MessageDigest.getInstance("SHA-256")
        .digest(number.trim().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object { const val KIND = "contact" }
}

/** Number-bearing results stay in the Android execution adapter. */
internal sealed interface ContactNumberResult {
    data class Resolved(val number: String) : ContactNumberResult {
        override fun toString(): String = "Resolved(number=[redacted])"
    }
    data object NotFound : ContactNumberResult
    data object PermissionRequired : ContactNumberResult
    data object Unavailable : ContactNumberResult
}

private class AndroidContactEntitySource(private val context: Context) : ContactEntitySource {
    override fun hasPermission(): Boolean = AniobCapabilityProbe.granted(context, Manifest.permission.READ_CONTACTS)

    override fun find(query: String): List<ContactEntityRow>? {
        // Escape LIKE metacharacters so a user's value is data, not a wildcard query.
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return queryRows("${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'", arrayOf("%$escaped%"))
    }

    override fun findById(rowId: Long): List<ContactEntityRow>? =
        queryRows("${ContactsContract.CommonDataKinds.Phone._ID} = ?", arrayOf(rowId.toString()))

    private fun queryRows(selection: String, args: Array<String>): List<ContactEntityRow>? {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        return context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ContactEntityRow(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()))
                }
            }
        }
    }
}
