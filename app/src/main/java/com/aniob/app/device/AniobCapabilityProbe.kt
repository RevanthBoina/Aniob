package com.aniob.app.device

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.core.capability.AniobCapabilityManifest

/**
 * Reads the live permission/service state the prompt manifest and the device-control layer share, so
 * the model is told the truth about what can run right now (and says "enable X" instead of failing).
 * Cheap: a handful of system flag reads, safe to call once per planning step.
 */
object AniobCapabilityProbe {

    fun snapshot(context: Context): AniobCapabilityManifest.DeviceState {
        val app = context.applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return AniobCapabilityManifest.DeviceState(
            accessibility = AniobAccessibilityService.isServiceConnected,
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
            callPhone = granted(app, Manifest.permission.CALL_PHONE),
            contacts = granted(app, Manifest.permission.READ_CONTACTS),
            location = granted(app, Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(app, Manifest.permission.ACCESS_COARSE_LOCATION),
            allFilesAccess = Environment.isExternalStorageManager(),
            dndAccess = nm?.isNotificationPolicyAccessGranted == true
        )
    }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
