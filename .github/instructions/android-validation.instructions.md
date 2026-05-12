---
name: Android Validation Guardrails
description: "Use when editing Android validation smoke tests, validation formatting guards, DeviceValidationService or DeviceEnvironmentInspector, MainActivity/AppControlApp validation entry and formatting flows, singleton-DB seed entry points, deterministic or real-root device-control boundary files, triage skill docs, or Phase 5 rooted-device validation docs."
applyTo:
  - "app/src/androidTest/java/com/plearn/appcontrol/AppControlAndroidJUnitRunner.kt"
  - "app/src/androidTest/java/com/plearn/appcontrol/ui/AppControlAppSmokeTest.kt"
  - "app/src/androidTest/java/com/plearn/appcontrol/ui/DeviceValidationUiSmokeTest.kt"
  - "app/src/test/java/com/plearn/appcontrol/appservice/DeviceValidationServiceTest.kt"
  - "app/src/test/java/com/plearn/appcontrol/platform/devicecontrol/RootDeviceControlPortTest.kt"
  - "app/src/test/java/com/plearn/appcontrol/ui/AppControlAppFormattingTest.kt"
  - "app/src/main/java/com/plearn/appcontrol/MainActivity.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/AppControlDatabaseEntryPoint.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/CapabilityModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/DeviceEnvironmentInspector.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/DeviceValidationService.kt"
  - "app/src/main/java/com/plearn/appcontrol/ui/AppControlApp.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/DeviceControlRuntimeOverrides.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/DeterministicDeviceControlPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/RootDeviceControlPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/SuRootShellPort.kt"
  - "doc/PHASE5_VALIDATION.md"
  - ".github/skills/android-instrumentation-triage/**/*.md"
---
# Android Validation Guardrails

- Default local instrumentation in this repo uses the custom AndroidJUnitRunner deterministic override. Treat it as a local smoke path, not as rooted-device acceptance.
- If the task requires real root behavior, explicitly disable deterministic override in the Gradle runner arguments and call that out in validation notes.
- Do not rely on `Build.*` or qemu heuristics as the primary rule for local-vs-real validation decisions.
- instrumentation seed must go through the application singleton database or a main-source Hilt EntryPoint. Do not create a second Room connection and do not use `clearAllTables` as a stability workaround.
- For connected androidTest failures, validate in this order: `compileDebugAndroidTestKotlin`, one failing method, the owning class, then the full suite.
- Read the HTML report from `app/build/reports/androidTests/connected/debug/index.html` and structured results from `app/build/outputs/androidTest-results/connected/debug` before changing helper waits or timeouts.
- New or changed validation flows must pass manual real execution before they are accepted for scheduled or continuous test-machine mode.
- Rooted-device acceptance must cover root, accessibility, notifications, target app install status, and battery optimization, auto-start, and timezone readiness.
