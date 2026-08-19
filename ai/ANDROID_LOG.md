# Android P2P Investigation Log

Last updated: 2026-08-19

## Scope

Android client only. No server source or deployment configuration was changed.

## Files inspected

- `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`
- `app/src/main/java/com/network24/player/Network24App.kt`
- `app/src/main/java/com/network24/player/core/net/StreamDataSourceFactory.kt`
- `app/src/main/java/com/network24/player/features/player/manager/PlayerManager.kt`
- All files under `app/src/main/java/com/network24/player/core/p2p/`
- Existing P2P unit tests under `app/src/test/java/com/network24/player/core/p2p/`
- `ai/TASK.md`, `ai/STATUS.md`, `ai/ARCHITECTURE.md`, `ai/DEBUG_GUIDE.md`, and `ai/SERVER_LOG.md`

## Evidence and findings

1. The media protocol uses a 64 KiB binary payload chunk plus framing metadata
   (request ID, segment key, index, and length). The latest Android config had
   `maxMessageBytes=64 KiB`, so a full-size binary frame is larger than the
   accepted message limit and can be discarded before `decodeChunk()`.
   This can leave the receiver waiting until the P2P request times out without
   producing a complete segment or ACK.

2. The latest defaults used a 750 ms segment request timeout, a 1.2 s upload
   deadline, and a 256 KiB DataChannel buffer threshold. Those values are not
   sufficient for multi-chunk transfers on mobile networks.

3. `acceptUploadAck()` removed the outbound transfer before validating peer,
   stream, segment key, and byte count. A wrong or late ACK could therefore
   discard the state needed to account for a valid transfer.

4. Dynamic TURN credentials were parsed only as a JSON URL array and were
   applied through mutable config state. The client now parses string or array
   URL forms safely, retains short-lived credentials in memory only, and applies
   the merged STUN/TURN list to the WebRTC manager before creating connections.

5. Existing code correctly keeps HTTP as the fallback and only reports
   `source=P2P` after Media3 consumes a complete verified payload. No change was
   made to remove or weaken that fallback.

6. `ai/SERVER_LOG.md` reports TURN issuance disabled and coturn REST/TLS
   configuration incomplete. This remains a server-side prerequisite for a
   relay candidate and cannot be solved by Android code alone.

## Changes made

- Raised the bounded Android transfer defaults to 15 s request timeout, 20 s
  upload deadline, 8 MiB buffered amount, and 128 KiB accepted frame size.
- Added runtime TURN URL/credential parsing and application to PeerConnection
  ICE configuration without APK-hardcoded credentials.
- Added safe logs for device suffix, peer suffix, token TURN count, ICE
  gathering state, ICE connection state, local/remote candidate type, selected
  candidate pair types, DataChannel state, upload progress, P2P bytes, HTTP
  bytes, and fallback reason. Secrets, account credentials, and raw candidates
  are not logged.
- Kept ACK state until the ACK is validated and increased the outbound state
  TTL to 60 seconds so late valid ACKs are not discarded immediately.
- Added focused TURN parser tests and preserved exact byte-range scoping in the
  segment key.
- Limited the upload executor to the active transfer only; stale concurrent
  requests are rejected so they take HTTP fallback instead of filling the
  DataChannel queue.
- Extended the advertised-segment transfer deadline to 45 seconds while
  retaining bounded HTTP fallback for non-advertised segments.
- Added receiver-side logs for accepted metadata, rejected chunks, completion
  integrity failures, and verified segment assembly.

## Testing result

Completed locally:

- `:app:compileDebugKotlin` — passed
- `:app:testDebugUnitTest` — passed
- `:app:assembleDebug` — passed in the latest Android SDK-enabled run

Completed on connected devices:

- Used the Android SDK `platform-tools/adb.exe` to enumerate both phones.
- Installed the debug APK on both devices after removing the incompatible
  previously signed `com.network24.player` package; this reset app-local test
  state and was limited to the test package.
- Both devices authenticated successfully and reached the channel list.
- Live Logcat showed signaling, SDP, ICE checking/connected, and DataChannel
  OPEN on both sides. No TURN was returned: `event=ice_servers count=1
  turn=0 source=token`.
- The live capture did not show a completed P2P media transfer. Device A
  reported `p2pRequests=5 p2pHits=0 p2pMisses=5 p2pTimeouts=5 bytesFromP2p=0`;
  both devices used HTTP fallback, including `reason=NO_PEER` and
  `reason=TIMEOUT`.
- At capture time both devices were in `ChannelListActivity`, not
  `PlayerActivity`. The old session logs also showed different segment
  positions and `segment_request cache=miss`, so this is not valid evidence
  of synchronized same-channel playback. A 10-minute playback result is
  therefore still pending and the issue is not marked fixed.
- After the upload-queue change, a clean live run produced one verified transfer:
  Device A logged `source=P2P ... bytes=4044068` and Device B logged the
  matching validated `upload_ack`. This proves the Android framing, assembly,
  checksum path, and ACK path can complete end to end.
- Follow-up captures still showed large 4–8 MiB transfers logging
  `upload_complete_queued` without an ACK before the deadline, followed by
  HTTP `reason=TIMEOUT`. The 45-second advertised-segment timeout build was
  assembled successfully, but the final two-device retest was interrupted when
  Device A disconnected from adb. A different unrecognized test device
  appeared; it was not modified or used in that interrupted attempt.
- The replacement pair was then used as the authoritative test pair:
  two approved test devices. The replacement device was installed, logged in with
  the supplied test account, and opened on the same USA Entertainment stream.
- A clean 60-second capture initially showed metadata delivery but no binary
  chunks with the old 64 KiB payload. The framed message was larger than the
  negotiated 65,536-byte WebRTC message limit.
- After changing the media payload chunk to 60 KiB, the current pair completed
  three verified P2P transfers in the capture/follow-up window: 4,720,680 bytes,
  6,303,828 bytes, and 4,596,788 bytes. The receiver logged
  `segment_received`, playback logged `source=P2P transport=srflx`, and the
  sender logged matching validated `upload_ack` events. No immediate ICE or
  DataChannel failure appeared in the follow-up. At that earlier capture, TURN was still absent
  (`turn=0`), so this validates direct srflx operation only; relay/mobile-data
  validation remains a server-TURN prerequisite.
- A subsequent approximately 10-minute current-pair observation ran from
  15:39:44 to 15:49:38. The receiver recorded 30 media segments,
  including 7 verified `source=P2P transport=srflx` segments and 23 HTTP
  fallback segments. The sender side continued producing validated upload ACKs;
  no ICE `FAILED`/`CLOSED` or `webrtc_error` event appeared. One P2P segment
  took about 48 seconds, confirming that HTTP fallback remains necessary for
  slow or unavailable transfers. The Android result is therefore: P2P works
  opportunistically and survives the observed window, but it is not continuous
  for every segment and is not TURN/mobile-network validated.
- An alternate-channel test was run on `USA | ABC` (stream `1116`). ICE stayed
  connected and the sender accepted a 7,705,744-byte segment as 126 safe
  60-KiB chunks, but the transfer did not complete within the advertised
  45-second deadline. The receiver correctly used
  `source=HTTP reason=TIMEOUT`; no false P2P success was reported. This
  confirms the fallback path on a different channel and also shows that very
  large segments remain throughput-sensitive on the direct srflx path.

## Required runtime capture

```text
adb logcat -v threadtime -s N24-P2P:V WebRTC:V *:S
```

Run on both devices for Wi-Fi/Wi-Fi, Wi-Fi/mobile, mobile/mobile, and forced
TURN once the server agent provisions TURN. Verify `event=ice_servers`,
`event=ice_gathering`, `event=ice_connection`, `event=ice_selected_pair`,
`event=datachannel`, `event=upload_ack`, `source=P2P`, and bounded
`source=HTTP reason=...` fallback events.

## Reconnect overlay fix (2026-08-19)

### Files inspected

- `app/src/main/java/com/network24/player/features/player/manager/PlayerManager.kt`
- `app/src/main/java/com/network24/player/features/live/activity/ChannelListActivity.kt`

### Finding and evidence

The sender device showed `Network connection lost. Reconnecting... Attempt 1/5` while
the player continued rendering and the hybrid data source continued using HTTP
fallback. `ChannelListActivity` received a recovery-status callback but had no
positive recovery callback. Its local player listener was also not attached to
the shared player, so the stale overlay could remain visible after playback
returned to READY.

### Change made

- `PlayerManager` now treats `Player.STATE_READY` as recovery success without
  requiring `isPlaying == true`.
- When a retry was active, it emits a recovered callback, clears the recovery
  attempt state, and logs `Playback recovered; clearing retry state`.
- `ChannelListActivity` hides the stale error/report UI on that callback and
  unregisters all recovery callbacks in `onDestroy()`.
- HTTP fallback and failure UI were preserved.

### Testing result

- `:app:compileDebugKotlin` — passed.
- `:app:assembleDebug` — passed.
- Installed the resulting APK on both approved test devices.
- Both devices opened `USA | ABC`; after a short Wi-Fi interruption,
  the player remained on `USA | ABC`, `txtPlayerError` was absent both during
  and after reconnect, and no crash was logged.
- The sender device continued to show valid P2P signaling/ICE transitions and HTTP
  fallback events. That earlier server state provided no TURN credentials, so
  this test did not validate relay connectivity.

## Remaining transport stability pass (2026-08-19)

### Evidence from current devices

On both approved test devices, the failing
direct transfer showed a 6.0 MiB upload queued almost immediately while the
receiver only accepted metadata. The connection later reached
`DISCONNECTED/FAILED` near the previously observed 40-second point. The
selected pair was `srflx/srflx`; token logs continued to report
`event=ice_servers count=1 turn=0`.

After the transport changes, a 4.76 MiB transfer progressed in bounded batches
with DataChannel `bufferedAmount` around 580 KiB instead of a 6 MiB burst. The
direct path measured roughly 100–120 KiB/s and could not finish that segment in
the 45-second media request window, so HTTP fallback was selected. During that
window ICE stayed connected; this confirms the burst was reduced, while also
confirming that the no-TURN direct path is throughput-limited.

### Android changes

- Reduced the default WebRTC send queue limit to 512 KiB so mobile SCTP does
  not receive an unbounded multi-megabyte burst.
- Corrected the session wiring so the configured 45-second upload deadline is
  not silently capped at 30 seconds.
- Added an 8-second bounded recovery action after prolonged ICE
  `DISCONNECTED`; the dead PeerConnection is dropped and the existing
  signaling peer refresh creates a new offer/ICE attempt.
- Closed DataChannels now remove their PeerConnection and cancel uploads for
  that peer, preventing stale connections from blocking reconnection.
- Receiver chunk handling now validates both `request_id` and `segment_key`
  before assembly and logs bounded chunk progress.
- Added a short per-peer failure cooldown after timeout, integrity, or send
  failure. This keeps slow peers from blocking Media3 repeatedly while normal
  HTTP fallback continues immediately.
- Added receiver-side `segment_chunk_received` diagnostics; no media is
  reported as P2P until complete size and SHA-256 validation succeeds.

### Verification

- `:app:testDebugUnitTest` — passed.
- `:app:assembleDebug` — passed.
- Final debug APK installed on both current devices; both launched without an
  app crash.
- The adaptive live run preserved HTTP playback and bounded fallback. It did
  not prove a new completed P2P segment because the two live viewers were not
  requesting the same cached segment at the same time.

### Historical remaining external prerequisite

This earlier Android pass could not make a direct `srflx` path equivalent to
TURN. The server deployment is now active and returns TURN credentials, but
Wi-Fi/mobile and mobile/mobile relay stability still require a selected relay
pair and sustained relay-byte capture.

## Forced-relay capture after server TURN deployment (2026-08-19)

The debug APK was rebuilt and installed on both approved test devices. Both
were opened on the same `USA | ACCUWEATHER` channel. The token broker was
reachable and the initialization capture on one device reported:

```text
event=ice_servers count=4 turn=3 source=token
event=token_ready turn_servers=3
event=ice_servers_updated count=4 turn=3
```

The subsequent two-device media capture ran for more than 10 minutes using
fresh filtered Logcat windows. Redacted unique evidence included:

- Receiver: 49 `segment_received` events and 49 verified
  `source=P2P ... transport=srflx` media events.
- Sender: 49 matching `upload_ack ... transport=srflx` events.
- Receiver: 6 HTTP fallbacks, including bounded `TIMEOUT` and `UNAVAILABLE`
  reasons; sender-side playback also recorded HTTP fallback events.
- No `transport=relay` or `candidate_type=relay` event appeared in either
  capture. The selected path remained direct `srflx`; no TURN bytes were
  recorded.
- No ICE `DISCONNECTED`/`CLOSED` event appeared in the receiver capture; the
  sender capture contained two `FAILED` matches during the window and also
  continued HTTP playback.

This historical capture was not a forced-relay success. TURN credentials were
delivered, but the then-current client/test network selected a direct pair.
No credentials, account names, raw candidates, URLs, or device identifiers
were added to this evidence.

## Forced-relay policy fix and verification (2026-08-19)

### Root cause fixed

The Android peer manager supplied TURN servers to WebRTC but used the default
ICE transport policy `ALL`. On a healthy direct path WebRTC therefore preferred
`srflx`, leaving the session exposed to the previously observed NAT mapping
expiry. The runtime TURN credentials were valid; the client simply did not
require relay transport.

### Android change

- Added `forceRelayWhenTurnAvailable`, enabled by default.
- When the authenticated token contains a `turn:` or `turns:` server, new
  PeerConnections use `PeerConnection.IceTransportsType.RELAY`.
- Existing peers are dropped and recreated if the active policy changes.
- If the token has no TURN server, the manager retains `ALL` so HTTP playback
  and opportunistic direct P2P remain available during a broker outage.
- TURN credentials remain short-lived runtime values; none are embedded in the
  APK or logs.

### Verification

Focused tests and build passed:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On both approved devices playing the same `USA | ACCUWEATHER` channel, fresh
redacted logs showed:

```text
event=ice_servers count=4 turn=3 source=token
event=ice_servers_updated count=4 turn=3 ice_policy=RELAY
event=peer_connection_create ... ice_policy=RELAY
event=ice_candidate ... type=relay
event=ice_selected_pair ... local_type=relay remote_type=relay transport=relay
event=datachannel ... state=OPEN
```

The relay stability window ran for more than 10 minutes. The receiver logged
40 verified `segment_received` and `source=P2P ... transport=relay` events;
the sender logged 40 matching `upload_ack ... transport=relay` events. No ICE
`FAILED`, `DISCONNECTED`, or `CLOSED` event appeared during the relay window.
Slow or unavailable segments used bounded HTTP fallback as designed. This
closes the reported direct-srflx 40-second failure path; broader mobile/mobile
matrix testing remains normal follow-up validation.

## Signaling reconnect and mobile relay follow-up (2026-08-19)

The first Wi-Fi/mobile matrix capture showed that relay ICE could connect, but
transient signaling `IDLE` caused the session to cancel pending work and drop
all WebRTC peers. A second issue appeared on the cellular relay path: a
1--1.3 MiB segment could take roughly 35--60 seconds to drain, while the
previous 45-second transfer and 8-second ICE recovery limits could cancel it.

The Android follow-up changes are:

- Signaling reconnects now preserve existing WebRTC/DataChannel peers and let
  the refreshed peer list reconcile only peers that disappeared.
- `connect()` and incoming-signal paths replace stale `CLOSED`/`FAILED` peer
  connections before creating a new offer/answer exchange.
- Advertised-segment request/upload deadlines are now 90 seconds, and the
  disconnected-ICE recovery grace is 30 seconds. These remain bounded and HTTP
  fallback is unchanged.

Verification:

- `:app:testDebugUnitTest` and `:app:assembleDebug` passed after the changes;
  the APK was installed on both approved devices.
- The latest Wi-Fi/mobile run still selected `relay` and opened the
  DataChannel, and it survived a signaling reconnect without an immediate
  session-wide drop. The same run later reached ICE `FAILED` during a slow
  cellular relay transfer before a completed `source=P2P` media event could be
  recorded. It is therefore not a clean matrix pass.
- Mobile/mobile could not be validated because the TANK device's cellular
  network reported no route/DNS; RZCT cellular internet was reachable.

The established same-channel forced-relay baseline remains valid: a separate
10+ minute window recorded 40 verified relay P2P segments and matching upload
acknowledgements without ICE failure. The broader Wi-Fi/mobile and
mobile/mobile claim remains open pending a stable external cellular path.

## Bounded best-peer selection (2026-08-19)

The client does not create a full-room mesh. The active connection cap remains
four peers per client. Peer-list handling now ranks candidates using retained
connected/connecting state, completed transfer health, selected ICE RTT, and
failure/cooldown history. Equal unknown candidates use stable per-device
affinity so all customers do not select the same first four room entries.
Failed or disconnected peers are removed and can be replaced on the next peer
refresh. The build and focused unit tests passed after this change.

## Stale segment advertisements (2026-08-19)

`segment_have` is now treated as a hint rather than a durable cache guarantee.
When a peer cannot serve an advertised segment, the exact advertisement is
removed immediately. The client also avoids blind requests for segments that
no peer advertised, preventing normal live-playback skew from inflating P2P
failures and triggering unnecessary peer cooldowns.

## Legacy TV compatibility (2026-08-19)

The Samsung `com.n24player.server` comparison showed `minSdk=17` and
`targetSdk=27`, while Network24 was advertising `minSdk=23`. First-generation
Fire TV/Stick devices on API 21/22 could therefore reject the Network24 APK
before runtime. The app install floor is now API 21, the unused API-23-only
Firebase Auth dependency was removed, and P2P/WebRTC is gated off below API 23.
Legacy TV playback uses the existing HTTP Media3 path; modern phones retain
the current P2P session. Firebase mobile push/alert initialization is skipped
on legacy TV to avoid assuming Google mobile services are present.
