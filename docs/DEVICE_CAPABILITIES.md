# Device capabilities: telling the model what it can do, and letting it do it

A model that was never told it is wired into a phone answers "I can't operate your device". This
change closes that gap on both sides: the model is **told** (capability manifest), and every claim
in that text is **backed** by a direct Android API (device commands) that needs no screen walk.

## One catalogue, four consumers

`core-agent/.../domain/DeviceCommand.kt` is the single source of truth. Add an entry and the compiler
forces the rest (the `when`s in `DeviceNarration` and `AniobDeviceControl` are exhaustive):

| Consumer | What it does with the catalogue |
| --- | --- |
| `AniobActionSchema` | accepts `{"tool":"DEVICE_CONTROL","command":"volume_set","args":{"percent":"70"}}`, flat args, and a bare `{"tool":"TORCH_ON"}` |
| `AniobCapabilityManifest` | generates the prompt block (full for cloud, terse for on-device SLMs) with live "OFF" markers |
| `AniobReflexResolver` | maps everyday sentences to a command with **0 tokens** and no UI |
| `AniobDeviceControl` (`:app`) | one documented Android API per command |

## Prompt side

- `system_prompt.txt` (3 synced copies) says the model *is* connected and must not refuse.
- `AniobViewModel` appends `AniobCapabilityManifest.render(AniobCapabilityProbe.snapshot(app), compact)`
  every run. `compact` is used when the on-device model drives the step, and when a cloud step is
  downgraded to it.
- A reply that is a refusal is detected (`AniobRefusalGuard`) and the repair retry becomes a correction
  instead of a schema complaint.
- Answer-only prompts (`AniobExternalAiTrigger`) carry `IDENTITY_LINE`, so "can you control my phone?"
  is answered "yes".

## Tiers

1. **Reflexes** (`AniobReflexResolver`, 0 tokens): media keys, play-from-search, volume (set/up/down/
   mute/get), torch off, screenshot, notification shade, quick settings, lock, hide keyboard, internet
   panel, hotspot, DND, timers/alarms (existing + dismiss), calendar/reminder, WhatsApp/Telegram/SMS deep
   links, turn-by-turn navigation, URL.
2. **Interceptors**: `AniobNotificationListenerService` -> `AniobNotificationHub` (recent notifications,
   OTP vault); `notification_list/dismiss/reply/action`, `otp_status/copy/paste`; `AniobClipboardWatcher`
   + `clipboard_get/set/paste`.
3. **Local data**: `AniobFileQuery` over `MediaStore.Files` (`file_find`, `file_open`), `storage_info`.

## Rules that keep it safe

- **Verification codes never reach a model.** They live in memory for 10 minutes, are masked in every
  string a prompt/log/chat can see (`AniobOtpExtractor.mask`), and leave only via `otp_copy`
  (clipboard flagged sensitive) or `otp_paste` (asks first).
- Commands that call, reply, paste a code or share a location are `DeviceRisk.CONFIRM`: the safety
  interceptor asks the user. They are never resolved by the reflex resolver.
- Commands that reach outside the phone are checked by the prompt-injection canary: an argument quoted
  off the screen cannot choose the recipient.
- A command whose permission is missing answers with the exact setting to enable (and opens that page
  once), so the agent says "turn on X" instead of "I can't".
- The clipboard text a model reads is DLP-sanitised first.

## Known platform limits (honest)

- Silent Wi-Fi join / hotspot toggle are not possible for a third-party app on Android 10+: both open
  the system panel and the UI loop finishes the toggle.
- PDFs/downloads from other apps are visible to `MediaStore` only with "All files access".
- Speakerphone is pressed through the in-call UI (accessibility) with `isSpeakerphoneOn` as a fallback.
- Clipboard reads from the background can be hidden by Android; the watcher cache is the fallback.
