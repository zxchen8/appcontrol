package com.plearn.appcontrol.platform.accessibility

import android.view.accessibility.AccessibilityNodeInfo

interface AccessibilityServiceHandle {
    fun currentRootNode(): AccessibilityNodeInfo?
}

interface AccessibilityServiceRegistry {
    fun attach(service: AccessibilityServiceHandle)

    fun detach(service: AccessibilityServiceHandle)

    fun currentService(): AccessibilityServiceHandle?
}

class InMemoryAccessibilityServiceRegistry : AccessibilityServiceRegistry {
    @Volatile
    private var activeService: AccessibilityServiceHandle? = null

    @Synchronized
    override fun attach(service: AccessibilityServiceHandle) {
        activeService = service
    }

    @Synchronized
    override fun detach(service: AccessibilityServiceHandle) {
        val currentService = activeService
        if (currentService === service) {
            activeService = null
        }
    }

    @Synchronized
    override fun currentService(): AccessibilityServiceHandle? = activeService
}