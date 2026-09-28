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
        val contentRoot = layoutInflater.inflate(R.layout.activity_about_device, null, false) as ViewGroup
        setContentView(setupGlobalRightDrawer(contentRoot, contentRoot.findViewById(R.id.btnMore)))

        findViewById<View>(R.id.aboutBack).setOnClickListener { finish() }

        val platform = platformName()
        findViewById<TextView>(R.id.aboutAppName).text = getString(R.string.app_name)
        findViewById<TextView>(R.id.aboutAppVersion).text =
            "Version ${BuildConfig.VERSION_NAME}  •  Build ${BuildConfig.VERSION_CODE}"
        findViewById<TextView>(R.id.aboutPlatformChip).apply {
            text = platform
            val accent = getColor(R.color.primary_light)
            background.mutate().setTint(
                android.graphics.Color.argb(
                    0x33,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent)
                )
            )
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Unknown" }
        val securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.orEmpty().ifBlank { "Not available" }
        } else {
            "Not available"
        }

        val sections = findViewById<LinearLayout>(R.id.aboutSections)
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

    /** Section header followed by a card of label / value rows. */
    private fun addSection(parent: LinearLayout, title: String, rows: List<Pair<String, String>>) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        parent.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(getColor(R.color.text_hint))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8)
                topMargin = dp(28)
            }
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_card)
            setPadding(dp(20), dp(6), dp(20), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                card.addView(View(this).apply {
                    setBackgroundColor(getColor(R.color.text_hint))
                    alpha = 0.15f
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 14f
                gravity = android.view.Gravity.END
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(row)
        }
        parent.addView(card)
    }
}
