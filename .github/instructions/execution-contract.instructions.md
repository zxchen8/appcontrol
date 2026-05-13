---
name: Execution Contract Guardrails
description: "Use when editing DSL parsing, execution entrypoints, runner lifecycle, scheduler semantics, task-management orchestration, UI files that own execution entry flows, capability boundaries, or the design docs that define those execution contracts."
applyTo:
  - "app/src/main/java/com/plearn/appcontrol/dsl/**/*.kt"
  - "app/src/main/java/com/plearn/appcontrol/runner/DefaultTaskRunner.kt"
  - "app/src/main/java/com/plearn/appcontrol/runner/RunnerModels.kt"
  - "app/src/main/java/com/plearn/appcontrol/scheduler/**/*.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/ForegroundSchedulerStandbyController.kt"
  - "app/src/main/java/com/plearn/appcontrol/ui/AppControlApp.kt"
  - "app/src/main/java/com/plearn/appcontrol/capability/CapabilityFacade.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/accessibility/**/*.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/DeviceControlRuntimeOverrides.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/DeterministicDeviceControlPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/RootDeviceControlPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/SuRootShellPort.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/CapabilityModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/ExecutionServicesModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/ManualTaskExecutionService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerForegroundRuntimeCoordinator.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerForegroundRuntimeState.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerForegroundService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerRecoveryAlarmScheduler.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerRecoveryOrchestrator.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerRecoveryReceiver.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/TaskManagementService.kt"
  - "doc/HIGH_LEVEL_DESIGN.md"
  - "doc/DETAILED_DESIGN.md"
---
# Execution Contract Guardrails

- `doc/HIGH_LEVEL_DESIGN.md` is the source of truth for v1 scope, module boundaries, and default runtime interaction boundaries.
- `doc/DETAILED_DESIGN.md` is the source of truth for DSL fields, defaults, scheduler conflict handling, and continuous semantics.
- UI configures, displays, triggers, and inspects results only; execution lifecycle and task flow logic must stay out of the UI layer.
- The execution engine must stay independent from the UI layer, and the scheduler must only decide when to trigger, not how task actions execute.
- Manual execution entrypoints and task-management orchestration must keep the same execution and schedule-state semantics as the runner and scheduler contracts.
- High-privilege or platform-specific behavior must stay behind the unified capability and adapter boundaries; optional capabilities must remain interface-driven and pluggable.
- New or changed DSL semantics must update `doc/DETAILED_DESIGN.md` first, then keep parser validation, runtime behavior, error codes, and tests aligned with that contract.
- New step types must add parameter validation, executor support, error codes, and success/failure/timeout coverage together.
- Do not silently widen platform compatibility or runtime scope beyond the current design documents.