package dev.fortress.ui

/**
 * Density-independent dp→px conversion for the programmatic console layouts.
 *
 * The first delivery used raw pixel paddings, which render visibly tighter on
 * high-density phones and oversized on low-density tablets; every console
 * screen now sizes through this helper so spacing scales with the device.
 *
 * Takes the raw screen density (a Float) instead of a Context/View so the
 * call sites carry no object-typed arguments at all:
 *
 *     val d = resources.displayMetrics.density
 *     setPadding(dpPx(d, 8), dpPx(d, 12), ...)
 */
internal fun dpPx(density: Float, value: Int): Int =
    (value * density + 0.5f).toInt()
