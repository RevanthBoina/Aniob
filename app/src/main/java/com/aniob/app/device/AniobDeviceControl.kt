package com.aniob.app.device

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aniob.app.service.AniobAccessibilityService
import com.aniob.app.service.AniobNotificationListenerService
import com.aniob.core.capability.AniobCapabilityManifest
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.safety.AniobDlpSanitizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Android implementation of every [DeviceCommand]: one documented API per command, no screen
 * walk and no model round trip. Reached from three places, all through [execute]:
 *
 *  - the reflex path (`AniobReflexResolver` shortcut, 0 tokens),
 *  - a model-emitted `DEVICE_CONTROL` action (`AniobViewModel` dispatch), and
 *  - the accessibility service's system-intent handler (replays, skills).
 *
 * Contract: never throws, always answers. A command that cannot run says exactly which setting to
 * enable ([DeviceControlResult.blockedBy]) and, where Android allows, opens that settings page, so the
 * agent never has to answer "I can't do that".
 */
object AniobDeviceControl {

    private const val NEW_TASK = Intent.FLAG_ACTIVITY_NEW_TASK
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Settings pages already opened for a missing permission in this process: ask once, never nag. */
    private val settingsOpened = HashSet<String>()

    fun execute(
        context: Context,
        command: DeviceCommand,
        args: Map<String, String>,
        service: AniobAccessibilityService? = AniobAccessibilityService.instance
    ): DeviceControlResult {
        val app = context.applicationContext
        // DND handles its own missing-access case by opening the right settings page.
        if (command != DeviceCommand.DND_ON && command != DeviceCommand.DND_OFF) {
            val state = AniobCapabilityProbe.snapshot(app)
            AniobCapabilityManifest.blockedReason(command, state)?.let { reason ->
                if (command.needsAccessibility) openSettings(app, Settings.ACTION_ACCESSIBILITY_SETTINGS, "a11y")
                if (command.needsNotificationAccess) openSettings(app, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "nla")
                return blocked(reason)
            }
        }
        return try {
            run(app, command, args, service)
        } catch (e: SecurityException) {
            fail("Android refused this (${e.message ?: "permission"}). Check the app's permissions in Settings.")
        } catch (_: ActivityNotFoundException) {
            fail("No app on this phone can handle ${command.wire}.")
        } catch (_: CameraAccessException) {
            fail("The camera is busy, so the flashlight could not change.")
        } catch (e: RuntimeException) {
            fail("${command.wire} did not complete: ${e.javaClass.simpleName}.")
        }
    }

    // ============================================================================================
    // dispatch
    // ============================================================================================

    private fun run(
        context: Context,
        command: DeviceCommand,
        args: Map<String, String>,
        service: AniobAccessibilityService?
    ): DeviceControlResult = when (command) {
        DeviceCommand.MEDIA_PLAY_PAUSE -> media(context, MediaOp.TOGGLE)
        DeviceCommand.MEDIA_PLAY -> media(context, MediaOp.PLAY)
        DeviceCommand.MEDIA_PAUSE -> media(context, MediaOp.PAUSE)
        DeviceCommand.MEDIA_NEXT -> media(context, MediaOp.NEXT)
        DeviceCommand.MEDIA_PREVIOUS -> media(context, MediaOp.PREVIOUS)
        DeviceCommand.MEDIA_STOP -> media(context, MediaOp.STOP)
        DeviceCommand.MEDIA_PLAY_SEARCH -> mediaPlaySearch(context, args)
        DeviceCommand.MEDIA_NOW_PLAYING -> nowPlaying(context)

        DeviceCommand.VOLUME_SET -> volumeSet(context, args)
        DeviceCommand.VOLUME_UP -> volumeStep(context, args, raise = true)
        DeviceCommand.VOLUME_DOWN -> volumeStep(context, args, raise = false)
        DeviceCommand.VOLUME_MUTE -> volumeMute(context, args, mute = true)
        DeviceCommand.VOLUME_UNMUTE -> volumeMute(context, args, mute = false)
        DeviceCommand.VOLUME_GET -> volumeGet(context, args)

        DeviceCommand.TORCH_ON -> torch(context, true, args)
        DeviceCommand.TORCH_OFF -> torch(context, false, args)
        DeviceCommand.SCREENSHOT -> global(service, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken")
        DeviceCommand.OPEN_NOTIFICATIONS -> global(service, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notification shade opened")
        DeviceCommand.OPEN_QUICK_SETTINGS -> global(service, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings opened")
        DeviceCommand.LOCK_SCREEN -> global(service, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Phone locked")
        DeviceCommand.HIDE_KEYBOARD -> hideKeyboard(service)
        DeviceCommand.OPEN_PANEL -> openPanel(context, args)
        DeviceCommand.HOTSPOT_SETTINGS -> hotspot(context)
        DeviceCommand.DND_ON -> dnd(context, on = true)
        DeviceCommand.DND_OFF -> dnd(context, on = false)

        DeviceCommand.CALL -> call(context, args, service)
        DeviceCommand.MESSAGE_WHATSAPP -> whatsapp(context, args)
        DeviceCommand.MESSAGE_TELEGRAM -> telegram(context, args)
        DeviceCommand.MESSAGE_SMS -> sms(context, args)

        DeviceCommand.NOTIFICATION_LIST -> notificationList(args)
        DeviceCommand.NOTIFICATION_DISMISS -> notificationDismiss(args)
        DeviceCommand.NOTIFICATION_REPLY -> notificationReply(context, args)
        DeviceCommand.NOTIFICATION_ACTION -> notificationAction(context, args)
        DeviceCommand.OTP_STATUS -> otpStatus()
        DeviceCommand.OTP_COPY -> otpCopy(context)
        DeviceCommand.OTP_PASTE -> otpPaste(context, service)
        DeviceCommand.CLIPBOARD_GET -> clipboardGet(context)
        DeviceCommand.CLIPBOARD_SET -> clipboardSet(context, args["text"].orEmpty())
        DeviceCommand.CLIPBOARD_PASTE -> clipboardPaste(service)

        DeviceCommand.FILE_FIND -> fileFind(context, args)
        DeviceCommand.FILE_OPEN -> fileOpen(context, args)
        DeviceCommand.STORAGE_INFO -> storageInfo()

        DeviceCommand.TIMER_SET -> timer(context, args)
        DeviceCommand.ALARM_SET -> alarmSet(context, args)
        DeviceCommand.ALARM_DISMISS -> alarmDismiss(context, args)
        DeviceCommand.ALARMS_SHOW -> launch(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Alarm list opened")
        DeviceCommand.CALENDAR_INSERT -> calendarInsert(context, args)

        DeviceCommand.NAVIGATE -> navigate(context, args)
        DeviceCommand.MAPS_SEARCH -> mapsSearch(context, args["query"].orEmpty())
        DeviceCommand.LOCATION_SHARE -> locationShare(context)

        DeviceCommand.OPEN_URL -> openUrl(context, args)
    }

    // ============================================================================================
    // media
    // ============================================================================================

    private enum class MediaOp(val keyCode: Int, val verb: String) {
        TOGGLE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Toggled playback"),
        PLAY(KeyEvent.KEYCODE_MEDIA_PLAY, "Resumed playback"),
        PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused playback"),
        NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, "Skipped to the next track"),
        PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Went to the previous track"),
        STOP(KeyEvent.KEYCODE_MEDIA_STOP, "Stopped playback")
    }

    /** Active sessions need notification access; without it the media key still reaches the top player. */
    private fun controllers(context: Context): List<MediaController> {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return emptyList()
        val listener = android.content.ComponentName(context, AniobNotificationListenerService::class.java)
        return runCatching { manager.getActiveSessions(listener) }.getOrDefault(emptyList())
    }

    private fun bestController(context: Context): MediaController? {
        val all = controllers(context)
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    private fun media(context: Context, op: MediaOp): DeviceControlResult {
        val controller = bestController(context)
        if (controller != null) {
            val transport = controller.transportControls
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            when (op) {
                MediaOp.TOGGLE -> if (playing) transport.pause() else transport.play()
                MediaOp.PLAY -> transport.play()
                MediaOp.PAUSE -> transport.pause()
                MediaOp.NEXT -> transport.skipToNext()
                MediaOp.PREVIOUS -> transport.skipToPrevious()
                MediaOp.STOP -> transport.stop()
            }
            return ok("${op.verb} (${appLabel(context, controller.packageName)}).")
        }
        // No readable session: the system routes the media key to whichever player was active last.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, op.keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, op.keyCode))
        return ok("${op.verb}.")
    }

    private fun mediaPlaySearch(context: Context, args: Map<String, String>): DeviceControlResult {
        val query = args["query"].orEmpty().trim()
        if (query.isEmpty()) return fail("Tell me what to play.")
        val pkg = args["app"]?.takeIf { it.isNotBlank() }
        fun intent(withPackage: Boolean) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(android.app.SearchManager.QUERY, query)
            if (withPackage && pkg != null) setPackage(pkg)
            addFlags(NEW_TASK)
        }
        val started = tryStart(context, intent(true)) || (pkg != null && tryStart(context, intent(false)))
        return if (started) {
            ok("Searching \"$query\" in ${pkg?.let { appLabel(context, it) } ?: "your music app"}. Check the screen to confirm it is playing.")
        } else {
            fail("No music app could start \"$query\".")
        }
    }

    private fun nowPlaying(context: Context): DeviceControlResult {
        val controller = bestController(context) ?: return ok("Nothing is playing right now.", informational = true)
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val state = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> "idle"
        }
        val what = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" by ").ifBlank { "unknown track" }
        return ok("${appLabel(context, controller.packageName)} is $state: $what.", informational = true)
    }

    // ============================================================================================
    // volume
    // ============================================================================================

    private fun streamOf(name: String?): Int = when (name?.lowercase()) {
        "ring", "ringer", "ringtone" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun streamLabel(stream: Int): String = when (stream) {
        AudioManager.STREAM_RING -> "Ring"
        AudioManager.STREAM_ALARM -> "Alarm"
        AudioManager.STREAM_NOTIFICATION -> "Notification"
        AudioManager.STREAM_VOICE_CALL -> "Call"
        AudioManager.STREAM_SYSTEM -> "System"
        else -> "Media"
    }

    private fun percentOf(audio: AudioManager, stream: Int): Int {
        val min = audio.getStreamMinVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        return if (max <= min) 0 else ((audio.getStreamVolume(stream) - min) * 100.0 / (max - min)).toInt()
    }

    private fun volumeSet(context: Context, args: Map<String, String>): DeviceControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val percent = args["percent"]?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100)
            ?: return fail("volume_set needs a percent between 0 and 100.")
        val stream = streamOf(args["stream"])
        val min = audio.getStreamMinVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val target = min + Math.round((max - min) * percent / 100.0).toInt()
        audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return ok("${streamLabel(stream)} volume set to ${percentOf(audio, stream)}%.")
    }

    private fun volumeStep(context: Context, args: Map<String, String>, raise: Boolean): DeviceControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = streamOf(args["stream"])
        val steps = (args["steps"]?.toIntOrNull() ?: 2).coerceIn(1, 15)
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(steps) { audio.adjustStreamVolume(stream, direction, if (it == steps - 1) AudioManager.FLAG_SHOW_UI else 0) }
        return ok("${streamLabel(stream)} volume ${if (raise) "raised" else "lowered"} to ${percentOf(audio, stream)}%.")
    }

    private fun volumeMute(context: Context, args: Map<String, String>, mute: Boolean): DeviceControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = streamOf(args["stream"])
        if (stream == AudioManager.STREAM_RING) {
            // Vibrate is the quietest ringer mode Android lets an app set without Do Not Disturb access.
            audio.ringerMode = if (mute) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            return ok(if (mute) "Ringer muted (vibrate only)." else "Ringer back to normal.")
        }
        audio.adjustStreamVolume(
            stream,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
        return ok("${streamLabel(stream)} ${if (mute) "muted" else "unmuted"}.")
    }

    private fun volumeGet(context: Context, args: Map<String, String>): DeviceControlResult {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = if (args["stream"].isNullOrBlank()) {
            listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_ALARM)
        } else listOf(streamOf(args["stream"]))
        val mode = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        return ok(streams.joinToString(", ") { "${streamLabel(it)} ${percentOf(audio, it)}%" } + " (ringer: $mode).", informational = true)
    }

    // ============================================================================================
    // hardware & system
    // ============================================================================================

    private const val TORCH_VERIFY_TIMEOUT_MS = 2_000L
    private const val MAX_TORCH_FOLLOW_UP_MS = 3_600_000L
    private const val DEFAULT_TORCH_FOLLOW_UP_MS = 500L

    /** Hardware-reported torch state, mirrored by [CameraManager.TorchCallback]; null = unknown. */
    @Volatile private var torchMirror: Boolean? = null
    @Volatile private var torchTrackedId: String? = null
    @Volatile private var pendingTorchFollowUp: Runnable? = null

    /**
     * Turns the torch on/off and then PROVES it. `CameraManager` exposes no synchronous getter, so
     * a process-wide torch-callback mirror is registered and polled after the dispatch. A dispatch
     * the hardware never confirmed returns [fail] — no layer above can report SUCCESS from an
     * unverified dispatch again (the 2026-11-04 false-success class, BUG-01).
     *
     * Compound goals arrive as args from the reflex decomposer (`then_command`, `then_delay_ms`):
     * "on and off after 5 seconds" schedules the reverse transition on the main handler — atomic,
     * screen-state agnostic, and itself verified when it fires. (Handler, not AlarmManager: the
     * follow-up belongs to this live session; use AlarmManager when out-of-process durability is
     * required.)
     */
    private fun torch(context: Context, on: Boolean, args: Map<String, String> = emptyMap()): DeviceControlResult {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return fail("This phone has no flashlight.")
        ensureTorchTracker(cam, id)
        cam.setTorchMode(id, on)
        if (!awaitTorchState(on, TORCH_VERIFY_TIMEOUT_MS)) {
            return fail(
                if (on) "The flashlight command was dispatched but the torch never reported ON."
                else "The flashlight command was dispatched but the torch never reported OFF."
            )
        }
        val followUp = args["then_command"]?.let { DeviceCommand.fromWire(it) }
        if (followUp == DeviceCommand.TORCH_ON || followUp == DeviceCommand.TORCH_OFF) {
            val followUpOn = followUp == DeviceCommand.TORCH_ON
            val delayMs = (args["then_delay_ms"]?.toLongOrNull() ?: DEFAULT_TORCH_FOLLOW_UP_MS)
                .coerceIn(0L, MAX_TORCH_FOLLOW_UP_MS)
            // Cancel any earlier sequence so two prompts cannot fight over the torch.
            pendingTorchFollowUp?.let(mainHandler::removeCallbacks)
            val followUpRunnable = Runnable {
                // NEVER poll from the main thread here: the TorchCallback is delivered on the same
                // looper, so blocking it would guarantee a timeout. The task-level success criteria
                // probe the final state after then_delay_ms instead.
                runCatching { cam.setTorchMode(id, followUpOn) }
            }
            pendingTorchFollowUp = followUpRunnable
            mainHandler.postDelayed(followUpRunnable, delayMs)
            val secs = delayMs / 1000L
            return ok(
                (if (on) "Flashlight on" else "Flashlight off") + " (verified); " +
                    (if (followUpOn) "back on" else "back off") + " after " + secs + "s."
            )
        }
        return ok(if (on) "Flashlight on (verified)." else "Flashlight off (verified).")
    }

    private fun torchCameraId(context: Context): Pair<CameraManager, String>? {
        val cam = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val ids = runCatching { cam.cameraIdList }.getOrNull() ?: return null
        val id = ids.firstOrNull {
            runCatching {
                cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        } ?: return null
        return cam to id
    }

    private fun ensureTorchTracker(cam: CameraManager, id: String) {
        // Registration fires an immediate callback with the CURRENT mode, seeding the mirror.
        if (torchTrackedId == id) return
        synchronized(this) {
            if (torchTrackedId == id) return
            torchMirror = null
            runCatching {
                cam.registerTorchCallback(object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (cameraId == id) torchMirror = enabled
                    }
                }, mainHandler)
            }
            torchTrackedId = id
        }
    }

    private fun awaitTorchState(expected: Boolean, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (torchMirror == expected) return true
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        return torchMirror == expected
    }

    /**
     * The last torch state the HARDWARE reported, or null when unknown/observable. Success-criteria
     * probes ([com.aniob.core.domain.DeviceAssertion]) read this instead of assuming dispatch=done.
     */
    fun currentTorchState(context: Context): Boolean? {
        val (cam, id) = torchCameraId(context) ?: return null
        ensureTorchTracker(cam, id)
        return torchMirror
    }

    private fun global(service: AniobAccessibilityService?, action: Int, done: String): DeviceControlResult {
        service ?: return blocked("Turn on Aniob in Settings > Accessibility")
        return if (service.performGlobalAction(action)) ok("$done.") else fail("Android did not accept that system action.")
    }

    private fun hideKeyboard(service: AniobAccessibilityService?): DeviceControlResult {
        service ?: return blocked("Turn on Aniob in Settings > Accessibility")
        val showing = service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (!showing) return ok("The keyboard is not showing.")
        return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) ok("Keyboard closed.")
        else fail("Could not close the keyboard.")
    }

    private fun openPanel(context: Context, args: Map<String, String>): DeviceControlResult {
        val (action, label) = when (args["name"]?.lowercase()) {
            "internet", "network", "data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY to "Internet panel"
            "wifi", "wi-fi" -> Settings.Panel.ACTION_WIFI to "Wi-Fi panel"
            "volume", "sound" -> Settings.Panel.ACTION_VOLUME to "Volume panel"
            "nfc" -> Settings.Panel.ACTION_NFC to "NFC panel"
            else -> return fail("Unknown panel. Use internet, wifi, volume or nfc.")
        }
        return launch(context, Intent(action), "$label opened. Pick the option on screen.")
    }

    private fun hotspot(context: Context): DeviceControlResult {
        val tether = Intent("android.settings.TETHER_SETTINGS")
        if (tryStart(context, tether.addFlags(NEW_TASK))) return ok("Hotspot settings opened. Switch Hotspot on from the screen.")
        return launch(context, Intent(Settings.ACTION_WIRELESS_SETTINGS), "Network settings opened. Open Hotspot from the screen.")
    }

    private fun dnd(context: Context, on: Boolean): DeviceControlResult {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "dnd")
            return blocked("Allow Aniob in Settings > Notifications > Do Not Disturb access, then ask again")
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return ok(if (on) "Do Not Disturb is on." else "Do Not Disturb is off.")
    }

    // ============================================================================================
    // calls & messages
    // ============================================================================================

    private fun call(context: Context, args: Map<String, String>, service: AniobAccessibilityService?): DeviceControlResult {
        val speaker = args["speaker"]?.equals("true", ignoreCase = true) == true
        val number = args["number"]?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }?.takeIf { it.isNotBlank() }
            ?: args["contact"]?.let { name ->
                resolveContactNumber(context, name)
                    ?: return contactsBlocked(context, name)
            }
            ?: return fail("call needs a number or a contact name.")
        val who = args["contact"] ?: number
        val canCall = AniobCapabilityProbe.granted(context, Manifest.permission.CALL_PHONE)
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val started = tryStart(context, Intent(action, Uri.fromParts("tel", number, null)).addFlags(NEW_TASK))
        if (!started) return fail("No phone app could place the call.")
        if (!canCall) {
            openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "callperm")
            return ok("Opened the dialer for $who. Tap Call to start it (allow the Phone permission for Aniob to dial by itself).")
        }
        if (speaker) enableSpeakerWhenConnected(context, service)
        return ok("Calling $who${if (speaker) " on speaker" else ""}.")
    }

    /**
     * Speakerphone cannot be forced from outside the in-call UI on most phones, so the Speaker button
     * is pressed through accessibility once the call screen is up (retrying for ~8 s), with
     * `AudioManager.isSpeakerphoneOn` as the belt-and-braces second try.
     */
    @Suppress("DEPRECATION")
    private fun enableSpeakerWhenConnected(context: Context, service: AniobAccessibilityService?) {
        var attempts = 0
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val tick = object : Runnable {
            override fun run() {
                attempts++
                val clicked = service?.rootInActiveWindow?.let { root ->
                    root.findAccessibilityNodeInfosByText("Speaker").any { node -> clickSelfOrParent(node) }
                } == true
                if (clicked) return
                if (attempts == 4) audio.isSpeakerphoneOn = true
                if (attempts < 12) mainHandler.postDelayed(this, 700L)
            }
        }
        mainHandler.postDelayed(tick, 1500L)
    }

    private fun clickSelfOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 4) {
            if (current.isClickable && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
            hops++
        }
        return false
    }

    private fun whatsapp(context: Context, args: Map<String, String>): DeviceControlResult {
        val text = args["text"].orEmpty()
        val phone = args["phone"]?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
            ?: args["contact"]?.let { resolveContactNumber(context, it)?.filter { c -> c.isDigit() } }
        val installed = WHATSAPP_PACKAGES.firstOrNull { isInstalled(context, it) }
            ?: return fail("WhatsApp is not installed.")
        if (phone == null) {
            // No number to deep-link to: open WhatsApp itself and let the UI loop find the chat.
            val launch = context.packageManager.getLaunchIntentForPackage(installed)?.addFlags(NEW_TASK)
                ?: return fail("Could not open WhatsApp.")
            tryStart(context, launch)
            return ok("Opened WhatsApp. Find the chat for ${args["contact"].orEmpty()} on screen" +
                (if (text.isNotBlank()) " and send: \"$text\"." else "."))
        }
        val uri = Uri.parse("whatsapp://send?phone=$phone" + if (text.isNotBlank()) "&text=${Uri.encode(text)}" else "")
        return if (tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(installed).addFlags(NEW_TASK))) {
            ok("WhatsApp chat opened${if (text.isNotBlank()) " with the message typed in" else ""}. Tap Send to deliver it.")
        } else fail("WhatsApp could not open that chat.")
    }

    private fun telegram(context: Context, args: Map<String, String>): DeviceControlResult {
        val to = args["to"].orEmpty().trim()
        val text = args["text"].orEmpty()
        if (to.isEmpty()) return fail("telegram_message needs a recipient.")
        val encodedText = if (text.isNotBlank()) "&text=${Uri.encode(text)}" else ""
        val username = to.removePrefix("@")
        val digits = to.filter { it.isDigit() }
        val uri = when {
            to.none { it.isLetter() } && digits.length >= 6 -> Uri.parse("tg://msg?to=$digits$encodedText")
            Regex("^[A-Za-z0-9_]{5,32}$").matches(username) -> Uri.parse("tg://resolve?domain=$username$encodedText")
            else -> resolveContactNumber(context, to)?.filter { it.isDigit() }
                ?.let { Uri.parse("tg://msg?to=$it$encodedText") }
        }
        if (uri == null) {
            val pkg = listOf("org.telegram.messenger", "org.telegram.messenger.web").firstOrNull { isInstalled(context, it) }
                ?: return fail("Telegram is not installed.")
            context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(NEW_TASK)?.let { tryStart(context, it) }
            return ok("Opened Telegram. Find the chat for $to on screen.")
        }
        return if (tryStart(context, Intent(Intent.ACTION_VIEW, uri).addFlags(NEW_TASK))) {
            ok("Telegram chat opened${if (text.isNotBlank()) " with the message typed in" else ""}. Tap Send to deliver it.")
        } else fail("Telegram is not installed.")
    }

    private fun sms(context: Context, args: Map<String, String>): DeviceControlResult {
        val phone = args["phone"]?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotBlank() }
            ?: args["contact"]?.let { resolveContactNumber(context, it) ?: return contactsBlocked(context, it) }
            ?: return fail("sms_message needs a phone number or contact name.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null))
            .putExtra("sms_body", args["text"].orEmpty())
            .addFlags(NEW_TASK)
        return if (tryStart(context, intent)) ok("Text message draft opened. Tap Send to deliver it.")
        else fail("No messaging app could open the draft.")
    }

    private fun contactsBlocked(context: Context, name: String): DeviceControlResult {
        return if (!AniobCapabilityProbe.granted(context, Manifest.permission.READ_CONTACTS)) {
            openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "contacts")
            blocked("Allow Contacts for Aniob in the app's permissions, or give a phone number instead of \"$name\"")
        } else fail("No contact called \"$name\" has a phone number.")
    }

    /** Best match for [name] among saved numbers: exact > prefix > contains, shorter names first. */
    private fun resolveContactNumber(context: Context, name: String): String? {
        if (!AniobCapabilityProbe.granted(context, Manifest.permission.READ_CONTACTS)) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        var best: Pair<Int, String>? = null
        var bestRank = Int.MAX_VALUE
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val display = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                if (number.isBlank()) continue
                val rank = when {
                    display.equals(name.trim(), ignoreCase = true) -> 0
                    display.startsWith(name.trim(), ignoreCase = true) -> 1000 + display.length
                    else -> 2000 + display.length
                }
                if (rank < bestRank) {
                    bestRank = rank
                    best = rank to number
                }
            }
        }
        return best?.second
    }

    // ============================================================================================
    // notifications, OTP, clipboard
    // ============================================================================================

    private fun listener(): AniobNotificationListenerService? = AniobNotificationListenerService.instance

    private fun noListener() = fail("Notification access is still connecting. Try again in a moment.")

    private fun matchNotification(args: Map<String, String>, needsReply: Boolean = false): android.service.notification.StatusBarNotification? {
        val service = listener() ?: return null
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != service.packageName }
            .sortedByDescending { it.postTime }
        val key = args["key"]
        val pkg = args["package"]
        val match = args["match"]?.lowercase()
        return active.firstOrNull { sbn ->
            val extras = sbn.notification?.extras
            val haystack = listOfNotNull(
                extras?.getCharSequence(android.app.Notification.EXTRA_TITLE),
                extras?.getCharSequence(android.app.Notification.EXTRA_TEXT),
                sbn.packageName
            ).joinToString(" ").lowercase()
            (key == null || sbn.key == key) &&
                (pkg.isNullOrBlank() || sbn.packageName.equals(pkg, true) || haystack.contains(pkg.lowercase())) &&
                (match == null || haystack.contains(match)) &&
                (!needsReply || sbn.notification?.actions.orEmpty().any { it.remoteInputs?.isNotEmpty() == true })
        }
    }

    private fun notificationList(args: Map<String, String>): DeviceControlResult {
        if (listener() == null) return noListener()
        val entries = AniobNotificationHub.recent(args["limit"]?.toIntOrNull() ?: 6, args["package"])
        if (entries.isEmpty()) return ok("No notifications.", informational = true)
        val lines = entries.joinToString("\n") { e ->
            "${e.appLabel}: ${e.title.ifBlank { "(no title)" }} — ${e.text.take(80)}"
        }
        return ok(lines, informational = true)
    }

    private fun notificationDismiss(args: Map<String, String>): DeviceControlResult {
        val service = listener() ?: return noListener()
        if (args["all"].equals("true", ignoreCase = true)) {
            service.cancelAllNotifications()
            return ok("All notifications cleared.")
        }
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != service.packageName }
        val match = args["match"]?.lowercase()
        val pkg = args["package"]
        val key = args["key"]
        val targets = active.filter { sbn ->
            val extras = sbn.notification?.extras
            val haystack = listOfNotNull(
                extras?.getCharSequence(android.app.Notification.EXTRA_TITLE),
                extras?.getCharSequence(android.app.Notification.EXTRA_TEXT),
                sbn.packageName
            ).joinToString(" ").lowercase()
            (key == null || sbn.key == key) &&
                (pkg.isNullOrBlank() || sbn.packageName.equals(pkg, true)) &&
                (match == null || haystack.contains(match))
        }
        if (targets.isEmpty()) return fail("No notification matched.")
        targets.forEach { service.cancelNotification(it.key) }
        return ok("Dismissed ${targets.size} notification${if (targets.size == 1) "" else "s"}.")
    }

    private fun notificationReply(context: Context, args: Map<String, String>): DeviceControlResult {
        if (listener() == null) return noListener()
        val text = args["text"].orEmpty()
        if (text.isBlank()) return fail("notification_reply needs the reply text.")
        val sbn = matchNotification(args, needsReply = true) ?: return fail("No notification with a reply box matched.")
        val action = sbn.notification.actions.first { it.remoteInputs?.isNotEmpty() == true }
        val inputs = action.remoteInputs
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return try {
            action.actionIntent.send(context, 0, fill)
            ok("Replied from the ${appLabel(context, sbn.packageName)} notification.")
        } catch (_: PendingIntent.CanceledException) {
            fail("That notification can no longer be replied to.")
        }
    }

    private fun notificationAction(context: Context, args: Map<String, String>): DeviceControlResult {
        if (listener() == null) return noListener()
        val title = args["title"].orEmpty().lowercase()
        if (title.isBlank()) return fail("notification_action needs the button title.")
        val sbn = matchNotification(args)?.takeIf { s -> s.notification.actions.orEmpty().any { it.title?.toString()?.lowercase()?.contains(title) == true } }
            ?: run {
                val service = listener() ?: return noListener()
                runCatching { service.activeNotifications }.getOrNull().orEmpty().firstOrNull { s ->
                    s.packageName != service.packageName &&
                        s.notification.actions.orEmpty().any { it.title?.toString()?.lowercase()?.contains(title) == true }
                }
            } ?: return fail("No notification has a \"$title\" button.")
        val action = sbn.notification.actions.first { it.title?.toString()?.lowercase()?.contains(title) == true }
        return try {
            action.actionIntent.send()
            ok("Pressed \"${action.title}\" on the ${appLabel(context, sbn.packageName)} notification.")
        } catch (_: PendingIntent.CanceledException) {
            fail("That notification button is no longer available.")
        }
    }

    private fun otpStatus(): DeviceControlResult {
        val otp = AniobNotificationHub.latestOtp()
            ?: return ok("No verification code in the last ${AniobNotificationHub.OTP_TTL_MS / 60_000} minutes.", informational = true)
        val age = ((System.currentTimeMillis() - otp.receivedAt) / 1000).coerceAtLeast(0)
        return ok("A verification code from ${otp.appLabel} arrived ${age}s ago. Use otp_copy to copy it.", informational = true)
    }

    private fun otpCopy(context: Context): DeviceControlResult {
        val otp = AniobNotificationHub.latestOtp()
            ?: return fail("No verification code arrived in the last ${AniobNotificationHub.OTP_TTL_MS / 60_000} minutes.")
        putOnClipboard(context, otp.code, sensitive = true)
        return ok("Verification code from ${otp.appLabel} copied to the clipboard.")
    }

    private fun otpPaste(context: Context, service: AniobAccessibilityService?): DeviceControlResult {
        service ?: return blocked("Turn on Aniob in Settings > Accessibility")
        val otp = AniobNotificationHub.latestOtp() ?: return fail("No fresh verification code to enter.")
        val field = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fail("No text field is focused. Tap the code box first.")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, otp.code)
        }
        var done = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!done) {
            putOnClipboard(context, otp.code, sensitive = true)
            done = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!done) return fail("The field refused the code. It is on the clipboard: paste it manually.")
        AniobNotificationHub.consumeOtp()
        return ok("Verification code entered.")
    }

    private fun clipboardGet(context: Context): DeviceControlResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val live = runCatching {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        val text = live?.takeIf { it.isNotBlank() } ?: AniobClipboardWatcher.lastText?.takeIf { it.isNotBlank() }
            ?: return ok("The clipboard is empty (or Android hides it from background apps).", informational = true)
        // Secrets copied by the user (cards, tokens, passwords) are masked before the model can read them.
        return ok("Clipboard: \"${AniobDlpSanitizer.sanitize(text).take(300)}\"", informational = true)
    }

    private fun clipboardSet(context: Context, text: String): DeviceControlResult {
        if (text.isEmpty()) return fail("clipboard_set needs text.")
        putOnClipboard(context, text, sensitive = false)
        return ok("Copied to the clipboard.")
    }

    private fun clipboardPaste(service: AniobAccessibilityService?): DeviceControlResult {
        service ?: return blocked("Turn on Aniob in Settings > Accessibility")
        val field = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fail("No text field is focused. Tap the field first.")
        return if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) ok("Pasted.") else fail("The field refused the paste.")
    }

    private fun putOnClipboard(context: Context, text: String, sensitive: Boolean) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Aniob", text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            // Keeps the code out of the clipboard preview bubble and out of clipboard history.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        cm.setPrimaryClip(clip)
        if (!sensitive) AniobClipboardWatcher.noteOwnWrite(text)
    }

    // ============================================================================================
    // files & storage
    // ============================================================================================

    private fun fileRequest(args: Map<String, String>, limit: Int) = AniobFileQuery.Request(
        kind = args["kind"].orEmpty(),
        query = args["query"].orEmpty(),
        withinHours = args["within_hours"]?.toLongOrNull(),
        olderThanDays = args["older_than_days"]?.toLongOrNull(),
        limit = limit
    )

    private fun noFilesHint(context: Context, args: Map<String, String>): DeviceControlResult {
        val needsAllFiles = args["kind"].orEmpty().lowercase() in setOf("pdf", "download", "document", "") && !Environment.isExternalStorageManager()
        if (needsAllFiles) {
            openSettings(context, Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, "allfiles")
            return blocked("Allow \"All files access\" for Aniob so PDFs and downloads are visible, or open the Files app and look on screen")
        }
        return fail("No matching files found.")
    }

    private fun fileFind(context: Context, args: Map<String, String>): DeviceControlResult {
        val hits = runCatching { AniobFileQuery.find(context, fileRequest(args, args["limit"]?.toIntOrNull() ?: 5)) }
            .getOrElse { return fail("Could not read the file index: ${it.javaClass.simpleName}.") }
        if (hits.isEmpty()) return noFilesHint(context, args)
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault())
        val lines = hits.take(5).mapIndexed { i, h ->
            val base = "${h.name} (${AniobFileQuery.humanSize(h.sizeBytes)}, ${fmt.format(java.util.Date(h.addedAt * 1000))})"
            if (i < 3) "$base ${h.uri}" else base
        }
        return ok("${hits.size} found:\n" + lines.joinToString("\n"), informational = true)
    }

    private fun fileOpen(context: Context, args: Map<String, String>): DeviceControlResult {
        val explicit = args["uri"]?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val hit = if (explicit == null) {
            runCatching { AniobFileQuery.find(context, fileRequest(args, 1)) }
                .getOrElse { return fail("Could not read the file index: ${it.javaClass.simpleName}.") }
                .firstOrNull() ?: return noFilesHint(context, args)
        } else null
        val uri = explicit ?: hit!!.uri
        val mime = hit?.mimeType ?: context.contentResolver.getType(uri) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (tryStart(context, view)) ok("Opened ${hit?.name ?: "the file"}.")
        else fail("No app can open ${hit?.name ?: "that file"} ($mime).")
    }

    private fun storageInfo(): DeviceControlResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val usedPct = if (total > 0) ((total - free) * 100 / total).toInt() else 0
        return ok("${AniobFileQuery.humanSize(free)} free of ${AniobFileQuery.humanSize(total)} ($usedPct% used).", informational = true)
    }

    // ============================================================================================
    // timers, alarms, calendar
    // ============================================================================================

    private fun timer(context: Context, args: Map<String, String>): DeviceControlResult {
        val seconds = args["seconds"]?.toDoubleOrNull()?.toInt()
            ?: args["minutes"]?.toDoubleOrNull()?.let { (it * 60).toInt() }
            ?: return fail("timer_set needs seconds or minutes.")
        if (seconds !in 1..86_400) return fail("A timer must be between 1 second and 24 hours.")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args["label"]?.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val shown = if (seconds % 60 == 0) "${seconds / 60} min" else "$seconds s"
        return launch(context, intent, "Timer started for $shown.")
    }

    private fun alarmSet(context: Context, args: Map<String, String>): DeviceControlResult {
        val hour = args["hour"]?.toIntOrNull() ?: return fail("alarm_set needs hour (0-23).")
        val minute = args["minute"]?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return fail("Alarm time must be 00:00-23:59.")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args["label"]?.takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return launch(context, intent, "Alarm set for %02d:%02d.".format(hour, minute))
    }

    private fun alarmDismiss(context: Context, args: Map<String, String>): DeviceControlResult {
        val label = args["label"]?.takeIf { it.isNotBlank() }
        val mode = when {
            label != null -> AlarmClock.ALARM_SEARCH_MODE_LABEL
            args["mode"].equals("all", ignoreCase = true) -> AlarmClock.ALARM_SEARCH_MODE_ALL
            else -> AlarmClock.ALARM_SEARCH_MODE_NEXT
        }
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, mode)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return launch(context, intent, "Alarm dismissed.")
    }

    private fun calendarInsert(context: Context, args: Map<String, String>): DeviceControlResult {
        val title = args["title"].orEmpty().trim()
        if (title.isEmpty()) return fail("calendar_insert needs a title.")
        val zone = ZoneId.systemDefault()
        val begin = parseMillis(args["begin"], zone)
        val end = parseMillis(args["end"], zone) ?: begin?.plus(60 * 60 * 1000L)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.HAS_ALARM, 1)
        begin?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        end?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        args["location"]?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        args["description"]?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        val whenText = if (begin != null) " for ${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(begin), zone).toString().replace('T', ' ')}" else ""
        return launch(context, intent, "Calendar event \"$title\"$whenText is filled in. Tap Save to keep it.")
    }

    /** ISO date-time, ISO date (09:00 that day) or epoch milliseconds. */
    private fun parseMillis(raw: String?, zone: ZoneId): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: text.toLongOrNull()
    }

    // ============================================================================================
    // maps & web
    // ============================================================================================

    private fun navigate(context: Context, args: Map<String, String>): DeviceControlResult {
        val destination = args["destination"].orEmpty().trim()
        if (destination.isEmpty()) return fail("navigate needs a destination.")
        val mode = args["mode"]?.lowercase()?.takeIf { it in setOf("d", "w", "b", "l") } ?: "d"
        val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$mode")
        val started = tryStart(context, Intent(Intent.ACTION_VIEW, uri).setPackage(MAPS_PACKAGE).addFlags(NEW_TASK)) ||
            tryStart(context, Intent(Intent.ACTION_VIEW, uri).addFlags(NEW_TASK)) ||
            tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(destination)}")).addFlags(NEW_TASK))
        return if (started) ok("Navigation to $destination started.") else fail("No maps app is installed.")
    }

    private fun mapsSearch(context: Context, query: String): DeviceControlResult {
        if (query.isBlank()) return fail("maps_search needs a query.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).addFlags(NEW_TASK)
        return if (tryStart(context, intent)) ok("Map search for \"$query\" opened.") else fail("No maps app is installed.")
    }

    @Suppress("MissingPermission")
    private fun locationShare(context: Context): DeviceControlResult {
        val fine = AniobCapabilityProbe.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = AniobCapabilityProbe.granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            openSettings(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "location")
            return blocked("Allow Location for Aniob in the app's permissions")
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?: return fail("No recent location is available. Turn on Location and try again.")
        val link = "https://maps.google.com/?q=${last.latitude},${last.longitude}"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        val chooser = Intent.createChooser(send, "Share location").addFlags(NEW_TASK)
        return if (tryStart(context, chooser)) ok("Share sheet opened with your current location. Pick who gets it.")
        else fail("Nothing could open the share sheet.")
    }

    private fun openUrl(context: Context, args: Map<String, String>): DeviceControlResult {
        val url = AniobIntentResolver.sanitizeAndValidateUrl(args["url"].orEmpty()) ?: return fail("That is not a safe http(s) address.")
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(NEW_TASK)
        val browser = args["browser"]?.takeIf { it.isNotBlank() }
        val started = (browser != null && tryStart(context, Intent(base).setPackage(browser))) || tryStart(context, base)
        return if (started) ok("Opened $url.") else fail("No browser could open that address.")
    }

    // ============================================================================================
    // shared helpers
    // ============================================================================================

    private fun ok(message: String, informational: Boolean = false) = DeviceControlResult(true, message, informational)

    private fun fail(message: String) = DeviceControlResult(false, message)

    private fun blocked(reason: String) =
        DeviceControlResult(false, "Not available yet. $reason.", blockedBy = reason)

    private fun launch(context: Context, intent: Intent, success: String): DeviceControlResult =
        if (tryStart(context, intent.addFlags(NEW_TASK))) ok(success) else fail("No app on this phone can do that.")

    private fun tryStart(context: Context, intent: Intent): Boolean {
        val ctx: Context = AniobAccessibilityService.instance ?: context
        return try {
            ctx.startActivity(intent.addFlags(NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Opens a settings page once per process and permission, so a missing grant is asked for, not nagged. */
    private fun openSettings(context: Context, action: String, tag: String) {
        if (!settingsOpened.add(tag)) return
        val intent = Intent(action).addFlags(NEW_TASK)
        if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS || action == Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) {
            intent.data = Uri.parse("package:${context.packageName}")
        }
        tryStart(context, intent)
    }

    private fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun appLabel(context: Context, pkg: String): String =
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
}
