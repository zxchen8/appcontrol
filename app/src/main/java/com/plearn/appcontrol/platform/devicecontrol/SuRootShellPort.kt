package com.plearn.appcontrol.platform.devicecontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class SuRootShellPort : RootShellPort {
    override suspend fun run(command: String): ShellCommandResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", command).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
            val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.waitFor()
            ShellCommandResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
            )
        } catch (error: IOException) {
            ShellCommandResult(
                exitCode = -1,
                stderr = error.message ?: "Failed to execute root shell command.",
            )
        }
    }
}