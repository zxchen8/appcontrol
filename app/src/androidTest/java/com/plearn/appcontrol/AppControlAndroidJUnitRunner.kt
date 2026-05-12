package com.plearn.appcontrol

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.plearn.appcontrol.platform.devicecontrol.DeviceControlRuntimeOverrides

class AppControlAndroidJUnitRunner : AndroidJUnitRunner() {
    private var useDeterministicDeviceControl: Boolean = true

    override fun onCreate(arguments: Bundle) {
        useDeterministicDeviceControl = arguments
            .getString(ARG_DETERMINISTIC_DEVICE_CONTROL)
            ?.toBooleanStrictOrNull()
            ?: true
        super.onCreate(arguments)
    }

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        DeviceControlRuntimeOverrides.useDeterministicDeviceControl = useDeterministicDeviceControl
        return super.newApplication(cl, className, context)
    }

    private companion object {
        const val ARG_DETERMINISTIC_DEVICE_CONTROL = "appcontrol.deterministicDeviceControl"
    }
}