Backend has feature flows:
offline text + small images via store-and-forward over Tor
low-latency text path
large files + AV via WebRTC P2P
Each device can act as a mini-server (retain/forward cached messages).
Optional always-on Raspberry Pi nodes provide relay/availability when user devices are offline.
Security layer handles identity + device keys + E2EE (Double Ratchet later).
Network layer provides multiple transports (Tor, WebRTC data, WebRTC media) chosen by routing logic.
Scale target is small (10–20 users), so correctness, resilience, and maintainability matter more than hyperscale optimization.

Identity model: 1 account identity + multiple device keys with:
immutable account_name (unique)
mutable display_username (non-unique)
Storage policy: any node may store packets for any recipient.
Tor transport: per-device onion service, long-lived while app is active.
WebRTC: signaling over Tor, direct media/data over normal internet, STUN allowed, no TURN.
Persistence: SQLite + blob/image cache with user-configurable max size.
Cross-platform parity: same protocol; mobile lifecycle constraints handled by background/push strategy.
Privacy posture: metadata minimization is “nice to have,” not strict v1 blocker.
Blind relays
Message delivery Effectively-once
