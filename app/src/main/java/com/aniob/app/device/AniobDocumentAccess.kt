package com.aniob.app.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * One honest implementation of "find, read and summarize a document anywhere it lives".
 *
 * The flow is deliberately simple and format-generic — no per-file-name logic anywhere:
 *
 *  1. **Discovery** — MediaStore (file index) for local files, or the system document picker for
 *     cloud providers (a signed-in Google Drive, OneDrive, …) and folders MediaStore can't see
 *     (Android 11+ scoped storage hides `Android/media/com.whatsapp/...`).
 *  2. **Read** — text-like formats are read directly; Office Open XML files (`.docx/.pptx/.xlsx/
 *     .odt/...`) are ZIP containers whose XML parts carry the real text, so we unzip in memory and
 *     strip the markup; PDFs are inflated stream-by-stream with the JDK's [Inflater] and the text
 *     operators are harvested best-effort.
 *  3. **Summarize / act** — the extracted text is handed back through the step history, so the
 *     local or cloud model can summarize it or act on it like any other observation.
 *
 * Every reader is capped (bytes in, characters out) and honest: when local extraction is not
 * possible — a scanned PDF, a legacy binary `.doc`, a DRM'd file — the result says so explicitly
 * instead of inventing content, and points at opening the file externally.
 */
object AniobDocumentAccess {

    const val MAX_READ_BYTES = 8 * 1024 * 1024
    const val MAX_TEXT_CHARS = 12_000
    const val MAX_SHARED_RECORDS = 20

    data class SharedFile(
        val name: String,
        val uri: String,
        val mime: String?,
        val sizeBytes: Long,
        val addedAtMs: Long,
        /** "share" (ACTION_SEND), "picker" (document picker) or "view" (external open). */
        val source: String
    )

    sealed class ReadResult {
        /** Extraction succeeded (possibly truncated to [MAX_TEXT_CHARS]). */
        data class Ok(val name: String, val mime: String?, val text: String, val truncated: Boolean) : ReadResult()
        /** The format cannot be read on-device; [message] says why and what to try instead. */
        data class Unsupported(val message: String) : ReadResult()
        /** An I/O failure talking to the provider. */
        data class Failed(val message: String) : ReadResult()
    }

    // ------------------------------------------------------------------------------------------
    // shared-file registry: what arrived in Aniob from share sheets and the document picker
    // ------------------------------------------------------------------------------------------

    private fun storeFile(context: Context) = java.io.File(context.applicationContext.filesDir, "shared_files.json")

    @Synchronized
    fun listShared(context: Context, limit: Int = MAX_SHARED_RECORDS): List<SharedFile> {
        val file = storeFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                SharedFile(
                    name = o.optString("name"),
                    uri = o.optString("uri"),
                    mime = o.optString("mime").ifBlank { null },
                    sizeBytes = o.optLong("size", 0L),
                    addedAtMs = o.optLong("added", 0L),
                    source = o.optString("source", "share")
                )
            }.sortedByDescending { it.addedAtMs }.take(limit)
        }.getOrDefault(emptyList())
    }

    /**
     * Persists a read grant for [uri] (when it is persistable) and records it in the shared-file
     * registry. Safe to call for both `ACTION_SEND` URIs and picker results. Never throws.
     */
    @Synchronized
    fun register(context: Context, uri: Uri, mime: String?, source: String, takePersistable: Boolean = true): SharedFile? {
        val appContext = context.applicationContext
        val (nameBeforeCopy, sizeBeforeCopy) = queryNameAndSize(appContext, uri)
        // Share-sheet grants are not persistable: Android only gives the picker a stable grant.
        // Copy the bytes into the app's private dir so the file stays readable after the
        // sending app dies, then record the private copy.
        val copied: java.io.File? = if (source == "share") copyToSharedDir(appContext, uri, nameBeforeCopy) else null
        val effectiveUri: Uri = copied?.let { Uri.fromFile(it) } ?: uri
        if (copied == null && takePersistable && source != "share") {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val record = SharedFile(
            name = nameBeforeCopy ?: (effectiveUri.lastPathSegment ?: "file"),
            uri = effectiveUri.toString(),
            mime = mime ?: runCatching { appContext.contentResolver.getType(uri) }.getOrNull(),
            sizeBytes = copied?.length() ?: sizeBeforeCopy,
            addedAtMs = System.currentTimeMillis(),
            source = source
        )
        val existing = listShared(appContext, MAX_SHARED_RECORDS).filterNot { it.uri == record.uri }
        val merged = (listOf(record) + existing).take(MAX_SHARED_RECORDS)
        runCatching {
            val array = JSONArray()
            for (f in merged) {
                array.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("uri", f.uri)
                        .put("mime", f.mime.orEmpty())
                        .put("size", f.sizeBytes)
                        .put("added", f.addedAtMs)
                        .put("source", f.source)
                )
            }
            storeFile(appContext).writeText(array.toString())
        }
        return record
    }

    fun findShared(context: Context, query: String): SharedFile? {
        val q = query.trim().lowercase()
        val all = listShared(context, MAX_SHARED_RECORDS)
        if (q.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { it.name.lowercase().contains(q) }
    }

    fun clearShared(context: Context) {
        runCatching { storeFile(context).delete() }
    }

    private fun queryNameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        if (uri.scheme == "file") {
            val f = uri.path?.let { java.io.File(it) }
            return (f?.name) to (f?.length() ?: 0L)
        }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    val size = if (c.isNull(1)) 0L else c.getLong(1)
                    name to size
                } else null
            }
        }.getOrNull() ?: (null to 0L)
    }

    /** MIME list for [kind]; single source of truth for both the intent path and the picker activity. */
    fun mimeTypesFor(kind: String): Array<String> =
        when (kind.trim().lowercase()) {
            "pdf" -> arrayOf("application/pdf")
            "document", "doc", "docx", "word", "resume", "letter" -> arrayOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.oasis.opendocument.text",
                "text/plain", "text/rtf"
            )
            "presentation", "slides", "ppt", "pptx", "deck" -> arrayOf(
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.oasis.opendocument.presentation"
            )
            "spreadsheet", "sheet", "xls", "xlsx", "excel" -> arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.oasis.opendocument.spreadsheet",
                "text/csv"
            )
            "image", "photo" -> arrayOf("image/*")
            else -> arrayOf("*/*")
        }

    /** Launch intent for the Storage Access Framework picker (cloud providers incl. Drive). */
    fun pickerIntent(kind: String): Intent {
        val mimes = mimeTypesFor(kind)
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    private fun copyToSharedDir(context: Context, uri: Uri, name: String?): java.io.File? {
        return runCatching {
            val dir = java.io.File(context.filesDir, "shared").apply { mkdirs() }
            val safe = (name ?: "shared").replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val target = java.io.File(dir, "share_${System.currentTimeMillis()}_$safe")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(target).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_READ_BYTES) {
                            target.delete()
                            return@runCatching null
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: return@runCatching null
            target
        }.getOrNull()
    }

    /** Canonical path of the private shared dir, used to confine file:// reads to it. */
    private fun sharedDirPath(context: Context): String =
        java.io.File(context.filesDir, "shared").canonicalPath

    /** Reads at most MAX_READ_BYTES+1 bytes from a file:// URI confined to the shared dir. */
    private fun readSharedFile(context: Context, uri: Uri): ByteArray? {
        val path = uri.path ?: return null
        val canon = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return null
        if (!canon.startsWith(sharedDirPath(context))) return null
        val f = java.io.File(canon)
        if (!f.isFile) return null
        val limit = (MAX_READ_BYTES + 1).toLong()
        val out = java.io.ByteArrayOutputStream()
        f.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (total < limit) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
        }
        return out.toByteArray()
    }

    /**
     * A Uri a viewer app is allowed to see: file:// copies become FileProvider content URIs
     * (file:// is rejected on modern Android); everything else passes through unchanged.
     */
    fun viewableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        val canon = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return uri
        if (!canon.startsWith(sharedDirPath(context))) return uri
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".workflow.fileprovider",
                java.io.File(canon)
            )
        }.getOrDefault(uri)
    }

    // ------------------------------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------------------------------

    /** Read/extract [uri] as text. Never throws; always carries an honest explanation. */
    fun readText(context: Context, uri: Uri, mimeType: String?): ReadResult {
        val appContext = context.applicationContext
        val (name, _) = queryNameAndSize(appContext, uri)
        val display = name ?: (uri.lastPathSegment ?: "file")
        val mime = (mimeType ?: runCatching { appContext.contentResolver.getType(uri) }.getOrNull()).orEmpty().lowercase()
        val ext = display.substringAfterLast('.', "").lowercase()

        val bytes = runCatching {
            if (uri.scheme == "file") {
                readSharedFile(appContext, uri)
            } else {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (total < MAX_READ_BYTES + 1) {
                    val n = input.read(buf, 0, minOf(buf.size, MAX_READ_BYTES + 1 - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
                }
            }
        }.getOrElse { return ReadResult.Failed("Could not open $display (${it.javaClass.simpleName}).") }
            ?: return ReadResult.Failed("Could not open $display.")

        if (bytes.size > MAX_READ_BYTES) {
            return ReadResult.Unsupported("$display is larger than ${MAX_READ_BYTES / (1024 * 1024)} MB; refusing to read it into memory. Open it instead.")
        }

        val isZipContainer = looksLikeZip(bytes)
        val text: String? = when {
            mime.startsWith("text/") || ext in TEXT_EXTENSIONS -> {
                String(bytes, Charsets.UTF_8)
            }
            ext == "json" || mime == "application/json" -> String(bytes, Charsets.UTF_8)
            isZipContainer -> extractFromZipContainer(bytes, display)
            ext == "pdf" || mime == "application/pdf" -> extractPdfText(bytes)
            ext in LEGACY_OFFICE_EXTENSIONS -> return ReadResult.Unsupported(
                "$display is a legacy binary Office file, which this device build cannot read. Open it in Word/WPS instead, or share it to Aniob as .docx/.pdf."
            )
            else -> null
        }

        val cleaned = text?.let { collapseWhitespace(it) }?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            return if (ext == "pdf" || mime == "application/pdf") {
                ReadResult.Unsupported(
                    "Could not extract text from $display on this device. The PDF may be scanned, encrypted or use embedded fonts. " +
                        "Ask me to open it instead and I will hand it to a PDF app."
                )
            } else {
                ReadResult.Unsupported(
                    "Could not extract text from $display (type ${mime.ifBlank { ext.ifBlank { "unknown" } }}). Ask me to open it instead."
                )
            }
        }
        val truncated = cleaned.length > MAX_TEXT_CHARS
        return ReadResult.Ok(
            name = display,
            mime = mime.ifBlank { null },
            text = if (truncated) cleaned.take(MAX_TEXT_CHARS) else cleaned,
            truncated = truncated
        )
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "tsv", "log", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts",
        "c", "cpp", "h", "go", "rs", "html", "css", "gradle", "properties", "sh", "json5", "rtf"
    )

    private val LEGACY_OFFICE_EXTENSIONS = setOf("doc", "ppt", "xls", "wps")

    internal fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    /** Extracts readable text from OOXML/ODF (ZIP) containers: strip tags, keep text nodes. */
    internal fun extractFromZipContainer(bytes: ByteArray, display: String): String? {
        val wanted = mutableMapOf<String, StringBuilder>()
        var sawContainer = false
        runCatching {
            ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.lowercase()
                    val isTextPart = when {
                        name == "word/document.xml" -> true                       // docx
                        name.startsWith("ppt/slides/slide") && name.endsWith(".xml") -> true  // pptx
                        name == "xl/sharedstrings.xml" -> true                    // xlsx
                        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> true
                        name == "content.xml" -> true                             // ODF (odt/ods/odp)
                        else -> false
                    }
                    if (isTextPart) {
                        sawContainer = true
                        val entryText = StringBuilder()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0) break
                            entryText.append(String(buf, 0, n, Charsets.UTF_8))
                            if (entryText.length > 4 * 1024 * 1024) break
                        }
                        wanted.getOrPut(name) { StringBuilder() }.append(stripXml(entryText.toString())).append('\n')
                    }
                    entry = zip.nextEntry
                }
            }
        }.getOrNull() ?: return null
        if (!sawContainer) return null
        // Reading order: document.xml alone, then slides in number order, then sheet strings.
        val ordered = wanted.entries.sortedBy { (name, _) ->
            when {
                name.contains("document.xml") || name == "content.xml" -> "0"
                name.contains("slides/") -> "1" + name.filter { it.isDigit() }.padStart(4, '0')
                name == "xl/sharedstrings.xml" -> "2"
                else -> "3" + name
            }
        }
        return ordered.joinToString("\n") { it.value }
    }

    internal fun stripXml(xml: String): String {
        // Keep text between tags; insert spaces at tag boundaries so words don't fuse.
        val withSpaces = xml.replace(Regex("<[^>]+>"), " ")
        return withSpaces
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#10;", "\n").replace("&nbsp;", " ")
    }

    private val WHITESPACE_RUN = Regex("[\\t\\x0B\\f ]+")

    private fun collapseWhitespace(text: String): String {
        val lines = text.split('\n', '\r').map { WHITESPACE_RUN.replace(it, " ").trim() }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    /**
     * Dependency-free PDF text harvest: inflate each content stream (zlib or raw deflate) and
     * pull the literal strings out of the text-showing operators. Works for a large class of
     * machine-generated PDFs; returns null when nothing legible was found (scanned/encrypted).
     */
    internal fun extractPdfText(bytes: ByteArray): String? {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        val stringRegex = Regex("""\((?:\\.|[^()\\])*\)""")
        var idx = 0
        while (out.length < MAX_TEXT_CHARS) {
            val streamAt = raw.indexOf("stream", idx)
            if (streamAt < 0) break
            var contentStart = streamAt + "stream".length
            if (raw.startsWith("\r\n", contentStart)) contentStart += 2 else if (contentStart < raw.length && (raw[contentStart] == '\n' || raw[contentStart] == '\r')) contentStart += 1
            val endAt = raw.indexOf("endstream", contentStart)
            if (endAt < 0) break
            val chunk = bytes.copyOfRange(contentStart, minOf(endAt, contentStart + MAX_READ_BYTES))
            val inflated = inflate(chunk)
            val body = inflated ?: if (looksMostlyPrintable(chunk)) String(chunk, Charsets.ISO_8859_1) else null
            if (body != null && (body.contains("Tj") || body.contains("TJ") || body.contains("Tf"))) {
                for (match in stringRegex.findAll(body)) {
                    val inner = match.value.substring(1, match.value.length - 1)
                    out.append(unescapePdfString(inner))
                    out.append(' ')
                    if (out.length >= MAX_TEXT_CHARS) break
                }
                out.append('\n')
            }
            idx = endAt + "endstream".length
        }
        return out.toString().ifBlank { null }
    }

    private fun inflate(chunk: ByteArray): String? {
        for (rawMode in listOf(false, true)) {
            val inflater = Inflater(rawMode)
            val sink = ByteArrayOutputStream()
            try {
                inflater.setInput(chunk)
                val buf = ByteArray(8192)
                while (!inflater.finished() && !inflater.needsInput()) {
                    val n = inflater.inflate(buf)
                    if (n == 0) break
                    sink.write(buf, 0, n)
                    if (sink.size() > MAX_READ_BYTES) break
                }
            } catch (_: Exception) {
                // try the next flavour
            } finally {
                inflater.end()
            }
            if (sink.size() > 0) return sink.toString("ISO-8859-1")
        }
        return null
    }

    private fun looksMostlyPrintable(chunk: ByteArray): Boolean {
        if (chunk.isEmpty()) return false
        val sample = minOf(chunk.size, 512)
        var printable = 0
        for (i in 0 until sample) {
            val b = chunk[i].toInt() and 0xFF
            if (b in 32..126 || b == '\n'.code || b == '\r'.code || b == '\t'.code) printable++
        }
        return printable * 100 / sample > 70
    }

    private fun unescapePdfString(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n', 'r', 't' -> out.append(' ').also { i += 2 }
                    '(', ')', '\\' -> out.append(n).also { i += 2 }
                    in '0'..'7' -> {
                        val oct = s.substring(i + 1, minOf(i + 4, s.length)).takeWhile { it in '0'..'7' }
                        out.append(oct.toIntOrNull(8)?.toChar() ?: ' ').also { i += 1 + oct.length }
                    }
                    else -> out.append(n).also { i += 2 }
                }
            } else {
                out.append(c); i++
            }
        }
        // PDF text is often drawn with kerning gaps; collapse the worst of it.
        return out.toString().replace(Regex("[ ]{2,}"), " ")
    }

}
