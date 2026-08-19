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
✅ Sustained relay P2P media transfer baseline with bounded HTTP fallback
✅ Stale segment advertisements removed on unavailable/timeout; blind misses suppressed
✅ Legacy TV install compatibility (API 21+) with HTTP-only fallback below API 23

## Pending

⏳ Wi-Fi/mobile and mobile/mobile network-matrix validation
⏳ Slow cellular relay transfer and ICE recovery validation on an available mobile path

## Current Debug Target

The reported direct-srflx 40-second failure is fixed by selecting TURN relay
transport whenever runtime TURN credentials are available. A separate
signaling-reconnect path now preserves healthy WebRTC peers, replaces stale
closed peers, gives slow mobile relay transfers longer bounded deadlines, and
keeps each client limited to a ranked set of four peers instead of connecting
to every customer in the room.

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
- The Wi-Fi/mobile matrix selected relay pairs, but the current cellular path
  later produced transient ICE disconnect/failure during a slow transfer. The
  latest client build no longer drops all healthy peers merely because the
  signaling socket reconnects, but this topology is not yet a clean sustained
  P2P pass.
- The mobile/mobile matrix could not be exercised: the approved TANK device's
  cellular network had no usable route or DNS, while the RZCT cellular path was
  reachable. This is an external device/carrier blocker, not Android P2P
  evidence.

## Next Steps

1. Repeat Wi-Fi/mobile on a stable cellular path and verify a completed
   `source=P2P ... transport=relay` segment after the longer transfer deadline
2. Repeat mobile/mobile when both approved devices have validated cellular
   internet
3. Monitor relay allocation capacity and fallback rates in production

## Server Agent execution update (2026-08-19)

The production TURN deployment is active and locally allocation-tested. The
forced-relay Android capture confirmed runtime TURN delivery, selected relay
transport on both devices, and sustained relay P2P media for more than 10
minutes. See `ai/ANDROID_LOG.md` and `ai/SERVER_LOG.md` for redacted evidence.
