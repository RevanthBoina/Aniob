package com.aniob.core.narration

import com.aniob.core.domain.DeviceCommand

/**
 * One plain-language sentence per [DeviceCommand] for the step timeline and TalkBack.
 *
 * Verification codes are never named or shown here: `otp_*` steps describe what is being done, not
 * what the code is. Pure JVM; an exhaustive `when` keeps a newly added command from reaching the
 * timeline without a sentence.
 */
object DeviceNarration {

    fun phrase(command: DeviceCommand): String = when (command) {
        DeviceCommand.MEDIA_PLAY_PAUSE -> "Toggling playback"
        DeviceCommand.MEDIA_PLAY -> "Resuming playback"
        DeviceCommand.MEDIA_PAUSE -> "Pausing playback"
        DeviceCommand.MEDIA_NEXT -> "Skipping to the next track"
        DeviceCommand.MEDIA_PREVIOUS -> "Going to the previous track"
        DeviceCommand.MEDIA_STOP -> "Stopping playback"
        DeviceCommand.MEDIA_PLAY_SEARCH -> "Finding something to play"
        DeviceCommand.MEDIA_NOW_PLAYING -> "Checking what is playing"
        DeviceCommand.VOLUME_SET -> "Setting the volume"
        DeviceCommand.VOLUME_UP -> "Turning the volume up"
        DeviceCommand.VOLUME_DOWN -> "Turning the volume down"
        DeviceCommand.VOLUME_MUTE -> "Muting the sound"
        DeviceCommand.VOLUME_UNMUTE -> "Unmuting the sound"
        DeviceCommand.VOLUME_GET -> "Checking the volume"
        DeviceCommand.TORCH_ON -> "Turning the flashlight on"
        DeviceCommand.TORCH_OFF -> "Turning the flashlight off"
        DeviceCommand.SCREENSHOT -> "Taking a screenshot"
        DeviceCommand.OPEN_NOTIFICATIONS -> "Opening your notifications"
        DeviceCommand.OPEN_QUICK_SETTINGS -> "Opening quick settings"
        DeviceCommand.LOCK_SCREEN -> "Locking the phone"
        DeviceCommand.HIDE_KEYBOARD -> "Closing the keyboard"
        DeviceCommand.OPEN_PANEL -> "Opening a settings panel"
        DeviceCommand.HOTSPOT_SETTINGS -> "Opening hotspot settings"
        DeviceCommand.DND_ON -> "Turning on Do Not Disturb"
        DeviceCommand.DND_OFF -> "Turning off Do Not Disturb"
        DeviceCommand.CALL -> "Placing a call"
        DeviceCommand.MESSAGE_WHATSAPP -> "Opening a WhatsApp chat"
        DeviceCommand.MESSAGE_TELEGRAM -> "Opening a Telegram chat"
        DeviceCommand.MESSAGE_SMS -> "Opening a text message"
        DeviceCommand.NOTIFICATION_LIST -> "Reading your notifications"
        DeviceCommand.NOTIFICATION_DISMISS -> "Clearing notifications"
        DeviceCommand.NOTIFICATION_REPLY -> "Replying from the notification"
        DeviceCommand.NOTIFICATION_ACTION -> "Using a notification button"
        DeviceCommand.OTP_STATUS -> "Checking for a verification code"
        DeviceCommand.OTP_COPY -> "Copying the verification code"
        DeviceCommand.OTP_PASTE -> "Entering the verification code"
        DeviceCommand.CLIPBOARD_GET -> "Reading the clipboard"
        DeviceCommand.CLIPBOARD_SET -> "Copying to the clipboard"
        DeviceCommand.CLIPBOARD_PASTE -> "Pasting from the clipboard"
        DeviceCommand.FILE_FIND -> "Searching your files"
        DeviceCommand.FILE_OPEN -> "Opening a file"
        DeviceCommand.STORAGE_INFO -> "Checking storage"
        DeviceCommand.TIMER_SET -> "Starting a timer"
        DeviceCommand.ALARM_SET -> "Setting an alarm"
        DeviceCommand.ALARM_DISMISS -> "Dismissing an alarm"
        DeviceCommand.ALARMS_SHOW -> "Showing your alarms"
        DeviceCommand.CALENDAR_INSERT -> "Preparing a calendar event"
        DeviceCommand.NAVIGATE -> "Starting navigation"
        DeviceCommand.MAPS_SEARCH -> "Searching the map"
        DeviceCommand.LOCATION_SHARE -> "Preparing to share your location"
        DeviceCommand.OPEN_URL -> "Opening a web page"
    }
}
