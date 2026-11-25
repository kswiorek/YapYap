## Design Constraints

The premises every design decision in YapYap is measured against. This file derives from docs/guide.md §1, README.md
(the premise list) and docs/e2ee.md (Security assumptions).

### Correctness, resilience and maintainability win over scale

YapYap is a messenger for a small trusted network (about 10–20 users per guide.md; README premises assume roughly
20–40). Design and code decisions prioritize correctness, resilience and maintainability over scale; the protocol is
optimized for ~10–20 peers, not hyperscale (no prekey bundle server, full re-folds per trigger, simple aggregate
mechanisms acceptable).

Sources: docs (guide.md §1; e2ee.md; README.md) · confidence 88.
Evidence:

- guide.md §1: "A messenger for a small trusted network (about 10–20 users). Correctness, resilience and maintainability
  win over scale."
- e2ee.md (Security assumptions): "**Small network** — Protocol optimized for ~10–20 peers, not hyperscale prekey
  distribution."
- README.md (Premise): "The design choices made here rely on the assumption that there are around 20-40 people using the
  network."

### No dependent servers; the same core runs on every device

No servers the core depends on: every device runs the Kotlin core as a library; the same core runs headless on optional
always-on nodes (e.g. a Raspberry Pi) acting as relays and availability anchors. Traffic split: text and small images
over Tor with store-and-forward through relays when the recipient is offline; a direct low-latency WebRTC path when both
sides are online; large files and AV over WebRTC peer-to-peer, signaled over Tor. All communications are end-to-end
encrypted, including group chats and file transfers.

Sources: docs (guide.md §1; README.md) · confidence 90.
Evidence:

- guide.md §1: "No servers it depends on. Every device runs the Kotlin core as a library; the same core runs headless on
  optional always-on nodes (e.g. a Raspberry Pi) that act as relays and availability anchors."
- guide.md §1 (Traffic): "text and small images over Tor, with store-and-forward through relays when the recipient is
  offline; a direct low-latency path over WebRTC when both sides are online; large files and audio/video over WebRTC
  peer-to-peer, signaled over Tor."
- README.md (Premise 13): "All communications are end-to-end encrypted, including group chats and file transfers."

### Relay retention and selection premise

Relay peers hold store-and-forward copies so delivery does not need sender and recipient online simultaneously; relay
selection is based on peer uptime to ensure a probability of success; relayed messages are pruned after 2 days or when
they exceed a user-set size limit; headless peers keep the network working when all users are offline.

Sources: docs (README.md premises; guide.md §1) · confidence 83.
Evidence:

- README.md (Premises 6–9): messages are simultaneously sent to a number of other peers; relay selection is based on
  peer uptime; a number of headless peers are run; "Relay messages are pruned after 2 days or they exceed a size limit
  set by the user."
- guide.md §1: "store-and-forward through relays when the recipient is offline."

### Identity is two-tiered: account and device

Identity is two-tiered: accounts own devices; the identity of all devices and accounts is known to all other peers
(carried on the global chain); all accounts belong to a global room where network events are broadcast. Ratchet sessions
are strictly device-to-device.

Sources: docs (README.md premises; guide.md §7; db schema.mmd) · confidence 85.
Evidence:

- README.md (Premises 10–12): "The identity of the user is two-tiered: account and device, with devices belonging to
  accounts." / "The identity of all devices and accounts is known to all other peers." / "All accounts belong to a
  global room, where events about the network are broadcast."
- guide.md §7: "Identity lives in the global chain: accounts and devices, each with signing and encryption keys. The
  global fold projects it into `accounts`/`devices`."
- db schema.mmd (CRYPTO_SESSIONS): "peer_device_id FK 'Ratchet state is strictly hardware-to-hardware'."
