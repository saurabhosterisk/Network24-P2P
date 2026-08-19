# Network24-P2P Current Status

Last Updated: 2026-08-19

## Architecture

Android client + WebRTC P2P media sharing + signaling server.

## Working

✅ Peer registration
✅ Signaling exchange
✅ SDP negotiation
✅ DataChannel connection
✅ Segment cache hit flow

## Pending

❌ Stable media transport
❌ TURN relay integration
❌ Long duration P2P verification

## Current Debug Target

Find why ICE connection becomes unstable after approximately 40 seconds and switches to HTTP fallback.

## Server investigation result (2026-08-19)

The approved coturn shared-secret/TLS deployment is now active and locally
allocation-tested. The token broker returned three short-lived TURN servers to
the Android client (`event=ice_servers count=4 turn=3`). See
`ai/SERVER_LOG.md` for the redacted deployment evidence.

Validation completed:

- Android `:app:testDebugUnitTest :app:assembleDebug`: passed, 12 tests.
- Backend `npm run typecheck`: passed.
- Backend `npm test`: 5 suites passed; signaling integration suite failed and
  needs follow-up before claiming the backend suite is green.
- Both approved Android devices received the debug APK and opened the same
  `USA | ACCUWEATHER` channel.
- A fresh two-device capture ran for more than 10 minutes. It recorded
  verified P2P media bytes, but every selected transport was direct `srflx`;
  no relay candidate/pair or TURN media bytes were observed. HTTP fallback
  remained bounded.

## Next Steps

1. Complete a true forced-relay test that selects `transport=relay`
2. Repeat Wi-Fi/mobile and mobile/mobile captures with relay evidence
3. Verify sustained `source=P2P transport=relay` bytes for 10 minutes
4. Resolve any remaining direct-path fallback/ICE failures

## Server Agent execution update (2026-08-19)

The production TURN deployment is active and locally allocation-tested. The
Android capture confirmed runtime TURN delivery but selected direct `srflx`
transport instead of relay, so the required forced-relay proof remains open.
See `ai/ANDROID_LOG.md` and `ai/SERVER_LOG.md` for redacted evidence.
