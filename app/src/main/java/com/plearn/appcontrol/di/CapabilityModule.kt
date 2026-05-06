package com.plearn.appcontrol.di

import android.content.Context
import com.plearn.appcontrol.appservice.AccessibilityConnectionPort
import com.plearn.appcontrol.appservice.AccessibilitySettingsPort
import com.plearn.appcontrol.appservice.AndroidAccessibilitySettingsPort
import com.plearn.appcontrol.appservice.DefaultDeviceEnvironmentInspector
import com.plearn.appcontrol.appservice.DeviceEnvironmentInspector
import com.plearn.appcontrol.appservice.EnvironmentRootShellPort
import com.plearn.appcontrol.appservice.RegistryAccessibilityConnectionPort
import com.plearn.appcontrol.appservice.RootShellEnvironmentPort
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
import com.plearn.appcontrol.platform.devicecontrol.RootDeviceControlPort
import com.plearn.appcontrol.platform.devicecontrol.RootShellPort
import com.plearn.appcontrol.platform.devicecontrol.SuRootShellPort
import com.plearn.appcontrol.runner.DefaultTaskRunner
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
    fun provideDeviceControlPort(rootShellPort: RootShellPort): DeviceControlPort =
        RootDeviceControlPort(shell = rootShellPort)

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
    fun provideTaskRunner(
        capabilityFacade: CapabilityFacade,
        timeSource: RunnerTimeSource,
    ): TaskRunner = DefaultTaskRunner(
        capabilityFacade = capabilityFacade,
        timeSource = timeSource,
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
    fun provideDeviceEnvironmentInspector(
        rootShellPort: EnvironmentRootShellPort,
        accessibilitySettingsPort: AccessibilitySettingsPort,
        accessibilityConnectionPort: AccessibilityConnectionPort,
    ): DeviceEnvironmentInspector = DefaultDeviceEnvironmentInspector(
        rootShellPort = rootShellPort,
        accessibilitySettingsPort = accessibilitySettingsPort,
        accessibilityConnectionPort = accessibilityConnectionPort,
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
}