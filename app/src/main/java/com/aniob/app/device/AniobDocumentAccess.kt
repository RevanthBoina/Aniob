package com.aniob.app.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * One honest implementation of "find, read and summarize a document anywhere it lives".
 *
 * The flow is deliberately simple and format-generic — no per-file-name logic anywhere:
 *
 *  1. **Discovery** — MediaStore (file index) for local files, or the system document picker for
 *     cloud providers (a signed-in Google Drive, OneDrive, …) and folders MediaStore can't see
 *     (Android 11+ scoped storage hides `Android/media/com.whatsapp/...`).
 *  2. **Read** — everything is resolved to a local file first (a `file://` confined to the shared
 *     store is read in place; a `content://` URI is *streamed* into a bounded cache temp file) and
 *     then parsed incrementally. Nothing materialises the whole payload in the heap more than
 *     once, and ZIP/PDF readers cap every intermediate buffer.
 *     - text-like formats are streamed;
 *     - Office Open XML (`.docx/.pptx/.xlsx`) and ODF are ZIP containers whose XML parts carry the
 *       real text — we walk entries and keep text nodes;
 *     - PDFs are scanned stream-by-stream with the JDK's [Inflater] and the text operators are
 *       harvested best-effort; larger or richer PDFs are declined honestly instead of risking an
 *       OOM.
 *  3. **Summarize / act** — the extracted text is handed back through the step history, so the
 *     local or cloud model can summarize it or act on it like any other observation.
 *
 * Every reader is capped and honest: when local extraction is impossible — a scanned PDF, a legacy
 * binary `.doc`, an oversized file — the result says so explicitly instead of inventing content.
 */
object AniobDocumentAccess {

    const val MAX_TEXT_CHARS = 12_000
    const val MAX_SHARED_RECORDS = 20

    /** Hard cap on bytes streamed into cache for one read. Larger files are declined. */
    private const val MAX_FILE_BYTES = 24L * 1024 * 1024

    /** PDFs are parsed from a single bounded byte array; bigger ones are declined. */
    private const val MAX_PDF_BYTES = 6L * 1024 * 1024

    /** Per-ZIP-entry cap (a pathological member cannot exhaust the heap). */
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024

    /** Per-PDF-stream chunk cap; oversized streams are skipped, not buffered. */
    private const val MAX_STREAM_CHUNK = 1024 * 1024

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

    /** A local file the reader can work from without heap pressure. */
    private sealed class ReadSource {
        data class Ready(val file: java.io.File, val deleteAfter: Boolean) : ReadSource()
        data class Error(val message: String) : ReadSource()
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
     * registry. Share arrivals are copied into private storage (grants aren't persistable), and
     * copies no longer referenced by the registry are deleted so disk usage stays bounded by the
     * registry cap. Safe to call for both `ACTION_SEND` URIs and picker results. Never throws.
     */
    @Synchronized
    fun register(context: Context, uri: Uri, mime: String?, source: String, takePersistable: Boolean = true): SharedFile? {
        val appContext = context.applicationContext
        val (nameBeforeCopy, sizeBeforeCopy) = queryNameAndSize(appContext, uri)
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
        evictOrphanedCopies(appContext, merged)
        return record
    }

    /** Delete share copies that the registry no longer references (bounded disk, bounded registry). */
    private fun evictOrphanedCopies(context: Context, keep: List<SharedFile>) {
        val dir = java.io.File(context.filesDir, "shared")
        val keepPaths = keep.mapNotNull { shared ->
            Uri.parse(shared.uri).takeIf { it.scheme == "file" }?.path
        }.mapNotNull { runCatching { java.io.File(it).canonicalPath }.getOrNull() }.toSet()
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("share_")) {
                val canon = runCatching { f.canonicalPath }.getOrNull() ?: return@forEach
                if (canon !in keepPaths) runCatching { f.delete() }
            }
        }
    }

    fun findShared(context: Context, query: String): SharedFile? {
        val q = query.trim().lowercase()
        val all = listShared(context, MAX_SHARED_RECORDS)
        if (q.isEmpty()) return all.firstOrNull()
        return all.firstOrNull { it.name.lowercase().contains(q) }
    }

    /** Delete the registry file and every private copy in the shared dir. */
    fun clearShared(context: Context) {
        runCatching { storeFile(context).delete() }
        java.io.File(context.filesDir, "shared").listFiles()?.forEach { runCatching { it.delete() } }
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
                        if (total > MAX_FILE_BYTES) {
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

    /** Canonical path of the private shared dir, with separator-boundary comparison. */
    private fun sharedDirPath(context: Context): String =
        java.io.File(context.filesDir, "shared").canonicalPath

    /** True only when [canon] is strictly inside the shared dir (sibling `shared_evil/` cannot match). */
    private fun isInsideSharedDir(sharedDir: String, canon: String): Boolean =
        canon.startsWith(sharedDir + java.io.File.separator)

    /**
     * A Uri a viewer app is allowed to see: file:// copies become FileProvider content URIs
     * (file:// is rejected on modern Android); everything else passes through unchanged.
     */
    fun viewableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        val canon = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return uri
        if (!isInsideSharedDir(sharedDirPath(context), canon)) return uri
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

    /**
     * Resolve [uri] to a local file without heap pressure:
     *  - a `file://` URI confined to the shared dir is read in place (no copy);
     *  - anything else is *streamed* into a bounded cache temp file ([MAX_FILE_BYTES] cap).
     */
    private fun openSource(context: Context, uri: Uri, display: String): ReadSource {
        if (uri.scheme == "file") {
            val canon = runCatching { uri.path?.let { java.io.File(it).canonicalPath } }.getOrNull()
                ?: return ReadSource.Error("Could not resolve $display.")
            return if (isInsideSharedDir(sharedDirPath(context), canon)) {
                ReadSource.Ready(java.io.File(canon), deleteAfter = false)
            } else {
                ReadSource.Error("Refusing to read $display: it is outside Aniob's private shared store.")
            }
        }
        val dir = java.io.File(context.cacheDir, "read_tmp").apply { mkdirs() }
        val tmp = java.io.File(dir, "read_${System.currentTimeMillis()}_${(Math.random() * 1e9).toLong()}.tmp")
        var total = 0L
        val result = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) {
                            return@runCatching false
                        }
                        output.write(buf, 0, n)
                    }
                }
                true
            } ?: throw java.io.IOException("provider returned no stream")
        }
        if (result.getOrDefault(false) != true) {
            tmp.delete()
            return if (total > MAX_FILE_BYTES) {
                ReadSource.Error("$display is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB; refusing to load it into memory.")
            } else {
                ReadSource.Error("Could not open $display.")
            }
        }
        return ReadSource.Ready(tmp, deleteAfter = true)
    }

    /** Read/extract [uri] as text. Never throws; always carries an honest explanation. */
    fun readText(context: Context, uri: Uri, mimeType: String?): ReadResult {
        val appContext = context.applicationContext
        val (name, _) = queryNameAndSize(appContext, uri)
        val display = name ?: (uri.lastPathSegment ?: "file")
        val mime = (mimeType ?: runCatching { appContext.contentResolver.getType(uri) }.getOrNull()).orEmpty().lowercase()
        val ext = display.substringAfterLast('.', "").lowercase()

        if (ext in LEGACY_OFFICE_EXTENSIONS) {
            return ReadResult.Unsupported(
                "$display is a legacy binary Office file, which this device build cannot read. Open it in Word/WPS instead, or share it to Aniob as .docx/.pdf."
            )
        }

        // Resolve to a local file first; nothing below buffers the payload into the heap.
        val source = openSource(appContext, uri, display)
        if (source is ReadSource.Error) return ReadResult.Failed(source.message)
        // Sealed type: Kotlin does not negative-narrow after the is-check, so cast explicitly.
        val ready = source as ReadSource.Ready
        val file = ready.file
        try {
            val isTextLike = mime.startsWith("text/") || ext in TEXT_EXTENSIONS ||
                ext == "json" || mime == "application/json"
            val text: String? = when {
                isTextLike -> readTextFileCapped(file)
                looksLikeZipFile(file) -> extractFromZipContainer(file)
                ext == "pdf" || mime == "application/pdf" -> {
                    if (file.length() > MAX_PDF_BYTES) {
                        return ReadResult.Unsupported(
                            "$display is larger than ${MAX_PDF_BYTES / (1024 * 1024)} MB; refusing to parse it into memory. Open it instead."
                        )
                    }
                    extractPdfText(file.readBytes())
                }
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
        } finally {
            if (ready.deleteAfter) runCatching { file.delete() }
        }
    }

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "tsv", "log", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts",
        "c", "cpp", "h", "go", "rs", "html", "css", "gradle", "properties", "sh", "json5", "rtf"
    )

    private val LEGACY_OFFICE_EXTENSIONS = setOf("doc", "ppt", "xls", "wps")

    private fun looksLikeZipFile(file: java.io.File): Boolean =
        runCatching {
            file.inputStream().use { input ->
                val b = ByteArray(4)
                val n = input.read(b)
                n >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
                    (b[2] == 0x03.toByte() || b[2] == 0x05.toByte() || b[2] == 0x07.toByte())
            }
        }.getOrDefault(false)

    /** Stream a text file, keeping at most MAX_TEXT_CHARS+1 characters. Never loads the whole file. */
    private fun readTextFileCapped(file: java.io.File): String {
        val sb = StringBuilder()
        var kept = 0
        java.io.BufferedReader(file.reader(Charsets.UTF_8), 16 * 1024).use { reader ->
            val buf = CharArray(8 * 1024)
            while (kept <= MAX_TEXT_CHARS) {
                val n = reader.read(buf)
                if (n <= 0) break
                val room = MAX_TEXT_CHARS + 1 - kept
                sb.append(buf, 0, minOf(n, room))
                kept += n
            }
        }
        return sb.toString()
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Extracts readable text from OOXML/ODF (ZIP) containers: strip tags, keep text nodes. */
    internal fun extractFromZipContainer(file: java.io.File): String? {
        val text = StringBuilder()
        var sawContainer = false
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                val wanted = mutableListOf<java.util.zip.ZipEntry>()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.lowercase()
                    val isTextPart = when {
                        name == "word/document.xml" -> true                       // docx
                        name.startsWith("ppt/slides/slide") && name.endsWith(".xml") -> true  // pptx
                        name == "xl/sharedstrings.xml" -> true                    // xlsx
                        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> true
                        name == "content.xml" -> true                             // ODF (odt/ods/odp)
                        else -> false
                    }
                    if (isTextPart) wanted += entry
                }
                if (wanted.isEmpty()) return@runCatching
                sawContainer = true
                val ordered = wanted.sortedBy { entry ->
                    val name = entry.name.lowercase()
                    when {
                        name.contains("document.xml") || name == "content.xml" -> "0"
                        name.contains("slides/") -> "1" + name.filter { it.isDigit() }.padStart(4, '0')
                        name == "xl/sharedstrings.xml" -> "2"
                        else -> "3" + name
                    }
                }
                for (entry in ordered) {
                    zip.getInputStream(entry).use { input ->
                        readCapped(input, MAX_ENTRY_BYTES)?.let { bytes ->
                            text.append(stripXml(String(bytes, Charsets.UTF_8))).append('\n')
                        }
                    }
                    if (text.length >= MAX_TEXT_CHARS) break
                }
            }
        }.getOrNull() ?: return null
        return if (sawContainer && text.isNotBlank()) text.toString() else null
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
     * Each stream chunk is capped at [MAX_STREAM_CHUNK] so the parse never buffers unbounded data.
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
            val chunkLen = minOf(endAt, contentStart + MAX_STREAM_CHUNK) - contentStart
            if (chunkLen <= 0) {
                idx = endAt + "endstream".length
                continue
            }
            val chunk = bytes.copyOfRange(contentStart, contentStart + chunkLen)
            val inflated = inflate(chunk)
            val body = inflated ?: if (looksMostlyPrintable(chunk)) String(chunk, Charsets.ISO_8859_1) else null
            if (body != null && (body.contains("Tj") || body.contains("TJ") || body.contains("Tf"))) {
                for (match in stringRegex.findAll(body)) {
                    val inner = match.value.substring(1, match.value.length - 1)
                    out.append(unescapePdfString(inner))
                    out.append(' ')
                    if (out.length >= MAX_TEXT_CHARS + 1) break
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
                    if (sink.size() > MAX_STREAM_CHUNK) break
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
