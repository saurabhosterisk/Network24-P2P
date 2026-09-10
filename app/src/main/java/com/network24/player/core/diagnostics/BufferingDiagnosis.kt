package com.network24.player.core.diagnostics

enum class BufferingCause {
    CLIENT_NETWORK,
    DEVICE_RESOURCES,
    SERVER_PROVIDER,
    // Exactly one short pause with no other signal (network, device, server
    // all checked out fine). Kept separate from SERVER_PROVIDER so a single
    // harmless hiccup - e.g. a channel switch settling - doesn't confidently
    // blame the server with no real evidence for it.
    MINOR_HICCUP,
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
    // Cumulative for the current channel, not just the live test window -
    // this is the strongest available evidence for a WiFi/network hiccup
    // that already happened and self-recovered before RUN AUTO CHECK was
    // pressed, which a live RSSI/throughput snapshot alone would miss.
    val behindLiveWindowCount: Int,
    val probe: StreamProbeResult,
    val device: DeviceHealthSnapshot
)

data class BufferingDiagnosis(
    val cause: BufferingCause,
    val title: String,
    val confidence: String,
    // Plain-language explanation a non-technical customer can read and
    // understand on its own - no HTTP codes, dBm, or ms fragments. Written
    // as full sentences, not a jargon dump.
    val summary: String,
    // Supporting detail, also in full plain sentences (numbers are folded
    // into the sentence, not left as bare technical fragments) so a support
    // agent can read these out loud to a customer as-is.
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
        val offline = input.device.networkType == "Offline" || !input.device.internetValidated
        // The player fell behind the live edge and had to reload - this is
        // WiFi/network packet loss on this device, not the server, even
        // when RSSI/link speed look fine in a snapshot taken after the fact.
        val fellBehindLiveEdge = input.behindLiveWindowCount > 0

        // ---------- 1. Device resources ----------
        if (badDevice) {
            if (input.device.availableRamMb in 1..299) {
                evidence += "This device only has ${input.device.availableRamMb} MB of free memory right now (out of ${input.device.totalRamMb} MB total) - that isn't enough to play video smoothly."
            }
            if (input.device.freeStorageMb in 1..399) {
                evidence += "This device only has ${input.device.freeStorageMb} MB of free storage left."
            }
            return BufferingDiagnosis(
                BufferingCause.DEVICE_RESOURCES,
                "This device is low on memory/storage",
                "High",
                "Your internet connection is fine, but this device itself doesn't have enough free memory or storage to play video smoothly right now - that's what's causing the buffering, not the channel or the network.",
                evidence,
                "Close any other open apps on this device, restart it, and free up some storage. Then try the channel again."
            )
        }

        // ---------- 2. Client network ----------
        if (offline || weakWifi || measuredTooLow || fellBehindLiveEdge) {
            if (offline) {
                evidence += "This device has no working internet connection right now."
            }
            if (weakWifi) {
                input.device.wifiRssiDbm?.let {
                    evidence += "The WiFi signal at this device is weak (signal reading: $it dBm). Anything weaker than -75 dBm struggles to stream video smoothly."
                }
                input.device.wifiLinkSpeedMbps?.let {
                    evidence += "The WiFi connection is only linking at $it Mbps, which is slow."
                }
            }
            if (measuredTooLow) {
                evidence += "This device is currently only receiving about ${"%.1f".format(input.measuredMbps)} Mbps, but this channel's video quality needs at least ${"%.1f".format(input.requiredMbps)} Mbps to play without pausing."
            }
            if (fellBehindLiveEdge) {
                evidence += "The channel had to reload itself ${input.behindLiveWindowCount} time(s) during this viewing session because video data wasn't arriving fast enough to keep up with the live broadcast - a clear sign of a bumpy internet connection, even if the signal looks fine right now."
                if (!weakWifi) {
                    input.device.wifiRssiDbm?.let {
                        evidence += "For reference, the WiFi signal reads $it dBm at this moment - short bursts of dropped data can still happen at this strength, they just don't show up in a signal-bar snapshot taken afterwards."
                    }
                }
            }
            if (input.rebufferCount > 0) {
                evidence += "The channel also paused to reload ${input.rebufferCount} time(s) during this 6-second test."
            }
            val summary = when {
                offline -> "This device isn't connected to the internet at all right now, so the channel has nothing to download video from."
                fellBehindLiveEdge && !weakWifi && !measuredTooLow ->
                    "Your internet connection lost some data for a few seconds. Even though the WiFi signal looks okay, the video download briefly couldn't keep up with the live broadcast, so the channel had to pause and reload - that's the buffering you saw. This is your home network, not the channel's server."
                else ->
                    "The internet connection reaching this device is too weak or unstable to keep up with this channel's video quality. That's what's causing the buffering - the channel's server and this device are both fine."
            }
            return BufferingDiagnosis(
                BufferingCause.CLIENT_NETWORK,
                "Your internet connection is the cause",
                if (offline || measuredTooLow || fellBehindLiveEdge) "High" else "Medium",
                summary,
                evidence,
                "Move this device closer to the WiFi router (or connect it with a cable), and turn off other devices/downloads sharing the same WiFi. A lower-bitrate/SD channel also needs less speed and may play more smoothly on this connection."
            )
        }

        // ---------- 3. Server / provider (real evidence only) ----------
        if (playbackError || serverResponseBad || slowStreamResponse) {
            if (playbackError) {
                evidence += "The video player itself reported a playback error while trying to play this channel."
            }
            if (serverResponseBad) {
                evidence += input.probe.responseCode?.let {
                    "The channel's server replied with an error (code $it) just now when we checked it directly."
                } ?: "The channel's server did not respond at all just now when we checked it directly."
            }
            if (slowStreamResponse) {
                input.probe.timeToFirstByteMs?.let {
                    evidence += "The channel's server took ${it} ms to start sending data - a healthy server usually starts in under a second."
                }
            }
            if (input.rebufferCount > 0) {
                evidence += "The channel also paused to reload ${input.rebufferCount} time(s) during this test."
            }
            return BufferingDiagnosis(
                BufferingCause.SERVER_PROVIDER,
                "The channel's source server is the cause",
                "High",
                "Your internet connection and this device both look fine, but the channel's own server is responding slowly or with errors right now. This can't be fixed from this device - it needs to be reported to the channel provider.",
                evidence,
                "Try a different channel to confirm the internet is otherwise fine. If this specific channel keeps failing, report it to the provider along with the time it happened."
            )
        }

        // ---------- 4. A single unexplained pause - not enough to blame anyone ----------
        if (input.rebufferCount > 0) {
            evidence += "The channel paused and reloaded ${input.rebufferCount} time(s) during this 6-second test (about ${input.bufferingMs / 1000}s total), but nothing else looked wrong - internet speed, WiFi signal, this device, and the channel's server all checked out normally."
            return BufferingDiagnosis(
                BufferingCause.MINOR_HICCUP,
                "One short pause - no clear cause found",
                "Low",
                "There was one brief pause just now, but everything we can check right now - your internet, this device, and the channel's server - looks healthy. A short, one-off hiccup like this can happen occasionally even on a good connection and usually isn't a sign of an ongoing problem.",
                evidence,
                "If this channel keeps buffering repeatedly (not just once), run this check again while it's actively happening - that will catch the real cause instead of a one-off blip."
            )
        }

        // ---------- 5. Test never actually played ----------
        if (!input.playbackStarted) {
            return BufferingDiagnosis(
                BufferingCause.INCONCLUSIVE,
                "Couldn't test - playback never started",
                "Low",
                "The channel never actually started playing during this test, so nothing could be measured.",
                listOf("No stable playback sample was captured during the test."),
                "Run the check again after selecting a channel and letting it play for a few seconds first."
            )
        }

        // ---------- 6. Everything looked fine ----------
        return BufferingDiagnosis(
            BufferingCause.NO_ISSUE,
            "No buffering issue right now",
            "High",
            "The channel played smoothly for the entire test - your internet connection, this device, and the channel's server all look healthy right now.",
            listOf(
                "No pauses or reloads happened during the test.",
                "Internet speed and the channel server's response both looked normal."
            ),
            "If buffering happens again, run this check again while it's actively happening - that gives the most accurate result."
        )
    }
}
