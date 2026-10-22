# AGENTS.md

Shared instructions for every coding agent in this repo — Claude, Gemini, Codex, Cursor.
`CLAUDE.md` and `GEMINI.md` just point here.

**GrammarFix** — Compose Multiplatform live grammar assistant.
`composeApp/` shared KMP (`commonMain`/`androidMain`/`iosMain`/`jvmMain`) ·
`androidCMP/` Android app · `llamaAndroid/` llama.cpp JNI · `iosApp/` iOS host ·
`gradle/libs.versions.toml` version catalog (`appIdProd` = applicationId).

## How to work

- Do only what the task asks: no unrequested features, tests, files, docs or refactors.
- Ask only when blocked, or before an action that needs approval (push, PR).
- Before "done", run the check for the change (android-build.md); if none can run, say why.
- When done and checked, stop. Report in five lines or fewer, in ASD-STE100 Simplified Technical English.
- Claude Code: medium effort for scoped edits; high for engine, native (`grammarfix_llama.cpp`) or prompt
  work. A second agent only for a review the user asked for.
- Decision model: global skill `local-model`. Global skills live in `global_skills/` (no agent reads it
  directly); install them with `bash global_skills/install.sh`.
  Turn on the local decision model first (`bash ~/.local-model/lm-on`), then use `jgl`,
  `lm-ask`, `lm-rank` and `rg` as the skill says. Rule checks for `lm-diffcheck` (run before a
  review): [.agents/diff-checks.txt](.agents/diff-checks.txt).

## Rules — `.agents/rules/`

| Rule | Read it when |
| --- | --- |
| [android-build.md](.agents/rules/android-build.md) | before calling any task done — the code must compile |
| [format.md](.agents/rules/format.md) | touching any Kotlin — ktfmt Google style is mandatory |
| [pure-local-llm-inference.md](.agents/rules/pure-local-llm-inference.md) | changing grammar/spell-correction logic or prompts — every fix comes from the on-device LLM (`llamaAndroid` → llama.cpp); never regex or dictionaries |
| [artemis-mobile-testing.md](.agents/rules/artemis-mobile-testing.md) | *on demand* — using ARTEMIS MCP tools; points to the upstream rules your agent should already have |
| [branch-pr-policy.md](.agents/rules/branch-pr-policy.md) | *always* — see the summary below |
| [parallel-agents.md](.agents/rules/parallel-agents.md) | the user asks for agents in parallel worktrees — pin the base commit, cap Gradle memory, clean up after |

## Skills — `.agents/skills/`

One folder per skill; each `SKILL.md` `description` says when to read it. Claude Code sees them
through the `.claude/skills/` links that `scripts/setup-env.*` create.

## Commands

New machine: run the setup doctor first — `scripts/setup-env.ps1` (Windows) or
`scripts/setup-env.sh` (macOS/Linux). After cloning: `git submodule update --init` (llama.cpp,
pinned to `b10356`); the first native build compiles llama.cpp and takes several minutes.

```bash
scripts/ci-local.sh                      # the CI checks (ktfmt + compileDebugKotlin) — run before pushing
scripts/gradle-agent.sh <tasks>          # agents: Gradle with errors only; full log in tmp/
./gradlew :androidCMP:assembleDebug      # build
./gradlew :androidCMP:testDebugUnitTest  # unit tests
./gradlew :androidCMP:validateScreenshotTest  # Compose preview screenshot tests
git config core.hooksPath .githooks      # once: pre-commit runs ktfmt, pre-push runs ci-local.sh
```

`ci-local.sh` flags: `--fix` reformats, `--format-only` skips the compile, `--committed-only`
matches CI exactly. GitHub Actions is disabled (org billing), so this script is the only gate.
Check in this order: `--format-only`, `compileDebugKotlin`, the one affected test class, then the
full suite or screenshot tests.

- The daemon JVM is pinned to Eclipse Temurin 21 (`gradle/gradle-daemon-jvm.properties`). Don't
  override it — other Java 21 runtimes lack `jlink` and break the build (#36).
- Never format with a `ktfmt` from your PATH; `ci-local.sh` fetches the version CI uses.

## Branches & PRs (strict)

Never push to `development` or `master`. Branch off `origin/development` as `<type>/<slug>`, push,
and open a PR against `development` with `gh pr create --body-file <file>` (never inline `--body`).
Never merge or approve a PR (`gh pr merge`, `gh pr review --approve`) — hand off the URL.

## Working on a bug

Follow [fix-issue](.agents/skills/fix-issue/SKILL.md)
(`/fix-issue <url>` in Claude Code): reproduce on the device → diagnose from real device state →
fix with a test that fails on the old behaviour → reinstall and re-run the reproduction → PR, stop.
A green unit test is not device verification. Inspect the device with ARTEMIS, not
`uiautomator dump`, which rebinds accessibility services.

## Housekeeping

`tmp/` (agent scratch), `memory/` (persistent local data such as device serials and coords),
`.cache/` and every `.env` are git-ignored. Never commit credentials. Never put a token in a remote URL or
`.git/config`; `gh` and the credential manager handle auth. Never clean `tmp/` unless the
user asks — see [workspace-cleanup](.agents/skills/workspace-cleanup/SKILL.md). In `.claude/`,
read `PLAN.md`; open `HISTORY.md` only when the task names an old date or PR.
