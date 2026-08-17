# Network24 P2P Android Integration

The first Android integration layer is now present under
`app/src/main/java/com/network24/player/core/p2p/`.

## Current deployment

- WebSocket: `wss://p2p.web24.live/ws`
- Protocol: version `1`
- P2P starts only for an already logged-in IPTV account and receives a
  server-issued short-lived client token.

## Safety contract

The P2P layer carries signaling metadata only. It must never proxy media
through the signaling server. The existing Media3 HTTP/CDN data source remains
authoritative and must continue to work if P2P is disabled, unavailable, slow,
or rejected.

`Network24SignalingClient` provides:

- WSS connection with bounded reconnect backoff.
- Signed token authentication without logging or persisting the token.
- Registration with device/app/protocol metadata.
- Stream join/leave and same-stream peer discovery requests.
- Authenticated offer/answer/ICE forwarding.
- Heartbeats and malformed/oversized message rejection.

## App wiring

The app’s existing IPTV username/password login is the source of identity. The
broker validates those credentials against the configured IPTV origin and then
returns a short-lived Network24 client token. The signing secret is never in
the APK. Credentials must only be sent to the HTTPS broker and are never
logged or persisted by the P2P layer.

```kotlin
val client = Network24SignalingClient(
    config = Network24P2pConfig(enabled = serverFeatureFlag),
    registration = Network24DeviceRegistration(
        deviceId = stableDeviceId,
        deviceType = "ANDROID",
        appVersion = BuildConfig.VERSION_NAME,
        region = region,
        country = country
    ),
    tokenProvider = Network24AccountTokenProvider(appContext, preferences),
    listener = listener
)
```

Firebase Anonymous Auth is not used for P2P. The Android provider exchanges
the existing IPTV account credentials with `/api/v1/client/token`; the API
validates them against its allowlisted IPTV origin and issues a five-minute
Network24 token.

`Network24WebRtcPeerManager` and the bounded `Network24SegmentCache` plus
`Network24HybridDataSource` are now present behind the same opt-in boundary.
The hybrid source checks cache, gives a peer at most 1.5 seconds by default,
and then immediately uses the existing HTTP source. Playlist/manifests are
excluded from P2P attempts; bounded media responses are cached for the next
same-stream peer. Playback never waits indefinitely for a peer.

The app now owns one account-authenticated `Network24P2pSession`, joins the exact
`LiveChannel.stream_id` room when a live channel is played, and exposes its
bounded peer fetcher to the hybrid factory. The normal player still uses the
existing HTTP factory while the server feature flag is off; an APK update
cannot silently change playback behavior.

## Validation

Baseline and integrated `:app:assembleDebug` builds pass on the server with
JDK 17 and Android API 35.
