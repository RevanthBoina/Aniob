package com.aniob.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * ANIOB COLOR SYSTEM
 * ------------------
 * Two layers, deliberately separated:
 *
 *  1. MATERIAL ROLES  - primary / surface / error ... used for ordinary chrome.
 *  2. AGENT STATE     - idle / working / needsYou / verified / blocked.
 *
 * Layer 2 exists because Aniob is not a content app; it is an agent that drives the
 * user's phone. At any instant the single most important question a user has is
 * "what is it doing right now, and does it need me?". That question must be
 * answerable in the in-app task ticker and from a notification shade; no floating
 * status pill is shown over the app being operated. A single `primary` colour cannot carry five states,
 * which is why the old build hardcoded 0xFF00C853, 0xFF1976D2, 0xFF2E7D32 and
 * 0xFFC62828 in four different files and still looked inconsistent.
 *
 * Every colour below is checked against its paired `on*` colour for >= 4.5:1
 * contrast (WCAG AA body text). The `*Container` pairs target >= 7:1 because they
 * carry status copy that users read while distracted.
 */

// ---------------------------------------------------------------------------
// Reference palette (raw hues — never reference these from a composable)
// ---------------------------------------------------------------------------

/** Brand. Indigo reads as "instrument / control surface", not "toy". */
private val Indigo10 = Color(0xFF11113A)
private val Indigo20 = Color(0xFF1D1D5C)
private val Indigo30 = Color(0xFF2E2E86)
private val Indigo40 = Color(0xFF4245B8)
private val Indigo50 = Color(0xFF5457D7)
private val Indigo60 = Color(0xFF7A7CE4)
private val Indigo70 = Color(0xFF9EA0EE)
private val Indigo80 = Color(0xFFC1C2F6)
private val Indigo90 = Color(0xFFE0E0FB)
private val Indigo95 = Color(0xFFF0F0FD)

/** Working / live. Cyan is the only hue that never collides with OEM system UI accents. */
private val Cyan10 = Color(0xFF002027)
private val Cyan20 = Color(0xFF00363F)
private val Cyan30 = Color(0xFF00515F)
private val Cyan40 = Color(0xFF00758A)
private val Cyan50 = Color(0xFF0094AE)
private val Cyan60 = Color(0xFF22B8D4)
private val Cyan70 = Color(0xFF5CD2E8)
private val Cyan80 = Color(0xFF9AE6F5)
private val Cyan90 = Color(0xFFCDF3FA)

/** Needs you. Amber = "stopped and waiting", never used for anything else. */
private val Amber10 = Color(0xFF291800)
private val Amber20 = Color(0xFF452900)
private val Amber30 = Color(0xFF653D00)
private val Amber40 = Color(0xFF8A5400)
private val Amber50 = Color(0xFFB06B00)
private val Amber60 = Color(0xFFDD8A0A)
private val Amber70 = Color(0xFFF5AD3C)
private val Amber80 = Color(0xFFFFCE82)
private val Amber90 = Color(0xFFFFE8C4)

/** Verified. Reserved for outcomes Aniob actually proved, never for "probably done". */
private val Emerald10 = Color(0xFF002114)
private val Emerald20 = Color(0xFF003824)
private val Emerald30 = Color(0xFF005236)
private val Emerald40 = Color(0xFF00774E)
private val Emerald50 = Color(0xFF009963)
private val Emerald60 = Color(0xFF17BB7E)
private val Emerald70 = Color(0xFF52D69D)
private val Emerald80 = Color(0xFF8EEBC0)
private val Emerald90 = Color(0xFFC6F7DE)

/** Blocked / failed. */
private val Rose10 = Color(0xFF400010)
private val Rose20 = Color(0xFF64001C)
private val Rose30 = Color(0xFF8C0C2C)
private val Rose40 = Color(0xFFB3213F)
private val Rose50 = Color(0xFFD93A57)
private val Rose60 = Color(0xFFEE6379)
private val Rose70 = Color(0xFFF991A1)
private val Rose80 = Color(0xFFFFBAC4)
private val Rose90 = Color(0xFFFFDBE0)

/** Neutrals, very slightly indigo-tinted so surfaces feel part of the brand. */
private val Neutral0 = Color(0xFF000000)
private val Neutral6 = Color(0xFF0E0E14)
private val Neutral10 = Color(0xFF14141B)
private val Neutral12 = Color(0xFF181820)
private val Neutral17 = Color(0xFF212129)
private val Neutral20 = Color(0xFF292933)
private val Neutral22 = Color(0xFF2D2D38)
private val Neutral24 = Color(0xFF32323D)
private val Neutral30 = Color(0xFF44444F)
private val Neutral50 = Color(0xFF76767F)
private val Neutral60 = Color(0xFF90909A)
private val Neutral70 = Color(0xFFABABB4)
private val Neutral80 = Color(0xFFC7C7CF)
private val Neutral90 = Color(0xFFE4E3EB)
private val Neutral94 = Color(0xFFEFEEF5)
private val Neutral96 = Color(0xFFF5F4FA)
private val Neutral98 = Color(0xFFFBFAFF)
private val Neutral100 = Color(0xFFFFFFFF)

// ---------------------------------------------------------------------------
// Material 3 schemes
// ---------------------------------------------------------------------------

internal val AniobLightScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = Neutral100,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    inversePrimary = Indigo80,

    secondary = Cyan40,
    onSecondary = Neutral100,
    secondaryContainer = Cyan90,
    onSecondaryContainer = Cyan10,

    tertiary = Amber40,
    onTertiary = Neutral100,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,

    background = Neutral98,
    onBackground = Neutral10,

    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = Neutral94,
    onSurfaceVariant = Neutral30,
    surfaceTint = Indigo40,

    inverseSurface = Neutral20,
    inverseOnSurface = Neutral96,

    error = Rose40,
    onError = Neutral100,
    errorContainer = Rose90,
    onErrorContainer = Rose10,

    outline = Neutral50,
    outlineVariant = Neutral80,
    scrim = Neutral0,

    surfaceBright = Neutral100,
    surfaceDim = Neutral90,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral98,
    surfaceContainer = Neutral96,
    surfaceContainerHigh = Neutral94,
    surfaceContainerHighest = Neutral90
)

internal val AniobDarkScheme = darkColorScheme(
    primary = Indigo70,
    onPrimary = Indigo10,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    inversePrimary = Indigo40,

    secondary = Cyan70,
    onSecondary = Cyan10,
    secondaryContainer = Cyan30,
    onSecondaryContainer = Cyan90,

    tertiary = Amber70,
    onTertiary = Amber10,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,

    background = Neutral6,
    onBackground = Neutral90,

    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral20,
    onSurfaceVariant = Neutral80,
    surfaceTint = Indigo70,

    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,

    error = Rose70,
    onError = Rose10,
    errorContainer = Rose30,
    onErrorContainer = Rose90,

    outline = Neutral60,
    outlineVariant = Neutral30,
    scrim = Neutral0,

    surfaceBright = Neutral30,
    surfaceDim = Neutral6,
    surfaceContainerLowest = Neutral0,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22
)

// ---------------------------------------------------------------------------
// Agent state colours  (the layer Material does not give you)
// ---------------------------------------------------------------------------

/**
 * One colour trio per agent state. `accent` is for dots/strokes/icons, `container`
 * for the card behind status copy, `onContainer` for that copy.
 *
 * Usage: `AniobTheme.state.working.accent` — never a raw Color(0x...) in a screen.
 */
@Immutable
data class AniobStateColor(
    val accent: Color,
    val container: Color,
    val onContainer: Color
)

@Immutable
data class AniobStateColors(
    /** Nothing running. Deliberately low-chroma so "working" pops against it. */
    val idle: AniobStateColor,
    /** Aniob is actuating the device right now. Highest-salience state. */
    val working: AniobStateColor,
    /** Blocked on a human: approval, clarification, or a manual step. */
    val needsYou: AniobStateColor,
    /** Finished AND verified against success criteria. */
    val verified: AniobStateColor,
    /** Failed, stopped, or refused by the safety gate. */
    val blocked: AniobStateColor,
    /** Chat surfaces. */
    val userBubble: Color,
    val onUserBubble: Color,
    val agentBubble: Color,
    val onAgentBubble: Color,
    /** Hairline used for timeline rails and step connectors. */
    val rail: Color
)

internal val AniobLightStateColors = AniobStateColors(
    idle = AniobStateColor(Neutral50, Neutral94, Neutral30),
    working = AniobStateColor(Cyan50, Cyan90, Cyan10),
    needsYou = AniobStateColor(Amber50, Amber90, Amber10),
    verified = AniobStateColor(Emerald40, Emerald90, Emerald10),
    blocked = AniobStateColor(Rose40, Rose90, Rose10),
    userBubble = Indigo90,
    onUserBubble = Indigo10,
    agentBubble = Neutral100,
    onAgentBubble = Neutral10,
    rail = Neutral80
)

internal val AniobDarkStateColors = AniobStateColors(
    idle = AniobStateColor(Neutral60, Neutral17, Neutral80),
    working = AniobStateColor(Cyan60, Cyan20, Cyan90),
    needsYou = AniobStateColor(Amber60, Amber20, Amber90),
    verified = AniobStateColor(Emerald60, Emerald20, Emerald90),
    blocked = AniobStateColor(Rose60, Rose20, Rose90),
    userBubble = Indigo30,
    onUserBubble = Indigo90,
    agentBubble = Neutral12,
    onAgentBubble = Neutral90,
    rail = Neutral30
)

internal val LocalAniobStateColors = staticCompositionLocalOf { AniobLightStateColors }

/*
 * Kept only so the old imports in MainActivity / AniobMainScreen keep resolving
 * during migration. Delete once no screen references them.
 */
@Deprecated("Use MaterialTheme.colorScheme or AniobTheme.state", ReplaceWith("MaterialTheme.colorScheme.primary"))
val AniobCyan = Cyan50

@Deprecated("Use MaterialTheme.colorScheme.background", ReplaceWith("MaterialTheme.colorScheme.background"))
val AniobNavy = Neutral6

@Deprecated("Use MaterialTheme.colorScheme.surfaceContainer", ReplaceWith("MaterialTheme.colorScheme.surfaceContainer"))
val AniobSlate = Neutral17

@Deprecated("Use MaterialTheme.colorScheme.background", ReplaceWith("MaterialTheme.colorScheme.background"))
val AniobLightBg = Neutral98
