# Current Task

## Problem
WebRTC P2P streaming media transport is unstable.

## Current Status

Completed:
- Signaling working
- SDP exchange working
- DataChannel OPEN on both devices
- Segment cache hit requests received

Issue:
- Actual media source=P2P is not stable
- Around 40 seconds later ICE fails
- HTTP fallback starts

## Android Agent

Check:
- PeerConnection configuration
- ICE server configuration
- TURN credential loading
- Media transport flow
- Segment source selection

Update:
ai/ANDROID_LOG.md

## Server Agent

Check:
- Signaling server status
- STUN/TURN configuration
- coturn relay setup
- WebRTC server logs
- Network/firewall ports

Update:
ai/SERVER_LOG.md

## Android Agent should now check

Inspect these exact files:

- `app/src/main/java/com/network24/player/core/p2p/Network24AccountTokenProvider.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24P2pConfig.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24SignalingClient.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24WebRtcPeerManager.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24P2pSession.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24HybridDataSource.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24SegmentCache.kt`
- `app/src/main/java/com/network24/player/core/p2p/Network24PeerProtocol.kt`

Capture filtered `N24-P2P` Logcat from both devices for at least 10 minutes:

```text
adb logcat -v threadtime -s N24-P2P:V WebRTC:V *:S
```

The capture must include `event=ice_servers`, local/remote SDP completion,
every candidate type (`host`, `srflx`, `prflx`, `relay`), ICE state changes,
selected candidate-pair transport, DataChannel OPEN/CLOSED, segment request,
segment completion, `source=P2P` bytes, `source=HTTP` fallback reason, and
session counters. Redact account names, URLs containing credentials, tokens,
and device identifiers before sharing.

Required testing steps:

1. Wi-Fi to Wi-Fi for 10 minutes; confirm the selected pair and continuous
   P2P bytes.
2. Wi-Fi to mobile data for 10 minutes; record whether the pair is direct or
   relay and whether ICE changes near 40 seconds.
3. Mobile data to mobile data for 10 minutes; require a relay candidate once
   TURN is provisioned.
4. Force TURN and verify `event=ice_servers` reports TURN URLs, the selected
   candidate type is `relay`, DataChannel remains open for 10 minutes, and
   P2P bytes increase.
5. Disable network briefly and verify bounded HTTP fallback, recovery, and no
   stale-room or stale-generation segment delivery.
6. Run the focused unit tests and debug build:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Do not report the issue as fixed without both-device logs showing a stable
selected pair and sustained P2P media bytes.

## Goal
Stable P2P media transfer for long duration with proper fallback.
