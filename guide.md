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
What I understand so far (matches your guide.md + diagram):
You want a Kotlin-first backend/core with a local API for GUI clients.
Messaging paths are split:
Tor store-and-forward for text + small images.
Low-latency text path (likely direct when possible).
WebRTC P2P for large files + voice/video (signaled over Tor).
Each user has one account identity and multiple device keys.
Any node (device or Raspberry Pi relay) can cache/forward packets for recipients.
Scale is intentionally small (10–20 users), favoring correctness/maintainability.
Mobile parity matters, and iOS may need Tor as a separate native module.
To make sure we’re 100% synced before designing the Tor module, these are the most important clarifications:
Runtime shape
Do you want one long-running backend process per device (daemon/service), with GUI talking over local IPC?
Or embedded backend library directly inside app process (especially on mobile)?
Tor integration per platform
Android: run Tor via embedded binary/service (e.g., from app sandbox)?
iOS: are you committed to a Swift Tor controller module exposing a bridge API to Kotlin/Flutter, or still open?
Protocol framing over Tor
Should Tor transport carry a custom binary envelope (recommended), or JSON/CBOR for v1?
Do you want transport payloads already encrypted at this layer, or plain envelopes with E2EE in upper layer (diagram suggests E2EE above transport)?
Store-and-forward semantics
TTL defaults for cached messages/images?
Delivery target: “effectively-once” via message IDs + dedup ACKs, correct?
Should relays be allowed to evict by LRU when cache full?
Identity bootstrap
How will devices discover each other’s onion addresses + device keys initially?
Manual QR/invite file for v1, or some lightweight directory relay?
Trust model for Raspberry Pi relays
Are Pis operated by trusted members only, or semi-trusted?
Should clients prefer friend devices first, then Pis, or vice versa?
Offline push/background expectations
For iOS/Android, is near-real-time while app closed required in v1, or “best effort when app wakes”?
GUI integration target
First GUI likely Kotlin Multiplatform Compose, Flutter, or both?
This affects IPC/API style we should freeze now (gRPC/HTTP/Unix socket/custom).
The backend needs to be able to run headless on the servers and have a gui attached when on actual used devices. As far as I know multiprocessing is not possible on mobile so the gui can just call the methods normally like a library, that is why I have made it a single project here.
On desktop (linux and windows) running precompiled binaries, android has a precompiled guardianproject library and iOS needs a small swift adapter to use the tor framework.
For the webrtc the iOS also needs a swift adapter, but for android and desktop there should be a library.
Yes, tor should carry a custom envelope with the encoded binary data and some routing information that is necessary for the message to reach the destination.
The TTL should be set by configs and dependent on the amount of space assigned either in the gui or a config in the case of the raspberry pi server
about effectively once that is correct. yes, deleting LRU is fine
It would be good for the devices to propagate over the network so I don't have to add the addresses manually to each device
The messages should be sent to the whole network, but should be completely encrypted, with only the metadata readable to the server.
Yes, mobile needs push notifications, but we can discuss the method when we get to it. For the gui let's start with Kotlin Compose. As for the tests, we definitely need unit tests and after that integration tests sending messages to itself and then to a separate instance on this computer. Also, what is the usual place to place the third party binaries in such a project? I can create the directory so you can then test with it. Is everything clear?