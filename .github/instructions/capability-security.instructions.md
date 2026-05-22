---
name: Privileged Capability Guardrails
description: "Use when editing Android manifest permissions or exported components, accessibility service config resources, foreground-service or recovery component owners, privileged capability facades, accessibility or root or deterministic device-control adapters and runtime overrides, environment-check services that gate privileged execution, or DI wiring that exposes those capabilities."
applyTo:
  - "app/src/main/AndroidManifest.xml"
  - "app/src/main/res/xml/accessibility_service_config.xml"
  - "app/src/main/java/com/plearn/appcontrol/MainActivity.kt"
  - "app/src/main/java/com/plearn/appcontrol/capability/CapabilityFacade.kt"
  - "app/src/main/java/com/plearn/appcontrol/di/CapabilityModule.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/DeviceEnvironmentInspector.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/DeviceValidationService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerForegroundService.kt"
  - "app/src/main/java/com/plearn/appcontrol/appservice/SchedulerRecoveryReceiver.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/accessibility/**/*.kt"
  - "app/src/main/java/com/plearn/appcontrol/platform/devicecontrol/**/*.kt"
---
# Privileged Capability Guardrails

- Keep Android permissions, exported components, receivers, and foreground-service declarations explicit and minimal; any new exported surface or privileged permission needs a concrete runtime reason.
- High-privilege behavior must stay behind the capability facade and platform adapter boundaries. Do not expose root shell or AccessibilityService internals directly from these owning surfaces.
- Optional capabilities must remain interface-driven and pluggable; deterministic or test-only overrides must stay clearly scoped so production still exercises the real capability chain.
- Environment or prerequisite checks that affect privileged execution must fail closed with explicit blocked or unavailable reasons. Do not silently bypass missing root, accessibility, notification, target-app, or scheduler-readiness conditions.
- Privileged adapter failures must stay explicit and typed; do not introduce uncategorized string failures or bypass paths inside these owning capability boundaries.
- Keep device-specific compatibility and permission handling centralized in the manifest, capability wiring, or the owning platform adapter instead of scattering it across unrelated modules.