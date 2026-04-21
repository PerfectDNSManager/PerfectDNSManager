package net.appstorefr.perfectdnsmanager.service

import net.appstorefr.perfectdnsmanager.IShellService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Service exécuté dans un processus séparé avec les privilèges shell (ADB) via Shizuku.
 * Utilisé par ShizukuManager pour exécuter des commandes système.
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText().trim()
            val errOutput = errReader.readText().trim()
            process.waitFor()
            reader.close()
            errReader.close()
            if (errOutput.isNotEmpty() && output.isEmpty()) errOutput else output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
