package com.plearn.appcontrol.runner

interface DiagnosticsArtifactCaptureGate {
    suspend fun canCaptureFailureArtifact(): Boolean
}

object AllowAllDiagnosticsArtifactCaptureGate : DiagnosticsArtifactCaptureGate {
    override suspend fun canCaptureFailureArtifact(): Boolean = true
}