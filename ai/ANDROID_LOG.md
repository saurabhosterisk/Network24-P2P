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

## Testing result

Completed locally:

- `:app:compileDebugKotlin` — passed
- `:app:testDebugUnitTest` — passed
- `:app:assembleDebug` — passed in the latest Android SDK-enabled run

Not completed in this workspace:

- `adb` is not installed or available on this host, so connected Android test
  phones could not be enumerated or installed with `adb install`.
- No two-device Logcat capture was available, so stable 10-minute P2P playback,
  relay candidate selection, and the reported 40-second ICE transition remain
  runtime verification tasks. The issue is not marked fixed on device evidence.

## Required runtime capture

```text
adb logcat -v threadtime -s N24-P2P:V WebRTC:V *:S
```

Run on both devices for Wi-Fi/Wi-Fi, Wi-Fi/mobile, mobile/mobile, and forced
TURN once the server agent provisions TURN. Verify `event=ice_servers`,
`event=ice_gathering`, `event=ice_connection`, `event=ice_selected_pair`,
`event=datachannel`, `event=upload_ack`, `source=P2P`, and bounded
`source=HTTP reason=...` fallback events.
