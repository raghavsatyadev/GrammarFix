---
name: compose-screenshot-test
description: Compose Preview Screenshot Testing for GrammarFix UI — AGP screenshot testing plugin, @PreviewTest, reference goldens, updateScreenshotTest, and validateScreenshotTest. Use when a UI change needs a screenshot test or the goldens need updating.
version: 1.0.0
---

# Compose Preview Screenshot Testing

GrammarFix uses Google's official Android Gradle Plugin Compose Preview Screenshot Testing
(`com.android.compose.screenshot`) to catch visual regressions across screens and components.

## Commands

| Task | Command |
| --- | --- |
| Validate screenshots against goldens | `./gradlew :androidCMP:validateScreenshotTest` |
| Record / update reference goldens | `./gradlew :androidCMP:updateScreenshotTest` |
| Run for specific variant | `./gradlew :androidCMP:validateDebugScreenshotTest` |

## Architecture & Layout

```text
androidCMP/
├── build.gradle.kts                            # alias(libs.plugins.compose.screenshot), experimental.enableScreenshotTest
├── src/
│   ├── screenshotTest/kotlin/.../              # Preview tests annotated with @PreviewTest + @Preview
│   │   └── AppScreenshotsTest.kt
│   └── screenshotTestDebug/reference/          # Golden reference images checked into git
gradle.properties                               # android.experimental.enableScreenshotTest=true
gradle/libs.versions.toml                       # screenshot plugin & screenshot-validation-api
```

## Writing Preview Tests

Follow the existing tests in
[AppScreenshotsTest.kt](../../../androidCMP/src/screenshotTest/kotlin/com/raghavsatyadev/grammarfix/AppScreenshotsTest.kt):

1. Annotate each test `@PreviewTest` + `@Preview(showBackground = true)`.
2. Wrap it in `AppTheme { }`; screens using `koinViewModel()` also need
   `KoinApplication(application = { modules(appModule) }) { }` (with `@Suppress("DEPRECATION")`).
3. Pass explicit state (e.g. an `AssistantSetup` with fixed permissions and no-op lambdas), never
   device- or time-dependent state.

## Workflow for UI Changes

1. **Modify UI**: Make design or layout changes (e.g. typography, colors, padding).
2. **Validate**: Run `./gradlew :androidCMP:validateScreenshotTest`. If intentional changes cause diffs, Gradle will report mismatches with HTML diff reports under `androidCMP/build/reports/screenshotTest/`.
3. **Update Goldens**: When the visual change is intended, record new golden images:
   ```bash
   ./gradlew :androidCMP:updateScreenshotTest
   ```
4. **Commit**: Check in the updated images in `androidCMP/src/screenshotTestDebug/reference/` alongside your code modifications.
