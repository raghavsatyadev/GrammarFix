---
name: android-device-test
description: On-device testing and bug reproduction for GrammarFix. ARTEMIS MCP tools are the primary driver; ADB and dev.sh cover install, permissions, logcat and file access. Use when a change needs checking on a phone or a bug needs reproducing.
version: 3.0.0
---

# android-device-test

ARTEMIS drives the UI; ADB handles everything below the UI. Proven prompts and commands for the
app's core flows are in [flows.md](flows.md) — start there.

## ARTEMIS (primary)

Tool basics (`mobile_diagnose` first, Flash vs Pro, device serials) are in
[artemis-mobile-testing](../../rules/artemis-mobile-testing.md). Use `mobile_get_device_state` for
ground truth and `mobile_inspect_trace` step screenshots as PR evidence. Never guess tap
coordinates — let ARTEMIS resolve targets.

Write task prompts as explicit steps: say which screen the app is already on, name the element to
tap (`'Text Input Field'`), and end with what to report. This is required when ARTEMIS runs on the
local Qwen model (it loops on vague "find the X tab" goals) and makes Gemini runs faster too.
Check which model a run used in its `stdout.log` (`ollama/…` vs `gemini-…`).

## Maestro replays (in-app flows)

Proven in-app flows are recorded as Maestro YAML in [flows/](flows/). Run them to check the app
still works before or after a change; they need no model calls from ARTEMIS and take ~1 min:

```bash
~/.maestro/maestro/bin/maestro --device <serial> test .agents/skills/android-device-test/flows
```

| Flow | Checks |
| --- | --- |
| `tabs.yaml` | every tab opens |
| `model-status.yaml` | offline model Ready or downloadable |
| `playground.yaml` | "Hello how is you?" gets an "are" suggestion (needs the model) |

**Maestro cannot test the overlay.** While a Maestro run is active, Android unbinds
`GrammarAccessibilityService` (it reconnects when Maestro exits). Overlay and other-app flows stay
on ARTEMIS. Keep `launchApp: {stopApp: false}` in every flow: a force-stop removes the service
from the enabled list. Set `MAESTRO_CLI_NO_ANALYTICS=1`.

## ADB & dev.sh (supplementary)

`D=.agents/skills/android-device-test/scripts/dev.sh` · package `com.raghavsatyadev.grammarfix`

Device cache in `memory/devices/`, dumps and screenshots in `tmp/` — see
[workspace-cleanup](../workspace-cleanup/SKILL.md).

| Task | Command |
| --- | --- |
| List devices | `adb devices -l` |
| Install debug build | `./gradlew :androidCMP:installDebug` |
| Grant overlay | `bash $D grant overlay` |
| Grant accessibility | `bash $D grant a11y` |
| Model / inference logs | `adb logcat -s GrammarFixLlama` (or `bash $D log GrammarFixLlama`) |
| Benchmark output | `adb logcat -s GRAMMARFIX_PHYSICAL_BENCHMARK` |
| Check downloaded model | `bash $D files` — `qwen3-1.7b-int4.gguf`, 1 282 439 264 bytes when complete |
| Keep screen awake | `adb shell "svc power stayon true; settings put system screen_off_timeout 600000"` |
| Reset app (also deletes the model) | `adb shell pm clear com.raghavsatyadev.grammarfix` |

## Gotchas

1. **`uiautomator dump` resets accessibility services.** It destroys and rebinds every service,
   including `GrammarAccessService`, which hides the bug you're chasing. `dev.sh`
   `dump/find/tap/scroll/wait/gone` all dump — fine for setup before the assistant is running,
   never while testing it. ARTEMIS reads the tree via `com.artemis.helper` without the side effect.
2. **Windows PowerShell corrupts `adb exec-out screencap -p > file.png`** (UTF-16 redirection).
   Use `adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png` or ARTEMIS instead.
3. **Samsung devices dim / palm-reject when idle** — set `svc power stayon true` before long runs.
4. **Wireless debugging can list one phone twice** (`adb-XXXX…` and `adb-XXXX (2)…`). ARTEMIS
   rejects the serial with a space in it. Keep one entry: `adb disconnect "<the (2) serial>"`; if
   none is left, `adb mdns services` shows the ports — `adb connect <ip>:<port>` and pass that
   `ip:port` as `device_serial`.
5. **Git Bash rewrites `/sdcard/…` paths** into Windows paths. Prefix adb commands with
   `MSYS_NO_PATHCONV=1`.
6. **Other grammar tools draw lookalike popups.** Grammarly
   (`com.grammarly.android.keyboard`) shows a dark pill with a red count badge
   ("Correct subject-verb agreement → are") above the keyboard; Gboard can proofread too. Before
   crediting a suggestion to GrammarFix, check whose window it is:
   `adb shell dumpsys window windows | grep -E "Window #|package="` — ours is
   `com.raghavsatyadev.grammarfix`.
7. **`connectedDebugAndroidTest` uninstalls the app afterwards**, deleting the downloaded model.
   Run UI tests without Gradle's uninstall:
   `./gradlew :androidCMP:installDebug :androidCMP:installDebugAndroidTest`, then
   `adb shell am instrument -w -e class <TestClass> com.raghavsatyadev.grammarfix.test/androidx.test.runner.AndroidJUnitRunner`
   (look for `OK (n tests)`). The Gradle task also reports FAILED even when every test passes.
8. **Reinstalling stops the overlay service.** After `installDebug`, start the assistant again
    (Setup tab → Start assistant) and `bash $D grant a11y`. A silent run (no rows, no log lines)
    looks like a broken fix. Before you conclude anything, check that the service is bound:
    `adb shell dumpsys accessibility | grep "Bound services"` must list GrammarFix. The S25 has
    kept the service in `enabled_accessibility_services` while it was not bound.
9. **Leave auto-rotate as the user set it — off unless they ask.** `adb shell monkey … LAUNCHER 1`
    switches it on, and ARTEMIS launches apps with monkey. Launch apps with `am start` (see
    flows.md). After an ARTEMIS run, reset it. Check before and after a run:
    `adb shell settings get system accelerometer_rotation` (0 = off), restore with
    `adb shell settings put system accelerometer_rotation 0`. Who changed it:
    `adb shell dumpsys settings | grep -A8 "History (accelerometer_rotation)"`.
10. **A Room schema older than the device's database stops analytics rows silently** (e.g. after a
    branch switch; corrections still work). Stop the app, then delete the database with its WAL
    files: `adb shell "run-as com.raghavsatyadev.grammarfix sh -c 'rm -f databases/grammarfix.db*'"`.
11. **Look before a coordinate tap.** A tap meant for a card can land on a button inside it (a
    delete opens its dialog, or worse). Take a screenshot first, aim at plain text on the card,
    and check the next screenshot shows the screen you expected before recording or tapping on.
12. **Put back every device setting you change.** `animator_duration_scale` (5 for frame-by-frame
    recordings) and `font_scale` (1.5 for large-text checks) are global: set them back to `1` /
    `1.0` in the same command that changed them, and read them back to confirm. Record at
    `screenrecord --size 720x1560` (or similar): native resolution on a tall phone fails or
    produces huge files.
