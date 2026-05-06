package com.plearn.appcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.plearn.appcontrol.appservice.DeviceValidationService
import com.plearn.appcontrol.ui.AppControlApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var deviceValidationService: DeviceValidationService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppControlApp(deviceValidationService = deviceValidationService)
        }
    }
}