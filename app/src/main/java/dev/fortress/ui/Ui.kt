package dev.fortress.ui

import android.content.Context

/**
 * Density-independent dp→px conversion for the programmatic console layouts.
 *
 * The first delivery used raw pixel paddings, which render visibly tighter on
 * high-density phones and oversized on low-density tablets; every console
 * screen now sizes through this helper so spacing scales with the device.
 */
internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density + 0.5f).toInt()
