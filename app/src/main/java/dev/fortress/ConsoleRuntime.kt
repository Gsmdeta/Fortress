package dev.fortress

/**
 * Console-wide runtime facts set once by ConsoleActivity from MainActivity's
 * triage (root availability, Magisk presence). Tabs read these to decide
 * which controls to expose (e.g. iptables enforcement needs root).
 */
object ConsoleRuntime {
    enum class RootProvider { NONE, MAGISK, KERNELSU, APATCH, UNKNOWN }

    @Volatile
    var rooted: Boolean = false

    @Volatile
    var magisk: Boolean = false

    @Volatile
    var rootProvider: RootProvider = RootProvider.NONE

    val rootProviderLabel: String
        get() = when (rootProvider) {
            RootProvider.NONE -> "none"
            RootProvider.MAGISK -> "Magisk"
            RootProvider.KERNELSU -> "KernelSU"
            RootProvider.APATCH -> "APatch"
            RootProvider.UNKNOWN -> "root manager unknown"
        }
}
