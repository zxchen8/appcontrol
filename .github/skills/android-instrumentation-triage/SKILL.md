---
name: android-instrumentation-triage
description: 'Triage Android instrumentation failures in this repo. Use for connectedDebugAndroidTest, ComposeTimeoutException, Hilt EntryPoint failures, Room seed instability, Superuser or su interference, deterministic device-control overrides, and rooted-device validation boundaries.'
argument-hint: 'Describe the failing command, test scope, device, and current symptom.'
user-invocable: true
---

# Android Instrumentation Triage

## When to Use
- `connectedDebugAndroidTest` fails on emulator or rooted device
- A single method passes but the class or full suite fails
- Compose smoke reports `ComposeTimeoutException`
- Hilt EntryPoint, singleton Room seed, recent run, or failure context smoke is unstable
- A local emulator behaves unlike a rooted test device
- You need to decide whether a failure belongs to compile, seed, environment, or true runtime behavior

## Repo-Specific Ground Rules
- Default local instrumentation in this repo uses a custom runner that enables deterministic device-control.
- Treat local deterministic smoke and rooted-device acceptance as separate conclusions.
- For repo rationale and bundled gotchas that are intentionally not duplicated here, see `references/repo-reference.md`.

## Procedure
1. Shrink scope first.
   - Run `:app:compileDebugAndroidTestKotlin`
   - Run one failing method
   - Run the owning class
   - Run the full `:app:connectedDebugAndroidTest` suite only after the smaller scopes are explained
2. Read the right evidence.
   - HTML report: `app/build/reports/androidTests/connected/debug/index.html`
   - Structured results: `app/build/outputs/androidTest-results/connected/debug`
   - If timeout or suite-order behavior is still ambiguous, use logcat, lifecycle events, and `references/repo-reference.md` before changing waits or helpers
3. Classify before editing.
   - Compile failure: Hilt, kapt, Room, Android test wiring
   - Seed failure: wrong DB path, foreign key crash, suite pollution
   - Environment failure: external Activity steals foreground, missing root, missing accessibility, deterministic override mismatch
   - Product failure: selector, refresh chain, runner logic, UI state, scheduling logic
4. Respect singleton DB discipline.
   - If this is the suspected slice, use `references/repo-reference.md` before changing DB helpers or clearing state

## Common Diagnoses
### Compose timeout
- Check whether MainActivity stayed in foreground
- Check whether the node never refreshed rather than just being slow
- Check whether the seed landed in the wrong DB connection

### Hilt EntryPoint or ClassCastException
- EntryPoint should live in main source, not a private androidTest-only interface
- The component must come from the app singleton component
- Seed helpers should not cast through test-local component types

### SQLite foreign key failure
- Usually indicates a seed-path, singleton DB, or seed-order issue; see `references/repo-reference.md`

## PowerShell Command Pattern
```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.plearn.appcontrol.ui.AppControlAppSmokeTest#shouldShowFailureContextForFailedManualRun"
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.plearn.appcontrol.ui.AppControlAppSmokeTest"
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.appcontrol.deterministicDeviceControl=false"
```

## Output Expectations
- Explain whether the failure is compile, seed, environment, or product behavior
- State the smallest next validation command
- If deterministic override is involved, say whether the result applies only to local smoke or also to rooted-device acceptance
