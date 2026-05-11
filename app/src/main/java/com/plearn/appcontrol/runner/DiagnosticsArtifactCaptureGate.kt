package com.plearn.appcontrol.runner

interface DiagnosticsArtifactCaptureGate {
    suspend fun canCaptureFailureArtifact(taskId: String? = null, runId: String? = null): Boolean
}

object AllowAllDiagnosticsArtifactCaptureGate : DiagnosticsArtifactCaptureGate {
    override suspend fun canCaptureFailureArtifact(taskId: String?, runId: String?): Boolean = true
}