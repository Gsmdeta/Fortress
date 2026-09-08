package dev.fortress.root

import android.content.Context
import android.content.Intent
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Installs Fortress as a "system" app by packaging it inside a Magisk module.
 *
 * WHY the module route (and not plain `pm install` into /system): modern
 * devices have read-only system partitions; Magisk's systemless overlay is the
 * only sanctioned way to place an APK under /system/priv-app without touching
 * the partition. The module survives OTAs and is removable from the Magisk app.
 *
 * FALLBACK (non-root): a PackageInstaller session with the
 * REQUEST_INSTALL_PACKAGES permission we declare in the manifest — the user
 * confirms the standard side-load prompt. `pm install` variations are kept for
 * root paths only because app-uid `pm install` is denied by default.
 */
object MagiskInstaller {

    private const val MODULE_ID = "fortress_console"
    private const val MODULE_NAME = "Fortress Security Console"
    private const val MODULE_VERSION = "v1.3.0"
    private const val MODULE_VERSION_CODE = 13

    /** human-readable progress from the packaging/install phases */
    suspend fun installAsSystemApp(
        context: Context,
        onLine: (String) -> Unit,
    ): Boolean {
        val workDir = File(context.cacheDir, "fortress-module").apply { deleteRecursively() }
        val moduleRoot = File(workDir, MODULE_ID)
        val zipFile = File(context.cacheDir, "$MODULE_ID.zip")

        onLine("[1/4] staging module skeleton")
        val apkPath = stageModule(context, moduleRoot)
            ?: run {
                onLine("!! source APK not resolvable (instant-app / split APK?)")
                return false
            }

        onLine("[2/4] zipping module ($apkPath)")
        zipModule(moduleRoot, zipFile)

        onLine("[3/4] magisk --install-module")
        val result = RootShell.magiskInstallModule(zipFile.absolutePath, onLine)
        if (!result.ok) {
            onLine("!! install-module failed (rc=${result.exitCode}) — see Magisk log")
            return false
        }

        onLine("[4/4] module staged — reboot required to overlay /system")
        return true
    }

    /**
     * Builds:
     *   module.prop                       (Magisk metadata)
     *   system/app/Fortress/Fortress.apk  (our own APK, copied from sourceDir)
     *   post-fs-data.sh                   (no-op guard script, kept minimal)
     */
    private fun stageModule(context: Context, moduleRoot: File): String? {
        val srcApk = context.applicationInfo.sourceDir ?: return null

        val prop = """
            id=$MODULE_ID
            name=$MODULE_NAME
            version=$MODULE_VERSION
            versionCode=$MODULE_VERSION_CODE
            author=fortress operators
            description=Systemless install of the Fortress security console. Detection tooling only.
        """.trimIndent()

        moduleRoot.mkdirs()
        File(moduleRoot, "module.prop").writeText(prop + "\n")
        File(moduleRoot, "post-fs-data.sh").writeText("#!/system/bin/sh\n# reserved: hardening hooks\nexit 0\n")

        val apkDir = File(moduleRoot, "system/app/Fortress").apply { mkdirs() }
        File(srcApk).copyTo(File(apkDir, "Fortress.apk"), overwrite = true)
        return srcApk
    }

    private fun zipModule(moduleRoot: File, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            moduleRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                val entry = ZipEntry(f.relativeTo(moduleRoot).path)
                zip.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Non-root fallback: standard PackageInstaller session (fires the user
     * consent dialog on API 26+). Kept separate from the Magisk path so the
     * console UI can offer exactly one option based on RootShell.isAvailable().
     */
    fun installViaSession(context: Context, apkPath: String, onLine: (String) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val apk = File(apkPath)
        onLine("opening PackageInstaller session for ${apk.name}")

        val session = installer.openSession(
            installer.createSession(
                android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )
            )
        )
        try {
            session.openWrite("fortress.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The status PendingIntent is where the system reports the user's
            // install decision; we route it to our own package's receiver.
            val statusIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(
                context, 0, statusIntent, android.app.PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
            onLine("session committed — awaiting user consent dialog")
        } finally {
            session.close()
        }
    }

    /**
     * Magisk modules only take effect after a reboot. `svc power reboot` runs
     * through the su stream; on failure we surface the manual instruction.
     */
    suspend fun requestReboot(onLine: (String) -> Unit): Boolean {
        onLine("requesting reboot (svc power reboot)")
        val r = RootShell.exec("svc power reboot", onLine = onLine, timeoutMs = 8_000)
        if (!r.ok) onLine("!! reboot command failed — please reboot manually")
        return r.ok
    }
}
