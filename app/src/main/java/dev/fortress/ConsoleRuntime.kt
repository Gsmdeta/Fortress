package dev.fortress

/**
 * Console-wide runtime facts set once by ConsoleActivity from MainActivity's
 * triage (root availability, Magisk presence). Tabs read these to decide
 * which controls to expose (e.g. iptables enforcement needs root).
 */
object ConsoleRuntime {
    @Volatile
    var rooted: Boolean = false

    @Volatile
    var magisk: Boolean = false
}
