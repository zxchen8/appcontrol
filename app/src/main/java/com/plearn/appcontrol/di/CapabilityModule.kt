package com.plearn.appcontrol.di

import android.content.Context
import android.os.Build
import com.plearn.appcontrol.appservice.AccessibilityConnectionPort
import com.plearn.appcontrol.appservice.AccessibilitySettingsPort
import com.plearn.appcontrol.appservice.AndroidAccessibilitySettingsPort
import com.plearn.appcontrol.appservice.AndroidNotificationStatusPort
import com.plearn.appcontrol.appservice.AndroidTargetPackageStatusPort
import com.plearn.appcontrol.appservice.DeviceTimeZonePort
import com.plearn.appcontrol.appservice.DefaultDeviceEnvironmentInspector
import com.plearn.appcontrol.appservice.DeviceEnvironmentInspector
import com.plearn.appcontrol.appservice.EnvironmentRootShellPort
import com.plearn.appcontrol.appservice.NotificationStatusPort
import com.plearn.appcontrol.appservice.RegistryAccessibilityConnectionPort
import com.plearn.appcontrol.appservice.RootShellEnvironmentPort
import com.plearn.appcontrol.appservice.SystemDeviceTimeZonePort
import com.plearn.appcontrol.appservice.TargetPackageStatusPort
import com.plearn.appcontrol.capability.AccessibilityPort
import com.plearn.appcontrol.capability.CapabilityFacade
import com.plearn.appcontrol.capability.DefaultCapabilityFacade
import com.plearn.appcontrol.capability.DeviceControlPort
import com.plearn.appcontrol.capability.DisabledVisionPort
import com.plearn.appcontrol.capability.VisionPort
import com.plearn.appcontrol.platform.accessibility.AccessibilityServiceRegistry
import com.plearn.appcontrol.platform.accessibility.AndroidAccessibilityNodeTreeAdapter
import com.plearn.appcontrol.platform.accessibility.CoroutinePauseController
import com.plearn.appcontrol.platform.accessibility.DefaultAccessibilityPort
import com.plearn.appcontrol.platform.accessibility.InMemoryAccessibilityServiceRegistry
import com.plearn.appcontrol.platform.accessibility.NodeTreeAdapter
import com.plearn.appcontrol.platform.devicecontrol.DeterministicDeviceControlPort
import com.plearn.appcontrol.platform.devicecontrol.DeviceControlRuntimeOverrides
import com.plearn.appcontrol.platform.devicecontrol.RootDeviceControlPort
import com.plearn.appcontrol.platform.devicecontrol.RootShellPort
import com.plearn.appcontrol.platform.devicecontrol.SuRootShellPort
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.runner.DefaultTaskRunner
import com.plearn.appcontrol.runner.DiagnosticsArtifactCaptureGate
import com.plearn.appcontrol.runner.RunnerTimeSource
import com.plearn.appcontrol.runner.SystemRunnerTimeSource
import com.plearn.appcontrol.runner.TaskRunner
import com.plearn.appcontrol.dsl.TaskDslParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CapabilityModule {
    @Provides
    @Singleton
    fun provideAccessibilityServiceRegistry(): AccessibilityServiceRegistry =
        InMemoryAccessibilityServiceRegistry()

    @Provides
    @Singleton
    fun provideNodeTreeAdapter(registry: AccessibilityServiceRegistry): NodeTreeAdapter =
        AndroidAccessibilityNodeTreeAdapter(registry)

    @Provides
    @Singleton
    fun provideRootShellPort(): RootShellPort = SuRootShellPort()

    @Provides
    @Singleton
    fun provideDeviceControlPort(
        rootShellPort: RootShellPort,
        @ApplicationContext context: Context,
    ): DeviceControlPort {
        val screenshotRootDir = context.filesDir.resolve("diagnostics/screenshots")
        return if (DeviceControlRuntimeOverrides.useDeterministicDeviceControl || isEmulatorDevice()) {
            DeterministicDeviceControlPort(
                screenshotRootDir = screenshotRootDir,
            )
        } else {
            RootDeviceControlPort(
                shell = rootShellPort,
                screenshotRootDir = screenshotRootDir,
            )
        }
    }

    @Provides
    @Singleton
    fun provideAccessibilityPort(nodeTreeAdapter: NodeTreeAdapter): AccessibilityPort =
        DefaultAccessibilityPort(
            nodeTreeAdapter = nodeTreeAdapter,
            pauseController = CoroutinePauseController,
        )

    @Provides
    @Singleton
    fun provideVisionPort(): VisionPort = DisabledVisionPort

    @Provides
    @Singleton
    fun provideRunnerTimeSource(): RunnerTimeSource = SystemRunnerTimeSource

    @Provides
    @Singleton
    fun provideDiagnosticsArtifactCaptureGate(
        runRecordRepository: RunRecordRepository,
    ): DiagnosticsArtifactCaptureGate = object : DiagnosticsArtifactCaptureGate {
        override suspend fun canCaptureFailureArtifact(taskId: String?, runId: String?): Boolean =
            runRecordRepository.canCaptureFailureArtifact(taskId = taskId, runId = runId)
    }

    @Provides
    @Singleton
    fun provideTaskRunner(
        capabilityFacade: CapabilityFacade,
        timeSource: RunnerTimeSource,
        diagnosticsArtifactCaptureGate: DiagnosticsArtifactCaptureGate,
    ): TaskRunner = DefaultTaskRunner(
        capabilityFacade = capabilityFacade,
        timeSource = timeSource,
        diagnosticsArtifactCaptureGate = diagnosticsArtifactCaptureGate,
    )

    @Provides
    @Singleton
    fun provideTaskDslParser(): TaskDslParser = TaskDslParser()

    @Provides
    @Singleton
    fun provideEnvironmentRootShellPort(rootShellPort: RootShellPort): EnvironmentRootShellPort =
        RootShellEnvironmentPort(rootShellPort)

    @Provides
    @Singleton
    fun provideAccessibilitySettingsPort(@ApplicationContext context: Context): AccessibilitySettingsPort =
        AndroidAccessibilitySettingsPort(context)

    @Provides
    @Singleton
    fun provideAccessibilityConnectionPort(
        registry: AccessibilityServiceRegistry,
    ): AccessibilityConnectionPort = RegistryAccessibilityConnectionPort(registry)

    @Provides
    @Singleton
    fun provideNotificationStatusPort(@ApplicationContext context: Context): NotificationStatusPort =
        AndroidNotificationStatusPort(context)

    @Provides
    @Singleton
    fun provideTargetPackageStatusPort(@ApplicationContext context: Context): TargetPackageStatusPort =
        AndroidTargetPackageStatusPort(context)

    @Provides
    @Singleton
    fun provideDeviceTimeZonePort(): DeviceTimeZonePort = SystemDeviceTimeZonePort

    @Provides
    @Singleton
    fun provideDeviceEnvironmentInspector(
        rootShellPort: EnvironmentRootShellPort,
        accessibilitySettingsPort: AccessibilitySettingsPort,
        accessibilityConnectionPort: AccessibilityConnectionPort,
        notificationStatusPort: NotificationStatusPort,
        targetPackageStatusPort: TargetPackageStatusPort,
        deviceTimeZonePort: DeviceTimeZonePort,
    ): DeviceEnvironmentInspector = DefaultDeviceEnvironmentInspector(
        rootShellPort = rootShellPort,
        accessibilitySettingsPort = accessibilitySettingsPort,
        accessibilityConnectionPort = accessibilityConnectionPort,
        notificationStatusPort = notificationStatusPort,
        targetPackageStatusPort = targetPackageStatusPort,
        deviceTimeZonePort = deviceTimeZonePort,
    )

    @Provides
    @Singleton
    fun provideCapabilityFacade(
        deviceControl: DeviceControlPort,
        accessibility: AccessibilityPort,
        vision: VisionPort,
    ): CapabilityFacade = DefaultCapabilityFacade(
        deviceControl = deviceControl,
        accessibility = accessibility,
        vision = vision,
    )

    private fun isEmulatorDevice(): Boolean {
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val hardware = Build.HARDWARE
        val product = Build.PRODUCT
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true)
    }
}