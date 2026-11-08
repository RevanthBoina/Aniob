package com.aniob.core.knowledge

import java.net.URLEncoder

/**
 * The install catalogue shared by the intent resolver (deep link), the criteria extractor
 * (verification) and the app layer (PackageManager probe). One source of truth, so the package a
 * task opens and the package it verifies can never drift apart.
 *
 * "Opening the Play Store listing" is NOT "installed": [AniobIntentResolver.installViaStore] opens
 * the terminal page, and [com.aniob.core.domain.SuccessCriteria.mustBeInstalled] makes the final
 * verdict depend on PackageManager actually seeing the package.
 */
object AniobStoreCatalog {

    const val PLAY_STORE_PACKAGE = "com.android.vending"

    /** Seeds for apps people ask to install; the on-device registry only knows what exists already. */
    private val PACKAGES: Map<String, String> = mapOf(
        "linkedin" to "com.linkedin.android",
        "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp",
        "whatsapp business" to "com.whatsapp.w4b",
        "spotify" to "com.spotify.music",
        "telegram" to "org.telegram.messenger",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "youtube" to "com.google.android.youtube",
        "reddit" to "com.reddit.frontpage",
        "snapchat" to "com.snapchat.android",
        "tiktok" to "com.zhiliaoapp.musically",
        "netflix" to "com.netflix.mediaclient",
        "gmail" to "com.google.android.gm",
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "discord" to "com.discord",
        "zoom" to "us.zoom.videomeetings",
        "uber" to "com.ubercab",
        "swiggy" to "in.swiggy.android",
        "zomato" to "com.application.zomato",
        "paytm" to "net.one97.paytm",
        "phonepe" to "com.phonepe.app"
    )

    /**
     * Not anchored: "can you install ludo", "please download the ludo app" and "install ludo for me"
     * are the same request as "install ludo". Politeness is stripped first so the verb is found.
     */
    private val INSTALL_RX = Regex(
        """\b(?:install|download)\s+(?:the\s+)?(?:app\s+)?([a-z0-9][a-z0-9 .&'_+-]{1,40}?)(?:\s+app)?\s*$"""
    )

    /** The app name of an install prompt ("install linkedin app" -> "linkedin"), or null. */
    fun installTarget(prompt: String): String? {
        val lower = com.aniob.core.lexicon.TaskLexicon.stripPoliteness(prompt)
            .lowercase().trim().trimEnd('?', '.', '!', ',', ';')
        val name = INSTALL_RX.find(lower)?.groupValues?.get(1)?.trim() ?: return null
        return name.removeSuffix(" app").trim().ifBlank { null }
    }

    fun packageFor(name: String?): String? {
        val key = name?.lowercase()?.trim() ?: return null
        return PACKAGES[key]
    }

    /** Package id when the install target is known; null when only a store search is possible. */
    fun packageForPrompt(prompt: String): String? = packageFor(installTarget(prompt))

    fun isInstallPrompt(prompt: String): Boolean = installTarget(prompt) != null

    /** The terminal deep link: an exact listing when known, otherwise a Play Store search. */
    fun deepLinkFor(name: String): String {
        val pkg = packageFor(name)
        return if (pkg != null) "market://details?id=$pkg"
        else "market://search?q=${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}&c=apps"
    }

    fun webLinkFor(name: String): String {
        val pkg = packageFor(name)
        return if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg"
        else "https://play.google.com/store/search?q=${URLEncoder.encode(name, "UTF-8")}&c=apps"
    }
}
