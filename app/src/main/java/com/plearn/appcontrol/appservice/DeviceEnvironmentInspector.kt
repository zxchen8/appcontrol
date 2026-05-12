package com.plearn.appcontrol.appservice

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.plearn.appcontrol.platform.accessibility.AccessibilityServiceRegistry
import com.plearn.appcontrol.platform.accessibility.AppControlAccessibilityService
import com.plearn.appcontrol.platform.devicecontrol.RootShellPort
import java.time.ZoneId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

const val DEFAULT_SAMPLE_TIMEZONE_ID = "Asia/Shanghai"

interface DeviceEnvironmentInspector {
    suspend fun inspect(targetPackageName: String? = null): DeviceEnvironmentReport
}

data class DeviceEnvironmentReport(
    val rootReady: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val foregroundPackageName: String?,
    val notificationsEnabled: Boolean,
    val targetPackageName: String?,
    val targetPackageInstalled: Boolean?,
    val deviceTimezoneId: String,
    val sampleTimezoneId: String,
    val sampleTimezoneAligned: Boolean,
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

interface NotificationStatusPort {
    fun areNotificationsEnabled(): Boolean
}

interface TargetPackageStatusPort {
    fun isInstalled(packageName: String): Boolean
}

interface DeviceTimeZonePort {
    fun systemTimeZoneId(): String
}

class DefaultDeviceEnvironmentInspector(
    private val rootShellPort: EnvironmentRootShellPort,
    private val accessibilitySettingsPort: AccessibilitySettingsPort,
    private val accessibilityConnectionPort: AccessibilityConnectionPort,
    private val notificationStatusPort: NotificationStatusPort,
    private val targetPackageStatusPort: TargetPackageStatusPort,
    private val deviceTimeZonePort: DeviceTimeZonePort,
) : DeviceEnvironmentInspector {
    override suspend fun inspect(targetPackageName: String?): DeviceEnvironmentReport {
        val rootCheck = rootShellPort.run(ROOT_CHECK_COMMAND)
        val rootReady = rootCheck.exitCode == 0
        val foregroundPackage = if (rootReady) {
            val foregroundCheck = rootShellPort.run(FOREGROUND_PACKAGE_COMMAND)
            parseForegroundPackage(foregroundCheck.stdout.ifBlank { foregroundCheck.stderr })
        } else {
            null
        }
        val normalizedTargetPackageName = targetPackageName?.trim()?.takeIf { it.isNotEmpty() }
        val deviceTimezoneId = deviceTimeZonePort.systemTimeZoneId()

        return DeviceEnvironmentReport(
            rootReady = rootReady,
            accessibilityEnabled = accessibilitySettingsPort.isServiceEnabled(),
            accessibilityConnected = accessibilityConnectionPort.isServiceConnected(),
            foregroundPackageName = foregroundPackage,
            notificationsEnabled = notificationStatusPort.areNotificationsEnabled(),
            targetPackageName = normalizedTargetPackageName,
            targetPackageInstalled = normalizedTargetPackageName?.let(targetPackageStatusPort::isInstalled),
            deviceTimezoneId = deviceTimezoneId,
            sampleTimezoneId = DEFAULT_SAMPLE_TIMEZONE_ID,
            sampleTimezoneAligned = deviceTimezoneId == DEFAULT_SAMPLE_TIMEZONE_ID,
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

class AndroidNotificationStatusPort(
    @ApplicationContext private val context: Context,
) : NotificationStatusPort {
    override fun areNotificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
}

class AndroidTargetPackageStatusPort(
    @ApplicationContext private val context: Context,
) : TargetPackageStatusPort {
    override fun isInstalled(packageName: String): Boolean {
        return try {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

object SystemDeviceTimeZonePort : DeviceTimeZonePort {
    override fun systemTimeZoneId(): String = ZoneId.systemDefault().id
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