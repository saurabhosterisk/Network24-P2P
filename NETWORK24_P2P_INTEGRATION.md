# Network24 P2P Android Integration

The first Android integration layer is now present under
`app/src/main/java/com/network24/player/core/p2p/`.

## Current deployment

- WebSocket: `wss://p2p.web24.live/ws`
- Protocol: version `1`
- P2P is disabled by default until the app receives a server-issued feature
  flag and a short-lived client token.

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

The app’s trusted authentication/backend layer must provide a short-lived
Network24 client token. Do not derive it from an IPTV password and do not embed
the server signing secret in the APK.

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
    tokenProvider = Network24TokenProvider { tokenStore.currentShortLivedToken() },
    listener = listener
)
```

The next implementation phase adds the WebRTC peer manager and segment cache
behind this client. It must use a short P2P deadline and immediately fall back
to the existing HTTP source; playback must never wait indefinitely for a peer.

## Validation

Baseline `:app:assembleDebug` and the feature-layer `:app:compileDebugKotlin`
both pass on the server with JDK 17 and Android API 35.
