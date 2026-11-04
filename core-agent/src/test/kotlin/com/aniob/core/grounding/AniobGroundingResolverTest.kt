package com.aniob.core.grounding

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AniobGroundingResolverTest {

    private fun makeNode(
        id: Int,
        text: String = "",
        contentDesc: String = "",
        viewId: String = "",
        clickable: Boolean = true,
        enabled: Boolean = true,
        visible: Boolean = true
    ): AniobNode = AniobNode(
        id = id,
        className = "android.widget.Button",
        text = text,
        contentDescription = contentDesc,
        viewId = viewId,
        isClickable = clickable,
        isEnabled = enabled,
        isVisibleToUser = visible,
        bounds = AniobRect(10, 10, 110, 60)
    )

    @Test
    fun `resolves valid visible enabled node by som index`() {
        val screen = AniobScreenState(
            packageName = "com.test",
            nodes = listOf(makeNode(id = 5, text = "Save"))
        )
        val resolved = AniobGroundingResolver.resolve(SemanticTarget.SomIndex(5), screen)
        assertNotNull(resolved)
        assertEquals(5, resolved?.node?.id)
        assertEquals(60, resolved?.x)
        assertEquals(35, resolved?.y)
    }

    @Test
    fun `rejects invisible or disabled node by som index`() {
        val screen = AniobScreenState(
            packageName = "com.test",
            nodes = listOf(
                makeNode(id = 1, text = "Disabled", enabled = false),
                makeNode(id = 2, text = "Invisible", visible = false)
            )
        )
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.SomIndex(1), screen))
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.SomIndex(2), screen))
    }

    @Test
    fun `rejects invisible or disabled node by text and content desc`() {
        val screen = AniobScreenState(
            packageName = "com.test",
            nodes = listOf(
                makeNode(id = 1, text = "Submit", enabled = false),
                makeNode(id = 2, contentDesc = "Close icon", visible = false)
            )
        )
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.Text("Submit"), screen))
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.ContentDesc("Close icon"), screen))
    }

    @Test
    fun `rejects ambiguous text targets without clickable disambiguation`() {
        val screen = AniobScreenState(
            packageName = "com.test",
            nodes = listOf(
                makeNode(id = 1, text = "More", clickable = true),
                makeNode(id = 2, text = "More", clickable = true)
            )
        )
        // Two identical clickable "More" nodes -> ambiguous -> null
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.Text("More"), screen))
    }

    @Test
    fun `disambiguates text targets when only one is clickable`() {
        val screen = AniobScreenState(
            packageName = "com.test",
            nodes = listOf(
                makeNode(id = 1, text = "Settings", clickable = false),
                makeNode(id = 2, text = "Settings", clickable = true)
            )
        )
        val resolved = AniobGroundingResolver.resolve(SemanticTarget.Text("Settings"), screen)
        assertNotNull(resolved)
        assertEquals(2, resolved?.node?.id)
    }
    @Test
    fun `coordinates without an observed node fail closed`() {
        val empty = AniobScreenState(packageName = "com.test", nodes = emptyList())
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.NormalizedCoordinate(50, 50), empty))
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.BoundingBox(10, 10, 20, 20), empty))
    }

    @Test
    fun `tied fuzzy labels are not guessed`() {
        val screen = AniobScreenState(packageName = "com.test", nodes = listOf(
            makeNode(1, text = "Settings", clickable = false),
            makeNode(2, text = "Settings", clickable = false)
        ))
        assertNull(AniobGroundingResolver.resolve(SemanticTarget.Text("Setting"), screen))
    }

}
