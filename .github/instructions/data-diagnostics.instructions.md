---
name: Data And Diagnostics Guardrails
description: "Use when editing persistence, repositories, run-record storage, diagnostics formatting, retention, diagnostics read-side composition, UI files that render diagnostics summaries, runtime and DSL files that decide diagnostics artifact capture, suppression, and sensitive logging behavior, or the design doc that defines those diagnostics and storage contracts."
applyTo:
  - "app/src/main/java/com/plearn/appcontrol/AppControlApplication.kt"
  - "app/src/main/java/com/plearn/appcontrol/capability/CapabilityFacade.kt"
  - "app/src/main/java/com/plearn/appcontrol/data/**/*.kt"
  - "app/src/main/java/com/plearn/appcontrol/diagnostics/**/*.kt"
  - "app/src/main/java/com/plearn/appcontrol/dsl/TaskDslModel.kt"
  - "app/src/main/java/com/plearn/appcontrol/dsl/TaskDslParser.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/CapabilityModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/DataModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/ExecutionServicesModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/runner/DefaultTaskRunner.kt"
  - "app/src/main/java/com/plearn/appcontrol/runner/RunnerModels.kt"
  - "app/src/main/java/com/plearn/appcontrol/runner/TaskExecutionRecorder.kt"
  - "app/src/main/java/com/plearn/appcontrol/runner/DiagnosticsArtifactCaptureGate.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/DeviceControlRuntimeOverrides.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/DeterministicDeviceControlPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/RootDeviceControlPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/SuRootShellPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/scheduler/TaskSchedulerService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/ManualTaskExecutionService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/AppDashboardService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/CredentialManagementService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/TaskMonitoringDetailService.kt"
  - "app/src/main/java/com/plearn/appcontrol/ui/AppControlApp.kt"
  - "doc/DETAILED_DESIGN.md"
---
# Data And Diagnostics Guardrails

- Run-record structure, diagnostics read-side behavior, and diagnostics-related DSL defaults or suppression conditions must stay aligned with `doc/DETAILED_DESIGN.md`.
- Room entities must not leak directly into the UI, scheduler, or runner layers; cross-layer access must go through repository boundaries.
- Multi-table writes that affect credential ordering, continuous cursors, task-run state, or session state must stay transactional.
- Logs must remain structured: every task execution and every step should preserve start, finish, status, and redacted parameter summaries.
- Sensitive variable values, raw input text, encrypted payloads, and recoverable credential material must not enter logs or diagnostics artifacts.
- Failure screenshots default to on; when suppression or storage fallback applies, persist an explicit structured reason instead of silently dropping evidence.
- Retention limits, cleanup triggers, and low-storage fallback behavior must stay explicit for local logs, screenshots, and diagnostics artifacts.
- Some runtime files carry both execution and diagnostics responsibilities; when this instruction and the execution-contract instruction both load, satisfy both contracts rather than collapsing concerns into one layer.