package com.aniob.app.device

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import com.aniob.core.domain.EntityReference
import com.aniob.core.domain.EntityResolution
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AniobContactResolverTest {
    private lateinit var app: Application
    private lateinit var provider: ContactProvider
    private lateinit var resolver: AniobContactEntityResolver

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider = ContactProvider()
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        resolver = AniobContactEntityResolver(app)
    }

    @Test fun zeroAndOneMatchReturnTypedResults() {
        assertEquals(EntityResolution.NotFound, resolver.resolve("contact", "Nobody"))
        provider.rows += ContactEntityRow(1, "River", "+12345678901")
        val result = resolver.resolve("contact", "River") as EntityResolution.Resolved
        assertEquals("River", result.candidate.label)
        assertFalse(result.candidate.toString().contains("12345678901"))
        assertEquals(ContactNumberResult.Resolved("+12345678901"), resolver.numberFor(result.candidate.reference))
        assertEquals(result.candidate.reference, (resolver.resolve("contact", "River") as EntityResolution.Resolved).candidate.reference)
    }

    @Test fun ambiguousChoicesUseStableIdsNotNumberLabels() {
        provider.rows += ContactEntityRow(11, "River", "+12345678901")
        provider.rows += ContactEntityRow(12, "River", "+12345678902")
        val result = resolver.resolve("contact", "River") as EntityResolution.MultipleCandidates
        assertEquals(2, result.candidates.size)
        assertNotEquals(result.candidates[0].reference, result.candidates[1].reference)
        assertFalse(result.candidates.toString().contains("12345678901"))
        assertFalse(result.candidates.toString().contains("12345678902"))
        val selected = result.select(result.candidates[1].reference)!!
        assertEquals(ContactNumberResult.Resolved("+12345678902"), resolver.numberFor(selected.candidate.reference))
        assertNull(result.select(EntityReference("contact", "River")))
        assertEquals(ContactNumberResult.NotFound, AniobContactEntityResolver(app).numberFor(selected.candidate.reference))
    }

    @Test fun deviceCommandOffersIdsAndRejectsStaleSelection() {
        provider.rows += ContactEntityRow(11, "River", "+12345678901")
        provider.rows += ContactEntityRow(12, "River", "+12345678902")
        val args = mapOf("contact" to "River", "text" to "hello")
        val asked = AniobDeviceControl.executeForTask(app, com.aniob.core.domain.DeviceCommand.MESSAGE_SMS,
            args, null, resolver, null)
        assertFalse(asked.ok)
        val candidates = asked.contactChoices!!
        val question = asked.clarify!!
        assertEquals(candidates.candidates.map { it.reference.id }, question.choices.map { it.id })
        assertFalse(question.toString().contains("12345678901"))
        assertFalse(question.toString().contains("12345678902"))
        assertFalse(question.acceptsAnswer("River"))
        val selected = candidates.candidates[1].reference
        provider.rows[1] = provider.rows[1].copy(number = "+12345678999")
        val stale = AniobDeviceControl.executeForTask(app, com.aniob.core.domain.DeviceCommand.MESSAGE_SMS,
            args, null, resolver, selected)
        assertFalse(stale.ok)
        assertTrue(stale.message.contains("no longer valid"))
        val foreignResolver = AniobContactEntityResolver(app)
        val recreated = AniobDeviceControl.executeForTask(app, com.aniob.core.domain.DeviceCommand.MESSAGE_SMS,
            args, null, foreignResolver, selected)
        assertFalse(recreated.ok)
    }

    @Test fun missingOrRevokedPermissionAndChangedNumbersFailClosed() {
        provider.rows += ContactEntityRow(11, "River", "+12345678901")
        val ref = (resolver.resolve("contact", "River") as EntityResolution.Resolved).candidate.reference
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(resolver.resolveReference(ref) is EntityResolution.PermissionRequired)
        assertEquals(ContactNumberResult.PermissionRequired, resolver.numberFor(ref))
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        provider.rows[0] = provider.rows[0].copy(number = "+12345678999")
        assertEquals(ContactNumberResult.NotFound, resolver.numberFor(ref))
        assertEquals(EntityResolution.NotFound, resolver.resolveReference(ref))
    }

    @Test fun unavailableProviderAndPhoneNumberDisplayNameAreNotMisrepresented() {
        provider.unavailable = true
        assertTrue(resolver.resolve("contact", "River") is EntityResolution.Unavailable)
        provider.unavailable = false
        provider.rows += ContactEntityRow(1, "+12345678901", "+12345678901")
        val candidate = (resolver.resolve("contact", "123") as EntityResolution.Resolved).candidate
        assertEquals("Contact", candidate.label)
        assertFalse(candidate.toString().contains("12345678901"))
        provider.unavailable = true
        assertEquals(ContactNumberResult.Unavailable, resolver.numberFor(candidate.reference))
    }

    private class ContactProvider : ContentProvider() {
        val rows = mutableListOf<ContactEntityRow>()
        var unavailable = false
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (unavailable) return null
            val selected = if (selection.orEmpty().contains(" LIKE ")) {
                val query = selectionArgs?.firstOrNull()?.removePrefix("%")?.removeSuffix("%").orEmpty()
                rows.filter { it.displayName.contains(query, ignoreCase = true) }
            } else rows.filter { it.rowId.toString() == selectionArgs?.firstOrNull() }
            val cursor = MatrixCursor(projection ?: arrayOf(ContactsContract.CommonDataKinds.Phone._ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER))
            selected.forEach { cursor.addRow(arrayOf<Any?>(it.rowId, it.displayName, it.number)) }
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
