package com.aniob.app

import android.content.Context
import android.content.pm.ApplicationInfo

/** Build-time policy based only on Android's real debuggable flag. */
object AniobBuild {
    fun isDeveloperBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
