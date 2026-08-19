# Network24-P2P Server Investigation

Last updated: 2026-08-19

## Server Agent execution (2026-08-19)

The requested production TURN deployment was not applied from this execution
namespace. The checkout contains no server/API source, and the namespace has
no usable network interface, systemd bus, or firewall control. No live service
restart, firewall change, token-broker request, external TURN allocation, or
Android device capture was possible. This is an environment boundary, not
evidence that relay transport works.

Initial pull completed at commit `c703b10`. `.tmp-webrtc/` was not present and
was not touched.

Redacted local evidence:

- Hostname reported `s303466.wholesaleinternet.net`; network identity queries
  were denied and DNS returned no address.
- `/etc/turnserver.conf` has `listening-port=3478`,
  `external-ip=204.12.206.90`, `realm=p2p.web24.live`, `fingerprint`,
  `lt-cred-mech`, and relay bounds `49152-65535`.
- The coturn file has no `tls-listening-port`, `cert`, `pkey`,
  `use-auth-secret`, or `static-auth-secret` directives.
- `/etc/network24/network24.env` has `NETWORK24_TURN_ENABLED=false` and no
  configured TURN host or TURN auth secret. Secret-bearing values were
  redacted and never printed.
- The Let's Encrypt certificate symlinks exist; the resolved private key is
  mode 600 and owned by root.
- `systemctl` could not connect to the bus; `ss` showed no visible listeners;
  `ufw`/`iptables` could not inspect the host firewall because netfilter is
  unavailable in this namespace.

Configuration backups were created before any possible edit, outside Git:

- `/root/network24-backups/turnserver.conf.20260819-110326`, mode 600,
  SHA-256 `e72227a547df565266d483997ed60e2d26cc6059edaefe23f7634f8b6a960450`
- `/root/network24-backups/network24.env.20260819-110326`, mode 600,
  SHA-256 `82ce33f610b2fdf0fba7b9256f0c4bc6cb4894966643a0a2f93d97032ae6df18`

Validation result:

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` could not start:
  Gradle reported no usable wildcard IP for this restricted machine. No
  application test or build result is claimed from this run.
- TURN shared-secret provisioning, TLS 5349, firewall policy, API token
  verification, external allocation, and forced-relay two-device testing are
  pending on an unconfined production host. There is no evidence here of
  `relay` candidates, sustained relay DataChannel bytes, or a 10-minute
  relay-based media session.

## Live deployment completed (2026-08-19 11:10 UTC)

After elevated host access became available, the approved TURN deployment was
applied and backed up before editing:

- Generated one server-only 256-bit shared secret and stored it outside Git
  at `/root/network24-backups/turn-auth-secret.20260819` with mode 600.
- Installed coturn TLS material under `/etc/turnserver/`; the private key is
  mode 640 and readable only by root and the `turnserver` service group,
  required because coturn runs as that service account.
- Configured public address `204.12.206.90`, TLS TCP `5349`, realm
  `p2p.web24.live`, REST shared-secret auth, and relay range `49152-65535`.
  TCP relay was not disabled.
- Enabled API TURN issuance with `NETWORK24_TURN_ENABLED=true`, host
  `p2p.web24.live`, and the matching secret. The TURN-capable backend artifact
  from the authoritative `/root` source was installed after typecheck and
  build passed; the previous artifact is backed up at
  `/root/network24-backups/dist-before-turn-20260819-110915`.
- Restarted coturn and API at `2026-08-19T11:10:49Z`; coturn, API, signaling,
  and Nginx all report active.
- Public TCP/UDP `3478` and TCP/UDP `5349` listeners are present as
  appropriate. TLS handshake to `p2p.web24.live:5349` verified the expected
  certificate name.
- Coturn’s built-in authenticated client completed UDP 3478, TCP 3478, and
  TLS 5349 allocations and client-to-client probes with 0% packet loss. The
  API secret equals the coturn secret; credential shape/HMAC, three TURN URLs,
  and 300-second expiry checks passed without printing credentials.
- The invalid-credential token request returned HTTP 401 as expected. A valid
  account request was not run because no approved IPTV test credentials are
  available in this session.

The required proof of actual relay-based WebRTC segment transport remains
pending: `adb devices` currently reports no connected devices, so no
two-device forced-relay capture, `type=relay` candidate-pair evidence, or
10-minute `source=P2P transport=relay` media-byte evidence exists yet.

## Result

The primary server-side finding is a TURN deployment mismatch. coturn is
running, but the Network24 token issuer is configured with TURN disabled, so
the Android token response does not contain TURN credentials or TURN URLs.
The Android client therefore uses only its default public STUN server. That
can produce an initially connected direct ICE pair while the NAT mapping or
mobile path later expires; the observed ICE failure and HTTP fallback are
consistent with that failure mode.

This is not proven to be the only possible cause until a two-device Logcat
capture records the selected candidate pair and the exact ICE state transition.
There is no client ICE/DataChannel log in this workspace for the reported
40-second event.

## Architecture path verified

- Android obtains a short-lived token from `/api/v1/client/token`.
- The API adds a `turn` object only when `turnEnabled`, `turnHost`, and
  `turnAuthSecret` are all configured.
- Android passes returned ICE servers to `PeerConnection`; otherwise its
  checked-in default is `stun:stun.l.google.com:19302`.
- WSS signaling authenticates, registers a server-owned peer ID, joins an
  exact stream room, returns same-stream peers, and forwards validated SDP
  and ICE metadata. It does not carry media bytes.
- The WebRTC DataChannel carries bounded binary segment chunks. Media3 tries
  complete local cache, then a bounded peer request, then the original HTTP
  HLS source.

## Host evidence

Collected 2026-08-19 from the unconfined host, with secrets redacted:

- `network24-p2p-signaling`, `network24-p2p-api`, Redis, PostgreSQL, Nginx,
  and coturn are active.
- Local signaling health: `{"status":"ok"}` and ready with one peer.
- Public HTTPS health and readiness both returned successfully; Redis and
  PostgreSQL dependencies were reported OK.
- Signaling metrics contained 718032 WebSocket messages, 274 registrations,
  2 peer expirations, 1 WebSocket client, and no ICE-specific failure metric.
- Host resources were healthy: load 0.27/0.12/0.08, 125 GiB RAM with about
  123 GiB available, no swap use, and root filesystem 3% used.
- coturn has been running since 2026-08-17 and listens on public UDP/TCP
  3478. No TLS 5349 listener was configured.
- `ufw` is inactive. The inspected firewall output did not provide a
  production-approved relay policy; this must be explicitly reviewed before
  exposing a bounded relay range.
- `/etc/network24/network24.env` status was:
  `NETWORK24_TURN_ENABLED=false`, `NETWORK24_TURN_HOST=<unset>`,
  `NETWORK24_TURN_AUTH_SECRET=<unset>`.
- `/etc/turnserver.conf` has `listening-port`, `external-ip`, `realm`, and
  relay port bounds, but no `use-auth-secret`, `static-auth-secret`,
  `tls-listening-port`, `cert`, or `pkey` directives.
- coturn logged TCP `Connection reset by peer` errors on 2026-08-17 from
  `106.219.133.92`; these are evidence of reset TCP sessions only and do not
  identify the Android ICE pair or cause.
- Earlier service history contained a transient signaling namespace failure
  because `/var/lib/network24` was absent. The service has been running since
  2026-08-16 19:09 UTC, so that historical startup issue does not explain a
  currently established DataChannel dropping after 40 seconds.
- No current warning/error entries were present for signaling or API, and no
  application log file containing the reported client ICE transition was
  available.

## Root-cause assessment

Primary: TURN is not delivered to clients and coturn is not configured for the
short-lived REST credentials generated by the API. A direct ICE connection is
therefore the only usable path. On networks with carrier NAT, symmetric NAT,
or expiring UDP mappings, a direct pair may open and later fail; HTTP fallback
then correctly takes over.

Not supported by current evidence:

- signaling message loss or unauthorized SDP/ICE forwarding;
- Redis/PostgreSQL outage;
- server CPU, memory, disk, or WebSocket resource exhaustion;
- proof that the 45-second server heartbeat TTL expired for the failing peer.

## Recommended production fix

Do not enable TURN with guessed values. Obtain and back up the current
deployment configuration, then provision:

1. An approved public TURN hostname/IP, realm, and `NETWORK24_TURN_AUTH_SECRET`.
2. Matching coturn `use-auth-secret`, realm, public address mapping, and a
   bounded `min-port`/`max-port` relay range.
3. A valid certificate/key and TLS 5349 listener (or an approved reason not
   to provide TLS TURN).
4. Firewall rules for UDP/TCP 3478, TCP 5349, and the exact relay range; keep
   Redis/PostgreSQL private.
5. A controlled restart and a forced-TURN two-device test confirming the
   Android token contains `turn`, selected candidate type is `relay`, bytes
   flow for at least 10 minutes, and HTTP fallback remains bounded.

No server configuration was changed in this investigation because the
required secret, certificate, public relay policy, and firewall scope are not
approved or available in the workspace.

## Related Android regression found during validation

The first unconfined Android verification failed one existing P2P test:
`Network24MediaRequestTest.stream origin stream id and byte range scope every
key`. Recent commit `441addd` had removed `position` and `length` from the
segment-key hash, allowing a full-body request and a byte-range request for the
same segment name to collide. Those fields were restored in
`Network24MediaRequest.kt`; this is a client-side correctness fix, not a
substitute for TURN provisioning.

## Commands executed

The following read-only commands were executed; secret-bearing output was
redacted or reduced to presence/absence:

```text
git pull
git status --short --branch
git log --oneline --decorate --graph -12 --all
rg --files
find ... README/TASK/STATUS/ARCHITECTURE/DEBUG/log/config/deployment files
systemctl is-active nginx redis-server postgresql coturn network24-p2p-signaling network24-p2p-api
systemctl status/cat coturn network24-p2p-signaling network24-p2p-api
ss -lntup
ufw status verbose
iptables -S
journalctl -u coturn/network24-p2p-signaling/network24-p2p-api/nginx
curl health/readiness endpoints and local signaling metrics
uptime; free -h; df -h; ps
rg TURN/ICE/signaling configuration and implementation paths
```
