package dev.fortress.ui

import android.content.Context

/**
 * Density-independent dp→px conversion for the programmatic console layouts.
 *
 * The first delivery used raw pixel paddings, which render visibly tighter on
 * high-density phones and oversized on low-density tablets; every console
 * screen now sizes through this helper so spacing scales with the device.
 *
 * Plain function (not a Context extension) so every call site resolves
 * identically under K2 regardless of the surrounding implicit receivers
 * (apply blocks, Fragments, activities).
 */
internal fun dpPx(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density + 0.5f).toInt()
