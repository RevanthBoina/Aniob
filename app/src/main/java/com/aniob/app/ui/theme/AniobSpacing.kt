package com.aniob.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * ANIOB SPACING + SIZING TOKENS
 * -----------------------------
 * Measured in the old build: 76 uses of 8.dp, 52 of 16.dp, 39 of 12.dp, 32 of 4.dp,
 * 18 of 6.dp, 18 of 10.dp, plus one-offs at 14/18/20/54/72/90.dp. Six of those values
 * are off the 4dp grid, which is exactly why the screens feel "almost aligned but not".
 *
 * This is a 4dp grid with named intent. If a value you need is not here, the layout
 * is probably wrong — not the scale.
 */
@Immutable
data class AniobSpacing(
    /** 2dp  — optical nudges only (icon baseline correction). */
    val hair: Dp = 2.dp,
    /** 4dp  — inside a chip, between an icon and its own label. */
    val xxs: Dp = 4.dp,
    /** 8dp  — between tightly related items in a row. */
    val xs: Dp = 8.dp,
    /** 12dp — inner padding of dense rows. */
    val s: Dp = 12.dp,
    /** 16dp — THE default. Card padding, screen gutter, list item padding. */
    val m: Dp = 16.dp,
    /** 24dp — between unrelated groups inside one screen. */
    val l: Dp = 24.dp,
    /** 32dp — section separation. */
    val xl: Dp = 32.dp,
    /** 48dp — empty-state breathing room. */
    val xxl: Dp = 48.dp,

    /** Horizontal screen gutter. Single source of truth. */
    val screenGutter: Dp = 16.dp,
    /** Vertical gap between chat messages from DIFFERENT authors. */
    val bubbleGap: Dp = 12.dp,
    /** Vertical gap between consecutive messages from the SAME author. */
    val bubbleGapGrouped: Dp = 3.dp,
    /** Space reserved above the composer so the last bubble never hides behind it. */
    val composerClearance: Dp = 8.dp
)

@Immutable
data class AniobSizing(
    /**
     * WCAG 2.5.5 / Material minimum. The old build shipped a 32.dp IconButton for
     * "pause task" (AniobChatScreen.kt:333) and a 24.dp one for "cancel queued task"
     * (line 474) — both below the floor, both on destructive-ish actions.
     */
    val minTouchTarget: Dp = 48.dp,
    /** Icons inside buttons. */
    val iconSmall: Dp = 18.dp,
    val iconMedium: Dp = 24.dp,
    val iconLarge: Dp = 32.dp,
    /** Empty-state hero glyph. */
    val heroIcon: Dp = 72.dp,
    /** Max readable measure for an answer bubble (~60 characters at bodyLarge). */
    val bubbleMaxWidth: Dp = 328.dp,
    /** The live status dot in the in-app ticker. */
    val statusDot: Dp = 10.dp,
    /** Height of the cockpit ticker. Fixed so it never reflows mid-task. */
    val tickerHeight: Dp = 52.dp,
    /** Hairline used for timeline rails. */
    val rail: Dp = 2.dp
)
