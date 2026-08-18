# Network24-P2P Architecture Notes

## Main Components

### Android App
- WebRTC PeerConnection client
- Media segment handling
- P2P cache requests
- HTTP fallback

### Signaling Server
- Peer registration
- SDP exchange
- ICE negotiation support

### Media Flow

Preferred:

Peer A -> Peer B (P2P)

Fallback:

Peer -> HTTP source

Future:

Peer -> TURN relay -> Peer

## Debug Priority

1. ICE connection stability
2. TURN relay
3. Media byte verification
4. Bandwidth measurement
