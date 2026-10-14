# Skill compression audit — 2026-09-30

Scope: every `SKILL.md` in `.agents/skills/` on `origin/development` (`feadd1a`), compared with
`AGENTS.md` and `.agents/rules/`. Word counts are whitespace-split; frontmatter is excluded from
body counts. No skill is changed by this audit.

## 1. Cost

Descriptions load at every session start. A body loads only when its skill is used.

| Skill | Body (words) | Description (words / chars) | Support files (words) |
| --- | ---: | ---: | ---: |
| project-onboarding-setup | **1 732** | 26 / 185 | — |
| android-device-test | 950 | 24 / 164 | 4 566 (flows.md 925, scripts, flows/, templates/) |
| local-llm-engine | 630 | 27 / 201 | — |
| workspace-cleanup | 373 | 18 / 137 | — |
| compose-screenshot-test | 340 | 18 / 169 | — |
| cmp-best-practices | 316 (533 with open PR #68) | 19 / 167 | — |
| artemis-key-rotation | 259 | 27 / 180 | — |
| material3-expressive | 198 (210 with #68) | 20 / 148 | — |
| fix-issue | 130 | **44** / 225 | — |
| **Total (tracked)** | **4 928** | **223** / 1 576 | |
| tool-usage-report *(local, untracked, temporary)* | 220 | 42 / 237 | 616 |
| nimble *(`tools/nimble-skill`, installed globally)* | ~865 | **~90** | |

For comparison: `AGENTS.md` 708 words; rules 1 157 words (branch-pr-policy 262 and android-build
95 are `always_on`; the rest load on demand).

`fix-issue` loads `project-onboarding-setup` in full, so one bug fix costs ~1 860 words of skill
text before any rule or linked skill; ~1 300 of those are environment setup the fix does not use.

## 2. Descriptions

The tracked set is 223 words. The size is acceptable; the content is not always a trigger.

| Skill | Finding | Suggested description |
| --- | --- | --- |
| fix-issue (44 w) | Restates the five workflow steps. | "Fix a GitHub issue end to end: device repro, fix with a test, device verify, PR. Use when given an issue link or number." (~22 w) |
| local-llm-engine (27 w) | "Technical guidelines, … protocols" is filler; no "when". | "Read before changing the on-device engines: Qwen3 GGUF (llama.cpp JNI) or Gemini Nano (AICore) — prompts, routing, AICore errors, benchmarks." (~20 w) |
| workspace-cleanup (18 w) | Nominal ("Organization of …"); no trigger. | "Where agent files go (tmp/ vs memory/). Read before deleting anything there — only on user command." (~17 w) |
| project-onboarding-setup (26 w) | Lists components; covers two jobs (setup and the bug workflow). | After the split in §3.1: "Set up a new machine: toolchain, ARTEMIS, decision model. Use when setup-env reports a failure." |
| artemis-key-rotation (27 w) | Good trigger. "Needs both keys in …" (8 w) repeats the body. | Drop the last sentence. |
| android-device-test, cmp-best-practices, compose-screenshot-test, material3-expressive | Topic lists without "Use when"; short enough. | Optional: prefix "Use when …". |
| tool-usage-report (42 w, local) | Temporary; goes when the skill is deleted. | — |
| nimble (~90 w, global) | The longest description loaded in every session, in every project. | Cut to the two triggers (long output → `nimble-ask`; unknown name → `jgl`), ~40 w. Lives in `tools/nimble-skill/`. |

Net saving if all are applied: ~50 words per session in this repo, ~50 more from `nimble`.

## 3. Bodies — per skill

### 3.1 project-onboarding-setup (1 732 w) — biggest win

- **Part 2 (≈410 w) belongs in `fix-issue`.** `fix-issue` is a 130-word pointer to Part 2, and
  `AGENTS.md` "Working on a bug" also points to Part 2. Move Part 2 into `fix-issue`; then a bug
  fix loads ~450 words instead of ~1 860.
- **Part 2 restates the always-on branch rule four times** (§4 below): the IMPORTANT callout
  (l.155–159), step 2 branch commands, the 85-word "CRITICAL — never use inline `--body`"
  paragraph (l.198–204), and step 8. Replace with one line: "Branch, push and PR per
  branch-pr-policy."
- **Part 1b (≈580 w) duplicates `tools/nimble-skill/SKILL.md`**: Jev rules and cost, `jgl`,
  `NIMBLE_KEEP_ALIVE`, `nimble-off`, `NIMBLE_HOOKS=0`. Keep only the hardware → recommendation
  table and the "ask before global install" step; link the nimble skill for the rest.
- **Qwen-only field notes (l.85–97, ≈170 w)** are test history. Move to a `reference.md` beside
  the skill, or keep only the last bullet (blank `OPENAI_API_KEY` silently falls back to Gemini).
- l.34 "ktfmt is not a prerequisite" repeats `AGENTS.md` and `format.md`.

### 3.2 android-device-test (950 w)

- **"ARTEMIS (primary)" (l.12–26)** repeats `artemis-mobile-testing.md` project essentials
  (`mobile_diagnose` first, Flash vs Pro) and ARTEMIS's own upstream rules.
- **Gotcha 1** (`uiautomator dump` rebinds services) is also in `AGENTS.md` and `flows.md:94`.
- **Gotcha 7** (model file and size) repeats the table row "Check downloaded model" and
  `flows.md:44`; gotcha 4 (`pm clear` deletes the model) repeats the table's "Reset app" row.
- **"Outputs & Storage"** repeats `workspace-cleanup` and `AGENTS.md` Housekeeping.
- Gotcha 10 holds two unrelated items (service rebind; Room schema). Split or cut.
- **Stale:** `templates/cache-layout.txt` and the `lib.sh` header comment say `.device-cache/`;
  `lib.sh` actually uses `memory/` and `tmp/`.

### 3.3 local-llm-engine (630 w)

- **"Core Architectural Principle" (≈70 w)** restates `pure-local-llm-inference.md`. Link it.
- **The ASCII diagram** repeats the two sections under it and costs far more tokens than words
  (box-drawing characters). Delete it.
- **Contradiction:** the benchmark commands use `connectedDebugAndroidTest`, which
  android-device-test gotcha 9 says uninstalls the app and deletes the downloaded model. Use the
  `installDebug` + `am instrument` form from gotcha 9.
- **Inconsistent size:** "~1.15 GB" here vs 1 282 439 264 bytes (1.28 GB) in android-device-test.
- The `GRAMMARFIX_PHYSICAL_BENCHMARK` logcat line is also in android-device-test.

### 3.4 workspace-cleanup (373 w)

- The key rule (never clean `tmp/` or `memory/` unless asked) is already in `AGENTS.md`
  Housekeeping. The CAUTION block, directory tree and three trivial `rm` blocks could shrink to
  ~100 words, or fold into `AGENTS.md` and the skill be deleted.
- **Hazard:** the "Resetting Persistent Memory" command deletes all of `memory/`, which also holds
  the ARTEMIS keys (`memory/artemis.env`, `memory/artemis-keys.md`, see artemis-key-rotation). The
  skill does not say so.
- The tree lists `tmp/scratch/`; nothing in the repo writes there.

### 3.5 compose-screenshot-test (340 w)

- Two full Kotlin examples (≈150 w). Point at `AppScreenshotsTest.kt` and keep the three rules
  (annotations, `AppTheme`, fixed state; `KoinApplication` for injected screens).
- `validateScreenshotTest` is also in `AGENTS.md` Commands and onboarding step 5; the
  `.\gradlew.bat` note is also in `android-build.md`.

### 3.6 Small skills — keep

- **cmp-best-practices** (316 w): the layout tree overlaps the one-line map in `AGENTS.md`, and
  the Colors bullet repeats material3-expressive (it links there — fine). Open PR #68 adds
  ~220 words; check its Stability section against the existing Stability bullet.
- **material3-expressive** (198 w), **artemis-key-rotation** (259 w): no material duplication.
- **fix-issue** (130 w): its last line ("Never merge or approve") is a fifth copy of the PR rule.

## 4. Repeated content across files

| Content | Where it appears | Keep in |
| --- | --- | --- |
| Never push to `development`/`master`; never merge/approve; `--body-file` | `AGENTS.md` Branches & PRs, `branch-pr-policy.md` (always on), onboarding Part 2 ×4, fix-issue, `.claude/settings.json` deny list | the rule + settings deny list; one-line pointer in AGENTS.md |
| Bug workflow (repro → fix+test → device verify → PR) | `AGENTS.md` "Working on a bug", onboarding Part 2, fix-issue description + body | fix-issue |
| `uiautomator dump` rebinds accessibility | `AGENTS.md`, android-device-test gotcha 1, `flows.md` | android-device-test |
| `mobile_diagnose` first; Flash vs Pro | `artemis-mobile-testing.md`, android-device-test, fix-issue, upstream ARTEMIS rules | the rule |
| No regex/dictionary corrections | `pure-local-llm-inference.md`, local-llm-engine, onboarding step 4, `AGENTS.md` table | the rule |
| Build / test / screenshot commands | `AGENTS.md` Commands, `android-build.md`, onboarding step 5, compose-screenshot-test, local-llm-engine | `AGENTS.md` |
| `.\gradlew.bat` on Windows | `android-build.md`, compose-screenshot-test | `android-build.md` |
| Never use `ktfmt` from PATH | `AGENTS.md`, `format.md`, onboarding l.34 | `format.md` |
| `tmp/` vs `memory/` layout; clean only on request | `AGENTS.md` Housekeeping, workspace-cleanup, android-device-test "Outputs & Storage", `templates/cache-layout.txt` (stale) | workspace-cleanup |
| Model file path and size | android-device-test table + gotcha 7, `flows.md`, local-llm-engine (different size) | local-llm-engine |
| Nimble / Jev / `jgl` / `nimble-off` | `AGENTS.md` How to work, onboarding Part 1b, `tools/nimble-skill/SKILL.md`, SessionStart hook | nimble skill |

## 5. Suggested order

1. Move onboarding Part 2 into `fix-issue` and cut its branch-rule copies (−1 300 words per bug fix).
2. Trim onboarding Part 1b to the hardware table + link to the nimble skill (−450).
3. Fix the local-llm-engine benchmark command and model size; drop its diagram and principle copy (−200).
4. Shrink workspace-cleanup and warn that `memory/` holds the ARTEMIS keys (−250).
5. Rewrite the `fix-issue`, `local-llm-engine`, `workspace-cleanup` and `nimble` descriptions (−100 per session).
6. Fix the stale `.device-cache/` references.
