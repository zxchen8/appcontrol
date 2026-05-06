package com.plearn.appcontrol.appservice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.plearn.appcontrol.platform.accessibility.AccessibilityServiceRegistry
import com.plearn.appcontrol.platform.accessibility.AppControlAccessibilityService
import com.plearn.appcontrol.platform.devicecontrol.RootShellPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceEnvironmentInspector {
    suspend fun inspect(): DeviceEnvironmentReport
}

data class DeviceEnvironmentReport(
    val rootReady: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val foregroundPackageName: String?,
)

data class ShellSnapshot(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
)

interface EnvironmentRootShellPort {
    suspend fun run(command: String): ShellSnapshot
}

interface AccessibilitySettingsPort {
    fun isServiceEnabled(): Boolean
}

interface AccessibilityConnectionPort {
    fun isServiceConnected(): Boolean
}

class DefaultDeviceEnvironmentInspector(
    private val rootShellPort: EnvironmentRootShellPort,
    private val accessibilitySettingsPort: AccessibilitySettingsPort,
    private val accessibilityConnectionPort: AccessibilityConnectionPort,
) : DeviceEnvironmentInspector {
    override suspend fun inspect(): DeviceEnvironmentReport {
        val rootCheck = rootShellPort.run(ROOT_CHECK_COMMAND)
        val rootReady = rootCheck.exitCode == 0
        val foregroundPackage = if (rootReady) {
            val foregroundCheck = rootShellPort.run(FOREGROUND_PACKAGE_COMMAND)
            parseForegroundPackage(foregroundCheck.stdout.ifBlank { foregroundCheck.stderr })
        } else {
            null
        }

        return DeviceEnvironmentReport(
            rootReady = rootReady,
            accessibilityEnabled = accessibilitySettingsPort.isServiceEnabled(),
            accessibilityConnected = accessibilityConnectionPort.isServiceConnected(),
            foregroundPackageName = foregroundPackage,
        )
    }

    private fun parseForegroundPackage(output: String): String? {
        return FOREGROUND_PACKAGE_REGEX.find(output)?.groupValues?.getOrNull(1)
    }

    companion object {
        const val ROOT_CHECK_COMMAND = "id"
        const val FOREGROUND_PACKAGE_COMMAND = "dumpsys window displays"
        private val FOREGROUND_PACKAGE_REGEX = Regex("([a-zA-Z0-9._]+)/[a-zA-Z0-9._$]+")
    }
}

class RootShellEnvironmentPort(
    private val rootShellPort: RootShellPort,
) : EnvironmentRootShellPort {
    override suspend fun run(command: String): ShellSnapshot {
        val result = rootShellPort.run(command)
        return ShellSnapshot(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
        )
    }
}

class RegistryAccessibilityConnectionPort(
    private val registry: AccessibilityServiceRegistry,
) : AccessibilityConnectionPort {
    override fun isServiceConnected(): Boolean = registry.currentService() != null
}

class AndroidAccessibilitySettingsPort(
    @ApplicationContext private val context: Context,
) : AccessibilitySettingsPort {
    override fun isServiceEnabled(): Boolean {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return accessibilityManager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { serviceInfo ->
                serviceInfo.resolveInfo.serviceInfo.packageName == context.packageName &&
                    serviceInfo.resolveInfo.serviceInfo.name == AppControlAccessibilityService::class.java.name
            }
    }
}