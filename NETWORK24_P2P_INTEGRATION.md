# Network24 P2P Android Integration

The first Android integration layer is now present under
`app/src/main/java/com/network24/player/core/p2p/`.

## Current deployment

- WebSocket: `wss://p2p.web24.live/ws`
- Protocol: version `1`
- P2P starts only for an already logged-in IPTV account and receives a
  server-issued short-lived client token.
- A public coturn relay is required for peers on networks that cannot form a
  direct ICE path. STUN alone is not sufficient for mobile-carrier NAT. The
  Android client supports authenticated UDP/TCP TURN and should be given a
  `turns:` endpoint too when TLS TURN is enabled. See
  `deploy/coturn/network24.conf.example`; inject the auth secret at deploy
  time and never commit it.

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

The live source used by this app is HLS: every live call site constructs an
Xtream-style `.m3u8` URL. `PlayerManager` installs `Network24HybridDataSource`
in the singleton Media3 player's actual request path. Manifests and encryption
keys remain HTTP-authoritative. Each media request uses this path:

```
Media3 -> complete local cache -> WebRTC peer (750 ms default) -> original HTTP
```

The cache accepts known- and unknown-content-length HLS media bodies only after
complete EOF/range validation. Keys are scoped by IPTV origin, logical stream,
credential-free segment identity, byte offset, and byte length. IPTV usernames,
passwords, signed URLs, and raw segment URLs are never sent to peers or logged.

DataChannel protocol v2 uses small JSON control frames (`segment_have`,
`segment_request`, `segment_meta`, `segment_complete`, `segment_unavailable`,
`segment_cancel`, `segment_ack`) and 16 KiB binary media frames. The receiver
requires exact stream/request/key, total byte count, complete chunk set, and
SHA-256 before making bytes available to Media3. Upload queues are bounded,
respect `bufferedAmount`, and are counted successful only after Media3 consumes
the complete peer body and sends an acknowledgement.

Channel switches increment a generation, cancel pending requests/uploads, drop
old PeerConnections, leave the old room, and join a credential-free origin +
channel room. Late frames cannot complete a new stream request. A first viewer
does not wait for P2P; HTTP opens immediately when no DataChannel is ready.

The app now owns one account-authenticated `Network24P2pSession`, joins the exact
`LiveChannel.stream_id` room when a live channel is played, and exposes its
bounded peer fetcher to the hybrid factory. The normal player still uses the
existing HTTP factory while the server feature flag is off; an APK update
cannot silently change playback behavior.

## Validation

Validated locally with JDK 17 / Android API 35:

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes.
- 12 focused JVM tests cover credential-independent keys, exact ranges,
  unknown-length completion, binary framing, checksum/incomplete rejection,
  native ICE input validation, peer-first selection, timeout fallback, and peer recovery.
- Backend tests include a real localhost WebSocket integration test proving
  both room members refresh, canonical ICE is routed, disconnect is removed,
  and a replacement peer is discovered.
- `N24-P2P` Logcat records session/room, discovery, SDP/ICE/DataChannel state,
  selected `host`/`srflx`/`relay` path, peer/HTTP media source, bytes, duration,
  fallback reason, upload acknowledgement, and final session counters.

Physical two-phone media and mobile-CGNAT/TURN acceptance remain mandatory
before production rollout; no Android device is attached to this build host.

## Signed release publication

On 2026-08-17, `:app:assembleRelease` completed successfully and APK v1/v2
signature verification passed. The published artifact at
`https://p2p.web24.live/app/N.apk` is byte-for-byte identical to the local
release APK (SHA-256:
`a2b6eb2b7f8026469fdacfa214441b155411bf51a0ef849a6cedfd188c960812`). The
previous published APK is recoverable at
`/opt/network24-p2p/backups/apk-before-release-20260817152320/N.apk`.
