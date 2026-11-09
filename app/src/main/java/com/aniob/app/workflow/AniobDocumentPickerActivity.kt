package com.aniob.app.workflow

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.aniob.app.device.AniobDocumentAccess

/**
 * Transparent trampoline that opens the Storage Access Framework document picker.
 *
 * This is the one pathway into cloud-stored documents (a signed-in Google Drive, OneDrive, a
 * WhatsApp folder, …) that Android allows a normal app without any OAuth of its own: the system
 * picker shows the user's providers, the user chooses a file, and we take a persistable read grant
 * on exactly that file. Nothing is read, listed or stored without that explicit user gesture.
 *
 * Extra `aniob.pick.kind` narrows the MIME set (pdf|document|presentation|spreadsheet|image|any).
 * The picked URI is persisted and recorded in the shared-file registry so later steps can read it.
 */
class AniobDocumentPickerActivity : ComponentActivity() {

    private lateinit var launcher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty().ifBlank { "any" }
        launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val mime = intent.getStringExtra(EXTRA_MIME)
                    ?: runCatching { contentResolver.getType(uri) }.getOrNull()
                val record = AniobDocumentAccess.register(this, uri, mime, source = "picker", takePersistable = true)
                Toast.makeText(
                    this,
                    if (record != null) "Got it: ${record.name}" else "Got the file, but couldn't remember it.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this, "No file picked.", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
        if (savedInstanceState == null) {
            launcher.launch(AniobDocumentAccess.mimeTypesFor(kind))
        }
    }

    companion object {
        const val EXTRA_KIND = "aniob.pick.kind"
        const val EXTRA_MIME = "aniob.pick.mime"
    }
}
