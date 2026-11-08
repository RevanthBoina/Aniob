# ANIOB UX Copy Deck

Single source of truth for every user-visible string (UX-0 §4, finalized in UX-7). Strings shipped
in `strings.xml` or rendered by composables must match this deck. Technical names (FAST_PATH,
OMNIROUTE_CLOUD, OMNIROUTE_API_KEY, M0, "32dp", "hierarchies", screen hashes) live behind a
"Details" affordance only — never on a user surface. The product name **OmniRoute** is the one
exception: it names the gateway the user is asked to connect, so the Cloud AI card may use it.

## Ban list (CI-enforced in UX-7)

`M0`, `32dp`, `hierarchies`, `actuates`, `FAST_PATH`, `OMNIROUTE_` (internal identifier spelling),
`Pre-flight`.

## Onboarding / Setup Concierge (UX-1)

| Slot | Copy |
|---|---|
| Welcome title | Your phone, on autopilot |
| Welcome bullet 1 | Tell it what you want in plain words |
| Welcome bullet 2 | It taps the buttons for you |
| Welcome bullet 3 | You approve anything risky |
| See how it works | See how it works |
| Demo caption | Watch it do a task — no permissions needed yet |
| Accessibility why | To tap buttons for you — only while a task runs |
| Overlay why | Allows visual target highlights on top of other apps |
| Notifications why | So you can pause or stop a task from anywhere |
| Battery why | Stops the system from killing Aniob mid-task |
| Brain why | Aniob needs a brain: on-device (private) or cloud (fast) |
| First task | Try your first task |
| Celebration | 🎉 Your first automation |
| What happened card | Here's what just happened |
| Skip | Skip for now |
| Remind later | Finish setup when you're ready |

## Chat / Composer (UX-2)

| Slot | Copy |
|---|---|
| Input placeholder | Message or command… |
| Empty title | What do you want Aniob to do? |
| Empty body | Type, speak, or tap a suggestion below. |
| Queue chip | After this task: %1$s |
| Grill title | Help me understand |
| Grill subtitle | A couple of quick details so I get this right. |
| Remember answer | Remember this answer |
| Remembered badge | saved ✓ |
| Grill footer | %1$d of %2$d · remembered %3$d |
| Cancel task | Cancel task |
| Template search | Search in… |
| Template message | Send message to… |
| Template alarm | Set alarm for… |

## Glass Cockpit (UX-3)

| Slot | Copy |
|---|---|
| Ticker running fallback | Step %1$d · %2$s |
| Ticker paused | Paused — do this step yourself, then Resume |

## Trust & Safety (UX-4)

| Slot | Copy |
|---|---|
| Confirm title | Aniob needs your approval |
| Confirm what | What: %1$s |
| Confirm why | Why: %1$s |
| Confirm approve once | Approve once |
| Confirm approve task | Approve for this task |
| Confirm deny | Deny |
| Evidence success | Done — here's the proof |
| Evidence failure | Couldn't finish — here's why |
| Stopped outcome | Stopped by you at step %1$d — nothing was undone |
| Trust center title | Trust Center |
| Safety standard | Balanced |
| Safety strict | Careful |
| Disable safety | Let it work |
| Safety off banner | Skip ordinary-action prompts; still ask before money, secrets, and deletion. |

## Cloud AI / Settings (UX-7)

The product name **OmniRoute** is allowed here even though internal identifiers
(`OMNIROUTE_CLOUD`, `OMNIROUTE_API_KEY`) are banned; the user is connecting that gateway.

| Slot | Copy |
|---|---|
| Card title | Cloud AI (OmniRoute) |
| Card body | Add the key from your OmniRoute dashboard for fast answers and cloud automation. |
| Gateway label | Gateway address |
| Gateway placeholder | http://192.168.1.20:20128/v1 |
| Gateway helper | Where OmniRoute runs. Plain http is accepted on your local network only. |
| Key label | OmniRoute key |
| Key placeholder | Paste the key from your dashboard |
| Model chip | Auto / Coding / Fast / Cheap |
| Model label | Model name |
| Model helper | auto lets OmniRoute choose. Any provider/model name from the dashboard also works. |
| Test | Test |
| Testing | Testing connection… |
| Save | Save |
| Details | Details |
| Setup guide | Setup guide |
| Banner saved | Saved |
| Banner connected | Connected |
| Banner bad key | Key rejected |
| Banner unreachable | Gateway unreachable |
| Banner invalid | Check these details |
| Save body | Saved. Run Test connection to confirm the gateway answers. |
| Cloud off body | Cloud AI is off. Add a key whenever you want it back. |
| Connected body | The gateway answered for this key. Save to keep it. |
| Bad key body | The gateway rejected this key. Copy it again from the OmniRoute dashboard. |
| Unreachable body | No usable answer from that address. Check Wi-Fi and the address, confirm OmniRoute is running, and that it has at least one provider connected. |
| Missing key | Add the key from your OmniRoute dashboard. |
| Missing address | Add the address of the computer running OmniRoute. |
| No secure storage | This device cannot store the key securely, so Cloud AI stays off. |
| Missing address hint | Add the gateway address to finish cloud setup. |
| Thinking dialog cloud | Cloud: ready (model) / Cloud: not set up |
| Brain step body | On-device (private) or cloud (fast). Cloud setup lives in Settings. |

## Research mode (study build)

The ablation selector is a study surface, not a consumer setting: the labels name the exact
condition so a run in `research_runs.csv` can be traced back to what the agent did. Mode ids
(`BASELINE`, `INTENT_SHORTCUT_ONLY`, `BURST_AND_FAST_OBSERVE`, `FULL_HUMAN_SPEED`) stay internal —
they appear only in the CSV, the log tag and the bracketed HUD prefix, never as prose.

| Slot | Copy |
|---|---|
| Settings card title | Research mode |
| Settings card body | Pick the condition before a test batch. It changes how the agent works, not what it can do. |
| Settings card footer | Each finished task is appended to research_runs.csv; averages are kept in research_summary_table.csv inside the app's files folder. |
| Study pass title | Study pass |
| Study pass warm-up | Warm-up |
| Study pass measured | Measured |
| Mode label — baseline | Baseline |
| Mode body — baseline | Full observation every step, one action per model call. No shortcuts, no replay. |
| Mode label — shortcuts | Shortcuts only |
| Mode body — shortcuts | Direct intent shortcuts are on; every remaining step uses the standard loop. |
| Mode label — burst | Burst + fast capture |
| Mode body — burst | Shortcuts, multi-action bursts and the event-driven capture skip. |
| Mode label — full | Full human speed |
| Mode body — full | Every mechanism, including verified replay from remembered tasks. |
| Stats card title | Research metrics |
| Stats empty | No task measured yet in this install. |
| Stats mean latency | Mean latency |
| Stats mean calls | Mean model requests per task |
| Stats success | Task success rate |
| Stats runs | Runs in this mode |
| Stats verdict manual | Last verdict was set manually. |
| Stats verdict auto | Last verdict came from automatic verification. |
| Stats override pass | Mark pass |
| Stats override fail | Mark fail |
| Stats clear | Clear saved runs |

Developer-only research metrics remain in the research statistics surface. They do not appear as a
completion Toast. Task completion uses one result notification; verified success does not pull Aniob
back over the app being operated, while failure or unverified completion opens chat for follow-up.

## Learning & Progress (UX-5)

| Slot | Copy |
|---|---|
| Skills title | Skills |
| Skills active | Active |
| Skills review | Needs review |
| Skills builtin | Built-in |
| Skills empty | No skills yet — teach your first |
| Moment vault | Used saved preference: %1$s ✓ |
| Moment skill | Replayed '%1$s' · 0 tokens |
| Stats completed | Tasks completed |
| Stats success | Success rate |
| Stats time saved | ≈Time saved (steps × 8s, estimate) |
| Stats streak | Current streak |
| Provider instant replay | Instant replay |
| Provider on-device | On-device |
| Provider cloud | Cloud |

## System Surfaces (UX-6)

| Slot | Copy |
|---|---|
| Notification progress title | %1$s |
| Notification pause | ⏸ Pause |
| Notification resume | ▶ Resume |
| Notification stop | ■ Stop |
| Done title | ✓ Done: %1$s |
| Failure title | Needs attention: %1$s |
| Interrupted title | Aniob was interrupted |
| Interrupted body | Aniob was killed by the system at step %1$d — run it again or view the partial steps. |
| Access lost title | Aniob lost access |
| Access lost body | Re-enable accessibility to continue, or stop the task. |
| Tile idle | Ask Aniob |
| Tile running | Stop task |
| Shortcut new task | New task |
| Shortcut teach | Teach a task |
| Shortcut skills | Skills |

## Failure reasons (UX-4 plain language)

| Internal code | User copy |
|---|---|
| grounding_miss | Couldn't find '<target>' on screen |
| no-effect | Nothing changed on screen after the action |
| expected text … not present | Expected to see '…', but it wasn't there |
| expected package … not foreground | '…' didn't open |
| watchdog loop_detected | Aniob got stuck repeating an action |
| payment route blocked | Aniob stopped this payment route for safety |
| destructive blocked | That action could erase your data, so Aniob stopped |
| protected value blocked | Aniob stopped before using a protected value |
| network unavailable | No internet connection |
| accessibility disabled | Accessibility access is turned off |
| timeout | The app took too long to respond |
