package com.plearn.appcontrol

import android.app.Application
import com.plearn.appcontrol.diagnostics.DiagnosticsRetentionStartupCleaner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AppControlApplication : Application() {
	@Inject
	lateinit var diagnosticsRetentionStartupCleaner: DiagnosticsRetentionStartupCleaner

	override fun onCreate() {
		super.onCreate()
		diagnosticsRetentionStartupCleaner.scheduleCleanup()
	}
}