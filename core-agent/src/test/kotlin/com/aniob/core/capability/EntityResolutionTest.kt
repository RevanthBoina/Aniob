package com.aniob.core.capability

import com.aniob.core.domain.EntityCandidate
import com.aniob.core.domain.EntityReference
import com.aniob.core.domain.EntityResolution
import org.junit.Assert.*
import org.junit.Test

class EntityResolutionTest {
    @Test fun `duplicate labels do not collapse distinct identities`() {
        val first = EntityCandidate(EntityReference("contact", "opaque-1"), "Alex", "Work")
        val second = EntityCandidate(EntityReference("contact", "opaque-2"), "Alex", "Personal")
        val resolution = EntityResolution.MultipleCandidates(listOf(first, second))
        assertEquals(second, resolution.select(second.reference)?.candidate)
        assertNull(resolution.select(EntityReference("contact", "Alex")))
        assertNull(resolution.select(EntityReference("file", "opaque-2")))
    }

    @Test fun `resolution outcomes remain distinct`() {
        val candidate = EntityCandidate(EntityReference("app", "package-id"), "A label")
        assertEquals(candidate.reference, EntityResolution.Resolved(candidate).candidate.reference)
        val notFound: EntityResolution = EntityResolution.NotFound
        assertEquals(EntityResolution.NotFound, notFound)
        assertEquals("contacts", EntityResolution.PermissionRequired("contacts").permission)
        assertEquals("offline", EntityResolution.Unavailable("offline").reason)
    }

    @Test fun `offered candidate list is a stable snapshot`() {
        val first = EntityCandidate(EntityReference("contact", "1"), "Sam")
        val second = EntityCandidate(EntityReference("contact", "2"), "Sam")
        val source = mutableListOf(first, second)
        val result = EntityResolution.MultipleCandidates(source)
        source.clear()
        assertEquals(first, result.select(first.reference)?.candidate)
        assertThrows(UnsupportedOperationException::class.java) {
            (result.candidates as MutableList<EntityCandidate>).clear()
        }
    }

    @Test fun `duplicate and mixed-kind candidates are rejected`() {
        val contact = EntityCandidate(EntityReference("contact", "1"), "Name")
        val file = EntityCandidate(EntityReference("file", "2"), "Name")
        assertThrows(IllegalArgumentException::class.java) {
            EntityResolution.MultipleCandidates(listOf(contact, contact.copy(label = "Other")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EntityResolution.MultipleCandidates(listOf(contact, file))
        }
    }
}
