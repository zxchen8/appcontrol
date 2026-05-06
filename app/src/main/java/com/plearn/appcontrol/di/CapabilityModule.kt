package com.plearn.appcontrol.di

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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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