# Ask once, at the start

Aniob asks everything it needs **before** a task starts and never interrupts the run afterwards.

## What is asked, and when

| Question | Asked when | Skipped for |
|---|---|---|
| Clarification (recipient, time, app...) | Before the task, one at a time, max 3 | Direct-intent tasks (`open settings`): no model call at all |
| Approval to pay / enter a secret / delete | Before the task, one at a time | Never skipped; `view payment history` is read-only and not asked |
| Approval to send / post / share / call | Only in the strict safety level | Typing `send hi to Asha` is the approval in the other levels |
| "Other hard-to-undo steps" | Only in the strict safety level | |

Questions appear in one small card above the chat bar (`AniobAskCard`). There is no bottom sheet and
no "reply from the notification" flow.

## What happens mid-run

`MidRunApproval` checks, it never asks:

* Approved at the start: the step goes ahead (and is still written to the confirmation history).
* Not approved / never asked: the step is **skipped** and a "Skipped" note is shown. Fail closed.
* Hard blocks (`AniobSafetyInterceptor`) are unchanged.
* The model may not stop to ask. A leftover `ASK_USER:` continues on its first (recommended) option once,
  or ends the run with the one missing detail. Payments, secrets, deletion and free-text values such as a
  recipient are never assumed.
* Controls not found twice end the run with a clear reason and a retry, instead of pausing.

## Staying in the chat

Submitting a task starts the progress notification but no longer sends the user to the home screen.
The chat is only left when the run is known to drive another app's screen; a direct-intent task brings
its own app to the front.

## A task always ends

`ensureTaskTerminated` is the termination guard on both pipeline call sites: an unexpected throw or an
early return seals the outcome, removes the ongoing notification and returns the chat to idle, instead
of leaving the task on "working" forever.

## Opt back in

`aniob_prefs` key `ask_timing = MID_RUN` restores the previous pause-and-wait confirmation.

## Parallel work

Independent start-up work overlaps with the question stage (`AniobParallel`): clarification planning runs
concurrently with the Doctor check, and the gateway connection and on-device engine warm up while the user
reads and answers. The observe, act and verify loop stays sequential on purpose: a phone has one screen.
