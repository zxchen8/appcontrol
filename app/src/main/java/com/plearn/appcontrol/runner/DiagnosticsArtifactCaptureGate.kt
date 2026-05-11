package com.plearn.appcontrol.runner

fun interface DiagnosticsArtifactCaptureGate {
    fun canCaptureFailureArtifact(): Boolean
}

object AllowAllDiagnosticsArtifactCaptureGate : DiagnosticsArtifactCaptureGate {
    override fun canCaptureFailureArtifact(): Boolean = true
}