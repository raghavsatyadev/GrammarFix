# Known Flows — GrammarFix (ARTEMIS & ADB)

Proven testing sequences for core GrammarFix flows.

ARTEMIS natural-language prompts are the **primary** interaction method. Direct ADB / `dev.sh` commands remain available as **supplementary / low-level** alternatives.

Helper script: `D=.agents/skills/android-device-test/scripts/dev.sh`

---

## 1. App Tabs Navigation

A floating pill tab bar at the bottom: **Model**, **Insights**, **Playground** (opens first),
**Tones**, **Setup** (Android only). Only the selected tab shows its label; the others are icons,
so target them by content description.

* **Maestro replay:** `flows/tabs.yaml`. Flows 2 and 3 also have replays
  (`model-status.yaml`, `playground.yaml`); see SKILL.md.

* **Primary (ARTEMIS):**
  ```text
  "Tap the 'Model' tab in the bottom tab bar."
  "Tap the 'Setup' tab in the bottom tab bar."
  ```
* **ADB / dev.sh Fallback:**
  ```bash
  bash $D tap "desc:Model"
  bash $D tap "desc:Playground"
  bash $D tap "desc:Setup"
  ```

---

## 2. Model Download Flow

* **Primary (ARTEMIS Task):**
  ```text
  "In GrammarFix, open the Model tab. In the 'Offline model' card tap 'Download model (1.15 GB)'. Wait until the status pill says Ready, or confirm the progress is advancing."
  ```
* **ADB / dev.sh Fallback:**
  ```bash
  bash $D launch && bash $D tap "desc:Model"
  bash $D tap "textc:Download model"   # then Pause / Resume / Delete model
  bash $D files                         # qwen3-1.7b-int4.gguf, 1282439264 bytes when complete
  ```
* **Note:** The download runs at ~30 MB/s. Rely on live screenshots (`mobile_get_device_state`)
  over dumps taken mid-transfer.

---

## 3. Playground Flow

* **Primary (ARTEMIS Task):**
  ```text
  "In GrammarFix's Playground tab, clear the 'Your text' field, type 'Hello how is you?', wait 10 seconds, then report the Suggestions card and tap the check button on the 'is -> are' row."
  ```
* **Local Qwen (verified, 4 turns)** — state the current screen and name elements:
  ```text
  "The GrammarFix app is open on its Playground tab. Do NOT navigate or swipe to look for tabs. 1. Tap the 'Your text' field. 2. Replace its text by typing exactly: Hello how is you? 3. Wait 10 seconds. 4. Report the GrammarFix suggestions shown (ignore Grammarly / keyboard popups). 5. Report the final field text, then finish."
  ```
* **Samples:** chips `#1`–`#10` load a benchmark sentence; **Run all 10** checks each in turn
  (button shows "Checking n of 10…").
* **ADB / dev.sh Fallback:**
  ```bash
  bash $D tap "desc:Playground"
  bash $D tap "text:#1"                  # loads benchmark sentence 1 and checks it
  bash $D log GrammarAssistantViewModel
  ```

---

## 4. Floating Overlay Assistant Setup

* **Permissions Setup (ADB - One-time per device):**
  ```bash
  bash $D grant overlay
  bash $D grant a11y
  ```
  *(Note: `grant a11y` rebinds the accessibility service; required after reinstall or test crashes).*

* **Assistant Activation:** the Setup tab shows each permission as Granted / Needed;
  **Start assistant** is enabled once both are granted.
  * **ARTEMIS:**
    ```text
    "Open GrammarFix, tap the 'Setup' tab, scroll down, and tap 'Start assistant'."
    ```
  * **ADB / dev.sh:**
    ```bash
    bash $D launch && bash $D tap "desc:Setup"
    bash $D scroll down 0.4 && bash $D tap "text:Start assistant"
    ```

* **Overlay Interaction in Target App (e.g. WhatsApp / Notes):** see "Floating overlay" below.
  `uiautomator dump` tears the overlay's accessibility service down; ARTEMIS does not.
  Log: `adb logcat -s GrammarAccessService`

---

## 5. A Safe WhatsApp Testing Field

Typing in a real contact's chat triggers "typing…". Always use "Message yourself":

* **ARTEMIS Task:**
  ```text
  "Open WhatsApp. Tap the new chat floating button in the bottom right. Tap 'Message yourself' to open the private self chat. Tap the message input box."
  ```
* **ADB Fallback:**
  ```bash
  # am start, not monkey: monkey switches auto-rotate on (SKILL.md gotcha 9).
  adb shell am start -n "$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.whatsapp | tail -1 | tr -d '\r')"
  bash $D tap "textc:Message yourself"
  bash $D tap "id:entry"
  ```
* *Safety Rule:* **Never tap send**. Overlay buttons are on the left; the send button is on the right. When finished, clear the draft.

---

## 6. Cleanup

* **Revoke Permissions & Stop App:**
  ```bash
  bash $D revoke a11y        # Removes GrammarFix's accessibility entry
  bash $D stop
  ```

---

## Floating overlay (Grammarly-style, issue #8)

The overlay is `SuggestionOverlay` (commonMain) hosted by `FloatingWidgetService`. States:
idle bubble (wand icon) → pill (count badge, "Replace “x”", fix, delete icon) → expanded list
(✓ / ✕ per fix, "Accept all (n)").

* Tap the fix text on the pill → accepts it; the badge count drops by one.
* Tap the delete icon (content description "Dismiss") → hides that fix; the text is unchanged.
* Tap the count badge ("Show all suggestions") → expands the list.
* Verified sentence: `she go to market becos she need suger` → 5 fixes.
* Grammarly draws a near-identical pill in the same spot; drag ours away (swipe on its body)
  before reading it, and check window owners as in SKILL.md gotcha 6.
