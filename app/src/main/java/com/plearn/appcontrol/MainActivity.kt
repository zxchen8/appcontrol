package com.plearn.appcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.plearn.appcontrol.appservice.AppDashboardService
import com.plearn.appcontrol.appservice.CredentialManagementService
import com.plearn.appcontrol.appservice.DeviceValidationService
import com.plearn.appcontrol.appservice.TaskManagementService
import com.plearn.appcontrol.appservice.TaskMonitoringDetailService
import com.plearn.appcontrol.ui.AppControlApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var deviceValidationService: DeviceValidationService

    @Inject
    lateinit var appDashboardService: AppDashboardService

    @Inject
    lateinit var taskManagementService: TaskManagementService

    @Inject
    lateinit var taskMonitoringDetailService: TaskMonitoringDetailService

    @Inject
    lateinit var credentialManagementService: CredentialManagementService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppControlApp(
                deviceValidationService = deviceValidationService,
                dashboardService = appDashboardService,
                taskManagementService = taskManagementService,
                taskMonitoringDetailService = taskMonitoringDetailService,
                credentialManagementService = credentialManagementService,
            )
        }
    }
}