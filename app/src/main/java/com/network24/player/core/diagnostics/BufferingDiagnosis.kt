package com.network24.player.core.diagnostics

enum class BufferingCause {
    CLIENT_NETWORK,
    DEVICE_RESOURCES,
    SERVER_PROVIDER,
    PLAYBACK,
    NO_ISSUE,
    INCONCLUSIVE
}

data class BufferingDiagnosisInput(
    val playbackStarted: Boolean,
    val rebufferCount: Int,
    val bufferingMs: Long,
    val measuredMbps: Double,
    val requiredMbps: Float,
    val errorType: String,
    val probe: StreamProbeResult,
    val device: DeviceHealthSnapshot
)

data class BufferingDiagnosis(
    val cause: BufferingCause,
    val title: String,
    val confidence: String,
    val summary: String,
    val evidence: List<String>,
    val action: String
)

object BufferingDiagnosisEngine {
    fun evaluate(input: BufferingDiagnosisInput): BufferingDiagnosis {
        val evidence = mutableListOf<String>()
        val weakWifi = input.device.networkType == "WiFi" &&
            (input.device.wifiRssiDbm?.let { it <= -75 } == true ||
                input.device.wifiLinkSpeedMbps?.let { it < 10 } == true)
        val measuredTooLow = input.requiredMbps > 0f &&
            input.measuredMbps > 0.0 &&
            input.measuredMbps < input.requiredMbps * 1.15f
        val badDevice = input.device.availableRamMb in 1..299 || input.device.freeStorageMb in 1..399
        val serverResponseBad = input.probe.responseCode == null || input.probe.responseCode !in 200..399
        val slowStreamResponse = input.probe.timeToFirstByteMs?.let { it >= 2_500L } == true
        val playbackError = input.errorType == "SOURCE" || input.errorType == "UNKNOWN"

        if (badDevice) {
            if (input.device.availableRamMb in 1..299) evidence += "Available RAM is only ${input.device.availableRamMb} MB"
            if (input.device.freeStorageMb in 1..399) evidence += "Free storage is only ${input.device.freeStorageMb} MB"
            return BufferingDiagnosis(
                BufferingCause.DEVICE_RESOURCES,
                "Device resources are low",
                "High",
                "The device may be causing playback instability.",
                evidence,
                "Close other apps, restart the device, and free storage before testing again."
            )
        }

        if (input.device.networkType == "Offline" || !input.device.internetValidated || weakWifi || measuredTooLow) {
            if (input.device.networkType == "Offline" || !input.device.internetValidated) evidence += "Internet validation failed"
            if (weakWifi) {
                input.device.wifiRssiDbm?.let { evidence += "WiFi signal is weak (${it} dBm)" }
                input.device.wifiLinkSpeedMbps?.let { evidence += "WiFi link speed is ${it} Mbps" }
            }
            if (measuredTooLow) evidence += "Measured ${"%.2f".format(input.measuredMbps)} Mbps vs required ${"%.1f".format(input.requiredMbps)} Mbps"
            if (input.rebufferCount > 0) evidence += "${input.rebufferCount} rebuffer event(s) during the test"
            return BufferingDiagnosis(
                BufferingCause.CLIENT_NETWORK,
                "Client network is the likely cause",
                if (input.device.networkType == "Offline" || measuredTooLow) "High" else "Medium",
                "The stream is not receiving data consistently from this device/network.",
                evidence,
                "Test near the WiFi router, use Ethernet/5 GHz WiFi, stop other downloads, or try another network."
            )
        }

        if (playbackError || serverResponseBad || slowStreamResponse || input.rebufferCount > 0) {
            if (playbackError) evidence += "Player reported ${input.errorType} error"
            input.probe.responseCode?.let { evidence += "Stream endpoint HTTP $it" }
            input.probe.timeToFirstByteMs?.let { evidence += "First stream byte arrived in ${it} ms" }
            if (input.rebufferCount > 0) evidence += "${input.rebufferCount} rebuffer event(s), ${input.bufferingMs / 1000}s total"
            return BufferingDiagnosis(
                BufferingCause.SERVER_PROVIDER,
                "Server / provider side is likely",
                if (playbackError || serverResponseBad) "High" else "Medium",
                "This device network looks usable, but the stream endpoint is failing or delivering data slowly.",
                evidence,
                "Check the same stream on another network/device. If it also buffers, report the stream ID to the provider/server team."
            )
        }

        if (!input.playbackStarted) {
            return BufferingDiagnosis(
                BufferingCause.INCONCLUSIVE,
                "Test was inconclusive",
                "Low",
                "Playback did not start long enough to identify the root cause.",
                listOf("No stable playback sample was captured"),
                "Run the check again while the channel is actively buffering."
            )
        }

        return BufferingDiagnosis(
            BufferingCause.NO_ISSUE,
            "No active buffering issue detected",
            "High",
            "The stream played normally during the diagnostic window.",
            listOf("No rebuffer event", "Network and stream response look normal"),
            "If the customer still sees buffering, repeat the test exactly while the problem is happening."
        )
    }
}
