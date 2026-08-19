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

## Next required Server Agent action

Provision and validate the approved coturn shared-secret/TLS deployment described
in `ai/SERVER_LOG.md`, then run the forced-TURN two-device test. Android now
accepts the token broker's short-lived TURN URLs and credentials at runtime;
do not add static TURN credentials to the APK.

## Server Agent handoff: complete TURN deployment

This is the next required server-side task. Do not modify Android code or put
any real secret, IPTV password, token, or private key in Git.

### 1. Inspect and back up the current deployment

On the production host for `p2p.web24.live`, first collect redacted evidence:

```bash
hostname
systemctl is-active coturn network24-p2p-api network24-p2p-signaling nginx
ss -lntup | grep -E ':3478|:5349'
grep -E '^(NETWORK24_TURN_ENABLED|NETWORK24_TURN_HOST|NETWORK24_TURN_AUTH_SECRET)=' /etc/network24/network24.env \
  | sed -E 's/(AUTH_SECRET=).*/\1<redacted>/'
```

Back up the exact files before editing. Store the backup outside the Git
checkout and report only the backup path and file hashes, never their secret
contents:

```bash
sudo install -m 600 /etc/turnserver.conf \
  "/root/network24-backups/turnserver.conf.$(date +%Y%m%d-%H%M%S)"
sudo install -m 600 /etc/network24/network24.env \
  "/root/network24-backups/network24.env.$(date +%Y%m%d-%H%M%S)"
```

### 2. Provision one server-only shared secret

Generate it on the production host or approved secret store:

```bash
openssl rand -hex 32
```

The same value must be injected into coturn as `static-auth-secret` and the
API as `NETWORK24_TURN_AUTH_SECRET`. Do not print it in command output, logs,
screenshots, commits, or the handoff response.

### 3. Configure coturn

Review `deploy/coturn/network24.conf.example` and install an equivalent
configuration using the real public IP and existing certificate paths. Required
directives are:

```text
listening-port=3478
tls-listening-port=5349
listening-ip=<PUBLIC_IP>
relay-ip=<PUBLIC_IP>
external-ip=<PUBLIC_IP>
realm=p2p.web24.live
fingerprint
lt-cred-mech
use-auth-secret
static-auth-secret=<SECRET_FROM_SECRET_STORE>
min-port=49152
max-port=65535
```

Use the valid Let's Encrypt certificate for `p2p.web24.live` with coturn's
`cert=` and `pkey=` directives. Confirm the private key is root-readable only.
Do not enable `no-tcp-relay`; TCP TURN must remain available for mobile
networks. Keep Redis/PostgreSQL ports private.

### 4. Configure the API and firewall

Set these API values in the server-only environment file, preserving all other
existing values:

```text
NETWORK24_TURN_ENABLED=true
NETWORK24_TURN_HOST=p2p.web24.live
NETWORK24_TURN_AUTH_SECRET=<same-secret-as-coturn>
```

Allow only these public ports in the provider/cloud firewall and host firewall:

```text
UDP 3478
TCP 3478
TCP 5349
UDP 49152-65535
```

### 5. Restart and verify service state

Restart coturn and the API using the deployment's normal service mechanism.
Then verify all of the following before reporting success:

```bash
systemctl is-active coturn network24-p2p-api network24-p2p-signaling nginx
ss -lntup | grep -E ':3478|:5349'
journalctl -u coturn -n 100 --no-pager
journalctl -u network24-p2p-api -n 100 --no-pager
```

The coturn log must show no certificate, realm, or auth-secret errors. Confirm
UDP relay allocation works from an approved external test network, not only
from localhost.

### 6. Verify token broker output without leaking credentials

Use the approved test account through a secure shell variable or existing
server-side test harness. Redact the token, username, password, and IPTV
credentials in all output. The response must contain:

```text
turn.urls includes turn:p2p.web24.live:3478?transport=udp
turn.urls includes turn:p2p.web24.live:3478?transport=tcp
turn.urls includes turns:p2p.web24.live:5349?transport=tcp
turn.username present
turn.password present
expires_in is short-lived (300 seconds)
```

### 7. Give Android Agent the forced-TURN evidence

After token verification, install the latest Android debug APK on the current
pair `TANK300000041351` and `RZCT90MRXQM`, open the same channel, and capture
both devices for at least 10 minutes using:

```bash
adb logcat -v threadtime -s N24-P2P:V WebRTC:V '*:S'
```

The handoff back to Android Agent must include redacted lines proving:

```text
event=ice_servers ... turn=3
event=ice_candidate ... type=relay
event=ice_selected_pair ... transport=relay
event=datachannel ... state=OPEN
event=segment_received ...
event=upload_ack ...
event=media ... source=P2P ... transport=relay
```

Also include any HTTP fallback lines and reasons. Do not claim TURN/P2P is
fixed unless the selected pair is `relay`, P2P bytes increase, and the
DataChannel remains usable for the full capture window. Report exact service
restart times, firewall result, selected candidate types, and any remaining
failure with timestamps.

## Android Agent Next Action

TURN provisioning is now complete on the production host. The remaining
action is the forced-relay two-device capture. Install the latest debug APK on
`TANK300000041351` and `RZCT90MRXQM`. Capture both devices for 10 minutes on
the same channel with:

```text
adb logcat -v threadtime -s N24-P2P:V WebRTC:V '*:S'
```

Record redacted `event=ice_servers`, relay candidates, the selected relay pair,
DataChannel OPEN, `segment_received`, `upload_ack`, increasing
`source=P2P ... transport=relay` bytes, and bounded HTTP fallback/recovery.
Do not report success unless the relay pair remains usable and verified P2P
media bytes increase for the full window.
