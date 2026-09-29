package com.pocket.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal class PocketTermuxBridge(context: Context) {

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_PERMISSION =
            "com.termux.permission.RUN_COMMAND"
        private const val RUN_COMMAND_SERVICE =
            "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    }

    private val appContext = context.applicationContext

    fun isAvailable(): Boolean {
        val pm = appContext.packageManager
        return runCatching {
            pm.getPackageInfo(TERMUX_PACKAGE, 0)
            appContext.checkSelfPermission(RUN_COMMAND_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun startPocketWeb(host: String = "0.0.0.0", port: Int = 8787): Boolean {
        require(Regex("^[0-9A-Za-z.:-]+$").matches(host)) { "invalid host" }
        require(port in 1..65535) { "invalid port" }

        val script =
            "nohup pocket web --host $host --port $port " +
                "> ~/.pocket-web.log 2>&1 & " +
                "echo $! > ~/.pocket-web.pid"

        return runCommand(
            listOf("sh", "-lc", script),
            background = true,
            executableOverride = "/data/data/com.termux/files/usr/bin/sh"
        )
    }

    fun stopPocketWeb(): Boolean =
        runCommand(
            listOf(
                "sh",
                "-lc",
                "if [ -f ~/.pocket-web.pid ]; then " +
                    "kill "$(cat ~/.pocket-web.pid)" 2>/dev/null || true; " +
                    "rm -f ~/.pocket-web.pid; " +
                    "fi"
            ),
            background = true,
            executableOverride = "/data/data/com.termux/files/usr/bin/sh"
        )

    private fun runCommand(
        command: List<String>,
        background: Boolean,
        executableOverride: String? = null
    ): Boolean {
        if (!isAvailable()) return false

        val executable =
            executableOverride ?: "/data/data/com.termux/files/usr/bin/" + command.first()
        val arguments =
            if (executableOverride == null) command.drop(1).toTypedArray()
            else command.drop(1).toTypedArray()

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_PATH, executable)
            putExtra(EXTRA_ARGUMENTS, arguments)
            putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_BACKGROUND, background)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        return runCatching {
            appContext.startService(intent)
            true
        }.getOrDefault(false)
    }
}
