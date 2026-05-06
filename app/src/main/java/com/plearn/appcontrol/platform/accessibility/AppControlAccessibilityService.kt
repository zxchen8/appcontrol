package com.plearn.appcontrol.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AppControlAccessibilityService : AccessibilityService(), AccessibilityServiceHandle {
    @Inject
    lateinit var registry: AccessibilityServiceRegistry

    override fun currentRootNode() = rootInActiveWindow

    override fun onServiceConnected() {
        super.onServiceConnected()
        registry.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        registry.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        registry.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}