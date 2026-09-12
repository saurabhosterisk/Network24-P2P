package com.network24.player.features.player.ui.dialogs

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.media3.common.Player
import com.network24.player.core.diagnostics.DeviceHealthCollector
import com.network24.player.core.net.SpeedMonitor
import com.network24.player.databinding.DialogStreamInfoBinding
import com.network24.player.features.player.manager.PlayerManager
import java.util.Locale

class StreamInfoDialog : DialogFragment() {
    private var _binding: DialogStreamInfoBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 1000L)
        }
    }

    private var selectedSection = SECTION_OVERVIEW

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogStreamInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnOverview.setOnClickListener { selectSection(SECTION_OVERVIEW) }
        binding.btnStreamDetails.setOnClickListener { selectSection(SECTION_STREAM) }
        binding.btnNetworkDetails.setOnClickListener { selectSection(SECTION_NETWORK) }
        binding.btnDeviceDetails.setOnClickListener { selectSection(SECTION_DEVICE) }
        binding.btnEventsDetails.setOnClickListener { selectSection(SECTION_EVENTS) }
        showSummaryPanel()
        configureRemoteButtons()
        binding.btnOverview.post { binding.btnOverview.requestFocus() }
        refreshUi()
    }

    private fun configureRemoteButtons() {
        val buttons = listOf(
            binding.btnOverview,
            binding.btnStreamDetails,
            binding.btnNetworkDetails,
            binding.btnDeviceDetails,
            binding.btnEventsDetails,
            binding.btnClose
        )
        buttons.forEach { button ->
            button.backgroundTintList = null
            button.stateListAnimator = null
            // The drawable provides the focus indicator. Keep the button's
            // bounds stable so DPAD navigation never changes the layout.
            button.scaleX = 1f
            button.scaleY = 1f
            button.elevation = 0f
        }
    }

    private fun selectSection(section: String) {
        selectedSection = section
        showDetailsPanel()
        renderDetails()
    }

    private fun refreshUi() {
        val player = PlayerManager.getExoPlayerOrNull() ?: run {
            binding.tvHealthScore.text = "- / 100"
            binding.tvHealthLabel.text = "-"
            binding.tvRebufferCount.text = "-"
            binding.tvBufferingTime.text = "-"
            binding.tvDiagnosisCause.text = "Player is not ready"
            binding.tvDiagnosis.text = "Start playback to see live stream status."
            binding.tvDiagnosisEvidence.text = "Evidence: -"
            binding.tvDiagnosisAction.text = "Action: Select a live channel"
            renderDetails()
            return
        }

        val downloadMbps = SpeedMonitor.getMbps()
        val requiredMbps = getRequiredSpeedMbps(player.videoFormat?.height ?: 0)
        val rebufferCount = PlayerManager.getRebufferCount()
        val bufferingMs = PlayerManager.getTotalBufferingMsIncludingActive()
        val error = PlayerManager.getLastError()
        val score = calculateHealthScore(downloadMbps, requiredMbps, rebufferCount, error != null)
        binding.tvHealthScore.text = "$score / 100"
        binding.tvHealthLabel.text = when {
            score >= 85 -> "GOOD"
            score >= 65 -> "FAIR"
            else -> "POOR"
        }
        binding.tvRebufferCount.text = rebufferCount.toString()
        binding.tvBufferingTime.text = formatDuration(bufferingMs)

        binding.tvDiagnosisCause.text = "Live player status"
        binding.tvDiagnosis.text = buildLiveStatus(player, downloadMbps, requiredMbps, rebufferCount, bufferingMs, error != null)
        binding.tvDiagnosisEvidence.text = "Evidence: current playback metrics"
        binding.tvDiagnosisAction.text = "Action: Check the Network, Device, and Stream tabs above for details."
        renderDetails()
    }

    private fun renderDetails() {
        if (_binding == null) return
        binding.tvDetailSection.text = sectionTitle(selectedSection)
        renderDetailRows(buildDetails(selectedSection))
    }

    private fun showSummaryPanel() {
        if (_binding == null) return
        binding.cardSummary.visibility = View.VISIBLE
        binding.cardDetails.visibility = View.GONE
    }

    private fun showDetailsPanel() {
        if (_binding == null) return
        binding.cardSummary.visibility = View.GONE
        binding.cardDetails.visibility = View.VISIBLE
    }

    private data class DetailRow(val label: String, val value: String)

    private fun buildDetails(section: String): List<DetailRow> {
        val player = PlayerManager.getExoPlayerOrNull()
        val device = runCatching { DeviceHealthCollector.collect(requireContext()) }.getOrNull()
        val required = getRequiredSpeedMbps(player?.videoFormat?.height ?: 0)
        return buildList {
            when (section) {
                SECTION_STREAM -> {
                    row("Endpoint host", safeHost(PlayerManager.getCurrentUrlOrEmpty()))
                    row("Player state", playerState(player))
                    row("Buffer", "${player?.bufferedPercentage ?: 0}%  •  ${formatDuration(player?.totalBufferedDuration ?: 0L)}")
                    row("Resolution", player?.videoFormat?.let { "${it.width} x ${it.height}" } ?: "-")
                    row("Video codec", player?.videoFormat?.sampleMimeType ?: "-")
                    row("Video bitrate", formatBitrate(player?.videoFormat?.bitrate))
                    row("Audio codec", player?.audioFormat?.sampleMimeType ?: "-")
                    row("Last error", PlayerManager.getLastError()?.errorCodeName ?: "None")
                }
                SECTION_NETWORK -> {
                    row("Connection", device?.networkType ?: "-")
                    row("Internet validated", device?.internetValidated?.let { if (it) "Yes" else "No" } ?: "-")
                    row("Stream throughput", formatMbps(SpeedMonitor.getMbps()))
                    row("Required throughput", if (required > 0f) formatMbps(required.toDouble()) else "-")
                    row("WiFi signal", device?.wifiRssiDbm?.let { "$it dBm" } ?: "Not available")
                    row("WiFi link speed", device?.wifiLinkSpeedMbps?.let { "$it Mbps" } ?: "Not available")
                    row("Last connection setup", formatDuration(PlayerManager.getLastConnectSetupMs()))
                    row("Slowest connection setup", formatDuration(PlayerManager.getSlowestConnectSetupMs()))
                    row("Slow connections (>1.5s)", PlayerManager.getSlowConnectSetupCount().toString())
                    row("Interpretation", "Weak WiFi or throughput below required quality points to the client network. \"Connection setup\" is DNS+connect+TLS time before any data arrives - a high value here with a fast throughput points to the network path, not the WiFi link itself.")
                }
                SECTION_DEVICE -> {
                    row("Available RAM", "${device?.availableRamMb ?: 0} MB / ${device?.totalRamMb ?: 0} MB")
                    row("Free storage", "${device?.freeStorageMb ?: 0} MB")
                    if (device?.batteryPresent == true) {
                        row("Battery", device.batteryPercent?.let { "$it%" } ?: "-")
                        row("Battery temperature", device.batteryTemperatureC?.let { String.format(Locale.US, "%.1f C", it) } ?: "-")
                    } else {
                        row("Battery", "No battery (mains/USB-powered device)")
                    }
                    row("Interpretation", "Low RAM/storage can cause instability even when network speed is good.")
                }
                SECTION_EVENTS -> {
                    row("Playback started", if (PlayerManager.hasEverStartedPlayback()) "Yes" else "No")
                    row("Rebuffers", PlayerManager.getRebufferCount().toString())
                    row("Total buffering", formatDuration(PlayerManager.getTotalBufferingMsIncludingActive()))
                    row("Live-edge reloads", PlayerManager.getBehindLiveWindowCount().toString())
                    row("Error category", PlayerManager.getStreamErrorType().name)
                    row("Error details", PlayerManager.getLastErrorMessage().ifBlank { "None" })
                    row("Recovery", "Automatic recovery is ${if (PlayerManager.getLastError() == null) "not active" else "active or recently triggered"}")
                }
                else -> {
                    row("Current player", playerState(player))
                    row("Current network", device?.networkType ?: "-")
                    row("Current rebuffer count", PlayerManager.getRebufferCount().toString())
                    row("Current buffering time", formatDuration(PlayerManager.getTotalBufferingMsIncludingActive()))
                }
            }
        }
    }

    private fun MutableList<DetailRow>.row(label: String, value: String) {
        add(DetailRow(label, value))
    }

    private fun renderDetailRows(rows: List<DetailRow>) {
        val container = binding.tvDetailBody
        container.removeAllViews()
        val density = resources.displayMetrics.density
        rows.forEachIndexed { index, row ->
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                setPadding(0, if (index == 0) 0 else (4 * density).toInt(), 0, (4 * density).toInt())
            }
            val labelView = TextView(requireContext()).apply {
                text = row.label
                setTextColor(android.graphics.Color.rgb(139, 152, 172))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val valueView = TextView(requireContext()).apply {
                text = row.value
                setTextColor(android.graphics.Color.rgb(231, 236, 245))
                textSize = 14f
                setLineSpacing(0f, 1.05f)
            }
            rowLayout.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.36f))
            rowLayout.addView(valueView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.64f))
            container.addView(rowLayout, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun calculateHealthScore(downloadMbps: Double, requiredMbps: Float, rebufferCount: Int, hasError: Boolean): Int {
        var score = 100
        val bufferingMs = PlayerManager.getTotalBufferingMsIncludingActive()
        if (rebufferCount > 0) score -= rebufferCount * 10
        if (bufferingMs > 30_000L) score -= 25 else if (bufferingMs > 10_000L) score -= 10
        if (requiredMbps > 0f && downloadMbps > 0.0 && downloadMbps < requiredMbps) score -= 20
        if (hasError) score -= 30
        return score.coerceIn(0, 100)
    }

    private fun buildLiveStatus(
        player: Player,
        downloadMbps: Double,
        requiredMbps: Float,
        rebufferCount: Int,
        bufferingMs: Long,
        hasError: Boolean
    ): String {
        if (hasError) return "Playback error detected; automatic recovery may be running."
        if (rebufferCount > 0) return "$rebufferCount buffering event(s), ${formatDuration(bufferingMs)} total."
        if (player.playbackState == Player.STATE_BUFFERING && !PlayerManager.hasEverStartedPlayback()) return "Stream is loading for the first time."
        if (requiredMbps > 0f && downloadMbps > 0.0 && downloadMbps < requiredMbps) return "Measured throughput may be low for this quality."
        return "No active buffering issue detected in current player metrics."
    }

    private fun playerState(player: Player?): String = when (player?.playbackState) {
        Player.STATE_BUFFERING -> if (player.isPlaying) "Playing (buffer refresh)" else "Buffering"
        Player.STATE_READY -> if (player.isPlaying) "Playing" else "Paused"
        Player.STATE_ENDED -> "Ended"
        Player.STATE_IDLE -> "Idle"
        else -> "Unknown"
    }

    private fun getRequiredSpeedMbps(height: Int): Float = when {
        height >= 2160 -> 30f
        height >= 1440 -> 18f
        height >= 1080 -> 12f
        height >= 720 -> 6f
        height > 0 -> 3f
        else -> 0f
    }

    private fun formatBitrate(value: Int?): String = when {
        value == null || value <= 0 -> "-"
        value >= 1_000_000 -> String.format(Locale.US, "%.2f Mbps", value / 1_000_000.0)
        else -> String.format(Locale.US, "%.0f kbps", value / 1000.0)
    }

    private fun formatMbps(value: Double): String = if (value > 0.0) String.format(Locale.US, "%.2f Mbps", value) else "-"

    private fun formatDuration(ms: Long): String = if (ms <= 0L) "0s" else {
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        when {
            hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun safeHost(url: String): String = runCatching { android.net.Uri.parse(url).host ?: "-" }.getOrDefault("-")

    private fun sectionTitle(section: String): String = when (section) {
        SECTION_STREAM -> "STREAM DETAILS"
        SECTION_NETWORK -> "NETWORK DETAILS"
        SECTION_DEVICE -> "DEVICE DETAILS"
        SECTION_EVENTS -> "PLAYBACK EVENTS"
        else -> "DIAGNOSTIC OVERVIEW"
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95f).toInt(),
            (resources.displayMetrics.heightPixels * 0.92f).toInt()
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroyView() {
        handler.removeCallbacks(ticker)
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val SECTION_OVERVIEW = "overview"
        private const val SECTION_STREAM = "stream"
        private const val SECTION_NETWORK = "network"
        private const val SECTION_DEVICE = "device"
        private const val SECTION_EVENTS = "events"

        fun newInstance(): StreamInfoDialog = StreamInfoDialog()
    }
}
