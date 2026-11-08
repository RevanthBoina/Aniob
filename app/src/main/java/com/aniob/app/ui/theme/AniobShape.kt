package com.aniob.app.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/*
 * ANIOB SHAPE SCALE
 * -----------------
 * The old build passed no `shapes` to MaterialTheme and then hardcoded
 * RoundedCornerShape(12/16/24/28.dp) in six files, so a Card on the chat screen and a
 * Card on the models screen had different radii for no reason.
 *
 * One scale, one meaning per step:
 *   xs  4dp   progress tracks, hairline chips
 *   s   10dp  dense rows, inline badges
 *   m   16dp  the default: cards, banners, sheets-in-content
 *   l   22dp  elevated surfaces that float over content
 *   xl  30dp  bottom sheets, dialogs, the composer
 */
val AniobShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

/** Shapes with a specific job, so no screen re-derives them. */
@Immutable
data class AniobComponentShapes(
    /** Assistant bubble: tail on the bottom-start. */
    val agentBubble: CornerBasedShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
    /** User bubble: tail on the bottom-end. */
    val userBubble: CornerBasedShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
    /** Consecutive bubbles from the same author — flatter join, tighter group. */
    val agentBubbleGrouped: CornerBasedShape = RoundedCornerShape(8.dp, 20.dp, 20.dp, 8.dp),
    val userBubbleGrouped: CornerBasedShape = RoundedCornerShape(20.dp, 8.dp, 8.dp, 20.dp),
    /** The text composer. Fully rounded so it reads as an input, not a card. */
    val composer: CornerBasedShape = RoundedCornerShape(26.dp),
    /** Bottom sheets. */
    val sheet: CornerBasedShape = RoundedCornerShape(30.dp, 30.dp, 0.dp, 0.dp),
    /** The task-result "proof" card. */
    val proofCard: CornerBasedShape = RoundedCornerShape(20.dp)
)
