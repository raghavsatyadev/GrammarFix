---
trigger: always_on
description: GrammarFix-specific overrides and facts for the shared agent-workflow skills and rules. Wins over them.
---

# Project overrides — GrammarFix

Shared skills/rules are links into agent-workflow (`scripts/link-project.sh`); this file and
`.agents/flows/` are this repo's own.

- **Theme**: `composeApp/src/commonMain/kotlin/com/raghavsatyadev/grammarfix/library/support/theme/Theme.kt`
  — **dark-only** `darkScheme`, `getAppTypoGraphy()`, `appShapes` (cards 24 dp, sheets 28 dp). No
  theme reveal. Motion helpers: copy from SCUS into `library/support/theme` or `ui/components`.
- **Layout**: `composeApp/src/commonMain/kotlin/com/raghavsatyadev/grammarfix/` → `engine/`
  (inference, prompts, diffing, `GrammarAssistantViewModel` — several `StateFlow`s, not one
  `UiState`), `library/support/` (theme, Koin di), `ui/` (`GrammarFixApp` shell + tab bar, model/,
  playground/, setup/, components/ `SectionCard` `StatusPill`, `SuggestionOverlay`).
  `androidCMP/` host app + services + unit tests; `llamaAndroid/` llama.cpp JNI. Android passes an
  `AssistantSetup`; iOS gets no Setup tab.
- **Native build**: NDK/CMake versions in `gradle/libs.versions.toml`; `git submodule update --init`
  (llama.cpp). Engine work: `local-llm-engine` skill, `pure-local-llm-inference` and
  `perfetto-profiling` rules (project-local).
- **ARTEMIS**: started by the repo's `.mcp.json` from `$ARTEMIS_HOME`; don't also run
  `artemis mcp --install claude`. State dir `~/.grammarfix-artemis/`.
- **Preview example**: `androidCMP/src/screenshotTest/kotlin/com/raghavsatyadev/grammarfix/AppScreenshotsTest.kt FullApp_DefaultTab_ScreenshotTest`.
- **Device notes** (log tags, model file, lookalike popups): `.agents/flows/flows.md`.
