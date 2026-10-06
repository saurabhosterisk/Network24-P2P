package com.network24.player.features.discover

import android.app.Activity
import android.content.Intent
import android.widget.Toast

/** The Movies tab: opens the Cinema Pro 3 app (used by the home and by every page with the top bar). */
object CinemaPro {
    private const val PACKAGE = "com.infahash.fvision.cpro3"

    // Fallback matching only, when the exact package name above isn't found - a Cinema Pro 3 build with a
    // different applicationId would still contain one of these in its package name or app label.
    private val NAME_HINTS = listOf("cpro3", "cinemapro", "cinema pro")

    fun open(a: Activity) {
        val launchIntent = a.packageManager.getLaunchIntentForPackage(PACKAGE) ?: findByNameHint(a)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                a.startActivity(launchIntent)
            } catch (_: Exception) {
                Toast.makeText(a, "Unable to open Cinema Pro 3.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(a, "Cinema Pro 3 is not installed on this device.", Toast.LENGTH_LONG).show()
        }
    }

    // Only ever runs after the exact lookup already failed, so this stays a rare, one-off scan.
    private fun findByNameHint(a: Activity): Intent? {
        val pm = a.packageManager
        val candidates = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        } catch (_: Exception) {
            return null
        }
        val match = candidates.firstOrNull { ri ->
            val pkg = ri.activityInfo?.packageName?.lowercase() ?: return@firstOrNull false
            val label = ri.loadLabel(pm)?.toString()?.lowercase() ?: ""
            NAME_HINTS.any { hint -> pkg.contains(hint.replace(" ", "")) || label.contains(hint) }
        } ?: return null
        return pm.getLaunchIntentForPackage(match.activityInfo?.packageName ?: return null)
    }
}
