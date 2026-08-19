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
✅ Runtime TURN delivery and relay-only ICE policy
✅ Sustained relay P2P media transfer with bounded HTTP fallback

## Pending

⏳ Wi-Fi/mobile and mobile/mobile network-matrix validation

## Current Debug Target

The reported direct-srflx 40-second failure is fixed by selecting TURN relay
transport whenever runtime TURN credentials are available.

## Server investigation result (2026-08-19)

The approved coturn shared-secret/TLS deployment is now active and locally
allocation-tested. The token broker returned three short-lived TURN servers to
the Android client (`event=ice_servers count=4 turn=3`). See
`ai/SERVER_LOG.md` for the redacted deployment evidence.

Validation completed:

- Android `:app:testDebugUnitTest :app:assembleDebug`: passed.
- Backend `npm run typecheck`: passed.
- Backend `npm test`: 5 suites passed; signaling integration suite failed and
  needs follow-up before claiming the backend suite is green.
- Both approved Android devices received the debug APK and opened the same
  `USA | ACCUWEATHER` channel.
- A fresh two-device capture ran for more than 10 minutes. It recorded
  verified P2P media bytes, but the earlier build selected direct `srflx`;
  no relay candidate/pair or TURN media bytes were observed. HTTP fallback
  remained bounded.
- The forced-relay build selected `transport=relay` on both devices and ran
  for more than 10 minutes with 40 verified relay P2P segments and matching
  upload acknowledgements. No ICE `FAILED`, `DISCONNECTED`, or `CLOSED` event
  occurred in that relay window.

## Next Steps

1. Run the Wi-Fi/mobile and mobile/mobile validation matrix
2. Monitor relay allocation capacity and fallback rates in production

## Server Agent execution update (2026-08-19)

The production TURN deployment is active and locally allocation-tested. The
forced-relay Android capture confirmed runtime TURN delivery, selected relay
transport on both devices, and sustained relay P2P media for more than 10
minutes. See `ai/ANDROID_LOG.md` and `ai/SERVER_LOG.md` for redacted evidence.
