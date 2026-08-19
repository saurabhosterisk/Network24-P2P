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
  retaining the short 1.5-second probe for non-advertised segments.
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
  Device A disconnected from adb. A different unrecognized `TANK 3` device
  appeared; it was not modified or used in that interrupted attempt.
- The replacement pair was then used as the authoritative test pair:
  `TANK 3` and `SM-S908E`. The new TANK device was installed, logged in with
  the supplied test account, and opened on the same USA Entertainment stream.
- A clean 60-second capture initially showed metadata delivery but no binary
  chunks with the old 64 KiB payload. The framed message was larger than the
  negotiated 65,536-byte WebRTC message limit.
- After changing the media payload chunk to 60 KiB, the current pair completed
  three verified P2P transfers in the capture/follow-up window: 4,720,680 bytes,
  6,303,828 bytes, and 4,596,788 bytes. The receiver logged
  `segment_received`, playback logged `source=P2P transport=srflx`, and the
  sender logged matching validated `upload_ack` events. No immediate ICE or
  DataChannel failure appeared in the follow-up. TURN was still absent
  (`turn=0`), so this validates direct srflx operation only; relay/mobile-data
  validation remains a server-TURN prerequisite.

## Required runtime capture

```text
adb logcat -v threadtime -s N24-P2P:V WebRTC:V *:S
```

Run on both devices for Wi-Fi/Wi-Fi, Wi-Fi/mobile, mobile/mobile, and forced
TURN once the server agent provisions TURN. Verify `event=ice_servers`,
`event=ice_gathering`, `event=ice_connection`, `event=ice_selected_pair`,
`event=datachannel`, `event=upload_ack`, `source=P2P`, and bounded
`source=HTTP reason=...` fallback events.
