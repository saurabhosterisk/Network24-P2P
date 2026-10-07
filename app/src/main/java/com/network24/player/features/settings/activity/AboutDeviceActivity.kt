package com.network24.player.features.settings.activity

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.network24.player.BuildConfig
import com.network24.player.R
import com.network24.player.core.base.BaseActivity

/**
 * Settings > About / Device Info: app version plus device and Android
 * details, laid out like the other Settings sub-screens.
 */
class AboutDeviceActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // built in code in the app's look
        kit = SettingsKit(this)
        val page = kit.page("About this device", "The app, this device and its system")
        val platform = platformName()
        page.hero.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.app_mark); adjustViewBounds = true }, LinearLayout.LayoutParams(kit.dp(64), kit.dp(64)))
        page.hero.addView(kit.text(getString(R.string.app_name), 22f, kit.textMain, 800).apply { setPadding(0, kit.dp(16), 0, 0) })
        page.hero.addView(kit.text("Version ${BuildConfig.VERSION_NAME}  ·  Build ${BuildConfig.VERSION_CODE}", 13f, kit.textSub, 600).apply { setPadding(0, kit.dp(6), 0, 0) })
        page.hero.addView(kit.text(platform.uppercase(), 11f, kit.accentSoft, 800).apply { letterSpacing = 0.1f; setPadding(kit.dp(10), kit.dp(5), kit.dp(10), kit.dp(5)); background = kit.shape(0x267C5CFF, 8f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = kit.dp(14) })
        page.hero.addView(kit.spacer())
        page.hero.addView(kit.text("Share these details with support when something does not work on this device.", 12f, kit.textSub, 500, lines = 3).apply { setLineSpacing(0f, 1.15f) })
        setContentView(setupGlobalRightDrawer(page.root, page.menu))

        val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" }
        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.orEmpty().ifBlank { "Not available" }
        } else {
            "Not available"
        }

        val sections = page.content
        addSection(
            sections, "DEVICE", listOf(
                "Platform" to platform,
                "Manufacturer" to manufacturer.replaceFirstChar { it.uppercase() },
                "Device model" to Build.MODEL.orEmpty().ifBlank { "Unknown" },
                "Device name" to deviceName()
            )
        )
        addSection(
            sections, "OPERATING SYSTEM", listOf(
                "Android version" to Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
                "Android SDK version" to Build.VERSION.SDK_INT.toString(),
                "Security patch level" to securityPatch
            )
        )
        addSection(
            sections, "APPLICATION", listOf(
                "App name" to getString(R.string.app_name),
                "App version" to BuildConfig.VERSION_NAME,
                "Build number" to BuildConfig.VERSION_CODE.toString()
            )
        )
    }

    private fun deviceName(): String =
        Build.DEVICE.orEmpty()
            .ifBlank { Build.PRODUCT.orEmpty() }
            .ifBlank { "Unknown" }

    private fun platformName(): String {
        val isTvDevice = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val model = Build.MODEL.orEmpty()
        val isAmazon = Build.MANUFACTURER.orEmpty().equals("Amazon", ignoreCase = true)
        val isFireTvModel = model.startsWith("AFT", ignoreCase = true) ||
            deviceName().startsWith("AFT", ignoreCase = true)
        return when {
            isAmazon && (isTvDevice || isFireTvModel) -> "Fire TV / Fire OS"
            isTvDevice -> "Android TV"
            else -> "Android Mobile"
        }
    }

    private lateinit var kit: SettingsKit

    /** Section label, then a glass card of label / value rows (the card takes the remote's focus so the page scrolls). */
    private fun addSection(parent: LinearLayout, title: String, rows: List<Pair<String, String>>) {
        parent.addView(kit.label(title))
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(kit.dp(18), kit.dp(4), kit.dp(18), kit.dp(4)); background = kit.shape(kit.surface, 14f, kit.line) }
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(kit.line) }, LinearLayout.LayoutParams(-1, 1))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, kit.dp(12), 0, kit.dp(12)) }
            row.addView(kit.text(label, 14f, kit.textSub, 600), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(kit.text(value, 14f, kit.textMain, 700))
            card.addView(row)
        }
        kit.focusable(card, 14f, 1.01f)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2))
        if (parent.childCount == 2) card.postDelayed({ card.requestFocus() }, 150)
    }
}
