package com.aniob.app.device

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * Tier-3 local data query: `MediaStore.Files` through the content resolver, so "find the PDF I
 * downloaded today" is one query instead of a scroll through a file manager.
 *
 * Visibility is Android's: with media permissions you see photos/video/audio, with "All files access"
 * you also see PDFs and downloads. An empty result is therefore reported honestly with the reason,
 * and the model is told to fall back to the Files app on screen.
 */
object AniobFileQuery {

    data class FileHit(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long,
        /** Seconds since epoch (`DATE_ADDED`). */
        val addedAt: Long
    )

    data class Request(
        val kind: String = "",
        val query: String = "",
        val withinHours: Long? = null,
        val olderThanDays: Long? = null,
        val limit: Int = 5,
        /** Bare extension without the dot, e.g. "py", "xlsx". Validated or ignored. */
        val extension: String = ""
    )

    private val DOCUMENT_MIMES = listOf(
        "application/pdf", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain"
    )

    /** Words that mean the same document to a person. Matching any one of a group matches all. */
    private val SYNONYMS = listOf(
        setOf("resume", "cv", "curriculum"),
        setOf("bill", "invoice", "receipt"),
        setOf("id", "aadhaar", "pan", "passport")
    )

    /** Words that say nothing about a file name and would match almost everything. */
    private val FILLER = setOf(
        "the", "and", "for", "from", "with", "that", "this", "file", "files", "document", "documents",
        "open", "find", "show", "today", "yesterday", "latest", "recent", "new", "old"
    )

    const val MAX_TERMS = 6
    private const val MIN_TOKEN_CHARS = 3

    /**
     * Name fragments to look for, most specific first: the phrase as typed (without a leading
     * "my"), then its words of at least [MIN_TOKEN_CHARS] characters, then synonyms of its
     * words (a synonym may be shorter, e.g. "cv"). At most [MAX_TERMS]; empty for a blank query.
     */
    fun expand(query: String): List<String> {
        val phrase = query.trim().lowercase().replace(Regex("\\s+"), " ")
            .removePrefix("my ").trim()
        if (phrase.isEmpty()) return emptyList()
        val words = phrase.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val terms = LinkedHashSet<String>()
        terms += phrase
        words.filterTo(terms) { it.length >= MIN_TOKEN_CHARS && it !in FILLER && it != "my" }
        words.forEach { word ->
            SYNONYMS.filter { word in it }.forEach { group ->
                group.filterTo(terms) { it != word }
            }
        }
        return terms.take(MAX_TERMS)
    }

    fun find(context: Context, request: Request, nowMs: Long = System.currentTimeMillis()): List<FileHit> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        when (request.kind.lowercase()) {
            "pdf" -> { where += "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"; args += "application/pdf" }
            "image", "photo" -> {
                where += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            }
            "screenshot" -> {
                where += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
                where += "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)"
                args += "%Screenshots%"
                args += "Screenshot%"
            }
            "download" -> { where += "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"; args += "Download/%" }
            "audio" -> {
                where += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                args += MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
            }
            "video" -> {
                where += "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
                args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            }
            "document" -> {
                where += "${MediaStore.Files.FileColumns.MIME_TYPE} IN (${DOCUMENT_MIMES.joinToString(",") { "?" }})"
                args += DOCUMENT_MIMES
            }
        }
        // Extension filter: files whose display name ends with .<ext>. Only a bare, safe extension is honoured.
        val ext = request.extension.trim().lowercase()
        if (ext.matches(Regex("[a-z0-9]{1,10}"))) {
            where += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            args += "%.$ext"
        }
        // People name a file by what it is ("my resume"), not by its file name ("CV_2024.pdf"),
        // so the name is matched against the phrase, its words and their common synonyms.
        val terms = expand(request.query)
        if (terms.isNotEmpty()) {
            where += terms.joinToString(" OR ", "(", ")") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
            args += terms.map { "%$it%" }
        }
        val nowSec = nowMs / 1000
        request.withinHours?.let {
            where += "${MediaStore.Files.FileColumns.DATE_ADDED} >= ?"
            args += (nowSec - it * 3600).toString()
        }
        request.olderThanDays?.let {
            where += "${MediaStore.Files.FileColumns.DATE_ADDED} <= ?"
            args += (nowSec - it * 86_400).toString()
        }
        // Folders and zero-byte rows are never what a person means by "the file I downloaded".
        where += "${MediaStore.Files.FileColumns.SIZE} > 0"

        val bundle = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where.joinToString(" AND "))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, request.limit.coerceIn(1, 25))
        }
        val base = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED
        )
        val hits = ArrayList<FileHit>()
        context.contentResolver.query(base, projection, bundle, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val added = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                hits += FileHit(
                    uri = android.content.ContentUris.withAppendedId(base, cursor.getLong(id)),
                    name = cursor.getString(name).orEmpty(),
                    mimeType = cursor.getString(mime),
                    sizeBytes = cursor.getLong(size),
                    addedAt = cursor.getLong(added)
                )
            }
        }
        return hits
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }
}
