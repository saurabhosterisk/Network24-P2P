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

Primary deployment finding: coturn is running, but Network24 TURN issuance is
disabled (`NETWORK24_TURN_ENABLED=false`) and the server has no configured
TURN REST secret or TLS listener. Android therefore receives no TURN servers
and uses STUN-only ICE. This is consistent with a direct path opening and
later failing under carrier/symmetric NAT, but a two-device ICE candidate-pair
Logcat capture is still required for final causal proof.

No server configuration was changed because the approved TURN secret,
certificate, relay range, and firewall policy are missing. See
`ai/SERVER_LOG.md` for evidence and the safe deployment sequence.

Validation completed:

- Android `:app:testDebugUnitTest :app:assembleDebug`: passed, 12 tests.
- Backend `npm run typecheck`: passed.
- Backend `npm test`: 5 suites passed; signaling integration suite failed and
  needs follow-up before claiming the backend suite is green.

## Next Steps

1. Verify TURN server and credentials
2. Collect ICE candidate logs
3. Verify media bytes coming from peer
4. Run long duration tests

## Server Agent execution update (2026-08-19)

The production TURN deployment is now active and locally allocation-tested.
The remaining proof is the two-device forced-relay capture; no Android
devices are currently connected. See `ai/SERVER_LOG.md` for restart time,
redacted logs, and backup paths.
