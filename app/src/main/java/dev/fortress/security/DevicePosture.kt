package dev.fortress.security

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import dev.fortress.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A concrete device posture check rendered by the dashboard. */
data class PostureCheck(
    val title: String,
    val value: String,
    val detail: String,
    val state: State,
) {
    enum class State { OK, WARN, CRITICAL, INFO }
}

data class DevicePostureSnapshot(
    val score: Int,
    val checks: List<PostureCheck>,
    val generatedAt: Long = System.currentTimeMillis(),
)

/**
 * Read-only device posture collection. Every value comes from Android system
 * APIs or a bounded root property query; no check changes device state.
 */
object DevicePosture {

    suspend fun collect(context: Context): DevicePostureSnapshot = withContext(Dispatchers.IO) {
        val rooted = RootShell.isAvailable()
        val checks = mutableListOf<PostureCheck>()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val devicePolicy = context.getSystemService(DevicePolicyManager::class.java)

        checks += PostureCheck(
            title = "Root provider",
            value = if (rooted) dev.fortress.ConsoleRuntime.rootProviderLabel else "none",
            detail = if (rooted) "su uid=0 is available for explicit operator actions" else "rooted deep visibility is unavailable",
            state = if (rooted) PostureCheck.State.OK else PostureCheck.State.WARN,
        )

        val verifiedBoot = rootProperty("ro.boot.verifiedbootstate", rooted)
            ?: if (Build.TAGS?.contains("test-keys") == true) "test-keys" else "unknown"
        checks += PostureCheck(
            title = "Verified boot",
            value = verifiedBoot,
            detail = "ro.boot.verifiedbootstate / build signing posture",
            state = when (verifiedBoot.lowercase(Locale.US)) {
                "green" -> PostureCheck.State.OK
                "yellow", "orange", "test-keys" -> PostureCheck.State.WARN
                "red" -> PostureCheck.State.CRITICAL
                else -> PostureCheck.State.INFO
            },
        )

        val selinux = rootProperty("getenforce", rooted)
            ?: runCatching { java.io.File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()
            ?: "unknown"
        checks += PostureCheck(
            title = "SELinux",
            value = if (selinux == "1") "Enforcing" else selinux,
            detail = "kernel mandatory access control mode",
            state = when (selinux.lowercase(Locale.US)) {
                "enforcing", "1" -> PostureCheck.State.OK
                "permissive", "0" -> PostureCheck.State.CRITICAL
                else -> PostureCheck.State.INFO
            },
        )

        val encryption = when (devicePolicy?.storageEncryptionStatus) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "active"
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "inactive"
            DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> "unsupported"
            else -> "unknown"
        }
        checks += PostureCheck(
            title = "Storage encryption",
            value = encryption,
            detail = "DevicePolicyManager storage encryption status",
            state = when (encryption) {
                "active" -> PostureCheck.State.OK
                "inactive" -> PostureCheck.State.CRITICAL
                else -> PostureCheck.State.WARN
            },
        )

        val adb = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED,
            0,
        ) == 1
        checks += PostureCheck(
            title = "ADB debugging",
            value = if (adb) "enabled" else "disabled",
            detail = "global debugging transport; disable outside a lab",
            state = if (adb) PostureCheck.State.WARN else PostureCheck.State.OK,
        )

        val developers = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
        checks += PostureCheck(
            title = "Developer options",
            value = if (developers) "enabled" else "disabled",
            detail = "platform development controls are visible",
            state = if (developers) PostureCheck.State.WARN else PostureCheck.State.OK,
        )

        val unknownSources = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.canRequestPackageInstalls()
        checks += PostureCheck(
            title = "Unknown-source installs",
            value = if (unknownSources) "allowed for Fortress" else "restricted",
            detail = "PackageInstaller fallback permission for this app",
            state = if (unknownSources) PostureCheck.State.WARN else PostureCheck.State.OK,
        )

        val unlocked = userManager?.isUserUnlocked ?: true
        checks += PostureCheck(
            title = "User profile",
            value = if (unlocked && keyguard?.isDeviceLocked != true) "unlocked" else "locked",
            detail = "required for complete user-storage visibility",
            state = if (unlocked) PostureCheck.State.OK else PostureCheck.State.INFO,
        )

        val patchAge = securityPatchAgeDays(Build.VERSION.SECURITY_PATCH)
        checks += PostureCheck(
            title = "Security patch",
            value = Build.VERSION.SECURITY_PATCH ?: "unknown",
            detail = if (patchAge == null) "patch age unavailable" else "$patchAge days old",
            state = when {
                patchAge == null -> PostureCheck.State.INFO
                patchAge > 365 -> PostureCheck.State.CRITICAL
                patchAge > 180 -> PostureCheck.State.WARN
                else -> PostureCheck.State.OK
            },
        )

        val deductions = checks.sumOf {
            when (it.state) {
                PostureCheck.State.OK -> 0
                PostureCheck.State.INFO -> 2
                PostureCheck.State.WARN -> 10
                PostureCheck.State.CRITICAL -> 25
            }
        }
        DevicePostureSnapshot((100 - deductions).coerceIn(0, 100), checks)
    }

    private suspend fun rootProperty(name: String, rooted: Boolean): String? {
        if (!rooted) return null
        return RootShell.exec("getprop $name").output.lastOrNull()?.trim()?.ifBlank { null }
            ?: RootShell.exec(name).output.lastOrNull()?.trim()?.ifBlank { null }
    }

    private fun securityPatchAgeDays(value: String?): Long? = runCatching {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value ?: return null) ?: return null
        TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - date.time).coerceAtLeast(0L)
    }.getOrNull()
}
